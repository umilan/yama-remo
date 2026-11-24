package com.yamaremo.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Dotted volume track, one dot per volume step: tap or drag to set the level. */
public class VolumeBar extends View {
    interface Listener { void onPick(int fraction1000); }

    float fraction;
    float offsetY;  // draw the dots this far below the vertical centre (touch area unchanged)
    int steps = 100;
    int color = MainActivity.C_TEXT;
    Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public VolumeBar(Context c) { super(c); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float f = Math.max(0, Math.min(1, e.getX() / (float) getWidth()));
            if (listener != null) listener.onPick(Math.round(f * 1000));
            return true;
        }
        return a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL || super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        int n = Math.max(2, Math.min(steps, 120));
        int filled = Math.round(fraction * n);
        float w = getWidth(), cy = getHeight() / 2f + offsetY;
        float on = 1.6f * d, off = 1.2f * d, onH = 3f * d;
        for (int i = 0; i < n; i++) {
            float x = on + (w - 2 * on) * i / (n - 1);
            if (i < filled) {
                p.setColor(color);
                c.drawRect(x - on, cy - onH, x + on, cy + onH, p);
            } else {
                p.setColor(MainActivity.C_DIM);
                c.drawRect(x - off, cy - off, x + off, cy + off, p);
            }
        }
    }
}
