package com.yamaremo.kiosk;

import android.content.Context;
import android.graphics.Typeface;

/**
 * App-wide font choice. Fonts are bundled in assets/fonts (Google Fonts, OFL, subset to Latin),
 * because the tablet can't download them itself. Add a font: put the .ttf files in
 * android/assets/fonts and add a row here.
 */
class Fonts {
    /** {id, name shown in the picker, regular file, bold file or null (single-weight font)} */
    static final String[][] LIST = {
            { "default", "Default (Roboto)", null, null },
            { "robotoflex", "Roboto Flex", "robotoflex-regular.ttf", "robotoflex-bold.ttf" },
            { "russoone", "Russo One", "russoone-regular.ttf", null },
            { "saira", "Saira", "saira-regular.ttf", "saira-bold.ttf" },
            { "mplusrounded1c", "Rounded Mplus 1c", "mplusrounded1c-regular.ttf", "mplusrounded1c-bold.ttf" },
            { "rubikone", "Rubik One", "rubikone-regular.ttf", null },
            { "scada", "Scada", "scada-regular.ttf", "scada-bold.ttf" },
            { "schibstedgrotesk", "Schibsted Grotesk", "schibstedgrotesk-regular.ttf", "schibstedgrotesk-bold.ttf" },
            { "schoolbell", "Schoolbell", "schoolbell-regular.ttf", null },
            { "secularone", "Secular One", "secularone-regular.ttf", null },
            { "sekuya", "Sekuya", "sekuya-regular.ttf", null },
            { "shadowsintolighttwo", "Shadows Into Light Two", "shadowsintolighttwo-regular.ttf", null },
            { "shantellsans", "Shantell Sans", "shantellsans-regular.ttf", "shantellsans-bold.ttf" },
            { "sharetech", "Share Tech", "sharetech-regular.ttf", null },
    };

    static Typeface regular = Typeface.DEFAULT;
    static Typeface bold = Typeface.DEFAULT_BOLD;

    static int index(String id) {
        for (int i = 0; i < LIST.length; i++) if (LIST[i][0].equals(id)) return i;
        return 0;
    }

    static Typeface[] load(Context ctx, int i) {
        String[] f = LIST[i];
        if (f[2] == null) return new Typeface[] { Typeface.DEFAULT, Typeface.DEFAULT_BOLD };
        try {
            Typeface r = Typeface.createFromAsset(ctx.getAssets(), "fonts/" + f[2]);
            // single-weight display fonts are heavy already: use them as the bold cut too
            Typeface b = f[3] != null ? Typeface.createFromAsset(ctx.getAssets(), "fonts/" + f[3]) : r;
            return new Typeface[] { r, b };
        } catch (Exception e) {
            return new Typeface[] { Typeface.DEFAULT, Typeface.DEFAULT_BOLD };
        }
    }

    static void apply(Context ctx, String id) {
        Typeface[] t = load(ctx, index(id));
        regular = t[0];
        bold = t[1];
    }
}
