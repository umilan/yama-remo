package com.yamaremo.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kiosk-style MusicCast remote for old Android tablets (built for the
 * Lenovo IdeaPad A3000-H, Android 4.2, 1024x600). Native counterpart of
 * musiccast-remote.html: talks to the speaker's YXC HTTP API directly, so
 * there is no CORS problem and no proxy is needed.
 */
public class MainActivity extends Activity {

    static final String PREFS = "yamaremo";
    static final String PREF_IP = "ip";
    static final String PREF_SCREEN_ON = "screen_on";
    static final String PREF_BOOT = "boot";
    static final String PREF_PIN = "pin";
    static final String DEFAULT_IP = "192.168.1.13"; // prefilled on first setup
    static final String PREF_UPDATE_URL = "update_url";
    static final String PREF_COMPACT_VOLUME = "compact_volume";
    static final String PREF_HIDE_SECS = "hide_controls_secs";
    static final String PREF_HIDE_ON = "hide_controls";
    static final String PREF_FAIL_LIMIT = "fail_limit";
    static final String PREF_HIDE_LOCATION = "hide_station_location";
    static final String PREF_FOLLOWER = "follow_speaker";
    static final String PREF_GESTURES = "swipe_gestures";
    static final String PREF_FONT = "font";
    static final String PREF_HIDE_NAV = "hide_nav_buttons";
    static final String PREF_TEXT_SCALE = "text_scale";

    /** Follower mode: the speaker's reported state always wins (other apps control it too). */
    boolean follower() { return prefs.getBoolean(PREF_FOLLOWER, true); }
    static final long UPDATE_EVERY_MS = 3600L * 1000; // hourly

    static final int C_BG = 0xFF000000;
    static final int C_TEXT = 0xFFE8E8EA;
    static final int C_GRAY = 0xFF8C8C8C;   // inactive tabs, artist
    static final int C_DIM = 0xFF747474;      // dimmed text: 50 % of the active white
    static final int C_DOT_OFF = 0xFF4E4E4E;  // empty volume dots
    static final int C_TAB_IDLE = C_DIM;      // inactive top tabs
    static final int C_RED = 0xFFC83C3C;
    /** Accent colour: Muted, Update, and messages triggered by gestures (swipe, pinch, double tap). */
    static final int C_ACCENT = 0xFFE0A030;
    static final int C_AMBER = C_ACCENT;

    static final String[] SRC_ID = { "airplay", "aux", "optical", "bluetooth", "net_radio" };
    static final String[] SRC_LABEL = { "AirPlay", "AUX", "Optical", "Bluetooth", "Radio" };

    static final int POLL_MS = 3000;

    // connection health (worker thread writes, UI reads)
    final AtomicInteger reqTotal = new AtomicInteger(), reqFailed = new AtomicInteger();
    int failStreak, worstStreak;
    long lastFailAt, startedAt = System.currentTimeMillis();
    android.net.wifi.WifiManager.WifiLock wifiLock;

    // speaker state (UI thread only)
    boolean everPolled, connected, powerOn, mute, playing, shuffle;
    String repeatMode = "off";
    int volume, maxVolume = 100;
    String input = "", track = "", artist = "", album = "";
    String playback = "";            // play / pause / stop, from getPlayInfo
    long powerChangedAt, inputChangedAt;
    boolean powerTarget;
    static final long SETTLE_MS = 8000; // how long the speaker gets to apply power/input changes
    long volumeHoldUntil;

