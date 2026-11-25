package com.yamaremo.kiosk;

import java.util.Locale;

/**
 * Pure text logic (no Android classes), so it can be unit-tested on any computer:
 * see android/tests/TextLogicTest.java and android/run-tests.sh.
 */
final class TextLogic {
    private TextLogic() { }

    static final String[][] OTHER_SOURCES = {
            { "usb", "USB" }, { "spotify", "Spotify" }, { "server", "Server" }, { "tidal", "TIDAL" },
            { "deezer", "Deezer" }, { "qobuz", "Qobuz" }, { "amazon_music", "Amazon Music" },
            { "napster", "Napster" }, { "pandora", "Pandora" }, { "siriusxm", "SiriusXM" },
            { "juke", "JUKE" }, { "radiko", "radiko" }, { "alexa", "Alexa" }, { "mc_link", "MusicCast Link" },
            { "tuner", "Tuner" }, { "line_in", "Line In" }, { "line1", "Line 1" }, { "line2", "Line 2" },
            { "line3", "Line 3" }, { "coaxial", "Coaxial" }, { "hdmi", "HDMI" }, { "tv", "TV" },
            { "phono", "Phono" }, { "cd", "CD" }, { "bluetooth", "Bluetooth" },
    };

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

    /** Lower-case station name without location, underscores and punctuation, for matching. */
    static String norm(String st) {
        return stripLocation(st == null ? "" : st).toLowerCase().replace('_', ' ').replaceAll("[^\\p{L}\\p{N} ]", "").trim();
    }

    /** True if the text has at least one letter or digit ("-" or "..." doesn't count). */
    static boolean hasText(String t) {
        if (t == null) return false;
        for (int i = 0; i < t.length(); i++) if (Character.isLetterOrDigit(t.charAt(i))) return true;
        return false;
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

    static String hhmm(int minute) { return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60); }

    /** Inputs with no tracks, so no transport row (same list as the web remote). */
    static boolean isLineIn(String id) {
        return "aux".equals(id) || "optical".equals(id) || "usb".equals(id) || "line_in".equals(id);
    }

    /** Fixed short names for the usual tabs; others come from OTHER_SOURCES. */
    static String tabLabel(String id) {
        if ("airplay".equals(id)) return "AirPlay";
        if ("aux".equals(id)) return "AUX";
        if ("optical".equals(id)) return "Optical";
        if ("bluetooth".equals(id)) return "Bluetooth";
        if ("net_radio".equals(id)) return "Radio";
        for (String[] o : OTHER_SOURCES) if (o[0].equals(id)) return o[1];
        String t = id.replace('_', ' ');
        return t.length() > 0 ? Character.toUpperCase(t.charAt(0)) + t.substring(1) : id;
    }

    static String tabsSummary(String csv) {
        StringBuilder sb = new StringBuilder();
        for (String id : csv.split(",")) if (id.length() > 0) sb.append(sb.length() > 0 ? ", " : "").append(tabLabel(id));
        return sb.toString();
    }
}
