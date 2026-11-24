package com.yamaremo.kiosk;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * Receives MusicCast (YXC) event notifications. The speaker sends a small JSON packet by UDP to
 * this port whenever something changes, for 10 minutes after the last request that carried the
 * X-AppName / X-AppPort headers (MainActivity sends them with every request).
 */
class Events extends Thread {
    static final int PORT = 41100;

    interface Listener { void onEvent(String json); }

    private final Listener listener;
    private volatile DatagramSocket socket;
    private volatile boolean running = true;

    Events(Listener l) {
        super("yamaremo-events");
        listener = l;
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(PORT);
            byte[] buf = new byte[4096];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                listener.onEvent(new String(p.getData(), 0, p.getLength(), "UTF-8"));
            }
        } catch (Exception ignored) {
            // socket closed or port unavailable: the app keeps polling, just without push updates
        }
    }

    void shutdown() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
    }
}
