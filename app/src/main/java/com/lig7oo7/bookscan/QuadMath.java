package com.lig7oo7.bookscan;

import java.util.Arrays;
import java.util.List;

/** 사각형 계산 모음 (안드로이드 의존 없음 → PC에서 시험 가능) */
final class QuadMath {
    private QuadMath() {}

    /** 원본(센서) 좌표 → 화면 기준 0~1 좌표. rot = 원본을 바로 세우려면 시계방향으로 돌릴 각도 */
    static float[] toDisplay(float x, float y, float W, float H, int rot) {
        switch (rot) {
            case 90:  return new float[]{1 - y / H, x / W};
            case 180: return new float[]{1 - x / W, 1 - y / H};
            case 270: return new float[]{y / H, 1 - x / W};
            default:  return new float[]{x / W, y / H};
        }
    }

    /**
     * 글자 줄들의 꼭짓점을 모두 감싸는 사각형. 화면에서 글자가 바로 서 있다고 보고
     * 왼위=(x+y 최소), 오른위=(x-y 최대), 오른아래=(x+y 최대), 왼아래=(x-y 최소)를 고른다.
     * → 책이 비스듬히(사다리꼴) 찍혀도 그 모양을 따라간다.
     */
    static float[] quadAround(List<float[]> lines, float dW, float dH, float margin) {
        if (lines.size() < 3) return null;
        // 외톨이 줄(배경의 다른 글자) 빼기: 줄 중심들의 중앙값에서 너무 먼 줄 제거
        int n = lines.size();
        float[] cx = new float[n], cy = new float[n];
        for (int i = 0; i < n; i++) {
            float[] p = lines.get(i);
            cx[i] = (p[0] + p[2] + p[4] + p[6]) / 4f;
            cy[i] = (p[1] + p[3] + p[5] + p[7]) / 4f;
        }
        float mx = median(cx), my = median(cy);
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = (float) Math.hypot(cx[i] - mx, cy[i] - my);
        float[] ds = d.clone();
        Arrays.sort(ds);
        float cut = ds[(int) Math.floor((n - 1) * 0.85)] * 1.5f + 0.02f * Math.max(dW, dH);

        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE, minD = Float.MAX_VALUE, maxD = -Float.MAX_VALUE;
        float[] tl = null, tr = null, br = null, bl = null;
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (d[i] > cut) continue;
            kept++;
            float[] p = lines.get(i);
            for (int k = 0; k < 4; k++) {
                float x = p[2 * k], y = p[2 * k + 1];
                float s = x + y, df = x - y;
                if (s < minS) { minS = s; tl = new float[]{x, y}; }
                if (s > maxS) { maxS = s; br = new float[]{x, y}; }
                if (df > maxD) { maxD = df; tr = new float[]{x, y}; }
                if (df < minD) { minD = df; bl = new float[]{x, y}; }
            }
        }
        if (kept < 3 || tl == null) return null;
        float[] q = {tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]};
        // 여백만큼 바깥으로 넓히기
        float ccx = (q[0] + q[2] + q[4] + q[6]) / 4f, ccy = (q[1] + q[3] + q[5] + q[7]) / 4f;
        float grow = 1f + margin * 2f;
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            out[2 * i] = clamp01((ccx + (q[2 * i] - ccx) * grow) / dW);
            out[2 * i + 1] = clamp01((ccy + (q[2 * i + 1] - ccy) * grow) / dH);
        }
        // 너무 작거나 찌그러진 건 버림
        float area = Math.abs(polyArea(out));
        if (area < 0.03f) return null;
        return out;
    }

    static float polyArea(float[] q) {
        float a = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            a += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1];
        }
        return a / 2f;
    }

    static float maxDelta(float[] a, float[] b) {
        float m = 0;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    static float median(float[] v) {
        float[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }


    /** 화면 기준 0~1 사각형을 왼위·오른위·오른아래·왼아래 순으로 정리 */
    static float[] order(float[] q) {
        float[] out = new float[8];
        float minS = Float.MAX_VALUE, maxS = -Float.MAX_VALUE, minD = Float.MAX_VALUE, maxD = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float x = q[2 * i], y = q[2 * i + 1], s = x + y, d = x - y;
            if (s < minS) { minS = s; out[0] = x; out[1] = y; }
            if (d > maxD) { maxD = d; out[2] = x; out[3] = y; }
            if (s > maxS) { maxS = s; out[4] = x; out[5] = y; }
            if (d < minD) { minD = d; out[6] = x; out[7] = y; }
        }
        return out;
    }
}
