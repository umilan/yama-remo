package com.yamaremo.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/** Big rounded touch button drawing either a text label or a transport icon. */
public class Btn extends View {
    static final int ICON_NONE = 0, ICON_PREV = 1, ICON_NEXT = 2, ICON_PLAY = 3, ICON_PAUSE = 4;

    String label = "";
    int icon = ICON_NONE;
    int bg = MainActivity.C_PANEL;
    int fg = MainActivity.C_TEXT;
    float textSize = 20;          // sp
    Runnable repeat;              // if set: fires on press and repeats while held

    private boolean pressed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Handler h = new Handler();
    private int repeats;

    private final Runnable repeater = new Runnable() {
        public void run() {
            if (!pressed || repeat == null) return;
            repeat.run();
            repeats++;
            h.postDelayed(this, 150);
        }
    };

    public Btn(Context c) {
        super(c);
        setClickable(true);
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            pressed = true;
            invalidate();
            if (repeat != null) {
                repeat.run();
                repeats = 0;
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

    private static int darken(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (r + g + b < 200) { r += 40; g += 40; b += 46; }       // dark panels: lighten
        else { r = r * 3 / 4; g = g * 3 / 4; b = b * 3 / 4; }     // coloured: darken
        return 0xFF000000 | (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), ht = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? darken(bg) : bg);
        rect.set(0, 0, w, ht);
        float r = dp(14);
        c.drawRoundRect(rect, r, r, paint);

        paint.setColor(fg);
        float cx = w / 2f, cy = ht / 2f, s = Math.min(w, ht) * 0.34f;
        switch (icon) {
        case ICON_PLAY:
            tri(c, cx + s * 0.08f, cy, s * 1.2f, true);
            break;
        case ICON_PAUSE:
            c.drawRect(cx - s * 0.5f, cy - s * 0.55f, cx - s * 0.15f, cy + s * 0.55f, paint);
            c.drawRect(cx + s * 0.15f, cy - s * 0.55f, cx + s * 0.5f, cy + s * 0.55f, paint);
            break;
        case ICON_PREV:
            c.drawRect(cx - s, cy - s * 0.5f, cx - s * 0.84f, cy + s * 0.5f, paint);
            tri(c, cx - s * 0.33f, cy, s, false);
            tri(c, cx + s * 0.5f, cy, s, false);
            break;
        case ICON_NEXT:
            tri(c, cx - s * 0.5f, cy, s, true);
            tri(c, cx + s * 0.33f, cy, s, true);
            c.drawRect(cx + s * 0.84f, cy - s * 0.5f, cx + s, cy + s * 0.5f, paint);
            break;
        default:
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize, getResources().getDisplayMetrics()));
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            String t = label;
            float max = w - dp(12);
            while (t.length() > 1 && paint.measureText(t) > max) t = t.substring(0, t.length() - 1);
            Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(t, cx, cy - (fm.ascent + fm.descent) / 2f, paint);
        }
    }

    private void tri(Canvas c, float cx, float cy, float size, boolean right) {
        float hs = size / 2f;
        path.reset();
        if (right) {
            path.moveTo(cx - hs, cy - hs); path.lineTo(cx - hs, cy + hs); path.lineTo(cx + hs, cy);
        } else {
            path.moveTo(cx + hs, cy - hs); path.lineTo(cx + hs, cy + hs); path.lineTo(cx - hs, cy);
        }
        path.close();
        c.drawPath(path, paint);
    }
}
