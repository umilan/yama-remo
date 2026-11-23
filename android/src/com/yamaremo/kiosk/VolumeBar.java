package com.yamaremo.kiosk;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Volume track: tap or drag anywhere on it to set the level. */
public class VolumeBar extends View {
    interface Listener { void onPick(int fraction1000); }

    float fraction;
    int color = MainActivity.C_ACCENT;
    Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

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
        float h = 14 * d, cy = getHeight() / 2f, w = getWidth();
        p.setColor(MainActivity.C_BG);
        r.set(0, cy - h / 2, w, cy + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, p);
        float fx = Math.max(h, w * fraction);
        p.setColor(color);
        r.set(0, cy - h / 2, fx, cy + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, p);
        // thumb
        p.setColor(MainActivity.C_TEXT);
        c.drawCircle(Math.max(h / 2, Math.min(w - h / 2, w * fraction)), cy, h * 0.9f, p);
    }
}