    // views
    Btn otherTab, offBtn, prevBtn, shuffleBtn, settingsBtn, playBtn, muteBtn, repeatBtn, nextBtn, volDn, volUp;
    Btn[] srcBtns = new Btn[SRC_ID.length];
    TextView trackText, artistText, stationText, clockText, dateText, settingsText, ipText, updateText;
    android.widget.FrameLayout bottom;
    boolean transportAllowed = true; // speaker on and the input has tracks (first render() corrects it)
    boolean transportAwake = true; // false while auto-hidden
    boolean swallowGesture;
    LinearLayout playBox, clockBox, transportRow, volRow, offRow;
    VolumeBar volBar;
    boolean clockShown = false;  // first render() switches to the right screen
    Updater.Info pendingUpdate;
    long lastUpdateCheck;
    boolean updateBusy;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newSingleThreadExecutor();
    final AtomicBoolean polling = new AtomicBoolean(false);
    final AtomicInteger pendingVolume = new AtomicInteger(-1);
    SharedPreferences prefs;
    boolean resumed;

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Fonts.apply(this, prefs.getString(PREF_FONT, "default"));
        Fonts.scale = prefs.getFloat(PREF_TEXT_SCALE, 1f);
        new Thread(new Runnable() { public void run() { Updater.init(getApplicationContext()); } }).start();
        // Android 4.x reuses dead pooled HTTP connections, which makes random requests fail
        System.setProperty("http.keepAlive", "false");
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yamaremo");
            wifiLock.setReferenceCounted(false);
        } catch (Exception ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        applyScreenOn();
        setContentView(buildUi());
        render();
        if (TextUtils.isEmpty(ip())) ui.postDelayed(new Runnable() {
            public void run() { showSettings(false); }
        }, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        try { if (wifiLock != null) wifiLock.acquire(); } catch (Exception ignored) { }
        hideSystemBars();
        ui.removeCallbacks(pollLoop);
        ui.post(pollLoop);
        if (clockShown) { ui.removeCallbacks(clockTick); clockTick.run(); }
        if (System.currentTimeMillis() - lastUpdateCheck > UPDATE_EVERY_MS) checkForUpdates(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        try { if (wifiLock != null) wifiLock.release(); } catch (Exception ignored) { }
        ui.removeCallbacks(pollLoop);
        ui.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ------------------------------------------------------------------
    // Auto-hide of the playback controls row
    // ------------------------------------------------------------------
    /** Seconds until the action row hides; 0 = never (checkbox off or 0 entered). */
    int hideSecs() { return prefs.getBoolean(PREF_HIDE_ON, true) ? prefs.getInt(PREF_HIDE_SECS, 10) : 0; }

    int transportRowHeight; // remembered while the row is GONE

    /**
     * Fade the row out while sliding the now-playing text down by half the row's
     * height, then remove the row (GONE) so the text sits in the new middle.
     */
    final Runnable hideTransport = new Runnable() {
        public void run() { if (hideSecs() > 0) hideTransportNow(); }
    };

    /** Fades the row out now (timer or a tap on the now-playing area). */
    void hideTransportNow() {
        if (!transportAllowed || !transportAwake) return;
        ui.removeCallbacks(hideTransport);
        {
            transportAwake = false;
            transportRowHeight = transportRow.getHeight();
            final float shift = transportRowHeight / 2f;
            playBox.animate().translationY(shift).setDuration(400).setListener(null);
            transportRow.animate().alpha(0f).setDuration(400).setListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (transportAwake) return;
                    transportRow.setVisibility(View.GONE);
                    playBox.animate().cancel();
                    playBox.setTranslationY(0); // the re-layout now centres it at the same spot
                }
            });
        }
    }

    /** Shows the row (if allowed) and restarts the hide timer. */
    void wakeTransport() {
        ui.removeCallbacks(hideTransport);
        if (!transportAwake) {
            transportAwake = true;
            transportRow.animate().cancel();
            playBox.animate().cancel();
            if (transportAllowed) {
                boolean wasGone = transportRow.getVisibility() == View.GONE;
                transportRow.setVisibility(View.VISIBLE);
                // the centre shrinks back: start the text where it is now and slide it up
                if (wasGone) playBox.setTranslationY(transportRowHeight / 2f);
                playBox.animate().translationY(0).setDuration(250).setListener(null);
                transportRow.animate().alpha(1f).setDuration(250).setListener(null);
            } else {
                playBox.setTranslationY(0);
            }
        }
        if (transportAllowed && hideSecs() > 0) ui.postDelayed(hideTransport, hideSecs() * 1000L);
    }

    void setTransportAllowed(boolean allowed) {
        if (allowed == transportAllowed) return;
        transportAllowed = allowed;
        transportRow.animate().cancel();
        playBox.animate().cancel();
        playBox.setTranslationY(0);
        if (!allowed) {
            ui.removeCallbacks(hideTransport);
            transportRow.setVisibility(View.GONE);
        } else {
            transportAwake = true;
            transportRow.setAlpha(1f);
            transportRow.setVisibility(View.VISIBLE);
            wakeTransport();
        }
    }

    // swipes anywhere on the playing screen: drag up/down = volume, swipe right/left = next/previous
    float gx0, gy0, gStepY;
    int gMode;          // 0 undecided, 1 volume drag, 2 horizontal swipe, -1 not a swipe (e.g. on the dots)

    /** Returns true while a swipe owns the gesture (the views below got ACTION_CANCEL). */
    boolean swipeGesture(android.view.MotionEvent e) {
        int a = e.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            gx0 = e.getRawX(); gy0 = e.getRawY(); gStepY = gy0;
            boolean enabled = prefs.getBoolean(PREF_GESTURES, true) && connected && powerOn && !clockShown;
            // dragging on the volume dots sets the volume directly, so leave that alone
            gMode = enabled && !(volRow.getVisibility() == View.VISIBLE && inView(volBar, e)) ? 0 : -1;
            return false;
        }
        if (gMode < 0) return false;
        float dx = e.getRawX() - gx0, dy = e.getRawY() - gy0, slop = dp(16);
        if (a == android.view.MotionEvent.ACTION_MOVE) {
            if (gMode == 0) {
                if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) gMode = 1;
                else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) gMode = 2;
                if (gMode == 0) return false;
                gStepY = e.getRawY();
                swallowGesture = false;
                android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c); // buttons under the finger are released, not pressed
                c.recycle();
            }
            if (gMode == 2) {
                // slider: the now-playing text follows the finger (with resistance if there are no tracks)
                float w = Math.max(1, playBox.getWidth());
                float x = isLineIn(input) ? dx * 0.3f : dx;
                playBox.animate().cancel();
                playBox.setTranslationX(x);
                playBox.setAlpha(1f - Math.min(0.6f, Math.abs(x) / w));
            }
            if (gMode == 1) {
                // volume goes down faster than up: finger distance per volume step
                float stepUp = dp(16), stepDown = dp(5);
                while (gStepY - e.getRawY() >= stepUp) { flashHint = "(swiping up)"; changeVolume(+1); gStepY -= stepUp; }
                while (e.getRawY() - gStepY >= stepDown) { flashHint = "(swiping down)"; changeVolume(-1); gStepY += stepDown; }
                flashHint = null;
            }
            return true;
        }
        if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            int m = gMode;
            gMode = -1;
            if (m == 2) {
                boolean commit = a == android.view.MotionEvent.ACTION_UP && Math.abs(dx) > dp(60);
                if (commit && !isLineIn(input)) slideTo(dx > 0 ? +1 : -1); // swipe left = next, right = previous
                else {
                    playBox.animate().translationX(0).alpha(1f).setDuration(200).setListener(null);
                    if (commit) { flashHint = dx > 0 ? "(swiping right)" : "(swiping left)"; skip(dx > 0 ? -1 : +1); flashHint = null; }
                }
            }
            return m > 0;
        }
        return gMode > 0;
    }

    /** Slides the current text out in the swipe direction, skips, and slides the new one in. */
    /** move: +1 = finger went right. Swipe left = next, swipe right = previous. */
    void slideTo(final int move) {
        final int dir = -move; // skip direction
        final float w = Math.max(1, playBox.getWidth());
        playBox.animate().translationX(move * w).alpha(0f).setDuration(180)
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    boolean cancelled;
                    @Override public void onAnimationCancel(android.animation.Animator an) { cancelled = true; }
                    @Override public void onAnimationEnd(android.animation.Animator an) {
                        if (cancelled) return;
                        flashHint = move > 0 ? "(swiping right)" : "(swiping left)";
                        skip(dir);
                        flashHint = null;
                        // the new one comes in from the other side
                        playBox.setTranslationX(-move * w);
                        playBox.animate().translationX(0).alpha(1f).setDuration(260).setListener(null);
                    }
                });
    }

    static boolean inView(View v, android.view.MotionEvent e) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = e.getRawX(), y = e.getRawY();
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    /** True if the touch lands where the (hidden) action row normally sits: just above the bottom row. */
    boolean hitsHiddenRow(android.view.MotionEvent e) {
        int[] loc = new int[2];
        bottom.getLocationOnScreen(loc);
        float y = e.getRawY();
        int h = transportRowHeight > 0 ? transportRowHeight : dp(104);
        return y >= loc[1] - h && y < loc[1];
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        int a = e.getActionMasked();
        // two fingers: pinch scales the text; the views stop getting this gesture
        scaleDetector().onTouchEvent(e);
        if (a == android.view.MotionEvent.ACTION_POINTER_DOWN && !pinching) {
            pinching = true;
            android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
            c.setAction(android.view.MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(c);
            c.recycle();
        }
        if (pinching) {
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) pinching = false;
            return true;
        }
        if (swipeGesture(e)) return true;
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            // a tap where the hidden row is only reveals it, it doesn't press a button
            boolean hiddenHit = transportAllowed && !transportAwake && hitsHiddenRow(e);
            // taps on the now-playing area toggle the row (handled by its click listener)
            boolean onNowPlaying = transportAllowed && !hiddenHit && inView((View) playBox.getParent(), e);
            if (!onNowPlaying) wakeTransport();
            if (hiddenHit) { swallowGesture = true; return true; }
        }
        if (swallowGesture) {
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) swallowGesture = false;
            return true;
        }
        return super.dispatchTouchEvent(e);
    }

    /** Kiosk: Back does nothing. */
    @Override
    public void onBackPressed() { }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // hardware volume keys control the speaker, not the tablet
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { changeVolume(+1); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { changeVolume(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    final Runnable pollLoop = new Runnable() {
        public void run() {
            poll();
            // the kiosk app stays in front for days, so the update check runs from here too
            if (System.currentTimeMillis() - lastUpdateCheck > UPDATE_EVERY_MS) checkForUpdates(false);
            if (resumed) ui.postDelayed(this, POLL_MS);
        }
    };

    void hideSystemBars() {
        View d = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 19) {
            // immersive sticky: bars stay hidden until swiped in
            flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | 0x00001000
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        }
        else if (prefs.getBoolean(PREF_HIDE_NAV, false)) {
            // Android 4.1-4.3: hidden until the next touch, and that first touch only brings the
            // buttons back. Layout flags keep the screen from jumping when they come and go.
            flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        }
        // Otherwise on 4.2 the navigation bar is only dimmed (low profile).
        d.setSystemUiVisibility(flags);
        if (!navListenerSet) {
            navListenerSet = true;
            d.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                public void onSystemUiVisibilityChange(int vis) {
                    // buttons came back after a tap: hide them again after 3 s
                    ui.removeCallbacks(rehideBars);
                    if (Build.VERSION.SDK_INT < 19 && prefs.getBoolean(PREF_HIDE_NAV, false)
                            && (vis & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)
                        ui.postDelayed(rehideBars, 3000);
                }
            });
        }
    }

    boolean navListenerSet;
    final Runnable rehideBars = new Runnable() { public void run() { if (resumed) hideSystemBars(); } };

    void applyScreenOn() {
        if (prefs.getBoolean(PREF_SCREEN_ON, true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    String ip() { return prefs.getString(PREF_IP, "").trim(); }

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------
    View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // --- source tabs + OFF
        LinearLayout tabs = row();
        tabsRow = tabs;
        for (int i = 0; i < SRC_ID.length; i++) {
            final int idx = i;
            Btn t = new Btn(this);
            t.label = SRC_LABEL[i];
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectInput(idx); }
            });
            srcBtns[i] = t;
            tabs.addView(t, weight());
        }
        // shows the current source when it isn't one of the tabs (Spotify, Server, USB...)
        otherTab = new Btn(this);
        otherTab.fg = C_TEXT;
        otherTab.setVisibility(View.GONE);
        tabs.addView(otherTab, weight());
        offBtn = new Btn(this);
        offBtn.label = "OFF";
        offBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (powerOn) togglePower(); }
        });
        tabs.addView(offBtn, weight());
        root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));

        // --- centre: now playing or clock
        android.widget.FrameLayout centre = new android.widget.FrameLayout(this);
        playBox = new LinearLayout(this);
        playBox.setOrientation(LinearLayout.VERTICAL);
        playBox.setGravity(Gravity.CENTER);
        playBox.setPadding(dp(40), 0, dp(40), 0);
        trackText = text("", 68, C_TEXT, true);
        artistText = text("", 48, C_GRAY, true);
        trackText.setGravity(Gravity.CENTER);
        artistText.setGravity(Gravity.CENTER);
        playBox.addView(trackText, matchWrap());
        playBox.addView(artistText, matchWrap());
        // radio only: station name, small, under the artist
        stationText = text("", 20, C_DIM, true);
        stationText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(10);
        playBox.addView(stationText, slp);
        playBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                poll();
                // tap toggles the action row
                if (transportAwake) hideTransportNow(); else wakeTransport();
            }
        });
        // taps on the song area: single = toggle action row, double = reset text size
        // (swipes anywhere on the screen are handled in dispatchTouchEvent)
        playBox.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) return true;
                if (a == android.view.MotionEvent.ACTION_UP) { tapOnNowPlaying(v, e); return true; }
                return a == android.view.MotionEvent.ACTION_CANCEL;
            }
        });
        centre.addView(playBox, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER);
        clockText = text("", 300, C_TEXT, true);
        clockText.setIncludeFontPadding(false);
        clockText.setGravity(Gravity.CENTER);
        dateText = text("", 46, C_TEXT, true);
        dateText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = matchWrap();
        dlp.topMargin = dp(14);
        clockBox.addView(clockText, matchWrap());
        clockBox.addView(dateText, dlp);
        // double-tap the clock: settings (PIN-protected if a PIN is set)
        final android.view.GestureDetector gd = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onDoubleTap(android.view.MotionEvent e) { showSettings(true); return true; }
                    @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) { toggleChrome(); return true; }
                });
        clockBox.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) { return gd.onTouchEvent(e); }
        });
        centre.addView(clockBox, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // fit the big texts to whatever space the centre gets
        centre.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) ui.post(new Runnable() { public void run() { fitTexts(); } });
            }
        });
        root.addView(centre, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // --- transport: icon + caption
        transportRow = row();
        prevBtn = iconBtn(Btn.ICON_PREV, "Prev", null);
        prevBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { skip(-1); }
        });
        shuffleBtn = iconBtn(Btn.ICON_SHUFFLE, "Shuffle", null);
        playBtn = iconBtn(Btn.ICON_PAUSE, "Pause", null);
        repeatBtn = iconBtn(Btn.ICON_REPEAT, "Repeat", null);
        nextBtn = iconBtn(Btn.ICON_NEXT, "Next", null);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { skip(+1); }
        });
        shuffleBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shuffle = !shuffle;
                flash(shuffle ? "Shuffle on" : "Shuffle off", C_TEXT, 1500);
                send("netusb/toggleShuffle", true);
            }
        });
        repeatBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                repeatMode = "off".equals(repeatMode) ? "all" : ("all".equals(repeatMode) ? "one" : "off");
                flash("off".equals(repeatMode) ? "Repeat off" : ("one".equals(repeatMode) ? "Repeat one" : "Repeat all"), C_TEXT, 1500);
                send("netusb/toggleRepeat", true);
            }
        });
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // follower: explicit play/pause for what the speaker reported, not a blind toggle
                String cmd = follower() ? (playing ? "pause" : "play") : "play_pause";
                playing = !playing; render();
                send("netusb/setPlayback?playback=" + cmd, true);
            }
        });
        settingsBtn = iconBtn(Btn.ICON_SETTINGS, "Settings", null);
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(true); }
        });
        muteBtn = iconBtn(Btn.ICON_SPEAKER, "Mute", null);
        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mute = !mute;
                if (mute) { flashUntil = 0; render(); } else flash("Unmuted", C_TEXT, 1200);
                send("main/setMute?enable=" + (mute ? "true" : "false"), true);
            }
        });
        for (Btn b : actionButtons()) transportRow.addView(b, weight());
        root.addView(transportRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)));

        // --- bottom: volume when on; Settings / update / IP when off
        bottom = new android.widget.FrameLayout(this);
        volRow = row();
        volDn = new Btn(this);
        volDn.label = "−"; volDn.textSize = 34; volDn.bold = false;
        volDn.repeat = new Runnable() { public void run() { changeVolume(-1); } };
        volUp = new Btn(this);
        volUp.label = "+"; volUp.textSize = 34; volUp.bold = false;
        volUp.repeat = new Runnable() { public void run() { changeVolume(+1); } };
        volBar = new VolumeBar(this);
        volBar.listener = new VolumeBar.Listener() {
            public void onPick(int fraction1000) { changeVolume(Math.round(fraction1000 * maxVolume / 1000f) - volume); }
        };
        volRow.addView(volDn, new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.MATCH_PARENT));
        volRow.addView(volBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        volRow.addView(volUp, new LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.MATCH_PARENT));
        bottom.addView(volRow);

        // clock-screen footer: "Settings · v1.29 · IP …" centred, one text style, each part tappable
        offRow = row();
        // sits on the bottom edge of the app area (the very bottom of the screen when the
        // navigation buttons are hidden)
        offRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        offRow.setPadding(0, 0, 0, dp(6));
        settingsText = footerText();
        settingsText.setText("Settings");
        settingsText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(true); }
        });
        updateText = footerText();
        updateText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // "Update x.y" when one is waiting, otherwise the installed version (tap = check)
                if (pendingUpdate != null) downloadAndInstall(pendingUpdate); else checkForUpdates(true);
            }
        });
        ipText = footerText();
        ipText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(false); }
        });
        offRow.addView(settingsText, wrapFull());
        offRow.addView(footerDot(), wrapFull());
        offRow.addView(updateText, wrapFull());
        offRow.addView(footerDot(), wrapFull());
        offRow.addView(ipText, wrapFull());
        bottom.addView(offRow);
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomHeight()));

        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (b - t != ob - ot || r - l != or - ol) ui.post(new Runnable() { public void run() { placeVolumeDots(); } });
            }
        });
        return root;
    }

    /** Bottom row height: compact (no padding under the volume bar) is the default. */
    int bottomHeight() {
        return prefs.getBoolean(PREF_COMPACT_VOLUME, true) ? dp(48) : dp(88);
    }

    void applyLayoutPrefs() {
        ViewGroup.LayoutParams lp = bottom.getLayoutParams();
        lp.height = bottomHeight();
        bottom.setLayoutParams(lp);
        ui.post(new Runnable() { public void run() { placeVolumeDots(); } });
    }

    /**
     * Puts the volume dots so the empty space above them (from the action-row captions) equals
     * the space below them down to the physical screen edge, including the navigation bar that
     * Android 4.x can only dim, not hide.
     */
    void placeVolumeDots() {
        View root = (View) bottom.getParent();
        if (root == null || bottom.getHeight() == 0) return;
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        android.util.DisplayMetrics real = new android.util.DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 17) getWindowManager().getDefaultDisplay().getRealMetrics(real);
        else getWindowManager().getDefaultDisplay().getMetrics(real);
        float navBar = Math.max(0, real.heightPixels - (loc[1] + root.getHeight()));
        float h = bottom.getHeight();
        float captionGap = dp(23);   // action-row caption baseline to the row's bottom edge
        float d = (captionGap + h - navBar) / 2f;          // dots' distance from the app's bottom edge
        d = Math.max(dp(8), Math.min(h - dp(8), d));
        float shift = h / 2f - d;
        volBar.offsetY = shift; volBar.invalidate();
        volDn.offsetY = shift; volDn.invalidate();
        volUp.offsetY = shift; volUp.invalidate();
    }

    TextView footerText() {
        TextView t = text("", 20, C_DIM, true);
        t.setGravity(Gravity.BOTTOM);
        t.setPadding(dp(10), 0, dp(10), 0); // generous touch area
        return t;
    }

    TextView footerDot() {
        TextView t = text("\u00b7", 20, C_DIM, true);
        t.setGravity(Gravity.BOTTOM);
        return t;
    }

    LinearLayout.LayoutParams wrapFull() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    long lastTapAt;
    float lastTapX, lastTapY;
    Runnable pendingTap;

    /** Single tap toggles the action row (after the double-tap window); double tap resets text size. */
    void tapOnNowPlaying(final View v, android.view.MotionEvent e) {
        long now = android.os.SystemClock.uptimeMillis();
        int timeout = android.view.ViewConfiguration.getDoubleTapTimeout();
        if (pendingTap != null && now - lastTapAt < timeout
                && Math.abs(e.getX() - lastTapX) < dp(40) && Math.abs(e.getY() - lastTapY) < dp(40)) {
            ui.removeCallbacks(pendingTap);
            pendingTap = null;
            resetTextScale();
            return;
        }
        lastTapAt = now; lastTapX = e.getX(); lastTapY = e.getY();
        pendingTap = new Runnable() { public void run() { pendingTap = null; v.performClick(); } };
        ui.postDelayed(pendingTap, timeout);
    }

    /** Next (+1) / previous (-1): radio stations on Radio, tracks elsewhere. */
    void skip(int dir) {
        if (!connected || !powerOn) return;
        if (isLineIn(input)) { flash("No tracks on " + sourceLabel(input), C_DIM, 1500); return; }
        if ("net_radio".equals(input)) {
            if (flashHint != null) flash(dir > 0 ? "Next station" : "Previous station", C_TEXT, 900);
            stepStation(dir);
            return;
        }
        flash(dir > 0 ? "Next" : "Previous", C_TEXT, 800);
        send("netusb/setPlayback?playback=" + (dir > 0 ? "next" : "previous"), true);
    }

    Btn[] actionButtons() {
        return new Btn[] { prevBtn, shuffleBtn, settingsBtn, playBtn, muteBtn, repeatBtn, nextBtn };
    }

    Btn iconBtn(int icon, String caption, final String cmd) {
        Btn b = new Btn(this);
        b.icon = icon;
        b.label = caption;
        if (cmd != null) b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { send(cmd, true); }
        });
        return b;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // every text with its base size, so a new text scale can be applied live
    final java.util.List<TextView> scaledTexts = new java.util.ArrayList<TextView>();
    final java.util.List<Float> baseSizes = new java.util.ArrayList<Float>();

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Fonts.scale);
        scaledTexts.add(t);
        baseSizes.add(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTypeface(bold ? Fonts.bold : Fonts.regular);
        return t;
    }

    static final String[][] OTHER_SOURCES = {
            { "usb", "USB" }, { "spotify", "Spotify" }, { "server", "Server" }, { "tidal", "TIDAL" },
            { "deezer", "Deezer" }, { "qobuz", "Qobuz" }, { "amazon_music", "Amazon Music" },
            { "napster", "Napster" }, { "pandora", "Pandora" }, { "siriusxm", "SiriusXM" },
            { "juke", "JUKE" }, { "radiko", "radiko" }, { "alexa", "Alexa" }, { "mc_link", "MusicCast Link" },
            { "tuner", "Tuner" }, { "line_in", "Line In" }, { "line1", "Line 1" }, { "line2", "Line 2" },
            { "line3", "Line 3" }, { "coaxial", "Coaxial" }, { "hdmi", "HDMI" }, { "tv", "TV" },
            { "phono", "Phono" }, { "cd", "CD" }, { "bluetooth", "Bluetooth" },
    };

    String sourceLabel(String id) {
        for (int i = 0; i < SRC_ID.length; i++) if (SRC_ID[i].equals(id)) return SRC_LABEL[i];
        for (String[] o : OTHER_SOURCES) if (o[0].equals(id)) return o[1];
        if (id == null || id.length() == 0) return "";
        // unknown id: "some_input" -> "Some input"
        String t = id.replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    boolean isTabSource(String id) {
        for (String t : SRC_ID) if (t.equals(id)) return true;
        return false;
    }

    final Runnable rerender = new Runnable() { public void run() { render(); } };

    // short message shown in the top (song) row, then the song comes back
    String flashText = "";
    int flashColor;
    long flashUntil;
    final Runnable flashEnd = new Runnable() { public void run() { render(); } };

    String flashHint;   // set while a gesture triggers a message: explains it in the artist row
    String flashSub;    // the hint of the message currently shown

    void flash(String text, int color, long ms) {
        flashSub = flashHint;
        flashText = text;
        flashColor = flashHint != null ? C_ACCENT : color; // gesture messages in the accent colour
        flashUntil = System.currentTimeMillis() + ms;
        ui.removeCallbacks(flashEnd);
        ui.postDelayed(flashEnd, ms + 20);
        render();
    }

    // ------------------------------------------------------------------
    // Radio: Prev/Next step through the speaker's saved radio presets
    // ------------------------------------------------------------------
    java.util.List<String[]> radioPresets;   // {num, name}
    long presetsLoadedAt, stationChangedAt;
    String pendingStation = "", trackAtSwitch = "";
    int presetIndex = -1;

    /** True while a new station is starting and the speaker may still report the old song. */
    boolean stationChanging() {
        return System.currentTimeMillis() - stationChangedAt < SETTLE_MS && track.equals(trackAtSwitch);
    }

    String cleanStation(String st) {
        return prefs.getBoolean(PREF_HIDE_LOCATION, true) ? stripLocation(st) : st;
    }

    volatile String presetDiag = "";

    /** Lower-case station name without location, underscores and punctuation, for matching. */
    static String norm(String st) {
        return stripLocation(st == null ? "" : st).toLowerCase().replace('_', ' ').replaceAll("[^\\p{L}\\p{N} ]", "").trim();
    }

    void stepStation(final int step) {
        if (TextUtils.isEmpty(ip())) return;
        final String currentStation = !TextUtils.isEmpty(artist) ? artist : album;
        net.execute(new Runnable() {
            public void run() {
                // always read the favourites fresh: they may have changed in the MusicCast app
                java.util.List<String[]> list = new java.util.ArrayList<String[]>();
                String r = requestRetry("netusb/getPresetInfo");
                String readError = null;
                try {
                    JSONObject j = new JSONObject(r);
                    if (j.optInt("response_code", 0) != 0) readError = "code " + j.optInt("response_code");
                    org.json.JSONArray arr = j.getJSONArray("preset_info");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.getJSONObject(i);
                        String name = p.optString("text", "");
                        if ("net_radio".equals(p.optString("input")) && name.length() > 0)
                            list.add(new String[] { String.valueOf(i + 1), name });
                    }
                    radioPresets = list;
                    presetsLoadedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    if (readError == null) readError = r == null ? "no answer" : "unexpected answer";
                    if (radioPresets != null) list = radioPresets;
                }
                final java.util.List<String[]> presets = list;
                StringBuilder names = new StringBuilder();
                for (String[] p : presets) names.append(names.length() > 0 ? ", " : "").append(p[0]).append(": ").append(p[1]);
                presetDiag = "Radio favourites on the speaker: " + presets.size()
                        + (presets.isEmpty() ? "" : " (" + names + ")")
                        + (readError != null ? " \u2013 last read failed: " + readError : "");
                if (presets.isEmpty()) {
                    final String re = readError;
                    ui.post(new Runnable() { public void run() {
                        flash(re != null ? "Couldn't read saved stations (" + re + ")"
                                : "No saved stations \u2013 add favourites in MusicCast", C_AMBER, 4000);
                    } });
                    return;
                }
                // start from the station that's playing now, if it is one of the favourites
                int cur = -1;
                String b = norm(currentStation);
                for (int i = 0; i < presets.size() && b.length() > 0; i++) {
                    String a = norm(presets.get(i)[1]);
                    if (a.length() > 0 && (a.equals(b) || a.contains(b) || b.contains(a))) { cur = i; break; }
                }
                if (cur < 0) cur = presetIndex >= 0 && presetIndex < presets.size() ? presetIndex : -1;
                final int n = presets.size();
                if (n == 1 && cur == 0) {
                    ui.post(new Runnable() { public void run() {
                        flash("Only one saved station \u2013 add more favourites in MusicCast", C_AMBER, 4000);
                    } });
                    return;
                }
                final int next = cur < 0 ? (step > 0 ? 0 : n - 1) : ((cur + step) % n + n) % n;
                presetIndex = next;
                final String[] target = presets.get(next);
                ui.post(new Runnable() { public void run() {
                    pendingStation = target[1];
                    stationChangedAt = System.currentTimeMillis();
                    trackAtSwitch = track;
                    flash((step > 0 ? "Next station " : "Previous station ") + (next + 1) + "/" + n, C_TEXT, 1500);
                    ui.removeCallbacks(rerender);
                    ui.postDelayed(rerender, SETTLE_MS + 100);
                } });
                String resp = request("netusb/recallPreset?zone=main&num=" + target[0]);
                int code = -1;
                try { code = resp == null ? -1 : new JSONObject(resp).optInt("response_code", 0); } catch (Exception ignored) { }
                final int fc = code;
                ui.post(new Runnable() { public void run() {
                    if (fc != 0) {
                        stationChangedAt = 0;
                        flash(fc < 0 ? "Couldn't reach the speaker"
                                : "Speaker refused " + cleanStation(target[1]) + " (code " + fc + ")", C_AMBER, 4000);
                    }
                    render();
                    ui.postDelayed(new Runnable() { public void run() { poll(); } }, 1200);
                } });
            }
        });
    }

    /** True if the text has at least one letter or digit ("-" or "..." doesn't count). */
    static boolean hasText(String t) {
        if (t == null) return false;
        for (int i = 0; i < t.length(); i++) if (Character.isLetterOrDigit(t.charAt(i))) return true;
        return false;
    }

    /** {headline, hint} when there is no song to show, based on what the speaker reports. */
    String[] situation(String station) {
        long now = System.currentTimeMillis();
        String src = sourceLabel(input);
        boolean playingNow = "play".equals(playback);
        if (now - powerChangedAt < SETTLE_MS && powerTarget)
            return new String[] { "Turning on\u2026", src };
        if ("net_radio".equals(input) && stationChanging())
            return new String[] { "Tuning in\u2026", cleanStation(pendingStation) };
        if (now - inputChangedAt < SETTLE_MS)
            return new String[] { "Switching to " + src + "\u2026", "" };
        if ("net_radio".equals(input)) {
            if (playingNow) return new String[] { "No track info", TextUtils.isEmpty(station) ? "Radio" : station };
            if (!TextUtils.isEmpty(station)) return new String[] { station, "Stopped \u2013 tap Play to resume" };
            return new String[] { "Radio", "Choose a station in the MusicCast app" };
        }
        if ("airplay".equals(input)) {
            if (playingNow) return new String[] { "AirPlay", "Playing \u2013 no song info" };
            return new String[] { "Waiting for AirPlay", "Choose this speaker on your iPhone, iPad or Mac" };
        }
        if ("bluetooth".equals(input)) {
            if (playingNow) return new String[] { "Bluetooth", "Playing \u2013 no song info" };
            return new String[] { "Waiting for Bluetooth", "Connect your phone to the speaker" };
        }
        if ("pause".equals(playback)) return new String[] { "Paused", src };
        return new String[] { "Not playing", src };
    }

    /** Splits radio metadata "Artist - Song" into {artist, song}; no separator → {"", whole text}. */
    static String[] splitArtistSong(String t) {
        if (t == null) return new String[] { "", "" };
        t = t.trim();
        for (String sep : new String[] { " - ", " \u2013 ", " \u2014 ", " ~ ", " | " }) {
            int i = t.indexOf(sep);
            if (i > 0 && i + sep.length() < t.length())
                return new String[] { t.substring(0, i).trim(), t.substring(i + sep.length()).trim() };
        }
        // some stations send "Artist-Song" without spaces: split only if there is exactly one dash
        int d = t.indexOf('-');
        if (d >= 2 && d == t.lastIndexOf('-') && t.length() - d - 1 >= 2)
            return new String[] { t.substring(0, d).trim(), t.substring(d + 1).trim() };
        return new String[] { "", t };
    }

    /** "Rádio_FM (Bratislava/Slovak)" → "Rádio_FM": drops a trailing part in brackets. */
    static String stripLocation(String station) {
        if (station == null) return "";
        String s = station.replaceAll("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$", "").trim();
        return s.length() > 0 ? s : station.trim();
    }

    /** Inputs with no tracks, so no transport row (same list as the web remote). */
    static boolean isLineIn(String id) {
        return "aux".equals(id) || "optical".equals(id) || "usb".equals(id) || "line_in".equals(id);
    }

    /** Shrinks text so it fits in one line of the given width, between max and min sp. */
    void fit(TextView t, float maxSp, float minSp, float widthPx) {
        if (widthPx <= 0) return;
        android.graphics.Paint p = new android.graphics.Paint(t.getPaint());
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSp, getResources().getDisplayMetrics());
        float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSp, getResources().getDisplayMetrics());
        String s = t.getText().toString();
        p.setTextSize(px);
        while (px > min && p.measureText(s) > widthPx) { px -= 2; p.setTextSize(px); }
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    void fitTexts() {
        View centre = (View) clockBox.getParent();
        float w = centre.getWidth(), h = centre.getHeight();
        if (w <= 0) return;
        float k = Fonts.scale;
        fit(trackText, 68 * k, 30 * Math.min(1f, k), w - dp(80));
        fit(artistText, 48 * k, 24 * Math.min(1f, k), w - dp(80));
        // clock: about three quarters of the width (scaled), limited by the height
        float px = Math.min(h * 0.62f * k, 400 * getResources().getDisplayMetrics().density);
        clockText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        fit(clockText, px / getResources().getDisplayMetrics().scaledDensity, 60 * Math.min(1f, k), w * Math.min(0.98f, 0.78f * k));
        // the date doesn't grow above 100 %: the space goes to the clock
        fit(dateText, 46 * Math.min(1f, k), 20 * Math.min(1f, k), w - dp(80));
        // fonts differ a lot in line height (Rounded Mplus is much taller than Roboto):
        // shrink the clock until clock + gap + date really fit the height
        float dateH = lineHeight(dateText, true);
        float gap = dp(14);
        px = clockText.getTextSize();
        float min = dp(60);
        android.graphics.Paint p = new android.graphics.Paint(clockText.getPaint());
        while (px > min) {
            p.setTextSize(px);
            android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
            if ((fm.descent - fm.ascent) + gap + dateH <= h - dp(4)) break;
            px -= 4;
        }
        clockText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        // same check for the playing screen: song + artist + station rows
        float rows = lineHeight(trackText, true) + lineHeight(artistText, true) + lineHeight(stationText, true) + dp(26);
        if (rows > h) {
            float shrink = Math.max(0.5f, h / rows);
            trackText.setTextSize(TypedValue.COMPLEX_UNIT_PX, trackText.getTextSize() * shrink);
            artistText.setTextSize(TypedValue.COMPLEX_UNIT_PX, artistText.getTextSize() * shrink);
        }
    }

    /** Applies Fonts.scale to every text and button now (pinch, settings, reset). */
    void applyTextScale() {
        for (int i = 0; i < scaledTexts.size(); i++) {
            TextView t = scaledTexts.get(i);
            if (t == trackText || t == artistText || t == clockText || t == dateText) continue; // fitTexts() sizes these
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSizes.get(i) * Fonts.scale);
        }
        fitTexts();
        for (Btn b : actionButtons()) b.invalidate();
        for (Btn b : srcBtns) b.invalidate();
        otherTab.invalidate(); offBtn.invalidate(); volDn.invalidate(); volUp.invalidate();
    }

    void setTextScale(float k, boolean save) {
        Fonts.scale = Math.max(0.6f, Math.min(2.0f, k));
        applyTextScale();
        if (save) prefs.edit().putFloat(PREF_TEXT_SCALE, Fonts.scale).commit();
    }

    // pinch anywhere: scale all text
    boolean pinching;
    android.view.ScaleGestureDetector scaleDetector;

    android.view.ScaleGestureDetector scaleDetector() {
        if (scaleDetector == null) scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                        setTextScale(Fonts.scale * d.getScaleFactor(), false);
                        flashHint = "(pinching)";
                        flash("Text size " + Math.round(Fonts.scale * 100) + " %", C_TEXT, 1200);
                        flashHint = null;
                        return true;
                    }
                    @Override public void onScaleEnd(android.view.ScaleGestureDetector d) {
                        setTextScale(Fonts.scale, true);
                    }
                });
        return scaleDetector;
    }

    void resetTextScale() {
        setTextScale(1f, true);
        flashHint = "(double tap)";
        flash("Text size 100 %", C_TEXT, 1200);
        flashHint = null;
    }

    /** Height a single-line TextView needs with its current font and size. */
    static float lineHeight(TextView t, boolean withPadding) {
        android.graphics.Paint.FontMetrics fm = t.getPaint().getFontMetrics();
        return withPadding ? fm.bottom - fm.top : fm.descent - fm.ascent;
    }

    void updateClock() {
        Date now = new Date();
        clockText.setText(android.text.format.DateFormat.getTimeFormat(this).format(now));
        dateText.setText(new SimpleDateFormat("EEEE, yyyy / MM / dd", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        fitTexts();
    }

    final Runnable clockTick = new Runnable() {
        public void run() {
            updateClock();
            long ms = System.currentTimeMillis();
            ui.postDelayed(this, 60000 - (ms % 60000) + 50);
        }
    };

    boolean switching; // crossfade between the playing screen and the clock in progress
    LinearLayout tabsRow;
    boolean chromeHidden; // clock screen: tabs and footer hidden by a tap

    /** Clock screen: a tap on the time/date fades the tabs and the footer out or back in. */
    void toggleChrome() {
        chromeHidden = !chromeHidden;
        for (final View v : new View[] { tabsRow, bottom }) {
            v.animate().cancel();
            if (!chromeHidden) {
                if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setVisibility(View.VISIBLE); }
                v.animate().alpha(1f).setDuration(250).setListener(null);
            } else {
                v.animate().alpha(0f).setDuration(250).setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        // GONE releases their space: the clock grows into it (fitTexts runs on the new size)
                        if (chromeHidden) v.setVisibility(View.GONE);
                    }
                });
            }
        }
    }

    /** Switches between the playing screen and the clock with a short crossfade. */
    void showClock(final boolean show) {
        if (show == clockShown) return;
        clockShown = show;
        final View centre = (View) clockBox.getParent();
        final View[] parts = { centre, bottom, transportRow };
        if (centre.getWidth() == 0 || !resumed) { applyScreen(show); return; } // first draw: no animation
        switching = true;
        for (View v : parts) v.animate().cancel();
        bottom.animate().alpha(0f).setDuration(180).setListener(null);
        transportRow.animate().alpha(0f).setDuration(180).setListener(null);
        centre.animate().alpha(0f).setDuration(180).setListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled || clockShown != show) return; // a newer switch took over
                applyScreen(show);
                switching = false;
                render();
                for (View v : parts) {
                    v.setAlpha(0f);
                    v.animate().alpha(1f).setDuration(280).setListener(null);
                }
            }
        });
    }

    void applyScreen(boolean show) {
        if (!show && chromeHidden) {
            // speaker on: the tabs are needed again
            chromeHidden = false;
            for (View v : new View[] { tabsRow, bottom, offRow }) { v.animate().cancel(); v.setAlpha(1f); v.setVisibility(View.VISIBLE); }
        }
        playBox.setVisibility(show ? View.GONE : View.VISIBLE);
        clockBox.setVisibility(show ? View.VISIBLE : View.GONE);
        volRow.setVisibility(show ? View.GONE : View.VISIBLE);
        offRow.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(clockTick);
        if (show) clockTick.run();
    }

    void render() {
        boolean on = connected && powerOn;

        // tabs: active source white, others grey; when off everything dim except OFF
        for (int i = 0; i < SRC_ID.length; i++) {
            srcBtns[i].fg = on && SRC_ID[i].equals(input) ? C_TEXT : C_TAB_IDLE;
            srcBtns[i].invalidate();
        }
        offBtn.fg = on ? C_TAB_IDLE : C_TEXT;
        boolean other = on && !TextUtils.isEmpty(input) && !isTabSource(input);
        if (other) { otherTab.label = sourceLabel(input); otherTab.invalidate(); }
        int ov = other ? View.VISIBLE : View.GONE;
        if (otherTab.getVisibility() != ov) otherTab.setVisibility(ov);
        offBtn.invalidate();

        showClock(!on);
        // line-in inputs have no tracks: only Settings and Mute stay in the row
        if (!switching) setTransportAllowed(on); // applied after the crossfade's fade-out
        int trackCtl = isLineIn(input) ? View.INVISIBLE : View.VISIBLE; // keep Settings/Mute in place
        for (Btn b : new Btn[] { prevBtn, shuffleBtn, playBtn, repeatBtn, nextBtn })
            if (b.getVisibility() != trackCtl) b.setVisibility(trackCtl);

        if (on) {
            String song = track, by = artist, station = "";
            if ("net_radio".equals(input)) {
                // radio sends one "Artist - Song" text; the artist field holds the station
                station = !TextUtils.isEmpty(artist) ? artist : album;
                if (prefs.getBoolean(PREF_HIDE_LOCATION, true)) station = stripLocation(station);
                String[] parts = splitArtistSong(track);
                by = parts[0];
                song = parts[1];
                // song but no artist: the station name takes the artist row
                if (TextUtils.isEmpty(by) && hasText(song)) { by = station; station = ""; }
                if (stationChanging()) { song = ""; station = ""; } // old song still reported
            }
            if (!hasText(song)) song = ""; // stations send "-" or similar when they have no info
            stationText.setText(station);
            stationText.setVisibility(TextUtils.isEmpty(station) ? View.GONE : View.VISIBLE);
            int artistColor = C_GRAY;
            if (!TextUtils.isEmpty(song)) {
                // song and artist both full white
                trackText.setText(song);
                trackText.setTextColor(C_TEXT);
                artistColor = C_TEXT;
                artistText.setText(TextUtils.isEmpty(by) ? sourceLabel(input) : by);
            } else if (isLineIn(input)) {
                trackText.setText(sourceLabel(input));
                trackText.setTextColor(C_TEXT);
                artistText.setText("External input");
            } else {
                // nothing to show yet: say what the speaker is doing
                String[] st = situation(station);
                trackText.setText(st[0]);
                trackText.setTextColor(C_GRAY);
                artistText.setText(st[1]);
                stationText.setVisibility(View.GONE);
                // a "Turning on" / "Switching" message expires on its own
                ui.removeCallbacks(rerender);
                if (System.currentTimeMillis() - Math.max(powerChangedAt, inputChangedAt) < SETTLE_MS)
                    ui.postDelayed(rerender, 1000);
            }
            artistText.setTextColor(artistColor);
            // the top row also carries short messages: volume, shuffle/repeat, radio notices, Muted
            if (System.currentTimeMillis() < flashUntil) {
                trackText.setText(flashText);
                trackText.setTextColor(flashColor);
                if (flashSub != null) { artistText.setText(flashSub); artistText.setTextColor(C_ACCENT); }
            } else if (mute) {
                trackText.setText("Muted");
                trackText.setTextColor(C_AMBER);
            }
            fitTexts();
        }

        playBtn.icon = playing ? Btn.ICON_PAUSE : Btn.ICON_PLAY;
        playBtn.label = playing ? "Pause" : "Play";
        boolean radio = "net_radio".equals(input);
        prevBtn.label = radio ? "Prev station" : "Prev";
        nextBtn.label = radio ? "Next station" : "Next";
        shuffleBtn.fg = shuffle ? C_TEXT : C_DIM;
        repeatBtn.fg = "off".equals(repeatMode) ? C_DIM : C_TEXT;
        repeatBtn.icon = "one".equals(repeatMode) ? Btn.ICON_REPEAT_ONE : Btn.ICON_REPEAT;
        muteBtn.icon = mute ? Btn.ICON_MUTED : Btn.ICON_SPEAKER;
        muteBtn.label = mute ? "Unmute" : "Mute";
        muteBtn.fg = mute ? C_AMBER : C_TEXT;
        for (Btn b : actionButtons()) b.invalidate();

        int maxv = maxVolume > 0 ? maxVolume : 100;
        volBar.steps = maxv;
        volBar.fraction = Math.max(0, Math.min(maxv, volume)) / (float) maxv;
        volBar.color = mute ? C_AMBER : C_TEXT;
        volBar.invalidate();

        // bottom row (off screen)
        if (TextUtils.isEmpty(ip())) { ipText.setText("Set speaker IP"); ipText.setTextColor(C_AMBER); }
        else if (!everPolled) { ipText.setText("IP " + ip() + " · connecting…"); ipText.setTextColor(C_DIM); }
        else if (!connected) { ipText.setText("IP " + ip() + " · not connected"); ipText.setTextColor(C_RED); }
        else { ipText.setText("IP " + ip()); ipText.setTextColor(C_DIM); }
        if (!updateBusy || pendingUpdate == null) {
            updateText.setText(pendingUpdate != null ? "Update " + pendingUpdate.versionName : "v" + versionName());
            updateText.setTextColor(pendingUpdate != null ? C_AMBER : C_DIM);
        }
    }

    // ------------------------------------------------------------------
    // Settings / kiosk exit
    // ------------------------------------------------------------------
    void showSettings(final boolean admin) {
        final String pin = prefs.getString(PREF_PIN, "");
        if (admin && !TextUtils.isEmpty(pin)) {
            final EditText pe = new EditText(this);
            pe.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pe.setHint("PIN");
            new AlertDialog.Builder(this)
                    .setTitle("Enter PIN")
                    .setView(pe)
                    .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (pin.equals(pe.getText().toString())) settingsDialog(true);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        settingsDialog(admin);
    }

    void settingsDialog(final boolean admin) {
        fontPick = null;
        LinearLayout box = new LinearLayout(this);
        // the container takes the initial focus, so no text field grabs it and opens the keyboard
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(10), p, 0);

        TextView l1 = new TextView(this);
        l1.setText("Speaker IP address (MusicCast app → device info, or your router's device list)");
        box.addView(l1);
        final EditText ipEdit = new EditText(this);
        ipEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ipEdit.setSingleLine(true);
        ipEdit.setText(TextUtils.isEmpty(ip()) ? DEFAULT_IP : ip());
        ipEdit.setHint(DEFAULT_IP);
        box.addView(ipEdit);

        final CheckBox screenOn = new CheckBox(this);
        final CheckBox boot = new CheckBox(this);
        final EditText pinEdit = new EditText(this);
        final EditText updateUrlEdit = new EditText(this);
        final CheckBox compactVol = new CheckBox(this);
        final CheckBox hideLoc = new CheckBox(this);
        final CheckBox followCb = new CheckBox(this);
        final CheckBox gestCb = new CheckBox(this);
        final CheckBox hideNavCb = new CheckBox(this);
        final EditText textScaleEdit = new EditText(this);
        final EditText hideSecsEdit = new EditText(this);
        final CheckBox hideOn = new CheckBox(this);
        final EditText failLimitEdit = new EditText(this);
        if (admin) {
            screenOn.setText("Keep screen on");
            screenOn.setChecked(prefs.getBoolean(PREF_SCREEN_ON, true));
            box.addView(screenOn);
            boot.setText("Start after reboot");
            boot.setChecked(prefs.getBoolean(PREF_BOOT, true));
            box.addView(boot);

            // font picker: each option is shown in its own font
            TextView fl0 = new TextView(this);
            fl0.setText("Font");
            fl0.setPadding(0, dp(8), 0, 0);
            box.addView(fl0);
            final int[] fontChoice = { Fonts.index(prefs.getString(PREF_FONT, "default")) };
            final android.widget.Button fontBtn = new android.widget.Button(this);
            fontBtn.setText(Fonts.LIST[fontChoice[0]][1]);
            fontBtn.setTypeface(Fonts.load(this, fontChoice[0])[0]);
            fontBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pickFont(fontChoice, fontBtn); }
            });
            box.addView(fontBtn);
            fontPick = fontChoice;
            TextView l2 = new TextView(this);
            l2.setText("Admin PIN for this screen (empty = no PIN)");
            box.addView(l2);
            pinEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pinEdit.setText(prefs.getString(PREF_PIN, ""));
            box.addView(pinEdit);

            TextView l3 = new TextView(this);
            l3.setText("Update URL (version.json) \u2013 leave empty for automatic");
            box.addView(l3);
            updateUrlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            updateUrlEdit.setSingleLine(true);
            updateUrlEdit.setText(updateUrl());
            updateUrlEdit.setHint("Automatic (jsDelivr, GitHub)");
            box.addView(updateUrlEdit);
            android.widget.Button chk = new android.widget.Button(this);
            chk.setText("Check for updates now  (installed: " + versionName() + ")");
            chk.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.edit().putString(PREF_UPDATE_URL, updateUrlEdit.getText().toString().trim()).commit();
                    checkForUpdates(true, (android.widget.Button) v);
                }
            });
            box.addView(chk);

            TextView adv = new TextView(this);
            adv.setText("ADVANCED");
            adv.setTextColor(0xFF33B5E5);
            adv.setTypeface(Typeface.DEFAULT_BOLD);
            adv.setPadding(0, dp(18), 0, dp(4));
            box.addView(adv);
            compactVol.setText("Volume bar without bottom padding");
            compactVol.setChecked(prefs.getBoolean(PREF_COMPACT_VOLUME, true));
            box.addView(compactVol);
            hideLoc.setText("Hide location in radio station names, e.g. \"(Bratislava/Slovak)\"");
            hideLoc.setChecked(prefs.getBoolean(PREF_HIDE_LOCATION, true));
            box.addView(hideLoc);
            followCb.setText("Follow the speaker (also controlled from a phone): its state always wins, "
                    + "Play and Pause are sent separately");
            followCb.setChecked(follower());
            box.addView(followCb);
            gestCb.setText("Swipe gestures on the whole screen: drag up/down = volume, swipe left/right = next/previous");
            gestCb.setChecked(prefs.getBoolean(PREF_GESTURES, true));
            box.addView(gestCb);
            if (Build.VERSION.SDK_INT < 19) {
                hideNavCb.setText("Hide navigation buttons (Home, Back, Recent). The first tap only brings them "
                        + "back; they hide again 3 s later");
                hideNavCb.setChecked(prefs.getBoolean(PREF_HIDE_NAV, false));
                box.addView(hideNavCb);
            }
            TextView tsl = new TextView(this);
            tsl.setText("Text size (%) \u2013 also pinch the screen; double-tap the song to reset");
            tsl.setPadding(0, dp(8), 0, 0);
            box.addView(tsl);
            LinearLayout tsRow = new LinearLayout(this);
            tsRow.setOrientation(LinearLayout.HORIZONTAL);
            textScaleEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            textScaleEdit.setSingleLine(true);
            textScaleEdit.setText(String.valueOf(Math.round(Fonts.scale * 100)));
            tsRow.addView(slider(textScaleEdit, 60, 200), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            tsRow.addView(textScaleEdit, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.Button tsReset = new android.widget.Button(this);
            tsReset.setText("Reset to 100 %");
            tsReset.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { textScaleEdit.setText("100"); }
            });
            tsRow.addView(tsReset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(tsRow);
            TextView hl = new TextView(this);
            hideOn.setText("Hide playback controls when not touched");
            hideOn.setChecked(prefs.getBoolean(PREF_HIDE_ON, true));
            hideOn.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) { hideSecsEdit.setEnabled(on); }
            });
            box.addView(hideOn);
            hl.setText("Hide them after (seconds)");
            box.addView(hl);
            hideSecsEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            hideSecsEdit.setSingleLine(true);
            hideSecsEdit.setText(String.valueOf(prefs.getInt(PREF_HIDE_SECS, 10)));
            hideSecsEdit.setEnabled(hideOn.isChecked());
            box.addView(sliderRow(hideSecsEdit, 1, 120));
            TextView fl = new TextView(this);
            fl.setText("Show \"not connected\" after this many failed checks in a row (one check every 3 s)");
            box.addView(fl);
            failLimitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            failLimitEdit.setSingleLine(true);
            failLimitEdit.setText(String.valueOf(failLimit()));
            box.addView(sliderRow(failLimitEdit, 1, 20));
            TextView health = new TextView(this);
            health.setText(healthText());
            health.setPadding(0, dp(8), 0, dp(8));
            box.addView(health);
        }

        AlertDialog.Builder bld = new AlertDialog.Builder(this)
                .setTitle(admin ? "Kiosk settings" : "Speaker")
                .setView(scroll(box))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        SharedPreferences.Editor e = prefs.edit();
                        String newIp = ipEdit.getText().toString().trim();
                        if (!validIp(newIp)) {
                            info("Speaker IP not saved", "\"" + newIp + "\" isn't a complete address. It needs four numbers "
                                    + "from 0 to 255 separated by dots, e.g. " + DEFAULT_IP + ".");
                            return;
                        }
                        boolean ipChanged = !newIp.equals(ip());
                        boolean fontChanged = false;
                        boolean scaleChanged = false;
                        e.putString(PREF_IP, newIp);
                        if (admin) {
                            e.putBoolean(PREF_SCREEN_ON, screenOn.isChecked());
                            e.putBoolean(PREF_BOOT, boot.isChecked());
                            e.putString(PREF_PIN, pinEdit.getText().toString().trim());
                            e.putString(PREF_UPDATE_URL, updateUrlEdit.getText().toString().trim());
                            e.putBoolean(PREF_COMPACT_VOLUME, compactVol.isChecked());
                            e.putBoolean(PREF_HIDE_LOCATION, hideLoc.isChecked());
                            e.putBoolean(PREF_FOLLOWER, followCb.isChecked());
                            e.putBoolean(PREF_GESTURES, gestCb.isChecked());
                            if (Build.VERSION.SDK_INT < 19) e.putBoolean(PREF_HIDE_NAV, hideNavCb.isChecked());
                            try {
                                int pct = Math.max(60, Math.min(200, Integer.parseInt(textScaleEdit.getText().toString().trim())));
                                e.putFloat(PREF_TEXT_SCALE, pct / 100f);
                                Fonts.scale = pct / 100f;
                                scaleChanged = true;
                            } catch (NumberFormatException ignored) { }
                            if (fontPick != null) {
                                String newFont = Fonts.LIST[fontPick[0]][0];
                                fontChanged = !newFont.equals(prefs.getString(PREF_FONT, "default"));
                                e.putString(PREF_FONT, newFont);
                            }
                            e.putBoolean(PREF_HIDE_ON, hideOn.isChecked());
                            int hs = prefs.getInt(PREF_HIDE_SECS, 10);
                            try { hs = Math.max(0, Math.min(3600, Integer.parseInt(hideSecsEdit.getText().toString().trim()))); }
                            catch (NumberFormatException ignored) { }
                            e.putInt(PREF_HIDE_SECS, hs);
                            int fl = failLimit();
                            try { fl = Math.max(1, Math.min(100, Integer.parseInt(failLimitEdit.getText().toString().trim()))); }
                            catch (NumberFormatException ignored) { }
                            e.putInt(PREF_FAIL_LIMIT, fl);
                        }
                        e.commit();
                        if (fontChanged) { recreate(); return; } // rebuild all views in the new font
                        applyScreenOn();
                        applyLayoutPrefs();
                        if (scaleChanged) applyTextScale();
                        wakeTransport();
                        if (ipChanged) { everPolled = false; connected = false; failStreak = 0; track = ""; artist = ""; input = ""; }
                        render();
                        poll();
                        hideSystemBars();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { hideSystemBars(); }
                });
        if (admin) {
            bld.setNeutralButton("Leave kiosk", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { leaveKiosk(); }
            });
        }
        AlertDialog dlg = bld.create();
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        dlg.show();
        box.requestFocus();
    }

    View scroll(View v) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(v);
        return sv;
    }

    /** Custom update URL, or "" for automatic (jsDelivr, then GitHub, then plain HTTP). */
    String updateUrl() {
        String u = prefs.getString(PREF_UPDATE_URL, "").trim();
        return u.equals(Updater.OLD_DEFAULT_URL) ? "" : u;
    }

    String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Four numbers 0-255 separated by dots (a host name with a letter is accepted too). */
    static boolean validIp(String s) {
        if (s == null || s.length() == 0) return false;
        if (s.matches(".*[a-zA-Z].*")) return s.matches("[a-zA-Z0-9.-]+");
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;
        for (String x : p) {
            if (!x.matches("\\d{1,3}") || Integer.parseInt(x) > 255) return false;
        }
        return true;
    }

    int[] fontPick; // choice in the open settings dialog

    /** List of fonts, each name drawn in its own font. */
    void pickFont(final int[] choice, final android.widget.Button target) {
        final String[] names = new String[Fonts.LIST.length];
        final android.graphics.Typeface[] faces = new android.graphics.Typeface[Fonts.LIST.length];
        for (int i = 0; i < names.length; i++) { names[i] = Fonts.LIST[i][1]; faces[i] = Fonts.load(this, i)[0]; }
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, names) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                TextView t = (TextView) v.findViewById(android.R.id.text1);
                t.setTypeface(faces[pos]);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                return v;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle("Font")
                .setSingleChoiceItems(ad, choice[0], new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        choice[0] = which;
                        target.setText(names[which]);
                        target.setTypeface(faces[which]);
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A SeekBar kept in sync with a number field, both ways. Typed values outside min..max are allowed. */
    android.widget.SeekBar slider(final EditText field, final int min, final int max) {
        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(max - min);
        final boolean[] syncing = { false };
        Runnable fromField = new Runnable() { public void run() {
            try {
                int v = Integer.parseInt(field.getText().toString().trim());
                syncing[0] = true;
                sb.setProgress(Math.max(min, Math.min(max, v)) - min);
                syncing[0] = false;
            } catch (NumberFormatException ignored) { }
        } };
        fromField.run();
        final Runnable ff = fromField;
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { if (!syncing[0]) ff.run(); }
        });
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean fromUser) {
                if (!fromUser || syncing[0]) return;
                syncing[0] = true;
                field.setText(String.valueOf(min + p));
                syncing[0] = false;
            }
            public void onStartTrackingTouch(android.widget.SeekBar b) { }
            public void onStopTrackingTouch(android.widget.SeekBar b) { }
        });
        return sb;
    }

    /** Slider + number field side by side. */
    View sliderRow(EditText field, int min, int max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(slider(field, min, max), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(field, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    void info(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    void checkForUpdates(boolean manual) { checkForUpdates(manual, null); }

    /** btn (optional): the button that started the check; it shows the progress instead of a toast. */
    void checkForUpdates(final boolean manual, final android.widget.Button btn) {
        if (updateBusy) return;
        updateBusy = true;
        lastUpdateCheck = System.currentTimeMillis();
        final String url = updateUrl();
        final CharSequence btnText = btn != null ? btn.getText() : null;
        if (btn != null) { btn.setText("Checking for updates\u2026"); btn.setEnabled(false); }
        else if (manual) toast("Checking for updates\u2026");
        new Thread(new Runnable() {
            public void run() {
                Updater.Info info = null;
                String err = null;
                try { info = Updater.check(MainActivity.this, url); }
                catch (Exception e) { err = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                final Updater.Info fi = info;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        updateBusy = false;
                        if (btn != null) { btn.setText(btnText); btn.setEnabled(true); }
                        pendingUpdate = fi != null && fi.newer ? fi : null;
                        render();
                        if (!manual) return;
                        if (fe != null) info("Update check failed", fe);
                        else if (fi != null && fi.newer) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Update available")
                                    .setMessage("Version " + fi.versionName + " is available (installed " + versionName() + ").\n\nSources:" + fi.report)
                                    .setPositiveButton("Update now", new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) { downloadAndInstall(fi); }
                                    })
                                    .setNegativeButton("Later", null)
                                    .show();
                        }
                        else if (fi != null) info("Up to date", "No newer version (installed " + versionName() + ")\n\nSources:" + fi.report);
                    }
                });
            }
        }).start();
    }

    void downloadAndInstall(final Updater.Info info) {
        if (updateBusy) return;
        updateBusy = true;
        updateText.setText("Downloading\u2026");
        new Thread(new Runnable() {
            public void run() {
                java.io.File f = null;
                String err = null;
                try { f = Updater.download(MainActivity.this, info); }
                catch (Exception e) { err = e.getClass().getSimpleName() + ": " + e.getMessage(); }
                final java.io.File ff = f;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        updateBusy = false;
                        updateText.setText("Update " + info.versionName);
                        if (fe != null) { toast("Download failed: " + fe); return; }
                        try { Updater.install(MainActivity.this, ff); }
                        catch (Exception e) { toast("Can't open the installer: " + e.getMessage()); }
                    }
                });
            }
        }).start();
    }

    /** Opens this app's system page, where "Clear defaults" stops it being the Home app. */
    void leaveKiosk() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------
    void togglePower() {
        powerOn = !powerOn;
        powerTarget = powerOn;
        powerChangedAt = System.currentTimeMillis();
        render();
        send(powerOn ? "main/setPower?power=on" : "main/setPower?power=standby", true);
    }

    void selectInput(int idx) {
        long now = System.currentTimeMillis();
        if (!powerOn) {
            powerOn = true; powerTarget = true; powerChangedAt = now;
            send("main/setPower?power=on", false);
        }
        if (!SRC_ID[idx].equals(input)) { inputChangedAt = now; track = ""; artist = ""; album = ""; playback = ""; }
        input = SRC_ID[idx];
        render();
        send("main/setInput?input=" + SRC_ID[idx], true);
    }

    void changeVolume(int delta) {
        int maxv = maxVolume > 0 ? maxVolume : 100;
        int v = Math.max(0, Math.min(maxv, volume + delta));
        volume = v;
        // follower: only a short hold so the bar doesn't jump while −/+ is held
        volumeHoldUntil = System.currentTimeMillis() + (follower() ? 600 : 2500);
        flash("Volume " + v + (v == maxv ? " \u00b7 max" : "") + (mute ? " \u00b7 muted" : ""), mute ? C_AMBER : C_TEXT, 1500);
        // coalesce: only the latest value is sent
        if (pendingVolume.getAndSet(v) < 0) {
            net.execute(new Runnable() {
                public void run() {
                    int val = pendingVolume.getAndSet(-1);
                    if (val >= 0) request("main/setVolume?volume=" + val);
                }
            });
        }
    }

    void send(final String path, final boolean thenPoll) {
        final String ipNow = ip();
        if (TextUtils.isEmpty(ipNow)) return;
        net.execute(new Runnable() {
            public void run() {
                final boolean ok = request(path) != null;
                ui.post(new Runnable() {
                    public void run() {
                        if (ok) { connected = true; failStreak = 0; }
                        else noteFailure();
                        everPolled = true;
                        render();
                        if (thenPoll) ui.postDelayed(new Runnable() { public void run() { poll(); } }, 250);
                    }
                });
            }
        });
    }

    void poll() {
        final String ipNow = ip();
        if (TextUtils.isEmpty(ipNow)) { render(); return; }
        if (!polling.compareAndSet(false, true)) return;
        net.execute(new Runnable() {
            public void run() {
                final String st = requestRetry("main/getStatus");
                String pi = null;
                try {
                    if (st != null && "on".equals(new JSONObject(st).optString("power"))) pi = requestRetry("netusb/getPlayInfo");
                } catch (Exception ignored) { }
                final String playInfo = pi;
                polling.set(false);
                ui.post(new Runnable() {
                    public void run() { applyPoll(st, playInfo); }
                });
            }
        });
    }

    void applyPoll(String st, String pi) {
        everPolled = true;
        if (st == null) {
            // one lost check isn't a disconnect: keep the screen until several fail in a row
            noteFailure();
            render();
            return;
        }
        connected = true;
        failStreak = 0;
        if (st != null) {
            try {
                JSONObject j = new JSONObject(st);
                if (j.optInt("response_code", 0) == 0) {
                    boolean polledOn = "on".equals(j.optString("power"));
                    // right after a power change the speaker may still report the old state
                    if (follower() || System.currentTimeMillis() - powerChangedAt > SETTLE_MS || polledOn == powerTarget) powerOn = polledOn;
                    maxVolume = j.optInt("max_volume", maxVolume);
                    if (System.currentTimeMillis() > volumeHoldUntil && pendingVolume.get() < 0)
                        volume = j.optInt("volume", volume);
                    mute = j.optBoolean("mute", mute);
                    String polledInput = j.optString("input", input);
                    if (follower() || System.currentTimeMillis() - inputChangedAt > SETTLE_MS || polledInput.equals(input)) input = polledInput;
                }
            } catch (Exception ignored) { }
        }
        if (!powerOn) { playing = false; playback = ""; track = ""; artist = ""; }
        else if (pi != null) {
            try {
                JSONObject j = new JSONObject(pi);
                String pb = j.optString("playback");
                playback = pb;
                playing = "play".equals(pb);
                String sh = j.optString("shuffle", "");
                if (sh.length() > 0) shuffle = !"off".equals(sh);
                String rp = j.optString("repeat", "");
                if (rp.length() > 0) repeatMode = rp;
                if ("play".equals(pb) || "pause".equals(pb)) {
                    track = j.optString("track", "");
                    artist = j.optString("artist", "");
                    album = j.optString("album", "");
                } else {
                    track = "";
                    // stopped radio: keep the station name for "Stopped" text
                    if (!"net_radio".equals(input)) { artist = ""; album = ""; }
                }
            } catch (Exception ignored) { }
        }
        render();
    }

    /** Blocking GET on the worker thread. Returns the body, or null on failure. */
    volatile String lastNetError = "";

    int failLimit() { return Math.max(1, prefs.getInt(PREF_FAIL_LIMIT, 3)); }

    void noteFailure() {
        failStreak++;
        worstStreak = Math.max(worstStreak, failStreak);
        lastFailAt = System.currentTimeMillis();
        if (failStreak >= failLimit()) connected = false;
    }

    String healthText() {
        int t = reqTotal.get(), f = reqFailed.get();
        long mins = (System.currentTimeMillis() - startedAt) / 60000;
        String last = lastFailAt == 0 ? "never" :
                android.text.format.DateFormat.getTimeFormat(this).format(new Date(lastFailAt));
        return "Connection: " + f + " of " + t + " requests failed in the last " + mins + " min ("
                + (t > 0 ? Math.round(100f * f / t) : 0) + "%), longest run " + worstStreak
                + " failed checks in a row, last failure " + last + "."
                + (lastNetError.length() > 0 ? "\nLast error: " + lastNetError : "")
                + (presetDiag.length() > 0 ? "\n" + presetDiag : "");
    }

    /** Read-only requests are safe to repeat: retry once after a short pause. */
    String requestRetry(String path) {
        String r = request(path);
        if (r == null) {
            try { Thread.sleep(400); } catch (InterruptedException e) { return null; }
            r = request(path);
        }
        return r;
    }

    String request(String path) {
        String r = requestOnce(path);
        reqTotal.incrementAndGet();
        if (r == null) reqFailed.incrementAndGet();
        return r;
    }

    String requestOnce(String path) {
        String ipNow = ip();
        if (TextUtils.isEmpty(ipNow)) return null;
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + ipNow + "/YamahaExtendedControl/v1/" + path);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "close");
            int code = c.getResponseCode();
            if (code != 200) { lastNetError = "HTTP " + code + " from the speaker"; return null; }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            lastNetError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int color(int c) { return Color.argb(255, Color.red(c), Color.green(c), Color.blue(c)); }
}
