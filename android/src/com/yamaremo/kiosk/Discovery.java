package com.yamaremo.kiosk;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds MusicCast speakers on the Wi-Fi: SSDP (UPnP) search first, then, if nothing answers,
 * a quick scan of the local /24 network for the YXC API. Blocking: call from a worker thread.
 */
class Discovery {

    static class Speaker {
        String ip, model = "", name = "", deviceId = "";
        public String toString() {
            String n = name.length() > 0 ? name : model;
            return (n.length() > 0 ? n + "  " : "") + "(" + ip + ")";
        }
    }

    static List<Speaker> search(Context ctx) {
        Set<String> ips = ssdp(ctx);
        List<Speaker> found = identify(ips);
        if (found.isEmpty()) found = identify(scanSubnet(ctx));
        return found;
    }

    /** SSDP M-SEARCH; returns the IP addresses of devices that answered. */
    static Set<String> ssdp(Context ctx) {
        Set<String> ips = new LinkedHashSet<String>();
        WifiManager.MulticastLock lock = null;
        DatagramSocket sock = null;
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("yamaremo-ssdp");
            lock.setReferenceCounted(false);
            lock.acquire();
            sock = new DatagramSocket();
            sock.setSoTimeout(400);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] targets = { "urn:schemas-upnp-org:device:MediaRenderer:1", "ssdp:all" };
            for (String st : targets) {
                String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\nST: " + st + "\r\n\r\n";
                byte[] b = msg.getBytes("US-ASCII");
                sock.send(new DatagramPacket(b, b.length, group, 1900));
            }
            long end = System.currentTimeMillis() + 3000;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                ips.add(p.getAddress().getHostAddress());
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
            if (lock != null) try { lock.release(); } catch (Exception ignored) { }
        }
        return ips;
    }

    /** Probes x.y.z.1-254 of the tablet's own network for the YXC API (parallel, ~4 s). */
    static Set<String> scanSubnet(Context ctx) {
        final Set<String> hits = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress(); // little-endian
            if (ip == 0) return hits;
            final String prefix = (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + ".";
            ExecutorService pool = Executors.newFixedThreadPool(32);
            for (int i = 1; i < 255; i++) {
                final String host = prefix + i;
                pool.execute(new Runnable() { public void run() {
                    if (get(host, "system/getDeviceInfo", 600) != null) hits.add(host);
                } });
            }
            pool.shutdown();
            pool.awaitTermination(8, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        return hits;
    }

    /** Keeps the addresses that answer the YXC API, with model, name and device id. */
    static List<Speaker> identify(Set<String> ips) {
        List<Speaker> list = new ArrayList<Speaker>();
        for (String ip : ips) {
            Speaker s = info(ip);
            if (s != null) list.add(s);
        }
        return list;
    }

    static Speaker info(String ip) {
        String r = get(ip, "system/getDeviceInfo", 2500);
        if (r == null) return null;
        try {
            JSONObject j = new JSONObject(r);
            if (j.optInt("response_code", -1) != 0) return null;
            Speaker s = new Speaker();
            s.ip = ip;
            s.model = j.optString("model_name", "");
            s.deviceId = j.optString("device_id", "");
            String n = get(ip, "system/getNetworkStatus", 2500);
            if (n != null) s.name = new JSONObject(n).optString("network_name", "");
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    static String get(String ip, String path, int timeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + "/YamahaExtendedControl/v1/" + path).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            String s = new String(out.toByteArray(), "UTF-8");
            return s.contains("response_code") ? s : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
