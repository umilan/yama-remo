package com.yamaremo.kiosk;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Self-update: reads version.json from the update URL, and if it names a
 * newer versionCode, downloads the APK next to it and opens the system
 * installer (Android 4.x can't install silently; the user taps Install).
 *
 * version.json: {"versionCode": 3, "versionName": "1.2", "apk": "yamaremo-kiosk.apk"}
 * "apk" may be relative to the version.json URL or absolute.
 */
class Updater {

    static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/umilan/yama-remo/main/android/version.json";

    static class Info {
        int versionCode;
        String versionName;
        String apkUrl;
    }

    /** Returns update info if a newer version exists, null if up to date. Throws on network errors. */
    static Info check(Context ctx, String jsonUrl) throws Exception {
        String body = new String(get(jsonUrl), "UTF-8");
        JSONObject j = new JSONObject(body);
        Info i = new Info();
        i.versionCode = j.getInt("versionCode");
        i.versionName = j.optString("versionName", String.valueOf(i.versionCode));
        i.apkUrl = new URL(new URL(jsonUrl), j.optString("apk", "yamaremo-kiosk.apk")).toString();
        int current = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        return i.versionCode > current ? i : null;
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

    static byte[] get(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                if (c instanceof HttpsURLConnection && Build.VERSION.SDK_INT < 20)
                    ((HttpsURLConnection) c).setSSLSocketFactory(tls12());
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                return out.toByteArray();
            }
            throw new IOException("Too many redirects");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // Android 4.1-4.4 support TLS 1.2 but don't enable it by default; GitHub requires it.
    private static SSLSocketFactory tls12Factory;

    private static synchronized SSLSocketFactory tls12() throws Exception {
        if (tls12Factory == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            tls12Factory = new Tls12Factory(ctx.getSocketFactory());
        }
        return tls12Factory;
    }

    private static class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls12Factory(SSLSocketFactory d) { this.d = d; }

        private Socket on(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                java.util.List<String> want = new java.util.ArrayList<String>();
                for (String p : ss.getSupportedProtocols())
                    if (p.equals("TLSv1.2") || p.equals("TLSv1.1") || p.equals("TLSv1")) want.add(p);
                ss.setEnabledProtocols(want.toArray(new String[0]));
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException { return on(d.createSocket(s, h, p, a)); }
        @Override public Socket createSocket(String h, int p) throws IOException { return on(d.createSocket(h, p)); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return on(d.createSocket(h, p, l, lp)); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { return on(d.createSocket(h, p)); }
        @Override public Socket createSocket(InetAddress a, int p, InetAddress l, int lp) throws IOException { return on(d.createSocket(a, p, l, lp)); }
    }
}
