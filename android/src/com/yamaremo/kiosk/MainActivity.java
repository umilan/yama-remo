package com.yamaremo.kiosk;

import static com.yamaremo.kiosk.TextLogic.*;

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
    static final String PREF_DEVICE_ID = "device_id";     // speaker identity, to follow it to a new IP
    static final String PREF_AUTODIM = "autodim";
    static final String PREF_ART_BG = "art_background";      // album art as blurred background
    static final String PREF_VOL_LIMIT = "volume_limit";      // highest volume the tablet sets, % of max
    static final String PREF_WAKE_ON = "wake_on";
    static final String PREF_WAKE_MIN = "wake_minute";        // minutes after midnight
    static final String PREF_WAKE_WEEKDAYS = "wake_weekdays";
    static final String PREF_WAKE_PRESET = "wake_preset";     // favourite number, 0 = last station
    static final String PREF_WAKE_PRESET_NAME = "wake_preset_name";
    static final String PREF_WAKE_VOL = "wake_volume";
    static final String PREF_WAKE_RAMP = "wake_ramp";         // minutes to reach the volume
    static final String PREF_WAKE_LAST = "wake_last";         // yyyymmdd of the last wake-up
    static final String PREF_TABS = "tabs";                   // comma-separated input ids, in order
    static final String PREF_OFF_ON = "autooff_on";
    static final String PREF_OFF_MIN = "autooff_minute";      // minutes after midnight
    static final String PREF_OFF_LAST = "autooff_last";       // yyyymmdd of the last auto-off
    static final String PREF_ART_LEVEL = "art_level";        // its visibility, %
    static final String PREF_DIM_CAMERA = "autodim_camera";
    static final String PREF_NIGHT_LUX = "night_lux";        // light sensor: night below this many lux
    static final String PREF_NIGHT_CAM = "night_cam";        // camera: night below this frame brightness (0-255)
    static final String PREF_NIGHT_BRIGHT = "night_brightness"; // screen backlight at night, %
    static final String PREF_NIGHT_TEXT = "night_text";      // text brightness at night, %

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

    static final String DEFAULT_TABS = "airplay,aux,optical,bluetooth,net_radio";
    static final int MAX_TABS = 7;
    // the source tabs, chosen and ordered in settings (PREF_TABS)
    static String[] SRC_ID = DEFAULT_TABS.split(",");
    static String[] SRC_LABEL = { "AirPlay", "AUX", "Optical", "Bluetooth", "Radio" };

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
    Btn[] srcBtns;
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
        Diag.init(getApplicationContext());
        Diag.log("APP", "started v" + versionName() + ", speaker IP " + (ip().length() > 0 ? ip() : "not set"));
        Fonts.apply(this, prefs.getString(PREF_FONT, "default"));
        Fonts.scale = prefs.getFloat(PREF_TEXT_SCALE, 1f);
        events = new Events(new Events.Listener() {
            public void onEvent(String json) {
                if (lastEventAt == 0) Diag.log("EVENTS", "push updates from the speaker are working");
                lastEventAt = System.currentTimeMillis();
                ui.post(new Runnable() { public void run() { poll(); } }); // poll() ignores overlapping calls
            }
        });
        events.start();
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
        // album art sits behind the whole UI
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(C_BG);
        artView = new android.widget.ImageView(this);
        artView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        artView.setVisibility(View.GONE);
        frame.addView(artView, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(buildUi(), new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentRoot = frame;
        setContentView(contentRoot);
        autoDim = new AutoDim(this, new AutoDim.Listener() {
            public void onReading(String source, float value) { onLight(source, value); }
        });
        render();
        if (TextUtils.isEmpty(ip())) ui.postDelayed(new Runnable() {
            public void run() { showSettings(false); }
        }, 400);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startAutoDim();
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
        if (autoDim != null) autoDim.stop();
        try { if (wifiLock != null) wifiLock.release(); } catch (Exception ignored) { }
        ui.removeCallbacks(pollLoop);
        ui.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
        if (events != null) events.shutdown();
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
        if (settingsOpen()) return super.dispatchTouchEvent(e); // no swipes / pinch in settings
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
    public void onBackPressed() { if (settingsOpen() && settingsSave != null) settingsSave.run(); }

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
            checkWake();
            checkAutoOff();
            // with push events working, a slow poll is enough (it also keeps the events subscription alive)
            boolean pushing = System.currentTimeMillis() - lastEventAt < 5 * 60 * 1000;
            if (resumed) ui.postDelayed(this, pushing ? 10000 : POLL_MS);
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
        root.setBackgroundColor(0); // the frame behind it is black (and may show album art)

        // --- source tabs + OFF
        LinearLayout tabs = row();
        tabsRow = tabs;
        loadTabs();
        srcBtns = new Btn[SRC_ID.length];
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


    String sourceLabel(String id) {
        for (int i = 0; i < SRC_ID.length; i++) if (SRC_ID[i].equals(id)) return SRC_LABEL[i];
        for (String[] o : OTHER_SOURCES) if (o[0].equals(id)) return o[1];
        if (id == null || id.length() == 0) return "";
        // unknown id: "some_input" -> "Some input"
        String t = id.replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }


    void loadTabs() {
        String[] ids = prefs.getString(PREF_TABS, DEFAULT_TABS).split(",");
        java.util.List<String> keep = new java.util.ArrayList<String>();
        for (String id : ids) if (id.trim().length() > 0 && keep.size() < MAX_TABS) keep.add(id.trim());
        if (keep.isEmpty()) keep.add("net_radio");
        SRC_ID = keep.toArray(new String[0]);
        SRC_LABEL = new String[SRC_ID.length];
        for (int i = 0; i < SRC_ID.length; i++) SRC_LABEL[i] = tabLabel(SRC_ID[i]);
    }

    /** Settings: pick and order the tabs from the inputs the speaker reports. */
    void chooseTabs(final android.widget.Button summary) {
        final String ipNow = ip();
        net.execute(new Runnable() { public void run() {
            final java.util.List<String> all = new java.util.ArrayList<String>();
            try {
                JSONObject f = new JSONObject(requestRetry("system/getFeatures"));
                org.json.JSONArray list = f.getJSONObject("system").getJSONArray("input_list");
                for (int i = 0; i < list.length(); i++) all.add(list.getJSONObject(i).getString("id"));
            } catch (Exception ignored) { /* speaker not reachable: offer the known inputs */ }
            ui.post(new Runnable() { public void run() { tabsDialog(all, summary); } });
        } });
    }

    void tabsDialog(java.util.List<String> fromSpeaker, final android.widget.Button summary) {
        // current tabs first (in their order, ticked), then the other inputs
        final java.util.List<String> order = new java.util.ArrayList<String>();
        final java.util.Set<String> on = new java.util.HashSet<String>();
        for (String id : SRC_ID) { order.add(id); on.add(id); }
        java.util.List<String> extra = new java.util.ArrayList<String>(fromSpeaker);
        if (extra.isEmpty()) for (String id : new String[] { "airplay", "aux", "optical", "bluetooth", "usb", "net_radio", "spotify", "server" }) extra.add(id);
        for (String id : extra) if (!order.contains(id)) order.add(id);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() { public void run() {
            list.removeAllViews();
            TextView hint = new TextView(MainActivity.this);
            hint.setText("Tick up to " + MAX_TABS + " tabs; \u25b2 \u25bc change the order. "
                    + (on.size() > MAX_TABS ? "Too many ticked!" : on.size() + " ticked."));
            list.addView(hint);
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                final String id = order.get(i);
                LinearLayout r = new LinearLayout(MainActivity.this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                CheckBox cb = new CheckBox(MainActivity.this);
                cb.setText(tabLabel(id) + "   (" + id + ")");
                cb.setChecked(on.contains(id));
                cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                        if (c) on.add(id); else on.remove(id);
                        rebuild[0].run();
                    }
                });
                r.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                android.widget.Button up = new android.widget.Button(MainActivity.this);
                up.setText("\u25b2");
                up.setEnabled(i > 0);
                up.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    java.util.Collections.swap(order, idx, idx - 1); rebuild[0].run(); } });
                android.widget.Button dn = new android.widget.Button(MainActivity.this);
                dn.setText("\u25bc");
                dn.setEnabled(i < order.size() - 1);
                dn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    java.util.Collections.swap(order, idx, idx + 1); rebuild[0].run(); } });
                r.addView(up);
                r.addView(dn);
                list.addView(r);
            }
        } };
        rebuild[0].run();
        new AlertDialog.Builder(this)
                .setTitle("Tabs")
                .setView(scroll(list))
                .setPositiveButton("Use these tabs", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        StringBuilder sb = new StringBuilder();
                        int n = 0;
                        for (String id : order) if (on.contains(id) && n < MAX_TABS) { sb.append(n++ > 0 ? "," : "").append(id); }
                        if (n == 0) { toast("Pick at least one tab"); return; }
                        pendingTabs = sb.toString();
                        summary.setText("Tabs: " + tabsSummary(pendingTabs) + "  (applies on Save)");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    String pendingTabs; // chosen in the open settings dialog, saved with Save


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


    volatile String presetReadError;
    volatile int freePresetSlot = -1;

    /** Worker thread: saves the station playing now as a speaker favourite. */
    void saveCurrentStation() {
        loadPresets();
        final int slot = freePresetSlot;
        if (slot < 0) {
            ui.post(new Runnable() { public void run() {
                flash("All favourite slots are full \u2013 remove one first", C_AMBER, 4000); } });
            return;
        }
        String resp = request("netusb/storePreset?num=" + slot);
        int code = -1;
        try { code = resp == null ? -1 : new JSONObject(resp).optInt("response_code", 0); } catch (Exception ignored) { }
        final int fc = code;
        ui.post(new Runnable() { public void run() {
            if (fc == 0) { flash("Saved as favourite " + slot, C_TEXT, 2500); showStationPicker(); }
            else flash(fc < 0 ? "Couldn't reach the speaker" : "Speaker refused to save (code " + fc + ")", C_AMBER, 4000);
        } });
    }

    /** Worker thread: removes a favourite (needs a speaker firmware that supports clearPreset). */
    void removeStation(final String[] preset) {
        String resp = request("netusb/clearPreset?num=" + preset[0]);
        int code = -1;
        try { code = resp == null ? -1 : new JSONObject(resp).optInt("response_code", 0); } catch (Exception ignored) { }
        final int fc = code;
        ui.post(new Runnable() { public void run() {
            if (fc == 0) { flash("Removed " + cleanStation(preset[1]), C_TEXT, 2500); showStationPicker(); }
            else flash(fc < 0 ? "Couldn't reach the speaker"
                    : "This speaker can't remove favourites from apps \u2013 remove it in the MusicCast app", C_AMBER, 5000);
        } });
    }

    /** Worker thread: reads the radio favourites fresh from the speaker ({number, name} each). */
    java.util.List<String[]> loadPresets() {
        java.util.List<String[]> list = new java.util.ArrayList<String[]>();
        String r = requestRetry("netusb/getPresetInfo");
        String readError = null;
        try {
            JSONObject j = new JSONObject(r);
            if (j.optInt("response_code", 0) != 0) readError = "code " + j.optInt("response_code");
            org.json.JSONArray arr = j.getJSONArray("preset_info");
            freePresetSlot = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String name = p.optString("text", "");
                if (freePresetSlot < 0 && name.length() == 0) freePresetSlot = i + 1; // first empty favourite
                if ("net_radio".equals(p.optString("input")) && name.length() > 0)
                    list.add(new String[] { String.valueOf(i + 1), name });
            }
            radioPresets = list;
            presetsLoadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            if (readError == null) readError = r == null ? "no answer" : "unexpected answer";
            if (radioPresets != null) list = radioPresets;
        }
        presetReadError = readError;
        StringBuilder names = new StringBuilder();
        for (String[] p : list) names.append(names.length() > 0 ? ", " : "").append(p[0]).append(": ").append(p[1]);
        presetDiag = "Radio favourites on the speaker: " + list.size()
                + (list.isEmpty() ? "" : " (" + names + ")")
                + (readError != null ? " \u2013 last read failed: " + readError : "");
        return list;
    }

    /** Index of the playing station among the favourites, or -1. */
    static int currentPreset(java.util.List<String[]> presets, String currentStation) {
        String b = norm(currentStation);
        for (int i = 0; i < presets.size() && b.length() > 0; i++) {
            String a = norm(presets.get(i)[1]);
            if (a.length() > 0 && (a.equals(b) || a.contains(b) || b.contains(a))) return i;
        }
        return -1;
    }

    void noPresetsMessage() {
        final String re = presetReadError;
        ui.post(new Runnable() { public void run() {
            flash(re != null ? "Couldn't read saved stations (" + re + ")"
                    : "No saved stations \u2013 add favourites in MusicCast", C_AMBER, 4000);
        } });
    }

    /** Worker thread: switches to a favourite and reports a refusal. */
    void recallPreset(final String[] target, final String message) {
        ui.post(new Runnable() { public void run() {
            pendingStation = target[1];
            stationChangedAt = System.currentTimeMillis();
            trackAtSwitch = track;
            if (message != null) flash(message, C_TEXT, 1500); else render();
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
                Diag.log("RADIO", "switch to favourite " + target[0] + " failed (code " + fc + ")");
            }
            render();
            ui.postDelayed(new Runnable() { public void run() { poll(); } }, 1200);
        } });
    }

    void stepStation(final int step) {
        if (TextUtils.isEmpty(ip())) return;
        final String currentStation = !TextUtils.isEmpty(artist) ? artist : album;
        net.execute(new Runnable() {
            public void run() {
                java.util.List<String[]> presets = loadPresets(); // fresh: may have changed in MusicCast
                if (presets.isEmpty()) { noPresetsMessage(); return; }
                int cur = currentPreset(presets, currentStation);
                if (cur < 0) cur = presetIndex >= 0 && presetIndex < presets.size() ? presetIndex : -1;
                int n = presets.size();
                if (n == 1 && cur == 0) {
                    ui.post(new Runnable() { public void run() {
                        flash("Only one saved station \u2013 add more favourites in MusicCast", C_AMBER, 4000);
                    } });
                    return;
                }
                int next = cur < 0 ? (step > 0 ? 0 : n - 1) : ((cur + step) % n + n) % n;
                presetIndex = next;
                recallPreset(presets.get(next), (step > 0 ? "Next station " : "Previous station ") + (next + 1) + "/" + n);
            }
        });
    }

    /** Tap on the active Radio tab: a grid of the saved stations to pick from. */
    void showStationPicker() {
        if (TextUtils.isEmpty(ip())) return;
        final String currentStation = !TextUtils.isEmpty(artist) ? artist : album;
        net.execute(new Runnable() {
            public void run() {
                final java.util.List<String[]> presets = loadPresets();
                if (presets.isEmpty()) { noPresetsMessage(); return; }
                final int cur = currentPreset(presets, currentStation);
                ui.post(new Runnable() { public void run() { stationGrid(presets, cur); } });
            }
        });
    }

    void stationGrid(final java.util.List<String[]> presets, final int cur) {
        final android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setAdapter(new android.widget.BaseAdapter() {
            public int getCount() { return presets.size(); }
            public Object getItem(int i) { return presets.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View cv, ViewGroup parent) {
                TextView t = cv instanceof TextView ? (TextView) cv : new TextView(MainActivity.this);
                t.setText(cleanStation(presets.get(i)[1]));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20 * Fonts.scale);
                t.setTypeface(Fonts.bold);
                t.setGravity(Gravity.CENTER);
                t.setMinHeight(dp(84));
                t.setPadding(dp(8), dp(8), dp(8), dp(8));
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setTextColor(i == cur ? C_BG : C_TEXT);
                t.setBackgroundColor(i == cur ? C_ACCENT : 0xFF2A2A2A);
                return t;
            }
        });
        AlertDialog.Builder bld = new AlertDialog.Builder(this)
                .setTitle("Radio stations  (long-press one to remove it)")
                .setView(grid)
                .setNegativeButton("Close", null);
        if (cur < 0 && "net_radio".equals(input) && connected && powerOn) {
            // the station playing now isn't a favourite yet: offer to save it
            bld.setPositiveButton("Save current station", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    net.execute(new Runnable() { public void run() { saveCurrentStation(); } });
                }
            });
        }
        final AlertDialog dlg = bld.create();
        grid.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> p, View v, final int i, long id) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Remove \"" + cleanStation(presets.get(i)[1]) + "\" from the favourites?")
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                dlg.dismiss();
                                net.execute(new Runnable() { public void run() { removeStation(presets.get(i)); } });
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
        });
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, final int i, long id) {
                dlg.dismiss();
                presetIndex = i;
                net.execute(new Runnable() { public void run() {
                    recallPreset(presets.get(i), "Station " + (i + 1) + "/" + presets.size());
                } });
            }
        });
        dlg.show();
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
        boolean showArt = on && artReady && prefs.getBoolean(PREF_ART_BG, true) && !"".equals(artUrl);
        int av = showArt ? View.VISIBLE : View.GONE;
        if (artView.getVisibility() != av) artView.setVisibility(av);
        artView.setAlpha(prefs.getInt(PREF_ART_LEVEL, 35) / 100f);
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
        volBar.limit = volumeLimit() / (float) maxv;
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
        final android.widget.Button findBtn = new android.widget.Button(this);
        findBtn.setText("Find speaker on this Wi-Fi");
        findBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { findSpeakers(findBtn, ipEdit); }
        });
        box.addView(findBtn);

        final CheckBox screenOn = new CheckBox(this);
        final CheckBox boot = new CheckBox(this);
        final EditText pinEdit = new EditText(this);
        final EditText updateUrlEdit = new EditText(this);
        final CheckBox compactVol = new CheckBox(this);
        final CheckBox artCb = new CheckBox(this);
        final EditText artLevelEdit = new EditText(this);
        final CheckBox hideLoc = new CheckBox(this);
        final CheckBox followCb = new CheckBox(this);
        final CheckBox gestCb = new CheckBox(this);
        final CheckBox hideNavCb = new CheckBox(this);
        final EditText textScaleEdit = new EditText(this);
        final CheckBox dimCb = new CheckBox(this);
        final EditText volLimitEdit = new EditText(this);
        final CheckBox wakeCb = new CheckBox(this);
        final CheckBox offCb = new CheckBox(this);
        final int[] offMin = { prefs.getInt(PREF_OFF_MIN, 60) };
        final CheckBox wakeWdCb = new CheckBox(this);
        final EditText wakeVolEdit = new EditText(this);
        final EditText wakeRampEdit = new EditText(this);
        final int[] wakeMin = { prefs.getInt(PREF_WAKE_MIN, 7 * 60) };
        final String[] wakeStation = { String.valueOf(prefs.getInt(PREF_WAKE_PRESET, 0)),
                prefs.getString(PREF_WAKE_PRESET_NAME, "Last played station") };
        final CheckBox camCb = new CheckBox(this);
        final EditText nightThEdit = new EditText(this);
        final EditText nightBrEdit = new EditText(this);
        final EditText nightTxEdit = new EditText(this);
        final boolean[] nightSensorSrc = { false };
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

            pendingTabs = null;
            final android.widget.Button tabsBtn = new android.widget.Button(this);
            tabsBtn.setText("Tabs: " + tabsSummary(prefs.getString(PREF_TABS, DEFAULT_TABS)));
            tabsBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { chooseTabs(tabsBtn); }
            });
            box.addView(tabsBtn);
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
            adv.setTag("section");
            adv.setTextColor(0xFF33B5E5);
            adv.setTypeface(Typeface.DEFAULT_BOLD);
            adv.setPadding(0, dp(18), 0, dp(4));
            box.addView(adv);
            compactVol.setText("Volume bar without bottom padding");
            compactVol.setChecked(prefs.getBoolean(PREF_COMPACT_VOLUME, true));
            box.addView(compactVol);
            artCb.setText("Album art / station logo as a blurred background");
            artCb.setChecked(prefs.getBoolean(PREF_ART_BG, true));
            box.addView(artCb);
            TextView all = new TextView(this);
            all.setText("Background visibility (%)");
            box.addView(all);
            artLevelEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            artLevelEdit.setText(String.valueOf(prefs.getInt(PREF_ART_LEVEL, 35)));
            box.addView(sliderRow(artLevelEdit, 5, 80));
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
            TextView roomHdr = new TextView(this);
            roomHdr.setText("ROOM LIGHT");
            roomHdr.setTextColor(0xFF33B5E5);
            roomHdr.setTypeface(Typeface.DEFAULT_BOLD);
            roomHdr.setPadding(0, dp(18), 0, dp(4));
            box.addView(roomHdr);
            dimCb.setText("Dim the screen when the room is dark");
            dimCb.setChecked(prefs.getBoolean(PREF_AUTODIM, true));
            box.addView(dimCb);
            camCb.setText("Use the front camera to measure room light if there is no light sensor "
                    + "(one tiny frame per minute, analysed and discarded, never stored)");
            camCb.setChecked(prefs.getBoolean(PREF_DIM_CAMERA, true));
            box.addView(camCb);
            final TextView lightNow = new TextView(this);
            lightNow.setText(lightText());
            box.addView(lightNow);
            final boolean sensorSrc = AutoDim.SENSOR.equals(autoDim.source(prefs.getBoolean(PREF_DIM_CAMERA, true)));
            TextView thl = new TextView(this);
            thl.setText(sensorSrc ? "Night mode below (lux)" : "Night mode below (camera brightness, compare with \"now\" above)");
            box.addView(thl);
            nightThEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            nightThEdit.setText(String.valueOf(sensorSrc ? prefs.getInt(PREF_NIGHT_LUX, 5) : prefs.getInt(PREF_NIGHT_CAM, 40)));
            box.addView(sliderRow(nightThEdit, 0, sensorSrc ? 100 : 255));
            TextView nbl = new TextView(this);
            nbl.setText("Screen brightness at night (%)");
            box.addView(nbl);
            nightBrEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            nightBrEdit.setText(String.valueOf(prefs.getInt(PREF_NIGHT_BRIGHT, 5)));
            box.addView(sliderRow(nightBrEdit, 1, 100));
            TextView ntl = new TextView(this);
            ntl.setText("Text brightness at night (%)");
            box.addView(ntl);
            nightTxEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            nightTxEdit.setText(String.valueOf(prefs.getInt(PREF_NIGHT_TEXT, 50)));
            box.addView(sliderRow(nightTxEdit, 10, 100));
            nightSensorSrc[0] = sensorSrc;

            TextView vlh = new TextView(this);
            vlh.setText("Volume limit (%): the tablet won't set the volume above this");
            vlh.setPadding(0, dp(8), 0, 0);
            box.addView(vlh);
            volLimitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            volLimitEdit.setText(String.valueOf(prefs.getInt(PREF_VOL_LIMIT, 100)));
            box.addView(sliderRow(volLimitEdit, 1, 100));

            box.addView(sectionHeader("WAKE-UP RADIO"));
            wakeCb.setText("Wake up with the radio");
            wakeCb.setChecked(prefs.getBoolean(PREF_WAKE_ON, false));
            box.addView(wakeCb);
            final android.widget.Button wakeTime = new android.widget.Button(this);
            wakeTime.setText("Time: " + hhmm(wakeMin[0]));
            wakeTime.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new android.app.TimePickerDialog(MainActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                        public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                            wakeMin[0] = h * 60 + m;
                            wakeTime.setText("Time: " + hhmm(wakeMin[0]));
                        }
                    }, wakeMin[0] / 60, wakeMin[0] % 60, android.text.format.DateFormat.is24HourFormat(MainActivity.this)).show();
                }
            });
            box.addView(wakeTime);
            wakeWdCb.setText("Weekdays only (Monday to Friday)");
            wakeWdCb.setChecked(prefs.getBoolean(PREF_WAKE_WEEKDAYS, false));
            box.addView(wakeWdCb);
            final android.widget.Button wakeSt = new android.widget.Button(this);
            wakeSt.setText("Station: " + wakeStation[1]);
            wakeSt.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    net.execute(new Runnable() { public void run() {
                        final java.util.List<String[]> presets = loadPresets();
                        ui.post(new Runnable() { public void run() {
                            final String[] items = new String[presets.size() + 1];
                            items[0] = "Last played station";
                            for (int i = 0; i < presets.size(); i++) items[i + 1] = cleanStation(presets.get(i)[1]);
                            new AlertDialog.Builder(MainActivity.this).setTitle("Wake-up station")
                                    .setItems(items, new DialogInterface.OnClickListener() {
                                        public void onClick(DialogInterface d, int w) {
                                            wakeStation[0] = w == 0 ? "0" : presets.get(w - 1)[0];
                                            wakeStation[1] = items[w];
                                            wakeSt.setText("Station: " + items[w]);
                                        }
                                    }).show();
                        } });
                    } });
                }
            });
            box.addView(wakeSt);
            TextView wvl = new TextView(this);
            wvl.setText("Wake-up volume");
            box.addView(wvl);
            wakeVolEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            wakeVolEdit.setText(String.valueOf(prefs.getInt(PREF_WAKE_VOL, 25)));
            box.addView(sliderRow(wakeVolEdit, 1, 100));
            TextView wrl = new TextView(this);
            wrl.setText("Raise the volume slowly over (minutes, 0 = start at full wake-up volume)");
            box.addView(wrl);
            wakeRampEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            wakeRampEdit.setText(String.valueOf(prefs.getInt(PREF_WAKE_RAMP, 3)));
            box.addView(sliderRow(wakeRampEdit, 0, 15));

            box.addView(sectionHeader("AUTO-OFF AT NIGHT"));
            offCb.setText("Switch the speaker off if it is still on at");
            offCb.setChecked(prefs.getBoolean(PREF_OFF_ON, false));
            box.addView(offCb);
            final android.widget.Button offTime = new android.widget.Button(this);
            offTime.setText("Time: " + hhmm(offMin[0]));
            offTime.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new android.app.TimePickerDialog(MainActivity.this, new android.app.TimePickerDialog.OnTimeSetListener() {
                        public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                            offMin[0] = h * 60 + m;
                            offTime.setText("Time: " + hhmm(offMin[0]));
                        }
                    }, offMin[0] / 60, offMin[0] % 60, android.text.format.DateFormat.is24HourFormat(MainActivity.this)).show();
                }
            });
            box.addView(offTime);

            box.addView(sectionHeader("DIAGNOSTICS"));
            android.widget.Button diag = new android.widget.Button(this);
            diag.setText("Show diagnostics log");
            diag.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showDiagnostics(); } });
            box.addView(diag);

            box.addView(sectionHeader("BACKUP"));
            TextView bkl = new TextView(this);
            bkl.setText("All settings as one file: " + backupFile().getAbsolutePath());
            box.addView(bkl);
            android.widget.Button exp = new android.widget.Button(this);
            exp.setText("Export settings");
            exp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { exportSettings(); } });
            box.addView(exp);
            android.widget.Button imp = new android.widget.Button(this);
            imp.setText("Import settings (replaces the current ones)");
            imp.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { importSettings(); } });
            box.addView(imp);

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

        final Runnable saveAction = new Runnable() {
                    public void run() {
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
                        boolean dimChanged = false;
                        e.putString(PREF_IP, newIp);
                        if (admin) {
                            e.putBoolean(PREF_SCREEN_ON, screenOn.isChecked());
                            e.putBoolean(PREF_BOOT, boot.isChecked());
                            e.putString(PREF_PIN, pinEdit.getText().toString().trim());
                            e.putString(PREF_UPDATE_URL, updateUrlEdit.getText().toString().trim());
                            e.putBoolean(PREF_COMPACT_VOLUME, compactVol.isChecked());
                            e.putBoolean(PREF_ART_BG, artCb.isChecked());
                            e.putInt(PREF_ART_LEVEL, intOf(artLevelEdit, 5, 80, prefs.getInt(PREF_ART_LEVEL, 35)));
                            artUrl = "\u0000"; // reload/refresh the background with the new setting on the next poll
                            e.putBoolean(PREF_HIDE_LOCATION, hideLoc.isChecked());
                            e.putBoolean(PREF_FOLLOWER, followCb.isChecked());
                            e.putBoolean(PREF_GESTURES, gestCb.isChecked());
                            if (Build.VERSION.SDK_INT < 19) e.putBoolean(PREF_HIDE_NAV, hideNavCb.isChecked());
                            e.putBoolean(PREF_AUTODIM, dimCb.isChecked());
                            e.putInt(PREF_VOL_LIMIT, intOf(volLimitEdit, 1, 100, prefs.getInt(PREF_VOL_LIMIT, 100)));
                            e.putBoolean(PREF_WAKE_ON, wakeCb.isChecked());
                            e.putBoolean(PREF_OFF_ON, offCb.isChecked());
                            e.putInt(PREF_OFF_MIN, offMin[0]);
                            if (pendingTabs != null && !pendingTabs.equals(prefs.getString(PREF_TABS, DEFAULT_TABS))) {
                                e.putString(PREF_TABS, pendingTabs);
                                fontChanged = true; // rebuild the screen with the new tabs
                            }
                            e.putInt(PREF_WAKE_MIN, wakeMin[0]);
                            e.putBoolean(PREF_WAKE_WEEKDAYS, wakeWdCb.isChecked());
                            e.putInt(PREF_WAKE_PRESET, Integer.parseInt(wakeStation[0]));
                            e.putString(PREF_WAKE_PRESET_NAME, wakeStation[1]);
                            e.putInt(PREF_WAKE_VOL, intOf(wakeVolEdit, 1, 100, prefs.getInt(PREF_WAKE_VOL, 25)));
                            e.putInt(PREF_WAKE_RAMP, intOf(wakeRampEdit, 0, 15, prefs.getInt(PREF_WAKE_RAMP, 3)));
                            if (wakeCb.isChecked() && wakeMin[0] != prefs.getInt(PREF_WAKE_MIN, -1)) e.remove(PREF_WAKE_LAST); // new time: may fire today
                            e.putBoolean(PREF_DIM_CAMERA, camCb.isChecked());
                            e.putInt(nightSensorSrc[0] ? PREF_NIGHT_LUX : PREF_NIGHT_CAM, intOf(nightThEdit, 0, nightSensorSrc[0] ? 1000 : 255,
                                    nightSensorSrc[0] ? prefs.getInt(PREF_NIGHT_LUX, 5) : prefs.getInt(PREF_NIGHT_CAM, 40)));
                            e.putInt(PREF_NIGHT_BRIGHT, intOf(nightBrEdit, 1, 100, prefs.getInt(PREF_NIGHT_BRIGHT, 5)));
                            e.putInt(PREF_NIGHT_TEXT, intOf(nightTxEdit, 10, 100, prefs.getInt(PREF_NIGHT_TEXT, 50)));
                            dimChanged = true;
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
                        if (dimChanged) { startAutoDim(); if (night) applyNight(true); }
                        wakeTransport();
                        if (ipChanged) { everPolled = false; connected = false; failStreak = 0; speakerChecked = false; track = ""; artist = ""; input = ""; }
                        render();
                        poll();
                        hideSystemBars();
                        closeSettings();
                    }
                };
        openSettingsScreen(box, admin, saveAction);
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

    // ------------------------------------------------------------------
    // Full-screen settings: sidebar of sections, big controls, X = save & close
    // ------------------------------------------------------------------
    android.widget.FrameLayout settingsScreen;

    boolean settingsOpen() { return settingsScreen != null; }

    void closeSettings() {
        if (settingsScreen == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(settingsScreen.getWindowToken(), 0);
        ((ViewGroup) settingsScreen.getParent()).removeView(settingsScreen);
        settingsScreen = null;
        hideSystemBars();
    }

    /** Splits the settings controls into sections at the "section" headers and shows them full screen. */
    void openSettingsScreen(LinearLayout box, boolean admin, final Runnable save) {
        closeSettings();
        // sections: everything before the first header is "General" (or "Speaker" for the IP-only view)
        final java.util.List<String> names = new java.util.ArrayList<String>();
        final java.util.List<LinearLayout> pages = new java.util.ArrayList<LinearLayout>();
        java.util.List<View> children = new java.util.ArrayList<View>();
        for (int i = 0; i < box.getChildCount(); i++) children.add(box.getChildAt(i));
        box.removeAllViews();
        LinearLayout page = null;
        for (View v : children) {
            if (page == null || "section".equals(v.getTag())) {
                String title = "section".equals(v.getTag()) ? ((TextView) v).getText().toString() : (admin ? "General" : "Speaker");
                names.add(title.substring(0, 1) + title.substring(1).toLowerCase(Locale.getDefault()));
                page = new LinearLayout(this);
                page.setOrientation(LinearLayout.VERTICAL);
                pages.add(page);
                TextView h = new TextView(this);
                h.setText(names.get(names.size() - 1));
                h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                h.setTypeface(Fonts.bold);
                h.setTextColor(C_TEXT);
                h.setPadding(0, 0, 0, dp(12));
                page.addView(h);
                if ("section".equals(v.getTag())) continue;
            }
            page.addView(v);
        }
        for (LinearLayout pg : pages) styleSettings(pg);

        final android.widget.FrameLayout screen = new android.widget.FrameLayout(this);
        screen.setBackgroundColor(C_BG);
        screen.setClickable(true); // nothing underneath reacts
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // sidebar
        final LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(12), dp(12), dp(12), dp(12));
        final android.widget.ScrollView content = new android.widget.ScrollView(this);
        content.setFillViewport(true);
        final LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.setPadding(dp(28), dp(20), dp(96), dp(40)); // right padding leaves room for the X
        holder.setFocusableInTouchMode(true); // keeps the keyboard closed until a field is tapped
        content.addView(holder);
        final java.util.List<TextView> items = new java.util.ArrayList<TextView>();
        for (int i = 0; i < names.size(); i++) {
            final int idx = i;
            TextView it = sideItem(names.get(i));
            it.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                holder.removeAllViews();
                holder.addView(pages.get(idx));
                content.scrollTo(0, 0);
                for (int k = 0; k < items.size(); k++) {
                    items.get(k).setTextColor(k == idx ? C_BG : C_TEXT);
                    items.get(k).setBackgroundDrawable(roundedBg(k == idx ? C_ACCENT : 0x00000000));
                }
            } });
            items.add(it);
            side.addView(it);
        }
        View spacer = new View(this);
        side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView cancel = sideItem("Cancel");
        cancel.setTextColor(C_DIM);
        cancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeSettings(); } });
        side.addView(cancel);
        if (admin) {
            TextView leave = sideItem("Leave kiosk");
            leave.setTextColor(C_DIM);
            leave.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeSettings(); leaveKiosk(); } });
            side.addView(leave);
        }
        android.widget.ScrollView sideScroll = new android.widget.ScrollView(this);
        sideScroll.setFillViewport(true);
        sideScroll.addView(side);
        sideScroll.setBackgroundColor(0xFF141414);
        body.addView(sideScroll, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        screen.addView(body, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // X: always visible in the top-right corner; saves and closes
        Btn x = new Btn(this);
        x.label = "\u2715";
        x.textSize = 30;
        x.bold = false;
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { save.run(); } });
        android.widget.FrameLayout.LayoutParams xl = new android.widget.FrameLayout.LayoutParams(dp(80), dp(80));
        xl.gravity = Gravity.TOP | Gravity.RIGHT;
        screen.addView(x, xl);

        settingsScreen = screen;
        settingsSave = save;
        ((ViewGroup) contentRoot).addView(screen, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        items.get(0).performClick();
        holder.requestFocus();
    }

    Runnable settingsSave;

    TextView sideItem(String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Fonts.bold);
        t.setTextColor(C_TEXT);
        t.setMinHeight(dp(64));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    android.graphics.drawable.GradientDrawable roundedBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        return g;
    }

    /** Big, app-styled controls: applied to every view of a settings page. */
    void styleSettings(View v) {
        if (v instanceof CheckBox) {
            CheckBox c = (CheckBox) v;
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            c.setTextColor(C_TEXT);
            c.setTypeface(Fonts.regular);
            c.setMinHeight(dp(64));
            c.setPadding(c.getPaddingLeft() + dp(8), dp(10), 0, dp(10));
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            e.setTextColor(C_TEXT);
            e.setHintTextColor(C_DIM);
            e.setTypeface(Fonts.regular);
            e.setMinHeight(dp(64));
        } else if (v instanceof android.widget.Button) {
            android.widget.Button b = (android.widget.Button) v;
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            b.setTextColor(C_TEXT);
            b.setTypeface(Fonts.bold);
            b.setAllCaps(false);
            b.setMinHeight(dp(68));
            b.setPadding(dp(20), dp(12), dp(20), dp(12));
            b.setBackgroundDrawable(roundedBg(0xFF2A2A2A));
            ViewGroup.LayoutParams lp0 = b.getLayoutParams();
            if (lp0 == null || lp0 instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = lp0 == null
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        : (LinearLayout.LayoutParams) lp0;
                lp.topMargin = dp(8);
                lp.bottomMargin = dp(8);
                if (lp.leftMargin == 0 && lp0 != null && lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) lp.leftMargin = dp(10);
                b.setLayoutParams(lp);
            }
        } else if (v instanceof android.widget.SeekBar) {
            v.setMinimumHeight(dp(64));
            v.setPadding(dp(24), dp(20), dp(24), dp(20));
        } else if (v instanceof TextView) {
            TextView t = (TextView) v;
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            t.setTextColor(C_GRAY);
            t.setTypeface(Fonts.regular);
            t.setPadding(t.getPaddingLeft(), Math.max(t.getPaddingTop(), dp(14)), t.getPaddingRight(), t.getPaddingBottom());
        }
        if (v instanceof ViewGroup && !(v instanceof android.widget.AdapterView)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleSettings(g.getChildAt(i));
        }
    }

    /** Everything useful for a remote diagnosis on one screen (photo-friendly). */
    void showDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("App ").append(versionName()).append(" \u00b7 Android ").append(Build.VERSION.RELEASE)
          .append(" \u00b7 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Speaker ").append(ip()).append(connected ? " connected" : " NOT connected")
          .append(", input ").append(input).append(", power ").append(powerOn ? "on" : "standby")
          .append(", volume ").append(volume).append('/').append(maxVolume).append('\n');
        sb.append("Push events: ").append(lastEventAt == 0 ? "none received" : "last " + (System.currentTimeMillis() - lastEventAt) / 1000 + " s ago").append('\n');
        sb.append(healthText()).append('\n').append(lightText()).append("\n\n");
        sb.append(Diag.text());
        final TextView t = new TextView(this);
        t.setText(sb.toString());
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle("Diagnostics")
                .setView(scroll(t))
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear log", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { Diag.clear(); Diag.log("APP", "log cleared"); }
                })
                .show();
    }

    TextView sectionHeader(String title) {
        TextView t = new TextView(this);
        t.setTag("section"); // starts a new section in the settings sidebar
        t.setText(title);
        t.setTextColor(0xFF33B5E5);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    static int intOf(EditText f, int min, int max, int def) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(f.getText().toString().trim()))); }
        catch (NumberFormatException e) { return def; }
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
                        String before = pendingUpdate != null ? pendingUpdate.versionName : "";
                        pendingUpdate = fi != null && fi.newer ? fi : null;
                        if (fe != null) Diag.log("UPDATE", (manual ? "check" : "automatic check") + " failed: " + fe);
                        else if (pendingUpdate != null && !pendingUpdate.versionName.equals(before))
                            Diag.log("UPDATE", pendingUpdate.versionName + " available (" + pendingUpdate.source + ")");
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
                        if (fe != null) { toast("Download failed: " + fe); Diag.log("UPDATE", "download failed: " + fe); return; }
                        Diag.log("UPDATE", "downloaded " + info.versionName + ", installer opened");
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
        // the Radio tab while already on Radio: pick a station
        if ("net_radio".equals(SRC_ID[idx]) && "net_radio".equals(input) && connected && powerOn) { showStationPicker(); return; }
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

    /** Highest volume (speaker steps) the tablet may set. */
    int volumeLimit() {
        int maxv = maxVolume > 0 ? maxVolume : 100;
        return Math.max(1, Math.round(maxv * prefs.getInt(PREF_VOL_LIMIT, 100) / 100f));
    }

    void changeVolume(int delta) {
        wakeRampActive = false; // the user takes over from a running wake-up ramp
        int maxv = maxVolume > 0 ? maxVolume : 100;
        int lim = volumeLimit();
        int v = Math.max(0, Math.min(lim, volume + delta));
        if (volume > lim && delta < 0) v = Math.max(0, volume + delta); // above the limit (set elsewhere): allow going down
        volume = v;
        // follower: only a short hold so the bar doesn't jump while −/+ is held
        volumeHoldUntil = System.currentTimeMillis() + (follower() ? 600 : 2500);
        flash("Volume " + v + (v == maxv ? " \u00b7 max" : (v == lim && delta > 0 ? " \u00b7 limit" : "")) + (mute ? " \u00b7 muted" : ""),
                mute ? C_AMBER : C_TEXT, 1500);
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
        if (!connected && everPolledOnce) Diag.log("CONNECTION", "restored");
        everPolledOnce = true;
        connected = true;
        failStreak = 0;
        rememberSpeaker();
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
                    updateArt(j.optString("albumart_url", ""));
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

    // ------------------------------------------------------------------
    // Speaker discovery and push events
    // ------------------------------------------------------------------
    Events events;
    boolean everPolledOnce;
    volatile long lastEventAt;
    boolean speakerChecked;
    long lastFollowAt;

    /** Once per IP: store the speaker's device id, so it can be found again if its IP changes. */
    void rememberSpeaker() {
        if (speakerChecked) return;
        speakerChecked = true;
        final String ipNow = ip();
        new Thread(new Runnable() { public void run() {
            Discovery.Speaker sp = Discovery.info(ipNow);
            if (sp != null && sp.deviceId.length() > 0) prefs.edit().putString(PREF_DEVICE_ID, sp.deviceId).commit();
        } }).start();
    }

    /** Speaker unreachable: look for the same device id on the network and switch to its new IP. */
    void followSpeaker() {
        final String id = prefs.getString(PREF_DEVICE_ID, "");
        if (id.length() == 0 || System.currentTimeMillis() - lastFollowAt < 60000) return;
        lastFollowAt = System.currentTimeMillis();
        new Thread(new Runnable() { public void run() {
            for (final Discovery.Speaker sp : Discovery.search(MainActivity.this)) {
                if (id.equals(sp.deviceId) && !sp.ip.equals(ip())) {
                    ui.post(new Runnable() { public void run() {
                        prefs.edit().putString(PREF_IP, sp.ip).commit();
                        everPolled = false; failStreak = 0; speakerChecked = true;
                        toast("Speaker found at its new address " + sp.ip);
                        Diag.log("SPEAKER", "found at new address " + sp.ip);
                        render();
                        poll();
                    } });
                    return;
                }
            }
        } }).start();
    }

    /** Settings button: search the Wi-Fi and let the user pick a speaker. */
    void findSpeakers(final android.widget.Button btn, final EditText ipEdit) {
        final CharSequence label = btn.getText();
        btn.setText("Searching\u2026 (up to 10 s)");
        btn.setEnabled(false);
        new Thread(new Runnable() { public void run() {
            final java.util.List<Discovery.Speaker> list = Discovery.search(MainActivity.this);
            ui.post(new Runnable() { public void run() {
                btn.setText(label);
                btn.setEnabled(true);
                if (list.isEmpty()) {
                    info("No speaker found", "No MusicCast speaker answered on this Wi-Fi. Check that the speaker is on "
                            + "and on the same network as the tablet, or type its IP address.");
                    return;
                }
                final String[] items = new String[list.size()];
                for (int i = 0; i < items.length; i++) items[i] = list.get(i).toString();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Speakers on this Wi-Fi")
                        .setItems(items, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int which) {
                                ipEdit.setText(list.get(which).ip);
                                if (list.get(which).deviceId.length() > 0)
                                    prefs.edit().putString(PREF_DEVICE_ID, list.get(which).deviceId).commit();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } });
        } }).start();
    }

    // ------------------------------------------------------------------
    // Auto-dim from room light
    // ------------------------------------------------------------------
    View contentRoot;
    AutoDim autoDim;
    String lightSource;
    float lightValue = -1;
    boolean night;

    void startAutoDim() {
        if (autoDim == null) return;
        autoDim.stop();
        if (prefs.getBoolean(PREF_AUTODIM, true)) autoDim.start(prefs.getBoolean(PREF_DIM_CAMERA, true));
        else applyNight(false);
    }

    float nightThreshold(String source) {
        return AutoDim.SENSOR.equals(source) ? prefs.getInt(PREF_NIGHT_LUX, 5) : prefs.getInt(PREF_NIGHT_CAM, 40);
    }

    void onLight(String source, float value) {
        lightSource = source;
        lightValue = value;
        if (!prefs.getBoolean(PREF_AUTODIM, true)) return;
        float th = nightThreshold(source);
        // hysteresis, so it doesn't flicker around the threshold
        if (!night && value < th) applyNight(true);
        else if (night && value > th * 1.3f + 3) applyNight(false);
    }

    // ------------------------------------------------------------------
    // Album art background
    // ------------------------------------------------------------------
    android.widget.ImageView artView;
    String artUrl = "";
    boolean artReady;

    void updateArt(final String path) {
        if (path.equals(artUrl)) return;
        artUrl = path;
        artReady = false;
        artView.setImageDrawable(null);
        if (path.length() == 0 || !prefs.getBoolean(PREF_ART_BG, true)) { render(); return; }
        final String url = path.startsWith("http") ? path : "http://" + ip() + (path.startsWith("/") ? "" : "/") + path;
        new Thread(new Runnable() { public void run() {
            final android.graphics.Bitmap b = blurredArt(url);
            ui.post(new Runnable() { public void run() {
                if (!path.equals(artUrl) || b == null) return;
                artView.setImageBitmap(b);
                artReady = true;
                render();
            } });
        } }).start();
    }

    /** Downloads the cover small and blurs it cheaply: shrink to a few pixels, scale back up smoothly. */
    static android.graphics.Bitmap blurredArt(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] data = out.toByteArray();
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / 128);
            o.inJustDecodeBounds = false;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) return null;
            android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(b, 16, Math.max(1, 16 * b.getHeight() / b.getWidth()), true);
            android.graphics.Bitmap mid = android.graphics.Bitmap.createScaledBitmap(tiny, 64, Math.max(1, 64 * b.getHeight() / b.getWidth()), true);
            return android.graphics.Bitmap.createScaledBitmap(mid, 256, Math.max(1, 256 * b.getHeight() / b.getWidth()), true);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Wake-up radio
    // ------------------------------------------------------------------
    boolean wakeRampActive;


    /** Called with every status check: fires once at the set minute. */
    void checkWake() {
        if (!prefs.getBoolean(PREF_WAKE_ON, false) || TextUtils.isEmpty(ip())) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int minute = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int target = prefs.getInt(PREF_WAKE_MIN, 7 * 60);
        // fire within 5 minutes after the set time (covers a missed check), once per day
        if (minute < target || minute > target + 5) return;
        int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
        if (prefs.getBoolean(PREF_WAKE_WEEKDAYS, false) && (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY)) return;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(c.getTime());
        if (today.equals(prefs.getString(PREF_WAKE_LAST, ""))) return;
        prefs.edit().putString(PREF_WAKE_LAST, today).commit();
        startWake();
    }

    /** Auto-off: once a night, if the speaker is still on at the set time, put it in standby. */
    void checkAutoOff() {
        if (!prefs.getBoolean(PREF_OFF_ON, false) || !connected || !powerOn) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int minute = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int target = prefs.getInt(PREF_OFF_MIN, 60);
        if (minute < target || minute > target + 5) return;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(c.getTime());
        if (today.equals(prefs.getString(PREF_OFF_LAST, ""))) return;
        prefs.edit().putString(PREF_OFF_LAST, today).commit();
        powerOn = false; powerTarget = false; powerChangedAt = System.currentTimeMillis();
        render();
        send("main/setPower?power=standby", true);
        toast("Auto-off: speaker switched to standby");
        Diag.log("AUTO-OFF", "speaker switched to standby");
    }

    void startWake() {
        final int preset = prefs.getInt(PREF_WAKE_PRESET, 0);
        final int lim = volumeLimit();
        final int targetVol = Math.max(1, Math.min(lim, prefs.getInt(PREF_WAKE_VOL, 25)));
        final int rampMin = prefs.getInt(PREF_WAKE_RAMP, 3);
        final int startVol = rampMin > 0 ? Math.min(3, targetVol) : targetVol;
        powerOn = true; powerTarget = true; powerChangedAt = System.currentTimeMillis();
        render();
        net.execute(new Runnable() { public void run() {
            request("main/setPower?power=on");
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            request("main/setMute?enable=false");
            request("main/setVolume?volume=" + startVol);
            request("main/setInput?input=net_radio");
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            if (preset > 0) request("netusb/recallPreset?zone=main&num=" + preset);
            else request("netusb/setPlayback?playback=play");
            ui.post(new Runnable() { public void run() {
                toast("Wake-up radio");
                Diag.log("WAKE-UP", "radio started" + (preset > 0 ? " (favourite " + preset + ")" : ""));
                poll();
                if (targetVol > startVol) {
                    wakeRampActive = true;
                    rampVolume(startVol, targetVol, rampMin * 60000L / (targetVol - startVol));
                }
            } });
        } });
    }

    /** Raises the volume one step at a time; stops when the user changes the volume. */
    void rampVolume(final int v, final int target, final long stepMs) {
        if (!wakeRampActive || v > target) { wakeRampActive = false; return; }
        net.execute(new Runnable() { public void run() { request("main/setVolume?volume=" + v); } });
        volume = v;
        volumeHoldUntil = System.currentTimeMillis() + 1000;
        render();
        ui.postDelayed(new Runnable() { public void run() { rampVolume(v + 1, target, stepMs); } }, stepMs);
    }

    // ------------------------------------------------------------------
    // Settings backup
    // ------------------------------------------------------------------
    java.io.File backupFile() {
        java.io.File dir = android.os.Environment.getExternalStorageDirectory();
        if (dir == null || !dir.canWrite()) dir = getExternalFilesDir(null);
        return new java.io.File(dir, "yamaremo-settings.json");
    }

    void exportSettings() {
        java.io.File f = backupFile();
        try {
            JSONObject all = new JSONObject();
            for (java.util.Map.Entry<String, ?> en : prefs.getAll().entrySet()) {
                Object v = en.getValue();
                String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Float ? "f"
                        : v instanceof Long ? "l" : "s";
                all.put(en.getKey(), new JSONObject().put("t", t).put("v", v));
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(new JSONObject().put("app", "yamaremo-kiosk").put("version", versionName()).put("settings", all)
                    .toString(2).getBytes("UTF-8"));
            out.close();
            info("Settings exported", "Saved " + all.length() + " settings to\n" + f.getAbsolutePath()
                    + "\n\nCopy this file to keep it, or to set up another tablet (put it in the same place there and tap Import).");
        } catch (Exception e) {
            info("Export failed", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void importSettings() {
        java.io.File f = backupFile();
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            JSONObject all = new JSONObject(new String(out.toByteArray(), "UTF-8")).getJSONObject("settings");
            SharedPreferences.Editor e = prefs.edit();
            java.util.Iterator<String> keys = all.keys();
            int count = 0;
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject o = all.getJSONObject(k);
                String t = o.getString("t");
                if ("b".equals(t)) e.putBoolean(k, o.getBoolean("v"));
                else if ("i".equals(t)) e.putInt(k, o.getInt("v"));
                else if ("f".equals(t)) e.putFloat(k, (float) o.getDouble("v"));
                else if ("l".equals(t)) e.putLong(k, o.getLong("v"));
                else e.putString(k, o.getString("v"));
                count++;
            }
            e.commit();
            toast("Imported " + count + " settings");
            recreate();
        } catch (java.io.FileNotFoundException e) {
            info("Nothing to import", "No backup found at\n" + f.getAbsolutePath());
        } catch (Exception e) {
            info("Import failed", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void applyNight(boolean on) {
        if (on != night) Diag.log("NIGHT", (on ? "on" : "off") + " (" + lightSource + " " + Math.round(lightValue) + ")");
        night = on;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? Math.max(0.01f, prefs.getInt(PREF_NIGHT_BRIGHT, 5) / 100f)
                : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        if (contentRoot != null)
            contentRoot.animate().alpha(on ? Math.max(0.1f, prefs.getInt(PREF_NIGHT_TEXT, 50) / 100f) : 1f).setDuration(1500);
    }

    String lightText() {
        String src = autoDim == null ? null : autoDim.source(prefs.getBoolean(PREF_DIM_CAMERA, true));
        if (src == null) return "Room light: no light sensor" + (prefs.getBoolean(PREF_DIM_CAMERA, true) ? " and no usable camera" : " (camera not allowed)");
        String unit = AutoDim.SENSOR.equals(src) ? " lux" : " (camera brightness 0\u2013255)";
        return "Room light from the " + src + ": now " + (lightValue < 0 || !src.equals(lightSource) ? "\u2013" : String.valueOf(Math.round(lightValue)))
                + unit + (night ? " \u2013 night mode on" : "");
    }

    int failLimit() { return Math.max(1, prefs.getInt(PREF_FAIL_LIMIT, 3)); }

    void noteFailure() {
        failStreak++;
        worstStreak = Math.max(worstStreak, failStreak);
        lastFailAt = System.currentTimeMillis();
        if (failStreak >= failLimit()) {
            if (connected) Diag.log("CONNECTION", "lost after " + failStreak + " failed checks (" + lastNetError + ")");
            connected = false;
            followSpeaker();
        }
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
            // subscribe to push events: the speaker then sends changes to Events.PORT
            c.setRequestProperty("X-AppName", "MusicCast/1.0(yamaremo)");
            c.setRequestProperty("X-AppPort", String.valueOf(Events.PORT));
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
