package com.lig7oo7.bookscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 찍은 JPEG 한 장을 스캔본으로 만든다:
 * 범위 잘라 반듯하게 펴기(원근 보정) → 추가 회전 → 문서 보정 → 양면 나누기 → 글자 인식 → 저장.
 */
final class Processor {
    private Processor() {}

    static final class Result {
        final List<File> files = new ArrayList<>();
        boolean ocrRan;
        /** 글자가 충분한 쪽들 중 가장 낮은 확신도 (0~1), 그런 쪽이 없으면 -1 */
        float worstConf = -1f;
        int totalChars;
        long ocrMs;
        /** 글자 인식 자체가 실패했을 때 오류 내용 */
        String ocrError;

        void deleteAll() {
            for (File f : files) Session.delete(f);
        }
    }

    /** 이보다 글자가 적으면 그림·빈 쪽으로 보고 흐림 판정에서 뺀다 */
    static final int MIN_CHARS = 20;

    static Result process(Context c, Session s, byte[] jpeg, int camRot, float[] quad, Ocr ocr) throws Exception {
        boolean split = Prefs.split(c);
        int maxLong = Prefs.maxLong(c);
        int quality = Prefs.jpegQuality(c);
        int limit = split ? Math.round(maxLong * 1.45f) : maxLong;
        float[] q = quad != null ? quad : Prefs.FULL;

        // 1) 적당한 크기로 읽기
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int longSide = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (longSide / (sample * 2) >= limit * 1.2f) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (src == null) throw new IOException("사진을 읽지 못했어요");

        // 2) 회전 + 범위 잘라 펴기를 한 번에 (화면 기준 좌표 → 원본 좌표)
        int W = src.getWidth(), H = src.getHeight();
        int rot = ((camRot % 360) + 360) % 360;
        boolean swap = rot == 90 || rot == 270;
        float dW = swap ? H : W, dH = swap ? W : H;     // 화면 방향으로 돌렸을 때 크기
        float[] srcPts = new float[8];
        float[] disp = new float[8];
        for (int i = 0; i < 4; i++) {
            float u = q[2 * i], v = q[2 * i + 1];
            disp[2 * i] = u * dW;
            disp[2 * i + 1] = v * dH;
            float x, y;
            switch (rot) {
                case 90:  x = v * W;       y = (1 - u) * H; break;
                case 180: x = (1 - u) * W; y = (1 - v) * H; break;
                case 270: x = (1 - v) * W; y = u * H;       break;
                default:  x = u * W;       y = v * H;
            }
            srcPts[2 * i] = x;
            srcPts[2 * i + 1] = y;
        }
        float ow = (dist(disp, 0, 1) + dist(disp, 3, 2)) / 2f;
        float oh = (dist(disp, 0, 3) + dist(disp, 1, 2)) / 2f;
        float sc = Math.min(1f, limit / Math.max(ow, oh));
        int outW = Math.max(16, Math.round(ow * sc)), outH = Math.max(16, Math.round(oh * sc));
        float[] dst = {0, 0, outW, 0, outW, outH, 0, outH};
        Matrix m = new Matrix();
        if (!m.setPolyToPoly(srcPts, 0, dst, 0, 4)) {
            src.recycle();
            throw new IOException("범위가 이상해요. 범위를 다시 지정해주세요");
        }
        Bitmap b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.WHITE);
        cv.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        src.recycle();

        // 3) 추가 회전
        int extra = ((Prefs.extraRotation(c) % 360) + 360) % 360;
        if (extra != 0) {
            Matrix r = new Matrix();
            r.postRotate(extra);
            Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), r, true);
            if (t != b) b.recycle();
            b = t.isMutable() ? t : t.copy(Bitmap.Config.ARGB_8888, true);
            if (b != t) t.recycle();
        }

        // 4) 양면 나누기 (보정 전 원본 상태로)
        List<Bitmap> pages = new ArrayList<>();
        if (split && b.getWidth() > b.getHeight()) {
            int half = b.getWidth() / 2;
            Bitmap left = Bitmap.createBitmap(b, 0, 0, half, b.getHeight());
            Bitmap right = Bitmap.createBitmap(b, half, 0, b.getWidth() - half, b.getHeight());
            b.recycle();
            if (Prefs.rightFirst(c)) { pages.add(right); pages.add(left); }
            else { pages.add(left); pages.add(right); }
        } else {
            pages.add(b);
        }

        // 5) 쪽마다: 글자 인식(원본으로 — 보정하면 얇은 획이 날아가 인식이 나빠짐) → 문서 보정 → 저장
        Result res = new Result();
        boolean enhance = Prefs.enhance(c);
        try {
            for (int i = 0; i < pages.size(); i++) {
                Bitmap p = pages.get(i);
                Ocr.Result r = null;
                if (ocr != null) {
                    try {
                        r = ocr.run(p);
                        res.ocrRan = true;
                        res.totalChars += r.chars;
                        res.ocrMs += r.ms;
                        if (r.chars >= MIN_CHARS && r.conf >= 0) {
                            res.worstConf = res.worstConf < 0 ? r.conf : Math.min(res.worstConf, r.conf);
                        }
                    } catch (Throwable t) {
                        res.ocrError = t.getClass().getSimpleName() + ": " + t.getMessage();
                    }
                }
                if (enhance) {
                    if (!p.isMutable()) {
                        Bitmap mut = p.copy(Bitmap.Config.ARGB_8888, true);
                        p.recycle();
                        p = mut;
                        pages.set(i, p);
                    }
                    Enhance.document(p);
                }
                File f = save(s, p, quality);
                res.files.add(f);
                if (r != null) {
                    PageFiles.writeText(f, r.text);
                    PageFiles.writeLines(f, r.lines);
                }
            }
        } catch (Exception e) {
            res.deleteAll();
            throw e;
        } finally {
            for (Bitmap p : pages) if (!p.isRecycled()) p.recycle();
        }
        return res;
    }

    private static float dist(float[] p, int a, int b) {
        float dx = p[2 * a] - p[2 * b], dy = p[2 * a + 1] - p[2 * b + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static File save(Session s, Bitmap b, int quality) throws IOException {
        File f = s.newFile();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, quality, fos)) throw new IOException("저장 실패");
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("저장 실패");
        }
        return f;
    }

    /** 썸네일용 작은 그림 */
    static Bitmap thumb(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    static int[] size(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        return new int[]{o.outWidth, o.outHeight};
    }
}
