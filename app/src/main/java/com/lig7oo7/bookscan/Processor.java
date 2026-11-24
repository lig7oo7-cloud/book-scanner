package com.lig7oo7.bookscan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 찍은 JPEG를 회전·축소·양면분할해서 세션 폴더에 저장한다. */
final class Processor {
    private Processor() {}

    static List<File> process(Context c, Session s, byte[] jpeg, int cameraRotation) throws IOException {
        boolean split = Prefs.split(c);
        int maxLong = Prefs.maxLong(c);
        int quality = Prefs.jpegQuality(c);
        // 양면이면 반쪽의 긴 변이 maxLong 정도 되도록 전체는 조금 크게 둔다 (펼친 면 가로:세로 ≈ 1.4:1)
        int limit = split ? Math.round(maxLong * 1.45f) : maxLong;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        int longSide = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (longSide / (sample * 2) >= limit) sample *= 2;

        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (b == null) throw new IOException("사진을 읽지 못했어요");

        float scale = Math.min(1f, (float) limit / Math.max(b.getWidth(), b.getHeight()));
        int rot = ((cameraRotation + Prefs.extraRotation(c)) % 360 + 360) % 360;
        if (scale < 0.999f || rot != 0) {
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }

        List<File> out = new ArrayList<>();
        try {
            if (split && b.getWidth() > b.getHeight()) {
                int half = b.getWidth() / 2;
                Bitmap left = Bitmap.createBitmap(b, 0, 0, half, b.getHeight());
                Bitmap right = Bitmap.createBitmap(b, half, 0, b.getWidth() - half, b.getHeight());
                if (Prefs.rightFirst(c)) {
                    out.add(save(s, right, quality));
                    out.add(save(s, left, quality));
                } else {
                    out.add(save(s, left, quality));
                    out.add(save(s, right, quality));
                }
                left.recycle();
                right.recycle();
            } else {
                out.add(save(s, b, quality));
            }
        } catch (IOException e) {
            for (File f : out) //noinspection ResultOfMethodCallIgnored
                f.delete();
            throw e;
        } finally {
            b.recycle();
        }
        return out;
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
