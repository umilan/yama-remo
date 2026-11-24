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
    static final String PREF_UPDATE_URL = "update_url";
    static final String PREF_COMPACT_VOLUME = "compact_volume";
    static final String PREF_HIDE_SECS = "hide_controls_secs";
    static final String PREF_HIDE_ON = "hide_controls";
    static final String PREF_FAIL_LIMIT = "fail_limit";
    static final String PREF_HIDE_LOCATION = "hide_station_location";
    static final long UPDATE_EVERY_MS = 3600L * 1000; // hourly

    static final int C_BG = 0xFF000000;
    static final int C_TEXT = 0xFFE8E8EA;
    static final int C_GRAY = 0xFF8C8C8C;   // inactive tabs, artist
    static final int C_DIM = 0xFF4E4E4E;    // off-state tabs, shuffle/repeat off, empty volume dots
    static final int C_RED = 0xFFC83C3C;
    static final int C_AMBER = 0xFFE0A030;

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
    Btn offBtn, prevBtn, shuffleBtn, settingsBtn, playBtn, muteBtn, repeatBtn, nextBtn, volDn, volUp, updateBtn;
    Btn[] srcBtns = new Btn[SRC_ID.length];
    TextView trackText, artistText, stationText, clockText, dateText, settingsText, ipText;
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
        // On 4.2 the navigation bar can only be dimmed (low profile); hiding
        // it there makes the first tap on any button just bring it back.
        d.setSystemUiVisibility(flags);
    }

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
            public void onClick(View v) {
                if ("net_radio".equals(input)) stepStation(-1);
                else send("netusb/setPlayback?playback=previous", true);
            }
        });
        shuffleBtn = iconBtn(Btn.ICON_SHUFFLE, "Shuffle", null);
        playBtn = iconBtn(Btn.ICON_PAUSE, "Pause", null);
        repeatBtn = iconBtn(Btn.ICON_REPEAT, "Repeat", null);
        nextBtn = iconBtn(Btn.ICON_NEXT, "Next", null);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if ("net_radio".equals(input)) stepStation(+1);
                else send("netusb/setPlayback?playback=next", true);
            }
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
            public void onClick(View v) { playing = !playing; render(); send("netusb/setPlayback?playback=play_pause", true); }
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

        offRow = row();
        offRow.setGravity(Gravity.CENTER_VERTICAL);
        settingsText = text("Settings", 20, C_DIM, true);
        settingsText.setPadding(dp(64), 0, dp(24), 0);
        settingsText.setGravity(Gravity.CENTER_VERTICAL);
        settingsText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(true); }
        });
        updateBtn = new Btn(this);
        updateBtn.fg = C_AMBER;
        updateBtn.textSize = 20;
        updateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pendingUpdate != null) downloadAndInstall(pendingUpdate); }
        });
        ipText = text("", 20, C_DIM, true);
        ipText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        ipText.setPadding(dp(24), 0, dp(64), 0);
        ipText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(false); }
        });
        offRow.addView(settingsText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        offRow.addView(updateBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        offRow.addView(ipText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bottom.addView(offRow);
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottomHeight()));

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

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    String sourceLabel(String id) {
        for (int i = 0; i < SRC_ID.length; i++) if (SRC_ID[i].equals(id)) return SRC_LABEL[i];
        if ("usb".equals(id)) return "USB";
        return id;
    }

    final Runnable rerender = new Runnable() { public void run() { render(); } };

    // short message shown in the top (song) row, then the song comes back
    String flashText = "";
    int flashColor;
    long flashUntil;
    final Runnable flashEnd = new Runnable() { public void run() { render(); } };

    void flash(String text, int color, long ms) {
        flashText = text;
        flashColor = color;
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

    void stepStation(final int step) {
        if (TextUtils.isEmpty(ip())) return;
        final String currentStation = !TextUtils.isEmpty(artist) ? artist : album;
        net.execute(new Runnable() {
            public void run() {
                if (radioPresets == null || System.currentTimeMillis() - presetsLoadedAt > 10 * 60 * 1000) {
                    java.util.List<String[]> list = new java.util.ArrayList<String[]>();
                    String r = requestRetry("netusb/getPresetInfo");
                    try {
                        org.json.JSONArray arr = new JSONObject(r).getJSONArray("preset_info");
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject p = arr.getJSONObject(i);
                            String name = p.optString("text", "");
                            if ("net_radio".equals(p.optString("input")) && name.length() > 0)
                                list.add(new String[] { String.valueOf(i + 1), name });
                        }
                        radioPresets = list;
                        presetsLoadedAt = System.currentTimeMillis();
                    } catch (Exception e) {
                        if (radioPresets == null) radioPresets = list;
                    }
                }
                final java.util.List<String[]> presets = radioPresets;
                if (presets.isEmpty()) {
                    ui.post(new Runnable() { public void run() {
                        flash("No saved stations \u2013 add favourites in MusicCast", C_AMBER, 4000);
                    } });
                    return;
                }
                // start from the station that's playing now, if it is one of the presets
                int cur = presetIndex;
                for (int i = 0; i < presets.size(); i++) {
                    String a = stripLocation(presets.get(i)[1]).toLowerCase(), b = stripLocation(currentStation).toLowerCase();
                    if (b.length() > 0 && (a.equals(b) || a.contains(b) || b.contains(a))) { cur = i; break; }
                }
                int n = presets.size();
                int next = cur < 0 ? (step > 0 ? 0 : n - 1) : ((cur + step) % n + n) % n;
                presetIndex = next;
                final String[] target = presets.get(next);
                ui.post(new Runnable() { public void run() {
                    pendingStation = target[1];
                    stationChangedAt = System.currentTimeMillis();
                    trackAtSwitch = track;
                    render();
                    ui.removeCallbacks(rerender);
                    ui.postDelayed(rerender, SETTLE_MS + 100);
                } });
                final boolean ok = request("netusb/recallPreset?zone=main&num=" + target[0]) != null;
                ui.post(new Runnable() { public void run() {
                    if (!ok) { stationChangedAt = 0; flash("Couldn't switch to " + cleanStation(target[1]), C_AMBER, 3000); }
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
        fit(trackText, 68, 30, w - dp(80));
        fit(artistText, 48, 24, w - dp(80));
        // clock: about three quarters of the width, limited by the height
        float px = Math.min(h * 0.62f, 400 * getResources().getDisplayMetrics().density);
        clockText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        fit(clockText, px / getResources().getDisplayMetrics().scaledDensity, 60, w * 0.78f);
        fit(dateText, 46, 20, w - dp(80));
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

    void showClock(boolean show) {
        if (show == clockShown) return;
        clockShown = show;
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
            srcBtns[i].fg = on ? (SRC_ID[i].equals(input) ? C_TEXT : C_GRAY) : C_DIM;
            srcBtns[i].invalidate();
        }
        offBtn.fg = on ? C_GRAY : C_TEXT;
        offBtn.invalidate();

        showClock(!on);
        // line-in inputs have no tracks: only Settings and Mute stay in the row
        setTransportAllowed(on);
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
                // artist bright, song dimmed
                trackText.setText(song);
                trackText.setTextColor(C_GRAY);
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
        updateBtn.label = pendingUpdate != null ? "Update " + pendingUpdate.versionName : "";
        updateBtn.setEnabled(pendingUpdate != null);
        updateBtn.invalidate();
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
        ipEdit.setText(ip());
        ipEdit.setHint("192.168.1.50");
        box.addView(ipEdit);

        final CheckBox screenOn = new CheckBox(this);
        final CheckBox boot = new CheckBox(this);
        final EditText pinEdit = new EditText(this);
        final EditText updateUrlEdit = new EditText(this);
        final CheckBox compactVol = new CheckBox(this);
        final CheckBox hideLoc = new CheckBox(this);
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
                    checkForUpdates(true);
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
            box.addView(hideSecsEdit);
            TextView fl = new TextView(this);
            fl.setText("Show \"not connected\" after this many failed checks in a row (one check every 3 s)");
            box.addView(fl);
            failLimitEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            failLimitEdit.setSingleLine(true);
            failLimitEdit.setText(String.valueOf(failLimit()));
            box.addView(failLimitEdit);
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
                        boolean ipChanged = !newIp.equals(ip());
                        e.putString(PREF_IP, newIp);
                        if (admin) {
                            e.putBoolean(PREF_SCREEN_ON, screenOn.isChecked());
                            e.putBoolean(PREF_BOOT, boot.isChecked());
                            e.putString(PREF_PIN, pinEdit.getText().toString().trim());
                            e.putString(PREF_UPDATE_URL, updateUrlEdit.getText().toString().trim());
                            e.putBoolean(PREF_COMPACT_VOLUME, compactVol.isChecked());
                            e.putBoolean(PREF_HIDE_LOCATION, hideLoc.isChecked());
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
                        applyScreenOn();
                        applyLayoutPrefs();
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

    void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    void checkForUpdates(final boolean manual) {
        if (updateBusy) return;
        updateBusy = true;
        lastUpdateCheck = System.currentTimeMillis();
        final String url = updateUrl();
        if (manual) toast("Checking for updates\u2026");
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
                        pendingUpdate = fi;
                        render();
                        if (!manual) return;
                        if (fe != null) toast("Update check failed: " + fe);
                        else if (fi == null) toast("Up to date (" + versionName() + ")");
                        else toast("Version " + fi.versionName + " is available \u2013 tap Update at the bottom of the clock screen");
                    }
                });
            }
        }).start();
    }

    void downloadAndInstall(final Updater.Info info) {
        if (updateBusy) return;
        updateBusy = true;
        updateBtn.label = "Downloading\u2026";
        updateBtn.invalidate();
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
                        updateBtn.label = "Update " + info.versionName;
                        updateBtn.invalidate();
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
        volumeHoldUntil = System.currentTimeMillis() + 2500;
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
                    if (System.currentTimeMillis() - powerChangedAt > SETTLE_MS || polledOn == powerTarget) powerOn = polledOn;
                    maxVolume = j.optInt("max_volume", maxVolume);
                    if (System.currentTimeMillis() > volumeHoldUntil && pendingVolume.get() < 0)
                        volume = j.optInt("volume", volume);
                    mute = j.optBoolean("mute", mute);
                    String polledInput = j.optString("input", input);
                    if (System.currentTimeMillis() - inputChangedAt > SETTLE_MS || polledInput.equals(input)) input = polledInput;
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
                + " failed checks in a row, last failure " + last + ".";
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
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static int color(int c) { return Color.argb(255, Color.red(c), Color.green(c), Color.blue(c)); }
}
