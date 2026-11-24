package com.lig7oo7.bookscan;

import android.graphics.Point;
import android.os.SystemClock;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 미리보기 화면에서 글자 줄들을 찾아, 글자 전체를 감싸는 사각형(원근 포함)을 계속 따라간다.
 * 결과는 화면 기준 0~1 좌표(왼위·오른위·오른아래·왼아래). 초당 2~4번 정도 갱신.
 */
final class TextTracker {
    interface Listener { void onQuad(float[] quad, int lines); }

    private final TextRecognizer rec =
            TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
    private final Executor exec = Executors.newSingleThreadExecutor();
    private final Listener listener;
    private volatile boolean inFlight;
    private volatile float[] quad;          // 부드럽게 다듬은 최신 사각형
    private volatile long quadTime;
    private volatile float marginPct = 4f;
    private byte[] nv21;

    TextTracker(Listener l) { listener = l; }

    void setMargin(float pct) { marginPct = pct; }

    boolean busy() { return inFlight; }

    /** 최근 maxAgeMs 안에 찾은 사각형, 없으면 null */
    float[] fresh(long maxAgeMs) {
        float[] q = quad;
        return q != null && SystemClock.elapsedRealtime() - quadTime <= maxAgeMs ? q.clone() : null;
    }

    void reset() { quad = null; }

    /**
     * 분석 스레드에서 호출: 밝기(Y) 평면만 넘겨받아 글자 인식을 비동기로 돌린다.
     * (색 정보는 회색으로 채움 — 글자 위치 찾는 데는 밝기만으로 충분)
     */
    void submit(java.nio.ByteBuffer y, int rowStride, int pixelStride, int w, int h, int rotation) {
        if (inFlight) return;
        int size = w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2);
        if (nv21 == null || nv21.length != size) {
            nv21 = new byte[size];
            Arrays.fill(nv21, w * h, size, (byte) 128);
        }
        if (pixelStride == 1 && rowStride == w) {
            y.rewind();
            y.get(nv21, 0, w * h);
        } else {
            for (int r = 0; r < h; r++) {
                int base = r * rowStride;
                for (int c = 0; c < w; c++) nv21[r * w + c] = y.get(base + c * pixelStride);
            }
        }
        inFlight = true;
        final byte[] data = nv21.clone();
        InputImage img = InputImage.fromByteArray(data, w, h, 0, InputImage.IMAGE_FORMAT_NV21);
        rec.process(img)
                .addOnSuccessListener(exec, t -> {
                    handle(t, w, h, rotation);
                    inFlight = false;
                })
                .addOnFailureListener(exec, e -> inFlight = false);
    }

    private void handle(Text t, int w, int h, int rot) {
        // 줄마다 꼭짓점 4개 → 화면(세로) 기준 픽셀 좌표로
        boolean swap = rot == 90 || rot == 270;
        float dW = swap ? h : w, dH = swap ? w : h;
        List<float[]> lines = new ArrayList<>();
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line ln : b.getLines()) {
                Point[] cp = ln.getCornerPoints();
                if (cp == null || cp.length != 4) continue;
                if (ln.getText().trim().length() < 2) continue;
                float[] pts = new float[8];
                for (int i = 0; i < 4; i++) {
                    float[] uv = QuadMath.toDisplay(cp[i].x, cp[i].y, w, h, rot);
                    pts[2 * i] = uv[0] * dW;
                    pts[2 * i + 1] = uv[1] * dH;
                }
                lines.add(pts);
            }
        }
        // 책이 옆으로 누워 있어도 글자 방향대로 사각형을 잡는다
        float[] px = QuadMath.pageQuad(lines, dW, dH, marginPct / 100f);
        if (px == null) {
            // 잠깐 놓쳐도 바로 지우지 않음 (fresh()의 유효시간으로 자연히 만료)
            listener.onQuad(fresh(1200), 0);
            return;
        }
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            q[2 * i] = px[2 * i] / dW;
            q[2 * i + 1] = px[2 * i + 1] / dH;
        }
        float[] prev = quad;
        if (prev != null && SystemClock.elapsedRealtime() - quadTime < 1500 && QuadMath.maxDelta(prev, q) < 0.12f) {
            for (int i = 0; i < 8; i++) q[i] = prev[i] * 0.5f + q[i] * 0.5f;   // 떨림 줄이기
        }
        quad = q;
        quadTime = SystemClock.elapsedRealtime();
        listener.onQuad(q.clone(), lines.size());
    }

    void close() { rec.close(); }
}
