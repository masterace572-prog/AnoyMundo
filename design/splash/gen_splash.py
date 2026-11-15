#!/usr/bin/env python3
"""
Anoy splash film as Lottie (reference asset; the app itself still plays the
film natively in SplashActivity.applyTime - this file is not wired in).

Text is outlined from the font the app ships (res/font/inter_semibold.ttf),
shaped with HarfBuzz; timings and easings are SplashActivity's constants.

  pip install fonttools uharfbuzz
  python gen_splash.py ../../xlox/app/src/main/res/font/inter_semibold.ttf main    anoy_splash.json
  python gen_splash.py ../../xlox/app/src/main/res/font/inter_semibold.ttf loop    anoy_splash_wait.json
  python gen_splash.py ../../xlox/app/src/main/res/font/inter_semibold.ttf reduced anoy_splash_reduced.json
  (--transparent drops the black "bg" layer)

Changes from the first draft, to match the app exactly:
  * Android (Minikin) puts half the letter-spacing before and half after each
    letter, so the "Anoy" | "Loader" gap is 8 dp + one tracking step (-1.2 px),
    and the line centres on that box (draft was 0.6-1.2 px off).
  * Waiting loop: the app breathes the whole "Anoy Hax" line (splashName), not
    just "Hax"; the return half is the reversed curve (ValueAnimator REVERSE).
  * system_mark is the real res/drawable/splash_mark.xml outline, not a square.
"""
import sys, json, os, re
from fontTools.svgLib.path import parse_path
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.basePen import BasePen

FPS, W, H = 60, 360, 800
STD  = (0.4, 0, 0.2, 1)        # cubic-bezier(0.4,0,0.2,1)
SWAP = (0.45, 0, 0.55, 1)      # cubic-bezier(0.45,0,0.55,1)
S = lambda t: round(t * FPS, 3)

# ---------- font ----------
class Font:
    def __init__(self, path):
        self.tt = TTFont(path)
        self.gs = self.tt.getGlyphSet()
        self.upem = self.tt['head'].unitsPerEm
        self.asc = self.tt['hhea'].ascent / self.upem
        self.desc = -self.tt['hhea'].descent / self.upem
        self.cap = self.tt['OS/2'].sCapHeight / self.upem
        self.hb = hb.Font(hb.Face(hb.Blob(open(path, 'rb').read())))

class OutlinePen(BasePen):
    """Glyph outline -> Lottie bezier paths (y down, origin = baseline)."""
    def __init__(self, gs, ox, sc, flip=True):
        super().__init__(gs)
        self.ox, self.sc, self.fy = ox, sc, (-1 if flip else 1)
        self.contours = []
        self.v = None
    def P(self, pt): return (self.ox + pt[0] * self.sc, self.fy * pt[1] * self.sc)
    def _moveTo(self, pt):
        p = self.P(pt); self.v, self.i, self.o = [p], [p], [p]
    def _lineTo(self, pt):
        p = self.P(pt); self.v.append(p); self.i.append(p); self.o.append(p)
    def _curveToOne(self, a, b, c):
        self.o[-1] = self.P(a); p = self.P(c)
        self.v.append(p); self.i.append(self.P(b)); self.o.append(p)
    def _closePath(self):
        v, i, o = self.v, self.i, self.o
        if len(v) > 1 and abs(v[0][0]-v[-1][0]) < 1e-6 and abs(v[0][1]-v[-1][1]) < 1e-6:
            i[0] = i[-1]; v.pop(); i.pop(); o.pop()
        rel = lambda a, b: [round(a[0]-b[0], 2), round(a[1]-b[1], 2)]
        self.contours.append({
            "c": True,
            "v": [[round(x, 2), round(y, 2)] for x, y in v],
            "i": [rel(a, b) for a, b in zip(i, v)],
            "o": [rel(a, b) for a, b in zip(o, v)]})
        self.v = None
    def _endPath(self): self.v = None

def shape(font, text, size, track_em):
    """Returns (paths, ink width). Width excludes trailing letter-spacing."""
    buf = hb.Buffer(); buf.add_str(text); buf.guess_segment_properties()
    hb.shape(font.hb, buf, {"kern": True})
    sc, tr, x, paths = size / font.upem, track_em * size, 0.0, []
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        pen = OutlinePen(font.gs, x + pos.x_offset * sc, sc)
        font.gs[font.tt.getGlyphName(info.codepoint)].draw(pen)
        paths += pen.contours
        x += pos.x_advance * sc + tr
    return paths, x - tr

