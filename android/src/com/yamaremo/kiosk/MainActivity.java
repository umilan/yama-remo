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
    static final long UPDATE_EVERY_MS = 6L * 3600 * 1000;

    static final int C_BG = 0xFF121418;
    static final int C_PANEL = 0xFF22262E;
    static final int C_ACCENT = 0xFF2F7DF6;
    static final int C_TEXT = 0xFFE8EAED;
    static final int C_MUTED = 0xFF8A9099;
    static final int C_GREEN = 0xFF2EA05A;
    static final int C_RED = 0xFFC83C3C;
    static final int C_AMBER = 0xFFE0A030;

    static final String[] SRC_ID = { "airplay", "aux", "optical", "bluetooth", "usb", "net_radio" };
    static final String[] SRC_LABEL = { "AirPlay", "AUX", "Optical", "Bluetooth", "USB", "Net Radio" };

    static final int POLL_MS = 3000;

    // speaker state (UI thread only)
    boolean everPolled, connected, powerOn, mute, playing;
    int volume, maxVolume = 100;
    String input = "", track = "", artist = "";
    long volumeHoldUntil;

    // views
    Btn powerBtn, ipBtn, playBtn, muteBtn, volDn, volUp, updateBtn;
    Updater.Info pendingUpdate;
    long lastUpdateCheck;
    boolean updateBusy;
    Btn[] srcBtns = new Btn[6];
    TextView statusText, trackText, subText, volText, clockText, dateText;
    LinearLayout volRow, transportRow;
    boolean clockShown = false;
    VolumeBar volBar;

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
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        // --- top bar
        LinearLayout top = row();
        powerBtn = new Btn(this);
        powerBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePower(); }
        });
        top.addView(powerBtn, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setGravity(Gravity.CENTER_VERTICAL);
        titleBox.setPadding(dp(16), 0, dp(8), 0);
        TextView title = text("MusicCast Remote", 22, C_TEXT, true);
        statusText = text("", 15, C_MUTED, false);
        titleBox.addView(title);
        titleBox.addView(statusText);
        // hidden way out of kiosk mode: long-press the title
        titleBox.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { showSettings(true); return true; }
        });
        top.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ipBtn = new Btn(this);
        ipBtn.textSize = 15;
        ipBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSettings(false); }
        });
        top.addView(ipBtn, new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.MATCH_PARENT));

        // shown only when a newer version is available
        updateBtn = new Btn(this);
        updateBtn.textSize = 17;
        updateBtn.bg = C_AMBER;
        updateBtn.fg = C_BG;
        updateBtn.setVisibility(View.GONE);
        updateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (pendingUpdate != null) downloadAndInstall(pendingUpdate); }
        });
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.MATCH_PARENT);
        up.leftMargin = dp(10);
        top.addView(updateBtn, up);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        // --- now playing / clock panel (takes all space the rows below leave)
        LinearLayout np = new LinearLayout(this);
        np.setOrientation(LinearLayout.VERTICAL);
        np.setGravity(Gravity.CENTER_VERTICAL);
        np.setPadding(dp(22), dp(8), dp(22), dp(8));
        np.setBackgroundDrawable(Btn.rounded(C_PANEL, dp(14)));
        trackText = text("", 30, C_TEXT, true);
        subText = text("", 19, C_MUTED, false);
        clockText = text("", 96, C_TEXT, true);
        clockText.setGravity(Gravity.CENTER_HORIZONTAL);
        clockText.setIncludeFontPadding(false);
        dateText = text("", 26, C_MUTED, false);
        dateText.setGravity(Gravity.CENTER_HORIZONTAL);
        np.addView(trackText);
        np.addView(subText);
        np.addView(clockText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        np.addView(dateText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        np.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { poll(); }
        });
        // size the clock to whatever height the panel gets
        np.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (b - t == ob - ot && r - l == or - ol) return;
                final float h = b - t - dp(16);
                final float w = r - l - dp(44);
                ui.post(new Runnable() {
                    public void run() {
                        float clockPx = Math.min(h * 0.58f, w / 3.4f);
                        clockText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(40), clockPx));
                        dateText.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(18), Math.min(h * 0.15f, dp(34))));
                    }
                });
            }
        });
        root.addView(np, spaced(0, 1));

        // --- volume (with mute)
        volRow = row();
        volDn = new Btn(this);
        volDn.label = "−";
        volDn.textSize = 40;
        volDn.repeat = new Runnable() { public void run() { changeVolume(-1); } };
        volRow.addView(volDn, new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout volMid = new LinearLayout(this);
        volMid.setOrientation(LinearLayout.VERTICAL);
        volMid.setGravity(Gravity.CENTER);
        volMid.setPadding(dp(22), dp(6), dp(22), dp(10));
        volMid.setBackgroundDrawable(Btn.rounded(C_PANEL, dp(14)));
        volText = text("", 20, C_TEXT, true);
        volText.setGravity(Gravity.CENTER);
        volBar = new VolumeBar(this);
        volBar.listener = new VolumeBar.Listener() {
            public void onPick(int fraction1000) {
                int v = Math.round(fraction1000 * maxVolume / 1000f);
                changeVolume(v - volume);
            }
        };
        volMid.addView(volText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        volMid.addView(volBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout.LayoutParams vmp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        vmp.leftMargin = dp(10); vmp.rightMargin = dp(10);
        volRow.addView(volMid, vmp);

        volUp = new Btn(this);
        volUp.label = "+";
        volUp.textSize = 40;
        volUp.repeat = new Runnable() { public void run() { changeVolume(+1); } };
        volRow.addView(volUp, new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.MATCH_PARENT));

        muteBtn = new Btn(this);
        muteBtn.textSize = 20;
        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mute = !mute; render();
                send("main/setMute?enable=" + (mute ? "true" : "false"), true);
            }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.MATCH_PARENT);
        mp.leftMargin = dp(10);
        volRow.addView(muteBtn, mp);
        root.addView(volRow, spaced(dp(96), 0));

        // --- transport (hidden for line-in sources and in standby)
        transportRow = row();
        Btn prev = new Btn(this);
        prev.icon = Btn.ICON_PREV;
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { send("netusb/setPlayback?playback=previous", true); }
        });
        playBtn = new Btn(this);
        playBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playing = !playing; render();
                send("netusb/setPlayback?playback=play_pause", true);
            }
        });
        Btn next = new Btn(this);
        next.icon = Btn.ICON_NEXT;
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { send("netusb/setPlayback?playback=next", true); }
        });
        transportRow.addView(prev, cell(false));
        transportRow.addView(playBtn, cell(true));
        transportRow.addView(next, cell(true));
        root.addView(transportRow, spaced(dp(96), 0));

        // --- sources (bottom)
        LinearLayout srcRow = row();
        for (int i = 0; i < 6; i++) {
            final int idx = i;
            Btn bt = new Btn(this);
            bt.label = SRC_LABEL[i];
            bt.textSize = 18;
            bt.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectInput(idx); }
            });
            srcBtns[i] = bt;
            srcRow.addView(bt, cell(i > 0));
        }
        root.addView(srcRow, spaced(dp(76), 0));

        return root;
    }
    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    LinearLayout.LayoutParams spaced(int height, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, weight);
        p.topMargin = dp(10);
        return p;
    }

    LinearLayout.LayoutParams cell(boolean gap) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (gap) p.leftMargin = dp(10);
        return p;
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
        return id;
    }

    /** Inputs with no tracks, so no previous/play/next (same list as the web remote). */
    static boolean isLineIn(String id) {
        return "aux".equals(id) || "optical".equals(id) || "usb".equals(id) || "line_in".equals(id);
    }

    void updateClock() {
        Date now = new Date();
        clockText.setText(android.text.format.DateFormat.getTimeFormat(this).format(now));
        dateText.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now));
    }

    final Runnable clockTick = new Runnable() {
        public void run() {
            updateClock();
            // next tick just after the minute changes
            long ms = System.currentTimeMillis();
            ui.postDelayed(this, 60000 - (ms % 60000) + 50);
        }
    };

    void showClock(boolean show) {
        if (show == clockShown) return;
        clockShown = show;
        trackText.setVisibility(show ? View.GONE : View.VISIBLE);
        subText.setVisibility(show ? View.GONE : View.VISIBLE);
        clockText.setVisibility(show ? View.VISIBLE : View.GONE);
        dateText.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(clockTick);
        if (show) clockTick.run();
    }

    void render() {
        boolean on = connected && powerOn;
        int dim = on ? C_TEXT : C_MUTED;

        powerBtn.label = powerOn ? "ON" : "OFF";
        powerBtn.bg = powerOn ? C_GREEN : C_PANEL;
        powerBtn.textSize = 22;
        powerBtn.invalidate();

        if (TextUtils.isEmpty(ip())) { statusText.setText("Set the speaker IP to start"); statusText.setTextColor(C_AMBER); }
        else if (!everPolled) { statusText.setText("Connecting…"); statusText.setTextColor(C_MUTED); }
        else if (!connected) { statusText.setText("Not connected – check Wi-Fi and the speaker IP"); statusText.setTextColor(C_RED); }
        else if (!powerOn) { statusText.setText("Connected – standby"); statusText.setTextColor(C_GREEN); }
        else { statusText.setText("Connected"); statusText.setTextColor(C_GREEN); }

        ipBtn.label = TextUtils.isEmpty(ip()) ? "Set IP" : "IP " + ip();
        ipBtn.invalidate();

        // speaker off or unreachable: big clock, only sources + power stay
        showClock(!on);
        volRow.setVisibility(on ? View.VISIBLE : View.GONE);
        transportRow.setVisibility(on && !isLineIn(input) ? View.VISIBLE : View.GONE);

        if (on) {
            if (!TextUtils.isEmpty(track)) {
                trackText.setText(track);
                trackText.setTextColor(C_TEXT);
                subText.setText(TextUtils.isEmpty(artist) ? sourceLabel(input) : artist + "   •   " + sourceLabel(input));
            } else {
                trackText.setText(isLineIn(input) ? sourceLabel(input) : "Not playing");
                trackText.setTextColor(isLineIn(input) ? C_TEXT : C_MUTED);
                subText.setText(isLineIn(input) ? "External input" : "Source: " + sourceLabel(input));
            }
        }

        for (int i = 0; i < 6; i++) {
            boolean act = on && SRC_ID[i].equals(input);
            srcBtns[i].bg = act ? C_ACCENT : C_PANEL;
            srcBtns[i].fg = act ? C_TEXT : dim;
            srcBtns[i].invalidate();
        }

        int maxv = maxVolume > 0 ? maxVolume : 100;
        int v = Math.max(0, Math.min(maxv, volume));
        volText.setText(mute ? "Volume " + v + "  (muted)" : "Volume " + v);
        volText.setTextColor(mute ? C_AMBER : dim);
        volBar.fraction = v / (float) maxv;
        volBar.color = mute ? C_MUTED : C_ACCENT;
        volBar.invalidate();
        volDn.fg = dim; volDn.invalidate();
        volUp.fg = dim; volUp.invalidate();

        playBtn.icon = playing ? Btn.ICON_PAUSE : Btn.ICON_PLAY;
        playBtn.bg = C_ACCENT;
        playBtn.invalidate();

        muteBtn.label = mute ? "Unmute" : "Mute";
        muteBtn.bg = mute ? C_AMBER : C_PANEL;
        muteBtn.fg = mute ? C_BG : dim;
        muteBtn.invalidate();
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
            l3.setText("Update URL (version.json)");
            box.addView(l3);
            updateUrlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            updateUrlEdit.setSingleLine(true);
            updateUrlEdit.setText(updateUrl());
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
                        }
                        e.commit();
                        applyScreenOn();
                        if (ipChanged) { everPolled = false; connected = false; track = ""; artist = ""; input = ""; }
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
        bld.show();
    }

    View scroll(View v) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(v);
        return sv;
    }

    String updateUrl() {
        String u = prefs.getString(PREF_UPDATE_URL, "").trim();
        return u.length() > 0 ? u : Updater.DEFAULT_URL;
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
                        updateBtn.label = fi != null ? "Update " + fi.versionName : "";
                        updateBtn.setVisibility(fi != null ? View.VISIBLE : View.GONE);
                        updateBtn.invalidate();
                        if (!manual) return;
                        if (fe != null) toast("Update check failed: " + fe);
                        else if (fi == null) toast("Up to date (" + versionName() + ")");
                        else toast("Version " + fi.versionName + " is available \u2013 tap Update in the top bar");
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
        render();
        send(powerOn ? "main/setPower?power=on" : "main/setPower?power=standby", true);
    }

    void selectInput(int idx) {
        if (!powerOn) { powerOn = true; send("main/setPower?power=on", false); }
        input = SRC_ID[idx];
        render();
        send("main/setInput?input=" + SRC_ID[idx], true);
    }

    void changeVolume(int delta) {
        int maxv = maxVolume > 0 ? maxVolume : 100;
        int v = Math.max(0, Math.min(maxv, volume + delta));
        volume = v;
        volumeHoldUntil = System.currentTimeMillis() + 2500;
        render();
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
                        connected = ok;
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
                final String st = request("main/getStatus");
                String pi = null;
                try {
                    if (st != null && "on".equals(new JSONObject(st).optString("power"))) pi = request("netusb/getPlayInfo");
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
        connected = st != null;
        if (st != null) {
            try {
                JSONObject j = new JSONObject(st);
                if (j.optInt("response_code", 0) == 0) {
                    powerOn = "on".equals(j.optString("power"));
                    maxVolume = j.optInt("max_volume", maxVolume);
                    if (System.currentTimeMillis() > volumeHoldUntil && pendingVolume.get() < 0)
                        volume = j.optInt("volume", volume);
                    mute = j.optBoolean("mute", mute);
                    input = j.optString("input", input);
                }
            } catch (Exception ignored) { }
        }
        if (!powerOn) { playing = false; track = ""; artist = ""; }
        else if (pi != null) {
            try {
                JSONObject j = new JSONObject(pi);
                String pb = j.optString("playback");
                playing = "play".equals(pb);
                if ("play".equals(pb) || "pause".equals(pb)) {
                    track = j.optString("track", "");
                    artist = j.optString("artist", "");
                } else { track = ""; artist = ""; }
            } catch (Exception ignored) { }
        }
        render();
    }

    /** Blocking GET on the worker thread. Returns the body, or null on failure. */
    String request(String path) {
        String ipNow = ip();
        if (TextUtils.isEmpty(ipNow)) return null;
        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + ipNow + "/YamahaExtendedControl/v1/" + path);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(3500);
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
