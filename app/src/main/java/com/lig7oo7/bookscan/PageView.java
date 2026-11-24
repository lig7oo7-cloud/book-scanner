package com.lig7oo7.bookscan;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * 쪽 사진 위에 인식된 글자 줄을 겹쳐 보여주는 화면.
 * 보기: 0 = 원본, 1 = 인식 영역 박스, 2 = 인식 글자 덮어보기.
 * 두 손가락 확대, 두 번 톡 확대/원래대로, 줄 톡 → 그 줄 선택, 확대 안 했을 때 옆으로 밀면 쪽 넘김.
 */
final class PageView extends View {
    interface Listener {
        void onLineTap(PdfWriter.Line line);
        void onSwipe(int dir);   // +1 다음 쪽, -1 이전 쪽
    }

    private Bitmap bmp;
    private float imgScale = 1f;            // 원본 사진 좌표 → bmp 좌표 (축소해서 읽었을 때)
    private List<PdfWriter.Line> lines = Collections.emptyList();
    private int mode = 1;
    private PdfWriter.Line selected;
    private Listener listener;

    private final Matrix m = new Matrix();  // bmp 좌표 → 화면
    private final Matrix inv = new Matrix();
    private float baseScale = 1f;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxFill = new Paint(), boxLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selFill = new Paint(), cover = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    PageView(Context c) {
        super(c);
        boxFill.setColor(0x2200BCD4);
        boxLine.setStyle(Paint.Style.STROKE);
        boxLine.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        boxLine.setColor(0xCC00ACC1);
        selFill.setColor(0x55FF9800);
        cover.setColor(0xF2FFFFF0);
        text.setColor(0xFF111111);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float cur = currentScale();
                float f = d.getScaleFactor();
                float target = Math.max(baseScale, Math.min(baseScale * 8f, cur * f));
                f = target / cur;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                clampPan();
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoomed()) {
                    m.postTranslate(-dx, -dy);
                    clampPan();
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (!zoomed() && e1 != null && listener != null
                        && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(e2.getX() - e1.getX()) > getWidth() * 0.15f) {
                    listener.onSwipe(vx < 0 ? +1 : -1);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (zoomed()) fit();
                else {
                    m.postScale(2.5f, 2.5f, e.getX(), e.getY());
                    clampPan();
                }
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                PdfWriter.Line hit = hitTest(e.getX(), e.getY());
                selected = hit;
                invalidate();
                if (listener != null) listener.onLineTap(hit);
                return true;
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    void setPage(Bitmap b, int origW, List<PdfWriter.Line> l) {
        bmp = b;
        imgScale = origW > 0 && b != null ? (float) b.getWidth() / origW : 1f;
        lines = l == null ? Collections.<PdfWriter.Line>emptyList() : l;
        selected = null;
        fit();
        invalidate();
    }

    void setMode(int md) {
        mode = md;
        invalidate();
    }

    void select(PdfWriter.Line l) {
        selected = l;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { fit(); }

    private void fit() {
        m.reset();
        if (bmp == null || getWidth() == 0) return;
        float s = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
        baseScale = s;
        m.postScale(s, s);
        m.postTranslate((getWidth() - bmp.getWidth() * s) / 2f, (getHeight() - bmp.getHeight() * s) / 2f);
    }

    private float currentScale() {
        float[] v = new float[9];
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private boolean zoomed() { return currentScale() > baseScale * 1.05f; }

    /** 사진이 화면 밖으로 너무 나가지 않게 */
    private void clampPan() {
        if (bmp == null) return;
        RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
        m.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        m.postTranslate(dx, dy);
    }

    /** 줄 꼭짓점(원본 좌표) → 화면 좌표 */
    private float[] screenPts(PdfWriter.Line l) {
        float[] p = new float[8];
        for (int i = 0; i < 8; i++) p[i] = l.p[i] * imgScale;
        m.mapPoints(p);
        return p;
    }

    private PdfWriter.Line hitTest(float x, float y) {
        PdfWriter.Line best = null;
        for (PdfWriter.Line l : lines) {
            if (inside(screenPts(l), x, y)) best = l;
        }
        return best;
    }

    private static boolean inside(float[] q, float x, float y) {
        boolean in = false;
        for (int i = 0, j = 3; i < 4; j = i++) {
            float xi = q[2 * i], yi = q[2 * i + 1], xj = q[2 * j], yj = q[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-6f) + xi) in = !in;
        }
        return in;
    }

    private static Path path(float[] q) {
        Path p = new Path();
        p.moveTo(q[0], q[1]);
        for (int i = 1; i < 4; i++) p.lineTo(q[2 * i], q[2 * i + 1]);
        p.close();
        return p;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) return;
        c.drawBitmap(bmp, m, bmpPaint);
        for (PdfWriter.Line l : lines) {
            float[] q = screenPts(l);
            Path p = path(q);
            if (mode == 2) {
                c.drawPath(p, cover);
                drawLineText(c, q, l.text);
            } else if (mode == 1) {
                c.drawPath(p, boxFill);
                c.drawPath(p, boxLine);
            }
            if (l == selected) c.drawPath(p, selFill);
        }
    }

    /** 줄 상자 안에 인식된 글자를 같은 방향·폭으로 그린다 */
    private void drawLineText(Canvas c, float[] q, String s) {
        float w = (float) Math.hypot(q[2] - q[0], q[3] - q[1]);
        float h = (float) Math.hypot(q[6] - q[0], q[7] - q[1]);
        if (w < 4 || h < 4) return;
        text.setTextScaleX(1f);
        text.setTextSize(h * 0.82f);
        float tw = text.measureText(s);
        if (tw > 0) text.setTextScaleX(Math.max(0.4f, Math.min(1.6f, w / tw)));
        float ang = (float) Math.toDegrees(Math.atan2(q[3] - q[1], q[2] - q[0]));
        c.save();
        c.rotate(ang, q[6], q[7]);
        c.drawText(s, q[6], q[7] - h * 0.18f, text);
        c.restore();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }
}
