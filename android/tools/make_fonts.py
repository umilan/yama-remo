# Builds the bundled fonts: python3 android/tools/make_fonts.py <folder with google/fonts .ttf files> android/assets/fonts
# Needs: pip install fonttools. Edit the make(...) lines at the bottom to add fonts.
import sys, os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

src, out = sys.argv[1], sys.argv[2]
UNICODES = ("U+0020-007E,U+00A0-024F,U+1E00-1EFF,U+2010-2027,U+2030-203A,"
            "U+20AC,U+2190-2193,U+2212,U+2715")

def make(srcfile, outname, wght=None, wdth=None):
    f = TTFont(os.path.join(src, srcfile))
    if "fvar" in f:
        axes = {a.axisTag: a.defaultValue for a in f["fvar"].axes}
        if wght is not None: axes["wght"] = wght
        if wdth is not None and "wdth" in axes: axes["wdth"] = wdth
        f = instancer.instantiateVariableFont(f, axes)
    opts = subset.Options()
    opts.layout_features = ["kern", "liga", "calt", "ccmp", "locl", "mark", "mkmk"]
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.drop_tables += ["DSIG"]
    s = subset.Subsetter(opts)
    s.populate(unicodes=subset.parse_unicodes(UNICODES))
    s.subset(f)
    path = os.path.join(out, outname)
    f.save(path)
    print(f"{outname:32s} {os.path.getsize(path):8d} bytes  glyphs={len(f.getGlyphOrder())}")

make("robotoflex_RobotoFlexVF.ttf", "robotoflex-regular.ttf", wght=400)
make("robotoflex_RobotoFlexVF.ttf", "robotoflex-bold.ttf", wght=700)
make("russoone_RussoOne-Regular.ttf", "russoone-regular.ttf")
make("saira_SairaVF.ttf", "saira-regular.ttf", wght=400, wdth=100)
make("saira_SairaVF.ttf", "saira-bold.ttf", wght=700, wdth=100)
make("mplusrounded1c_MPLUSRounded1c-Regular.ttf", "mplusrounded1c-regular.ttf")
make("mplusrounded1c_MPLUSRounded1c-Bold.ttf", "mplusrounded1c-bold.ttf")
make("rubikone_RubikOne-Regular.ttf", "rubikone-regular.ttf")
