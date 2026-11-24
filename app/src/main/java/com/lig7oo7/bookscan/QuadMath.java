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

    /**
     * 글자 줄들로 '본문 영역' 사각형을 찾는다 (픽셀 좌표). 책이 옆으로 누워 있어도(90°·180°) 된다.
     * 꼭짓점 순서는 '글자 기준' 왼위·오른위·오른아래·왼아래 → 이 순서대로 펴면 쪽이 저절로 바로 선다.
     *
     * 방법: 줄 방향(평균 각도)으로 좌표를 돌려 글자를 가로로 눕힌 뒤,
     *  - 왼쪽 가장자리: 줄 시작점들 중 가장 왼쪽 무리에 직선을 맞춰 기울기(원근)를 얻고, 모든 줄이 안쪽에 오도록 밈
     *  - 오른쪽도 같은 방식 (짧은 마지막 줄·들여쓰기는 무리에서 빠지므로 테두리를 끌어당기지 않음)
     *  - 위·아래: 가장 위/아래 줄
     *  - margin(0~1)만큼 바깥으로 넓힘
     * 줄이 3개 미만이거나 결과가 이상하면 null.
     */
    static float[] pageQuad(List<float[]> linesIn, float W, float H, float margin) {
        if (linesIn.size() < 3) return null;
        // 1) 외톨이 줄 빼기
        int n0 = linesIn.size();
        float[] cx = new float[n0], cy = new float[n0];
        for (int i = 0; i < n0; i++) {
            float[] p = linesIn.get(i);
            cx[i] = (p[0] + p[2] + p[4] + p[6]) / 4f;
            cy[i] = (p[1] + p[3] + p[5] + p[7]) / 4f;
        }
        float mx = median(cx), my = median(cy);
        float[] d = new float[n0];
        for (int i = 0; i < n0; i++) d[i] = (float) Math.hypot(cx[i] - mx, cy[i] - my);
        float[] ds = d.clone();
        Arrays.sort(ds);
        float cut = ds[(int) Math.floor((n0 - 1) * 0.85)] * 1.5f + 0.02f * Math.max(W, H);
        java.util.ArrayList<float[]> lines = new java.util.ArrayList<>();
        for (int i = 0; i < n0; i++) if (d[i] <= cut) lines.add(linesIn.get(i));
        int n = lines.size();
        if (n < 3) return null;

        // 2) 글자 방향 (줄의 왼위→오른위 방향 평균, 길이 가중)
        double sx = 0, sy = 0;
        for (float[] p : lines) { sx += p[2] - p[0]; sy += p[3] - p[1]; }
        if (sx == 0 && sy == 0) return null;
        double th = Math.atan2(sy, sx);
        float c = (float) Math.cos(th), s = (float) Math.sin(th);

        // 3) 글자 기준 좌표로 돌리기: x' = x c + y s, y' = -x s + y c
        float[] lx = new float[2 * n], ly = new float[2 * n], rx = new float[2 * n], ry = new float[2 * n];
        float[] hts = new float[n];
        float top = Float.MAX_VALUE, bot = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float[] p = lines.get(i);
            float[] t = new float[8];
            for (int k = 0; k < 4; k++) {
                float x = p[2 * k], y = p[2 * k + 1];
                t[2 * k] = x * c + y * s;
                t[2 * k + 1] = -x * s + y * c;
                top = Math.min(top, t[2 * k + 1]);
                bot = Math.max(bot, t[2 * k + 1]);
            }
            lx[2 * i] = t[0]; ly[2 * i] = t[1]; lx[2 * i + 1] = t[6]; ly[2 * i + 1] = t[7];   // 왼위, 왼아래
            rx[2 * i] = t[2]; ry[2 * i] = t[3]; rx[2 * i + 1] = t[4]; ry[2 * i + 1] = t[5];   // 오른위, 오른아래
            hts[i] = Math.abs((t[7] + t[5]) / 2f - (t[1] + t[3]) / 2f);
        }
        float lineH = Math.max(1f, median(hts));
        float height = bot - top;
        if (height < lineH) return null;

        float aL = edgeSlope(lx, ly, false, lineH, height);
        float aR = edgeSlope(rx, ry, true, lineH, height);
        float bL = Float.MAX_VALUE, bR = -Float.MAX_VALUE;
        for (int i = 0; i < 2 * n; i++) {
            bL = Math.min(bL, lx[i] - aL * ly[i]);
            bR = Math.max(bR, rx[i] - aR * ry[i]);
        }
        float width = ((aR * top + bR) - (aL * top + bL) + (aR * bot + bR) - (aL * bot + bL)) / 2f;
        if (width < lineH) return null;

        // 4) 여백
        float mw = margin * width, mh = margin * height;
        bL -= mw; bR += mw; top -= mh; bot += mh;
        float[] q = {
                aL * top + bL, top,
                aR * top + bR, top,
                aR * bot + bR, bot,
                aL * bot + bL, bot};

        // 5) 원래 좌표로 되돌리기: x = x' c - y' s, y = x' s + y' c
        float[] out = new float[8];
        for (int k = 0; k < 4; k++) {
            float xp = q[2 * k], yp = q[2 * k + 1];
            out[2 * k] = Math.max(0f, Math.min(W, xp * c - yp * s));
            out[2 * k + 1] = Math.max(0f, Math.min(H, xp * s + yp * c));
        }
        float area = Math.abs(polyArea(out));
        if (area < 0.03f * W * H || !convex(out)) return null;
        return out;
    }

    /**
     * 가장자리(왼쪽이면 가장 왼쪽 무리, 오른쪽이면 가장 오른쪽 무리) 점들에 x = a*y + b 직선을 맞춰 기울기 a를 낸다.
     * 맞는 점이 적거나 흩어져 있으면 0(글자 줄에 수직).
     */
    static float edgeSlope(float[] xs, float[] ys, boolean right, float lineH, float height) {
        int n = xs.length;
        float a = 0f, b = right ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (int i = 0; i < n; i++) b = right ? Math.max(b, xs[i]) : Math.min(b, xs[i]);
        float tol = Math.max(3f * lineH, 0.12f * height);
        for (int iter = 0; iter < 3; iter++) {
            double sy = 0, sx = 0, syy = 0, sxy = 0;
            int m = 0;
            for (int i = 0; i < n; i++) {
                float r = xs[i] - (a * ys[i] + b);
                if (Math.abs(r) <= tol) {
                    sy += ys[i]; sx += xs[i]; syy += ys[i] * ys[i]; sxy += ys[i] * xs[i];
                    m++;
                }
            }
            if (m < 6) return 0f;
            double den = m * syy - sy * sy;
            if (Math.abs(den) < 1e-6) return 0f;
            double na = (m * sxy - sy * sx) / den;
            // 무리가 세로로 충분히 퍼져 있어야 기울기를 믿는다
            double spread = Math.sqrt(Math.max(0, syy / m - (sy / m) * (sy / m)));
            if (spread < 0.15 * height) return 0f;
            a = (float) Math.max(-0.3, Math.min(0.3, na));
            b = (float) ((sx - a * sy) / m);
            tol = Math.max(1.5f * lineH, 0.02f * height);
        }
        return a;
    }

    static boolean convex(float[] q) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4, k = (i + 2) % 4;
            float cr = (q[2 * j] - q[2 * i]) * (q[2 * k + 1] - q[2 * j + 1])
                    - (q[2 * j + 1] - q[2 * i + 1]) * (q[2 * k] - q[2 * j]);
            int sg = cr > 0 ? 1 : (cr < 0 ? -1 : 0);
            if (sg == 0) continue;
            if (sign == 0) sign = sg;
            else if (sg != sign) return false;
        }
        return sign != 0;
    }

    /** 줄들의 평균 기울기(도). 시계방향이 +. 줄이 없으면 NaN */
    static float textAngleDeg(List<float[]> lines) {
        double sx = 0, sy = 0;
        for (float[] p : lines) { sx += p[2] - p[0]; sy += p[3] - p[1]; }
        if (sx == 0 && sy == 0) return Float.NaN;
        return (float) Math.toDegrees(Math.atan2(sy, sx));
    }

    /** 글자를 바로 세우려면 시계방향으로 몇 도(0/90/180/270) 돌려야 하는지 */
    static int uprightRotation(float angleDeg) {
        if (Float.isNaN(angleDeg)) return 0;
        int k = Math.round(angleDeg / 90f);          // 글자가 k*90° 만큼 돌아가 있음
        return (((-k * 90) % 360) + 360) % 360;
    }
}
