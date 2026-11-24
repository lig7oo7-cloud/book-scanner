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
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 찍은 JPEG 한 장을 스캔본으로 만든다:
 * 글자로 본문 테두리 찾기 → 잘라 펴기(원근·방향 보정) → 양면 나누기 → 글자 인식 → 문서 보정 → 저장.
 */
final class Processor {
    private Processor() {}

    static final class Result {
        final List<File> files = new ArrayList<>();
        boolean ocrRan;
        /** 글자가 충분한 쪽들 중 가장 낮은 확신도 (0~1), 그런 쪽이 없으면 -1 */
        float worstConf = -1f;
        int totalChars;
        int lines;
        /** 자동 테두리를 사진에서 못 찾음 */
        boolean autoMissed;
        /** 첫 쪽에서 읽은 글자 (미리보기용) */
        String firstText;

        void deleteAll() {
            for (File f : files) Session.delete(f);
        }
    }

    /** 이보다 글자가 적으면 그림·빈 쪽으로 보고 흐림 판정에서 뺀다 */
    static final int MIN_CHARS = 20;
    /** 방향·테두리를 찾을 때 쓰는 작은 사진의 긴 변 */
    private static final int QUICK_LONG = 1600;

    /**
     * @param camRot 카메라 사진을 화면 방향으로 세우려면 돌릴 각도
     * @param liveQuad 미리보기에서 찾아둔 테두리(화면 0~1 좌표) — 자동 모드에서 사진으로 못 찾았을 때만 씀
     * @param ocr null이면 글자 인식·자동 테두리 없이 저장
     */
    static Result process(Context c, Session s, byte[] jpeg, int camRot, float[] liveQuad, Ocr ocr) throws Exception {
        boolean split = Prefs.split(c);
        int maxLong = Prefs.maxLong(c);
        int quality = Prefs.jpegQuality(c);
        int cropMode = Prefs.cropMode(c);
        Result res = new Result();

        // 1) 크게 읽어서 화면 방향으로 세우기 (글자가 작은 책도 읽히도록 최대 4000px)
        Bitmap d = decodeUpright(jpeg, camRot, 4000);
        float W = d.getWidth(), H = d.getHeight();

        // 2) 잘라낼 사각형 정하기 (픽셀 좌표, 꼭짓점 순서 = 펼친 뒤의 왼위·오른위·오른아래·왼아래)
        float[] quad = null;
        boolean needOrient = true;
        if (cropMode == Prefs.CROP_AUTO && ocr != null) {
            float sc = Math.min(1f, QUICK_LONG / Math.max(W, H));
            Bitmap small = sc < 1f ? Bitmap.createScaledBitmap(d, Math.round(W * sc), Math.round(H * sc), true) : d;
            Ocr.Result quick = ocr.run(small, 1f / sc);
            if (small != d) small.recycle();
            quad = QuadMath.pageQuad(quick.quads(2), W, H, Prefs.marginPct(c) / 100f);
            if (quad != null) needOrient = false;      // 글자 방향 순서로 펴므로 저절로 바로 선다
            else res.autoMissed = true;
            if (quad == null && liveQuad != null) quad = toPixels(liveQuad, W, H);
        } else if (cropMode == Prefs.CROP_FIXED) {
            quad = toPixels(Prefs.crop(c), W, H);
        }
        if (quad == null) quad = new float[]{0, 0, W, 0, W, H, 0, H};

        // 3) 잘라 펴기 (원근 보정)
        float ow = (dist(quad, 0, 1) + dist(quad, 3, 2)) / 2f;
        float oh = (dist(quad, 0, 3) + dist(quad, 1, 2)) / 2f;
        int cap = split ? maxLong * 2 : maxLong;
        float k = Math.min(1f, cap / Math.max(ow, oh));
        int outW = Math.max(16, Math.round(ow * k)), outH = Math.max(16, Math.round(oh * k));
        float[] dst = {0, 0, outW, 0, outW, outH, 0, outH};
        Matrix m = new Matrix();
        if (!m.setPolyToPoly(quad, 0, dst, 0, 4)) {
            d.recycle();
            throw new IOException("범위가 이상해요. 범위를 다시 지정해주세요");
        }
        Bitmap b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Color.WHITE);
        cv.drawBitmap(d, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        d.recycle();

        // 4) 방향 자동 맞추기 (고정 범위·전체 모드, 또는 자동 테두리를 못 찾았을 때)
        int rot = 0;
        if (needOrient && ocr != null && Prefs.autoOrient(c)) {
            float sc = Math.min(1f, QUICK_LONG / (float) Math.max(outW, outH));
            Bitmap small = sc < 1f ? Bitmap.createScaledBitmap(b, Math.round(outW * sc), Math.round(outH * sc), true) : b;
            Ocr.Result quick = ocr.run(small, 1f / sc);
            if (small != b) small.recycle();
            List<float[]> ql = quick.quads(2);
            if (ql.size() >= 3) rot = QuadMath.uprightRotation(QuadMath.textAngleDeg(ql));
        }
        rot = (rot + Prefs.extraRotation(c)) % 360;
        if (rot != 0) b = rotate(b, rot);

        // 5) 양면 나누기
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

        // 6) 쪽마다: 원본 색 그대로 글자 인식(보정 전이 더 잘 읽힘) → 문서 보정 → 저장
        try {
            for (Bitmap p0 : pages) {
                Bitmap p = p0.isMutable() ? p0 : p0.copy(Bitmap.Config.ARGB_8888, true);
                if (p != p0) p0.recycle();
                Ocr.Result r = null;
                if (ocr != null) {
                    r = ocr.run(p);
                    res.ocrRan = true;
                    res.totalChars += r.chars;
                    res.lines += r.lines.size();
                    if (r.chars >= MIN_CHARS && r.conf >= 0) {
                        res.worstConf = res.worstConf < 0 ? r.conf : Math.min(res.worstConf, r.conf);
                    }
                    if (res.firstText == null) res.firstText = r.text();
                }
                if (Prefs.enhance(c)) Enhance.document(p);
                File f = save(s, p, quality);
                p.recycle();
                res.files.add(f);
                if (r != null) {
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(Session.textOf(f)), StandardCharsets.UTF_8)) {
                        w.write(r.text());
                    }
                    OcrLine.write(r.lines, Session.ocrOf(f));
                }
            }
        } catch (Exception e) {
            res.deleteAll();
            for (Bitmap p : pages) if (!p.isRecycled()) p.recycle();
            throw e;
        }
        return res;
    }

    private static float[] toPixels(float[] q01, float W, float H) {
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            q[2 * i] = q01[2 * i] * W;
            q[2 * i + 1] = q01[2 * i + 1] * H;
        }
        return q;
    }

    /** 긴 변이 maxLong 근처가 되게 줄여 읽고, rot만큼 시계방향으로 돌려 세운다 */
    private static Bitmap decodeUpright(byte[] jpeg, int rot, int maxLong) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int longSide = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (longSide / sample > maxLong * 1.3f) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (src == null) throw new IOException("사진을 읽지 못했어요");
        rot = ((rot % 360) + 360) % 360;
        return rot == 0 ? src : rotate(src, rot);
    }

    private static Bitmap rotate(Bitmap b, int deg) {
        Matrix r = new Matrix();
        r.postRotate(deg);
        Bitmap t = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), r, true);
        if (t != b) b.recycle();
        if (t.isMutable()) return t;
        Bitmap mt = t.copy(Bitmap.Config.ARGB_8888, true);
        t.recycle();
        return mt;
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
