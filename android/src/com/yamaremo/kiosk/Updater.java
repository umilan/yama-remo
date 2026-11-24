package com.yamaremo.kiosk;

import android.content.Context;
import android.content.Intent;
import android.net.SSLCertificateSocketFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Self-update: reads version.json from the update URL, and if it names a
 * newer versionCode, downloads the APK next to it and opens the system
 * installer (Android 4.x can't install silently; the user taps Install).
 *
 * version.json: {"versionCode": 3, "versionName": "1.2", "apk": "yamaremo-kiosk.apk"}
 * "apk" may be relative to the version.json URL or absolute.
 */
class Updater {

    /** Old built-in default; if it is still saved in settings it means "automatic". */
    static final String OLD_DEFAULT_URL =
            "https://raw.githubusercontent.com/umilan/yama-remo/main/android/version.json";

    /**
     * Tried in order when no custom URL is set. jsDelivr still accepts the old TLS
     * setups of Android 4.x; plain HTTP is the last resort and is safe enough here
     * because Android only installs an update signed with the same key.
     */
    static final String[] AUTO_URLS = {
            // githack: GitHub mirror on Cloudflare, caches only a few minutes
            "https://raw.githack.com/umilan/yama-remo/main/android/version.json",
            // jsDelivr: works with Android 4.2 TLS, but may keep branch files up to 12 h
            "https://cdn.jsdelivr.net/gh/umilan/yama-remo@main/android/version.json",
            "https://raw.githubusercontent.com/umilan/yama-remo/main/android/version.json",
            "http://cdn.jsdelivr.net/gh/umilan/yama-remo@main/android/version.json",
    };

    static class Info {
        int versionCode;
        String versionName;
        String apkUrl;
        boolean newer;       // newer than the installed app
        String source = "";  // host that answered
        String purge = "";   // result of clearing jsDelivr's cache
        String report = "";  // what every source answered
    }

    /**
     * Returns update info if a newer version exists, null if up to date.
     * customUrl empty = try AUTO_URLS in order. Throws with every source's error if all fail.
     */
    /** Asks jsDelivr to drop its cached copy (it keeps branch files up to 12 h otherwise). */
    static final String PURGE_URL =
            "https://purge.jsdelivr.net/gh/umilan/yama-remo@main/android/version.json";

    /** Always returns what the server has; Info.newer says whether it's an update. */
    static Info check(Context ctx, String customUrl) throws Exception {
        boolean auto = customUrl == null || customUrl.length() == 0;
        String[] urls = auto ? AUTO_URLS : new String[] { customUrl };
        String purge = "";
        StringBuilder report = new StringBuilder();
        if (auto) {
            try { get(PURGE_URL); purge = "cache cleared"; Thread.sleep(1500); }
            catch (Exception e) { purge = "cache clear failed (" + short_(e) + ")"; } // the check still works, maybe with a stale copy
        }
        // ask every source and take the highest version: one stale cache can't hide an update
        Info best = null;
        int current = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        for (String jsonUrl : urls) {
            URL u = new URL(jsonUrl);
            String name = (u.getProtocol().equals("http") ? "http " : "") + u.getHost();
            try {
                JSONObject j = new JSONObject(new String(get(jsonUrl), "UTF-8"));
                Info i = new Info();
                i.versionCode = j.getInt("versionCode");
                i.versionName = j.optString("versionName", String.valueOf(i.versionCode));
                i.apkUrl = new URL(u, j.optString("apk", "yamaremo-kiosk.apk")).toString();
                i.source = name;
                report.append("\n").append(name).append(": ").append(i.versionName);
                if (best == null || i.versionCode > best.versionCode) best = i;
            } catch (Exception e) {
                report.append("\n").append(name).append(": ").append(short_(e));
            }
        }
        if (best == null) throw new IOException(report.toString().trim());
        best.newer = best.versionCode > current;
        best.purge = purge;
        best.report = (purge.length() > 0 ? "jsDelivr " + purge : "") + report;
        return best;
    }

    static String short_(Exception e) {
        String m = e.getMessage();
        if (m != null && m.length() > 140) m = m.substring(0, 140) + "\u2026";
        return e.getClass().getSimpleName() + (m != null ? " " + m : "");
    }

    /** Downloads the APK to shared storage (the installer must be able to read it). */
    static File download(Context ctx, Info info) throws Exception {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = new File(Environment.getExternalStorageDirectory(), "Download");
        dir.mkdirs();
        File f = new File(dir, "yamaremo-update.apk");
        byte[] data = get(info.apkUrl);
        if (data.length < 1000 || data[0] != 'P' || data[1] != 'K') throw new IOException("Downloaded file is not an APK");
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
        f.setReadable(true, false);
        return f;
    }

    static void install(Context ctx, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    /**
     * Minimal HTTP/1.0 GET over our own sockets. Android 4.x's HttpsURLConnection
     * silently retries with SSLv3 after a failed handshake, which hides the real error
     * and never works with modern servers; here TLS 1.2/1.1/1.0 are forced, SNI is set
     * and the hostname is verified.
     */
    static byte[] get(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            URL u = new URL(url);
            boolean https = "https".equalsIgnoreCase(u.getProtocol());
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(15000);
            if (https) {
                SSLCertificateSocketFactory f = (SSLCertificateSocketFactory) SSLCertificateSocketFactory.getDefault(10000, null);
                SSLSocket ss = (SSLSocket) f.createSocket(s, host, port, true);
                java.util.List<String> want = new java.util.ArrayList<String>();
                for (String p : ss.getSupportedProtocols())
                    if (p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1")) want.add(p);
                ss.setEnabledProtocols(want.toArray(new String[0]));
                if (Build.VERSION.SDK_INT >= 17) f.setHostname(ss, host);
                ss.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.getSession())) {
                    ss.close();
                    throw new SSLException("certificate does not match " + host);
                }
                s = ss;
            }
            try {
                String path = u.getFile().length() > 0 ? u.getFile() : "/";
                String req = "GET " + path + " HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: yamaremo-kiosk\r\n"
                        + "Accept: */*\r\nConnection: close\r\n\r\n";
                OutputStream out = s.getOutputStream();
                out.write(req.getBytes("US-ASCII"));
                out.flush();
                InputStream in = s.getInputStream();
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
                byte[] data = all.toByteArray();

                int hdrEnd = -1;
                for (int i = 0; i + 3 < data.length; i++)
                    if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') { hdrEnd = i; break; }
                if (hdrEnd < 0) throw new IOException("bad HTTP response");
                String[] lines = new String(data, 0, hdrEnd, "ISO-8859-1").split("\r\n");
                String[] status = lines[0].split(" ");
                int code = status.length > 1 ? Integer.parseInt(status[1]) : 0;
                String location = null;
                for (String l : lines)
                    if (l.regionMatches(true, 0, "Location:", 0, 9)) location = l.substring(9).trim();
                if (code >= 300 && code < 400 && location != null) {
                    url = new URL(u, location).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                byte[] body = new byte[data.length - hdrEnd - 4];
                System.arraycopy(data, hdrEnd + 4, body, 0, body.length);
                return body;
            } finally {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
        throw new IOException("Too many redirects");
    }
}
