package com.yamaremo.kiosk;

import static com.yamaremo.kiosk.TextLogic.*;

/** Plain-Java tests for TextLogic (no JUnit needed). Run: android/run-tests.sh */
public class TextLogicTest {
    static int run, failed;

    static void eq(String what, Object expected, Object actual) {
        run++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) { failed++; System.out.println("FAIL " + what + ": expected [" + expected + "] got [" + actual + "]"); }
    }

    static void split(String in, String artist, String song) {
        String[] r = splitArtistSong(in);
        eq("split artist of " + in, artist, r[0]);
        eq("split song of " + in, song, r[1]);
    }

    public static void main(String[] a) {
        // radio "Artist - Song"
        split("Björk - Human Behaviour", "Björk", "Human Behaviour");
        split("Elán – Voda, čo ma drží nad vodou", "Elán", "Voda, čo ma drží nad vodou");
        split("AC/DC - Back In Black - Live", "AC/DC", "Back In Black - Live");
        split("Jay-Z - Empire State of Mind", "Jay-Z", "Empire State of Mind");
        split("Gabriels-Blood On My Hands", "Gabriels", "Blood On My Hands");
        split("Jay-Z", "", "Jay-Z");
        split("Twenty-One Pilots-Heathens", "", "Twenty-One Pilots-Heathens");
        split("Radio Expres", "", "Radio Expres");
        split("Song only -", "", "Song only -");
        split(null, "", "");

        // station names
        eq("strip location", "Rádio_FM", stripLocation("Rádio_FM (Bratislava/Slovak)"));
        eq("strip brackets", "Europa 2", stripLocation("Europa 2 [Slovakia]"));
        eq("keep inner brackets", "Jazz (Live) FM", stripLocation("Jazz (Live) FM"));
        eq("keep if only brackets", "(Only brackets)", stripLocation("(Only brackets)"));
        eq("norm", "rádio fm", norm("Rádio_FM (Bratislava/Slovak)"));
        eq("norm punctuation", "fun radio", norm("* Fun Radio! *"));

        // placeholder titles
        eq("hasText dash", false, hasText("-"));
        eq("hasText dots", false, hasText("..."));
        eq("hasText null", false, hasText(null));
        eq("hasText word", true, hasText("Whispers"));

        // IP validation
        eq("ip ok", true, validIp("192.168.1.13"));
        eq("ip 3 parts", false, validIp("192.168.13"));
        eq("ip > 255", false, validIp("192.168.1.300"));
        eq("ip empty part", false, validIp("192.168..13"));
        eq("ip empty", false, validIp(""));
        eq("host name", true, validIp("nx-n500.local"));

        // misc
        eq("hhmm", "07:05", hhmm(7 * 60 + 5));
        eq("hhmm midnight", "00:00", hhmm(0));
        eq("line-in aux", true, isLineIn("aux"));
        eq("line-in radio", false, isLineIn("net_radio"));
        eq("tab label radio", "Radio", tabLabel("net_radio"));
        eq("tab label spotify", "Spotify", tabLabel("spotify"));
        eq("tab label unknown", "Some input", tabLabel("some_input"));
        eq("tabs summary", "AirPlay, Radio", tabsSummary("airplay,net_radio"));

        System.out.println(run + " checks, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
