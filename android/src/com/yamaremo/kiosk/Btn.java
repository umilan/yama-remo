package com.yamaremo.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Flat touch target: a text label, or an outline icon with an optional
 * caption under it. bg = 0 means no background (only a faint press highlight).
 */
public class Btn extends View {
    static final int ICON_NONE = 0, ICON_PREV = 1, ICON_NEXT = 2, ICON_PLAY = 3, ICON_PAUSE = 4,
            ICON_SHUFFLE = 5, ICON_REPEAT = 6, ICON_REPEAT_ONE = 7, ICON_SETTINGS = 8,
            ICON_SPEAKER = 9, ICON_MUTED = 10;

    String label = "";
    int icon = ICON_NONE;
    int bg = 0;
    int fg = MainActivity.C_TEXT;
    float textSize = 20;          // sp
    boolean bold = true;
    float offsetY;                // draw the label this far below the centre
    Runnable repeat;              // if set: fires on press and repeats while held

    private boolean pressed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Handler h = new Handler();

    private final Runnable repeater = new Runnable() {
        public void run() {
            if (!pressed || repeat == null) return;
            repeat.run();
            h.postDelayed(this, 150);
        }
    };

    public Btn(Context c) {
        super(c);
        setClickable(true);
    }

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    float sp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            pressed = true;
            invalidate();
            if (repeat != null) {
                repeat.run();
                h.postDelayed(repeater, 450);
            }
            return true;
        case MotionEvent.ACTION_MOVE:
            boolean inside = e.getX() >= 0 && e.getY() >= 0 && e.getX() <= getWidth() && e.getY() <= getHeight();
            if (!inside && pressed) { pressed = false; h.removeCallbacks(repeater); invalidate(); }
            return true;
        case MotionEvent.ACTION_UP:
            if (pressed) {
                pressed = false;
                h.removeCallbacks(repeater);
                invalidate();
                if (repeat == null) performClick();
            }
            return true;
        case MotionEvent.ACTION_CANCEL:
            pressed = false;
            h.removeCallbacks(repeater);
            invalidate();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), ht = getHeight();
        float r = dp(14);
        rect.set(dp(4), dp(4), w - dp(4), ht - dp(4));
        paint.setStyle(Paint.Style.FILL);
        if (bg != 0) {
            paint.setColor(bg);
            c.drawRoundRect(rect, r, r, paint);
        }
        if (pressed) {
            paint.setColor(0x26FFFFFF);
            c.drawRoundRect(rect, r, r, paint);
        }

        float cx = w / 2f, cy = ht / 2f;
        if (icon != ICON_NONE) {
            boolean caption = label != null && label.length() > 0;
            float s = dp(34);
            float iy = caption ? cy - dp(14) : cy;
            drawIcon(c, cx, iy, s);
            if (caption) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fg);
                paint.setTextSize(sp(15));
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(label, cx, iy + s / 2 + dp(14) + sp(15) * 0.8f, paint);
            }
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fg);
        paint.setTextSize(sp(textSize));
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        paint.setTextAlign(Paint.Align.CENTER);
        String t = label;
        float max = w - dp(8);
        while (t.length() > 1 && paint.measureText(t) > max) t = t.substring(0, t.length() - 1);
        Paint.FontMetrics fm = paint.getFontMetrics();
        c.drawText(t, cx, cy + offsetY - (fm.ascent + fm.descent) / 2f, paint);
    }

    private void stroke() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3.2f));
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(fg);
    }

    private void drawIcon(Canvas c, float cx, float cy, float s) {
        stroke();
        float hs = s / 2;
        path.reset();
        switch (icon) {
        case ICON_PREV:
            c.drawLine(cx - hs * 0.7f, cy - hs * 0.8f, cx - hs * 0.7f, cy + hs * 0.8f, paint);
            path.moveTo(cx + hs * 0.7f, cy - hs * 0.8f);
            path.lineTo(cx + hs * 0.7f, cy + hs * 0.8f);
            path.lineTo(cx - hs * 0.45f, cy);
            path.close();
            c.drawPath(path, paint);
            break;
        case ICON_NEXT:
            c.drawLine(cx + hs * 0.7f, cy - hs * 0.8f, cx + hs * 0.7f, cy + hs * 0.8f, paint);
            path.moveTo(cx - hs * 0.7f, cy - hs * 0.8f);
            path.lineTo(cx - hs * 0.7f, cy + hs * 0.8f);
            path.lineTo(cx + hs * 0.45f, cy);
            path.close();
            c.drawPath(path, paint);
            break;
        case ICON_PLAY:
            path.moveTo(cx - hs * 0.55f, cy - hs * 0.85f);
            path.lineTo(cx - hs * 0.55f, cy + hs * 0.85f);
            path.lineTo(cx + hs * 0.8f, cy);
            path.close();
            c.drawPath(path, paint);
            break;
        case ICON_PAUSE: {
            float bw = hs * 0.5f, rr = bw / 2;
            rect.set(cx - hs * 0.75f, cy - hs * 0.95f, cx - hs * 0.75f + bw, cy + hs * 0.95f);
            c.drawRoundRect(rect, rr, rr, paint);
            rect.set(cx + hs * 0.75f - bw, cy - hs * 0.95f, cx + hs * 0.75f, cy + hs * 0.95f);
            c.drawRoundRect(rect, rr, rr, paint);
            break;
        }
        case ICON_SHUFFLE: {
            float l = cx - hs * 0.9f, rgt = cx + hs * 0.75f, t = cy - hs * 0.5f, b = cy + hs * 0.5f;
            path.moveTo(l, t);
            path.cubicTo(cx, t, cx - hs * 0.1f, b, rgt, b);
            path.moveTo(l, b);
            path.cubicTo(cx, b, cx - hs * 0.1f, t, rgt, t);
            c.drawPath(path, paint);
            arrow(c, rgt, t, hs * 0.32f);
            arrow(c, rgt, b, hs * 0.32f);
            break;
        }
        case ICON_SETTINGS: {
            // gear: ring + 8 teeth + hub
            float ro = hs * 0.62f, ri = hs * 0.26f;
            c.drawCircle(cx, cy, ro, paint);
            c.drawCircle(cx, cy, ri, paint);
            for (int k = 0; k < 8; k++) {
                double a = Math.PI / 4 * k;
                float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
                c.drawLine(cx + dx * ro, cy + dy * ro, cx + dx * hs * 0.92f, cy + dy * hs * 0.92f, paint);
            }
            break;
        }
        case ICON_SPEAKER:
        case ICON_MUTED: {
            path.moveTo(cx - hs * 0.9f, cy - hs * 0.3f);
            path.lineTo(cx - hs * 0.5f, cy - hs * 0.3f);
            path.lineTo(cx - hs * 0.05f, cy - hs * 0.75f);
            path.lineTo(cx - hs * 0.05f, cy + hs * 0.75f);
            path.lineTo(cx - hs * 0.5f, cy + hs * 0.3f);
            path.lineTo(cx - hs * 0.9f, cy + hs * 0.3f);
            path.close();
            c.drawPath(path, paint);
            if (icon == ICON_MUTED) {
                c.drawLine(cx + hs * 0.3f, cy - hs * 0.3f, cx + hs * 0.9f, cy + hs * 0.3f, paint);
                c.drawLine(cx + hs * 0.3f, cy + hs * 0.3f, cx + hs * 0.9f, cy - hs * 0.3f, paint);
            } else {
                rect.set(cx - hs * 0.25f, cy - hs * 0.4f, cx + hs * 0.45f, cy + hs * 0.4f);
                c.drawArc(rect, -50, 100, false, paint);
                rect.set(cx - hs * 0.35f, cy - hs * 0.8f, cx + hs * 0.85f, cy + hs * 0.8f);
                c.drawArc(rect, -50, 100, false, paint);
            }
            break;
        }
        case ICON_REPEAT:
        case ICON_REPEAT_ONE: {
            rect.set(cx - hs * 0.85f, cy - hs * 0.55f, cx + hs * 0.85f, cy + hs * 0.55f);
            c.drawRoundRect(rect, hs * 0.45f, hs * 0.45f, paint);
            arrow(c, cx + hs * 0.25f, cy - hs * 0.55f, hs * 0.3f);
            if (icon == ICON_REPEAT_ONE) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(hs * 0.8f);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);
                c.drawText("1", cx, cy + hs * 0.28f, paint);
            }
            break;
        }
        }
    }

    /** Small arrowhead pointing right with its tip at (x, y). */
    private void arrow(Canvas c, float x, float y, float a) {
        c.drawLine(x - a, y - a, x, y, paint);
        c.drawLine(x - a, y + a, x, y, paint);
    }
}
