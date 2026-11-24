package com.lig7oo7.bookscan;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 미리보기 위에 '스캔할 범위' 사각형을 그린다. 편집 중에는 모서리 4개를 끌어 맞출 수 있다.
 * 좌표는 카메라 화면(3:4) 기준 0~1 로 저장한다.
 */
final class CropOverlay extends View {
    private final float[] q = Prefs.FULL.clone();
    private boolean editing;
    private int active = -1;
    private final RectF content = new RectF();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dim = new Paint();
    private final Path path = new Path();
    private final float density;

    CropOverlay(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * density);
        line.setColor(0xFF4DD0B1);
        handle.setColor(0xFFFFB300);
        dim.setColor(0x66000000);
    }

    void setQuad(float[] v) {
        System.arraycopy(v, 0, q, 0, 8);
        invalidate();
    }

    float[] getQuad() { return q.clone(); }

    void setEditing(boolean e) {
        editing = e;
        invalidate();
    }

    boolean isEditing() { return editing; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // 미리보기는 FIT_CENTER + 3:4(세로) — 실제 카메라 화면이 보이는 영역
        float target = 3f / 4f;
        if ((float) w / h > target) {
            float cw = h * target;
            content.set((w - cw) / 2f, 0, (w + cw) / 2f, h);
        } else {
            float ch = w / target;
            content.set(0, (h - ch) / 2f, w, (h + ch) / 2f);
        }
    }

    private float px(int i) { return content.left + q[2 * i] * content.width(); }
    private float py(int i) { return content.top + q[2 * i + 1] * content.height(); }

    @Override
    protected void onDraw(Canvas c) {
        if (!editing && Prefs.isFull(q)) return;
        path.reset();
        path.moveTo(px(0), py(0));
        for (int i = 1; i < 4; i++) path.lineTo(px(i), py(i));
        path.close();
        if (editing) {
            // 범위 밖을 어둡게
            Path outside = new Path();
            outside.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            outside.setFillType(Path.FillType.EVEN_ODD);
            outside.addPath(path);
            c.drawPath(outside, dim);
        }
        line.setAlpha(editing ? 255 : 140);
        c.drawPath(path, line);
        if (editing) {
            for (int i = 0; i < 4; i++) c.drawCircle(px(i), py(i), 14 * density, handle);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editing) return false;          // 편집 아닐 때는 아래 미리보기(초점 맞추기)로 넘김
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                active = -1;
                float best = 56 * density;
                for (int i = 0; i < 4; i++) {
                    float d = (float) Math.hypot(x - px(i), y - py(i));
                    if (d < best) { best = d; active = i; }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (active >= 0) {
                    q[2 * active] = clamp((x - content.left) / content.width());
                    q[2 * active + 1] = clamp((y - content.top) / content.height());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = -1;
                return true;
        }
        return true;
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
