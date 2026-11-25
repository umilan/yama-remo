package com.yamaremo.kiosk;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Small diagnostics log: the last MAX events (connection lost/restored, update results, wake-up,
 * auto-off, night mode, errors, crashes), kept in a file so it survives restarts and crashes.
 * Shown in Settings → ADVANCED → Diagnostics.
 */
final class Diag {
    static final int MAX = 300;
    private static final LinkedList<String> lines = new LinkedList<String>();
    private static File file;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private Diag() { }

    static synchronized void init(Context ctx) {
        if (file != null) return;
        file = new File(ctx.getFilesDir(), "diagnostics.log");
        try {
            if (file.exists()) {
                FileInputStream in = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                in.close();
                for (String l : new String(data, 0, off, "UTF-8").split("\n")) if (l.length() > 0) lines.add(l);
                while (lines.size() > MAX) lines.removeFirst();
            }
        } catch (Exception ignored) { }
        // crashes: write the cause to the log, then let Android handle it as usual
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String trace = sw.toString();
                if (trace.length() > 1500) trace = trace.substring(0, 1500) + "…";
                log("CRASH", "in thread " + t.getName() + ": " + trace.replace("\n", " | "));
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    static synchronized void log(String category, String message) {
        String line = TIME.format(new Date()) + "  " + category + ": " + message.replace('\n', ' ');
        lines.add(line);
        while (lines.size() > MAX) lines.removeFirst();
        save();
    }

    /** Newest first. */
    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) sb.append(lines.get(i)).append('\n');
        return sb.length() > 0 ? sb.toString() : "(no events yet)\n";
    }

    static synchronized void clear() {
        lines.clear();
        save();
    }

    private static void save() {
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            FileOutputStream out = new FileOutputStream(file);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { }
    }
}
