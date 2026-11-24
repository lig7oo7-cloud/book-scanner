package com.lig7oo7.bookscan;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

/**
 * 미리보기 프레임의 밝기를 64x48 점으로 뽑아 직전 프레임과의 평균 차이를 낸다.
 * 차이가 크면 '움직이는 중'(책장 넘김), 작으면 '멈춤'.
 */
final class MotionAnalyzer implements ImageAnalysis.Analyzer {
    static final int GX = 64, GY = 48;

    interface Listener { void onFrame(float diff, int[] sig); }

    private final Listener listener;
    private int[] prev;
    private volatile TextTracker tracker;

    MotionAnalyzer(Listener l) { listener = l; }

    /** null이면 글자 테두리 추적 끔 */
    void setTracker(TextTracker t) { tracker = t; }

    @Override
    public void analyze(@NonNull ImageProxy img) {
        try {
            ImageProxy.PlaneProxy p = img.getPlanes()[0];      // Y(밝기) 평면
            ByteBuffer buf = p.getBuffer();
            int rs = p.getRowStride(), ps = p.getPixelStride();
            int w = img.getWidth(), h = img.getHeight();
            int[] sig = new int[GX * GY];
            for (int j = 0; j < GY; j++) {
                int y = (int) ((j + 0.5f) * h / GY);
                for (int i = 0; i < GX; i++) {
                    int x = (int) ((i + 0.5f) * w / GX);
                    int idx = y * rs + x * ps;
                    sig[j * GX + i] = idx < buf.limit() ? (buf.get(idx) & 0xff) : 0;
                }
            }
            TextTracker t = tracker;
            if (t != null && !t.busy()) {
                t.submit(buf, rs, ps, w, h, img.getImageInfo().getRotationDegrees());
            }
            float diff = prev == null ? -1f : meanAbsDiff(sig, prev, false);
            prev = sig;
            listener.onFrame(diff, sig);
        } finally {
            img.close();
        }
    }

    /**
     * 두 신호의 평균 절대 차이. normalize=true면 전체 밝기 차이(노출 변화)를 빼고 비교한다
     * — 같은 페이지 판정용.
     */
    static float meanAbsDiff(int[] a, int[] b, boolean normalize) {
        int n = Math.min(a.length, b.length);
        float ma = 0, mb = 0;
        if (normalize) {
            long sa = 0, sb = 0;
            for (int k = 0; k < n; k++) { sa += a[k]; sb += b[k]; }
            ma = (float) sa / n;
            mb = (float) sb / n;
        }
        float s = 0;
        for (int k = 0; k < n; k++) s += Math.abs((a[k] - ma) - (b[k] - mb));
        return s / n;
    }
}