# ---------- lottie helpers ----------
def prop(v):
    """number -> static; list of (frame, value, ease|None=hold) -> animated."""
    if not isinstance(v, list):
        return {"a": 0, "k": v}
    ks = []
    for n, (t, val, e) in enumerate(v):
        k = {"t": t, "s": [val]}
        if n < len(v) - 1:
            if e is None: k["h"] = 1
            else:
                k["o"] = {"x": [e[0]], "y": [e[1]]}
                k["i"] = {"x": [e[2]], "y": [e[3]]}
        ks.append(k)
    return {"a": 1, "k": ks}

def KS(o, px, py):
    return {"o": prop(o), "r": prop(0),
            "p": {"s": True, "x": prop(px), "y": prop(py)},   # split position
            "a": {"a": 0, "k": [0, 0, 0]}, "s": {"a": 0, "k": [100, 100, 100]}}

def rgb(h): return [int(h[i:i+2], 16) / 255 for i in (1, 3, 5)] + [1]
GTR = {"ty": "tr", "p": {"a": 0, "k": [0, 0]}, "a": {"a": 0, "k": [0, 0]},
       "s": {"a": 0, "k": [100, 100]}, "r": {"a": 0, "k": 0},
       "o": {"a": 0, "k": 100}, "sk": {"a": 0, "k": 0}, "sa": {"a": 0, "k": 0}}

def layer(ind, name, items, ks, ip, op):
    return {"ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ks": ks, "ao": 0,
            "shapes": [{"ty": "gr", "nm": name, "it": items + [dict(GTR)]}],
            "ip": ip, "op": op, "st": 0, "bm": 0}

def text_layer(ind, name, paths, color, o, px, py, ip, op):
    items = [{"ty": "sh", "nm": "g", "ks": {"a": 0, "k": p}} for p in paths]
    items.append({"ty": "fl", "nm": "fill", "c": {"a": 0, "k": rgb(color)},
                  "o": {"a": 0, "k": 100}, "r": 1})
    return layer(ind, name, items, KS(o, px, py), ip, op)

def mark_paths():
    """res/drawable/splash_mark.xml (288 x 288 viewport) -> Lottie paths."""
    here = os.path.dirname(os.path.abspath(__file__))
    xml = open(os.path.join(here, "../../xlox/app/src/main/res/drawable/splash_mark.xml")).read()
    pen = OutlinePen(None, 0, 1, flip=False)
    for d in re.findall(r'android:pathData="([^"]+)"', xml):
        parse_path(d, pen)
    return pen.contours

