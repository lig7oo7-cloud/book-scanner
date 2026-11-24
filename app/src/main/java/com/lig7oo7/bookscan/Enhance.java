package com.lig7oo7.bookscan;

import android.graphics.Bitmap;

/**
 * 문서 보정: 종이 밝기(조명 얼룩, 책 가운데 그림자)를 추정해 나눠서 배경은 하얗게,
 * 글씨는 진하게 만든 흑백 이미지로 바꾼다. vFlat의 '문서' 필터와 같은 역할.
 */
final class Enhance {
    private Enhance() {}

    private static int lum(int c) {
        return (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
    }

    /** b는 수정 가능한(mutable) 비트맵이어야 한다. 제자리에서 바꾼다. */
    static void document(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int sw = Math.max(3, w / 32), sh = Math.max(3, h / 32);

        // 1) 아주 작게 줄여서 '종이 밝기 지도'를 만든다
        Bitmap small = Bitmap.createScaledBitmap(b, sw, sh, true);
        int[] sp = new int[sw * sh];
        small.getPixels(sp, 0, sw, 0, 0, sw, sh);
        small.recycle();
        int[] l = new int[sw * sh];
        for (int i = 0; i < l.length; i++) l[i] = lum(sp[i]);
        // 글씨 때문에 어두워진 칸을 피하려고 주변 3x3 중 가장 밝은 값을 종이로 본다
        int[] mx = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int m = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(sh - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.min(sw - 1, Math.max(0, x + dx));
                        m = Math.max(m, l[yy * sw + xx]);
                    }
                }
                mx[y * sw + x] = 0xff000000 | (m << 16) | (m << 8) | m;
            }
        }
        Bitmap bgSmall = Bitmap.createBitmap(mx, sw, sh, Bitmap.Config.ARGB_8888);
        Bitmap bg = Bitmap.createScaledBitmap(bgSmall, w, h, true);
        bgSmall.recycle();

        // 2) 한 줄씩: 밝기 / 종이밝기 → 대비 늘리기
        int[] row = new int[w], brow = new int[w];
        final float lo = 0.35f, hi = 0.92f;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            bg.getPixels(brow, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int bl = Math.max(24, brow[x] & 0xff);
                float n = (float) lum(row[x]) / bl;
                int g = (int) ((n - lo) / (hi - lo) * 255f);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                row[x] = 0xff000000 | (g << 16) | (g << 8) | g;
            }
            b.setPixels(row, 0, w, 0, y, w, 1);
        }
        bg.recycle();
    }
}
