# Builds the bundled fonts for the Android app.
#   python3 android/tools/make_fonts.py <out dir> <folder with google/fonts .ttf files> [more folders...]
# Downloaded files are named "<dir>_<file>" (e.g. "russoone_RussoOne-Regular.ttf").
# Needs: pip install fonttools. Add fonts to FONTS below and a row to Fonts.java.
import sys, os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

out, srcs = sys.argv[1], sys.argv[2:]
UNICODES = ("U+0020-007E,U+00A0-024F,U+1E00-1EFF,U+2010-2027,U+2030-203A,"
            "U+20AC,U+2190-2193,U+2212,U+2715")

# (source file, output file, wght for variable fonts)
FONTS = [
    ("robotoflex_RobotoFlexVF.ttf", "robotoflex-regular.ttf", 400),
    ("robotoflex_RobotoFlexVF.ttf", "robotoflex-bold.ttf", 700),
    ("russoone_RussoOne-Regular.ttf", "russoone-regular.ttf", None),
    ("saira_SairaVF.ttf", "saira-regular.ttf", 400),
    ("saira_SairaVF.ttf", "saira-bold.ttf", 700),
    ("mplusrounded1c_MPLUSRounded1c-Regular.ttf", "mplusrounded1c-regular.ttf", None),
    ("mplusrounded1c_MPLUSRounded1c-Bold.ttf", "mplusrounded1c-bold.ttf", None),
    ("rubikone_RubikOne-Regular.ttf", "rubikone-regular.ttf", None),
    ("scada_Scada-Regular.ttf", "scada-regular.ttf", None),
    ("scada_Scada-Bold.ttf", "scada-bold.ttf", None),
    ("schibstedgrotesk_SchibstedGrotesk[wght].ttf", "schibstedgrotesk-regular.ttf", 400),
    ("schibstedgrotesk_SchibstedGrotesk[wght].ttf", "schibstedgrotesk-bold.ttf", 700),
    ("schoolbell_Schoolbell-Regular.ttf", "schoolbell-regular.ttf", None),
    ("secularone_SecularOne-Regular.ttf", "secularone-regular.ttf", None),
    ("sekuya_Sekuya-Regular.ttf", "sekuya-regular.ttf", None),
    ("shadowsintolighttwo_ShadowsIntoLightTwo-Regular.ttf", "shadowsintolighttwo-regular.ttf", None),
    ("shantellsans_ShantellSans[BNCE,INFM,SPAC,wght].ttf", "shantellsans-regular.ttf", 400),
    ("shantellsans_ShantellSans[BNCE,INFM,SPAC,wght].ttf", "shantellsans-bold.ttf", 700),
    ("sharetech_ShareTech-Regular.ttf", "sharetech-regular.ttf", None),
]

def find(name):
    for d in srcs:
        p = os.path.join(d, name)
        if os.path.exists(p): return p
    return None

for srcfile, outname, wght in FONTS:
    path = find(srcfile)
    if not path:
        if not os.path.exists(os.path.join(out, outname)): print("MISSING", srcfile)
        continue
    f = TTFont(path)
    if "fvar" in f:
        axes = {a.axisTag: a.defaultValue for a in f["fvar"].axes}
        if wght is not None: axes["wght"] = max(min(wght, max(a.maxValue for a in f["fvar"].axes if a.axisTag == "wght")),
                                                 min(a.minValue for a in f["fvar"].axes if a.axisTag == "wght"))
        f = instancer.instantiateVariableFont(f, axes)
    opts = subset.Options()
    opts.layout_features = ["kern", "liga", "calt", "ccmp", "locl", "mark", "mkmk"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.drop_tables += ["DSIG"]
    s = subset.Subsetter(opts)
    s.populate(unicodes=subset.parse_unicodes(UNICODES))
    s.subset(f)
    dst = os.path.join(out, outname)
    f.save(dst)
    print(f"{outname:36s} {os.path.getsize(dst):8d} bytes  glyphs={len(f.getGlyphOrder())}")