# ---------- build ----------
def build(fontpath, mode, bg=True):
    f = Font(fontpath)
    kick, wK = shape(f, "WELCOME TO", 11, 0.22)
    anoy, wA = shape(f, "Anoy",   40, -0.03)
    ldr,  wL = shape(f, "Loader", 40, -0.03)
    hax,  wH = shape(f, "Hax",    40, -0.03)
    GAP = 8 + (-0.03 * 40)                   # 8 dp margin + Minikin half-tracks
    xK  = (W - wK) / 2
    xA1 = (W - (wA + GAP + wL)) / 2          # "Anoy Loader" centred
    xA2 = (W - (wA + GAP + wH)) / 2          # "Anoy Hax" centred
    slot1 = xA1 + wA + GAP                   # Loader/Hax left edge (before slide)
    slot2 = xA2 + wA + GAP                   # same slot after slide
    shift = xA2 - xA1                        # == (wL - wH)/2

    KL, GV, LH, CY = 14, 12, 44, 0.42 * H    # kicker line box 14 (inferred), gap 12
    top = CY - (KL + GV + LH) / 2            # group top (301)
    kb  = top + (KL - (f.asc + f.desc) * 11) / 2 + f.asc * 11        # kicker baseline
    wb0 = top + KL + GV + (LH - (f.asc + f.desc) * 40) / 2 + f.asc * 40
    wb1 = CY + f.cap * 40 / 2                # cap-height middle on y=336
    print(f"wL={wL:.2f} wH={wH:.2f} slide={shift:.2f}px lift={wb0-wb1:.2f}px")

    layers = []
    if mode == "main":
        N = 171
        layers = [
          ("kicker", kick, "#9A9AA1",
             [(0,0,STD),(S(.40),100,None),(S(1.15),100,STD),(S(1.67),0,None)],
             xK, [(S(1.15),kb,STD),(S(1.67),kb-8,None)], 0, N),
          ("hax", hax, "#FFFFFF",
             [(S(1.56),0,SWAP),(S(1.56+.70*.45),100,None)],
             [(S(1.56),slot1,SWAP),(S(2.01),slot2,None)],
             [(S(1.20),wb0,STD),(S(1.72),wb1,None)], 0, N),
          ("loader", ldr, "#FFFFFF",
             [(S(.12),0,STD),(S(.52),100,None),(S(1.56+.30*.45),100,SWAP),(S(2.01),0,None)],
             [(S(1.56),slot1,SWAP),(S(2.01),slot2,None)],
             [(S(1.20),wb0,STD),(S(1.72),wb1,None)], 0, N),
          ("anoy", anoy, "#FFFFFF",
             [(S(.12),0,STD),(S(.52),100,None)],
             [(S(1.56),xA1,SWAP),(S(2.01),xA2,None)],
             [(S(1.20),wb0,STD),(S(1.72),wb1,None)], 0, N),
        ]
    elif mode == "loop":
        N = 144   # 2.4 s; set the player to repeat
        # SplashActivity.breathe(): the whole line, 1 -> 0.72 -> 1, 1.2 s each way,
        # FAST_OUT_SLOW_IN out and the same curve played backwards on the return.
        BACK = (1 - STD[2], 1 - STD[3], 1 - STD[0], 1 - STD[1])     # (0.8, 0, 0.6, 1)
        pulse = [(0,100,STD),(72,72,BACK),(144,100,None)]
        layers = [
          ("hax", hax, "#FFFFFF", pulse, slot2, wb1, 0, N),
          ("anoy", anoy, "#FFFFFF", pulse, xA2, wb1, 0, N),
        ]
    else:  # reduced: 1.0 s still, hard cut, 0.7 s still
        N = 102
        layers = [
          ("kicker", kick, "#9A9AA1", 100, xK, kb, 0, 60),
          ("hax", hax, "#FFFFFF", 100, slot2, wb1, 60, N),
          ("loader", ldr, "#FFFFFF", 100, slot1, wb0, 0, 60),
          ("anoy", anoy, "#FFFFFF", 100,
             [(0,xA1,None),(60,xA2,None)], [(0,wb0,None),(60,wb1,None)], 0, N),
        ]

    out = [text_layer(n + 1, nm, p, c, o, px, py, ip, op)
           for n, (nm, p, c, o, px, py, ip, op) in enumerate(layers)]

    if mode == "main":   # the system-splash wordmark, 288 dp, window centre
        items = [{"ty": "sh", "nm": "g", "ks": {"a": 0, "k": p}} for p in mark_paths()]
        items.append({"ty": "fl", "nm": "fill", "c": {"a": 0, "k": rgb("#FFFFFF")},
                      "o": {"a": 0, "k": 100}, "r": 1})
        out.append(layer(len(out) + 1, "system_mark", items,
                         KS([(0,100,STD),(S(.22),0,None)], W/2 - 144, H/2 - 144), 0, N))
    if bg:
        out.append({"ddd": 0, "ind": len(out) + 1, "ty": 1, "nm": "bg", "sw": W, "sh": H,
                    "sc": "#000000", "ip": 0, "op": N, "st": 0, "bm": 0,
                    "ks": {"o": prop(100), "r": prop(0), "p": {"a": 0, "k": [W/2, H/2, 0]},
                           "a": {"a": 0, "k": [W/2, H/2, 0]}, "s": {"a": 0, "k": [100,100,100]}}})
    return {"v": "5.7.0", "fr": FPS, "ip": 0, "op": N, "w": W, "h": H,
            "nm": f"Anoy splash ({mode})", "ddd": 0, "assets": [], "layers": out}

if __name__ == "__main__":
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    data = build(a[0], a[1], bg="--transparent" not in sys.argv)
    s = json.dumps(data, separators=(",", ":"))
    open(a[2], "w").write(s)
    print(f"{a[2]}: {len(s)/1024:.1f} KB, {data['op']} frames")
