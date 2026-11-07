#!/usr/bin/env python3
"""
Check an Anoy splash Lottie against the app's code - NOT against a device.

  pip install rlottie-python numpy fonttools uharfbuzz skia-pathops
  python verify.py anoy_splash.json            [--variant A1|A2|A1A2] [--lift 13.0]
  python verify.py anoy_splash_wait.json
  python verify.py anoy_splash_reduced.json

What it compares (and what it does not):
  * Expected values come from SplashActivity's code, ported below: the
    timeline constants and easings (applyTime, breathe, playReduced) and the
    horizontal layout rules the app uses at runtime:
      - gap "Anoy" | slot = measureText(" ") at 40 dp, -0.03 em
        (slot.setMarginStart(Math.round(anoy.getPaint().measureText(" "))))
      - TextView width = shaped advances + letter spacing on every character
        (Minikin puts half before and half after each character)
      - the name row and the kicker are centred; recentreDx =
        (width(Loader) - width(Hax)) / 2
    computed here from the font file with HarfBuzz - independently of
    gen_splash.py. Values are in dp; the app rounds the gap and recentreDx to
    whole device pixels (<= 0.5 px), which this file does not model.
  * Vertical: only the MOVES are checked (kicker drift 8 dp, the lift). The
    lift is measured by the app from the real layout on the device; here it is
    an assumed 13.0 dp (--lift). Absolute vertical positions are not checked.
  * Measured values come from rendering each layer alone with rlottie (4x) and
    taking its alpha-weighted ink centroid and its peak alpha, every frame.
    Expected ink centroids use the glyph outlines' area centroids (fontTools
    StatisticsPen) placed at the Android glyph origins. Inter has overlapping
    contours (A, n, y, e, x, W, M); they are merged first (skia-pathops), as
    the nonzero fill draws them once.
"""
import argparse, copy, json, os, re, tempfile
import numpy as np
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.statisticsPen import StatisticsPen
from fontTools.pens.recordingPen import DecomposingRecordingPen
import pathops
from fontTools.svgLib.path import parse_path
from rlottie_python import LottieAnimation

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "../../xlox/app/src/main/res/font/inter_semibold.ttf")
MARK = os.path.join(HERE, "../../xlox/app/src/main/res/drawable/splash_mark.xml")
W, H, SS = 360, 800, 4

# ------------------------------------------------ SplashActivity, ported
def bez(x1, y1, x2, y2):
    def f(p):
        if p <= 0: return 0.0
        if p >= 1: return 1.0
        lo, hi = 0.0, 1.0
        for _ in range(60):
            t = (lo + hi) / 2
            x = 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3
            lo, hi = (t, hi) if x < p else (lo, t)
        t = (lo + hi) / 2
        return 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3
    return f

FOSI, DISSOLVE = bez(.4, 0, .2, 1), bez(.45, 0, .55, 1)
EMPH = bez(.2, 0, 0, 1)                      # variant A1 only
lin = lambda t, s, d: 0.0 if t <= s else 1.0 if t >= s + d else (t - s) / d
win = lambda p, a, b: 0.0 if p <= a else 1.0 if p >= b else (p - a) / (b - a)
prog = lambda t, s, d, e: e(lin(t, s, d))

def apply_time(t, lift, dx, variant=""):
    """SplashActivity.applyTime(t): {layer: (alpha, tx, ty)}; ty < 0 is up."""
    exit_ease = EMPH if "A1" in variant else FOSI
    arrive_ms = 460 if "A2" in variant else 400            # ARRIVE_FADE_MS
    arrive = prog(t, 0, arrive_ms, FOSI)
    name = prog(t, 120, arrive_ms, FOSI)                   # NAME_AT_MS 120
    leave = prog(t, 1150, 520, exit_ease)                  # CUT_AT_MS, CUT_MS
    ny = -prog(t, 1200, 520, exit_ease) * lift             # MOVE_AT_MS, MOVE_MS
    swap = lin(t, 1560, 450)                               # SWAP_AT_MS, SWAP_MS
    nx = DISSOLVE(swap) * dx
    return {"system_mark": (1 - prog(t, 0, 220, FOSI), 0, 0),
            "kicker": (arrive * (1 - leave), 0, -leave * 8),
            "anoy": (name, nx, ny),
            "loader": (name * (1 - DISSOLVE(win(swap, .30, 1))), nx, ny),
            "hax": (name * DISSOLVE(win(swap, 0, .70)), nx, ny)}

def breathe(t):
    """breathe(): 1 -> 0.72 over 1200 ms (FOSI), REVERSE, so the way back is
    the same fraction played backwards."""
    frac = t / 1200 if t <= 1200 else 1 - (t - 1200) / 1200
    return 1 + (0.72 - 1) * FOSI(frac)

def expected(mode, f, lift, dx, variant):
    t = f * 1000 / 60
    if mode == "main":
        return apply_time(t, lift, dx, variant)
    end = apply_time(2850, lift, dx)
    if mode == "loop":
        a = breathe(t)
        return {"anoy": (a, end["anoy"][1], 0), "hax": (a, end["hax"][1], 0)}
    # reduced: applyTime(CUT_AT_MS) for 1000 ms, then applyTime(FILM_MS)
    return apply_time(1150, lift, dx) if t < 1000 else end

# ------------------------------------------------ layout from the font
def ink_stats(draw):
    """Area and centroid of an outline with overlaps merged (nonzero fill)."""
    path = pathops.Path()
    draw(path.getPen())
    path.simplify(fix_winding=True)
    pen = StatisticsPen()
    path.draw(pen)
    return abs(pen.area), pen.meanX

class Text:
    def __init__(self, tt, hbfont, text, size, track_em):
        self.tr = track_em * size
        sc = size / tt["head"].unitsPerEm
        buf = hb.Buffer(); buf.add_str(text); buf.guess_segment_properties()
        hb.shape(hbfont, buf, {"kern": True})
        gs, x, area, mx = tt.getGlyphSet(), self.tr / 2, 0.0, 0.0
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            rec = DecomposingRecordingPen(gs)
            gs[tt.getGlyphName(info.codepoint)].draw(rec)
            a, gx = ink_stats(rec.replay)
            a *= sc * sc
            if a:
                area += a
                mx += a * (x + pos.x_offset * sc + gx * sc)
            x += pos.x_advance * sc + self.tr
        self.width = x - self.tr / 2          # TextView width, half-tracks both ends
        self.ink_cx = mx / area if area else 0.0   # ink centroid from the view's left edge

def layout():
    tt = TTFont(FONT)
    hbf = hb.Font(hb.Face(hb.Blob(open(FONT, "rb").read())))
    kick = Text(tt, hbf, "WELCOME TO", 11, 0.22)
    anoy, loader, hax = (Text(tt, hbf, s, 40, -0.03) for s in ("Anoy", "Loader", "Hax"))
    margin = Text(tt, hbf, " ", 40, -0.03).width      # Paint.measureText(" ")
    row = anoy.width + margin + loader.width
    left = (W - row) / 2
    slot = left + anoy.width + margin
    dx = (loader.width - hax.width) / 2               # recentreDx
    cx = {"kicker": (W - kick.width) / 2 + kick.ink_cx,
          "anoy": left + anoy.ink_cx,
          "loader": slot + loader.ink_cx,
          "hax": slot + hax.ink_cx}
    # system splash wordmark: 288 dp vector centred in the window
    paths = re.findall(r'android:pathData="([^"]+)"', open(MARK).read())
    _, mx = ink_stats(lambda pen: [parse_path(d, pen) for d in paths])
    cx["system_mark"] = W / 2 - 144 + mx
    return cx, dx, margin

# ------------------------------------------------ render + compare
def render_layer(data, name, frames):
    d = copy.deepcopy(data)
    d["layers"] = [l for l in d["layers"] if l.get("nm") == name]
    p = tempfile.mktemp(suffix=".json")
    json.dump(d, open(p, "w"))
    anim, out = LottieAnimation.from_file(p), {}
    for f in frames:
        a = np.asarray(anim.render_pillow_frame(frame_num=f, width=W * SS, height=H * SS))[..., 3] / 255.0
        m = a.max()
        if m < 0.02:
            out[f] = (m, None, None)
            continue
        ys, xs = np.nonzero(a)
        w = a[ys, xs]
        out[f] = (m, (xs * w).sum() / w.sum() / SS + 0.5 / SS, (ys * w).sum() / w.sum() / SS)
    os.remove(p)
    return out

RANGES = {"main": [("arrive 0-31", 0, 31), ("hold 32-68", 32, 68), ("leave + lift 69-104", 69, 104),
                   ("swap 93-121", 93, 121), ("hold 122-170", 122, 170)],
          "loop": [("down 0-72", 0, 72), ("up 72-143", 72, 143)],
          "reduced": [("welcome 0-59", 0, 59), ("final 60-101", 60, 101)]}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("json"); ap.add_argument("--variant", default="")
    ap.add_argument("--lift", type=float, default=13.0, help="assumed; the app measures it")
    args = ap.parse_args()
    data = json.load(open(args.json))
    mode = "loop" if "(loop)" in data["nm"] else "reduced" if "(reduced)" in data["nm"] else "main"
    last = data["op"] - 1
    frames = list(range(0, last + 1))
    cx, dx, margin = layout()
    print(f"{os.path.basename(args.json)} [{mode}{' ' + args.variant if args.variant else ''}]  "
          f"gap measureText(\" \")={margin:.2f} dp  recentreDx={dx:.2f} dp  lift={args.lift} (assumed)")
    names = [l["nm"] for l in data["layers"] if l.get("ty") == 4]
    errs = {f: [0.0, 0.0, 0.0, ""] for f in frames}   # opacity, x, y-move, worst layer
    for nm in names:
        got = render_layer(data, nm, frames)
        ref = None
        for f in frames:
            ea, ex, ey = expected(mode, f, args.lift, dx, args.variant)[nm]
            ma, mx, my = got[f]
            e = errs[f]
            e[0] = max(e[0], abs(ma - ea))
            if mx is None or ea < 0.1:
                continue
            if ref is None:
                ref = (my, ey)
            ddx = abs(mx - (cx[nm] + ex))
            ddy = abs((my - ref[0]) - (ey - ref[1]))
            if ddx > e[1]: e[1], e[3] = ddx, nm
            e[2] = max(e[2], ddy)
    print(f"{'frames':22s} {'max opacity err':>16s} {'max x err (abs)':>16s} {'max y-move err':>15s}")
    for label, a, b in RANGES[mode]:
        sel = [errs[f] for f in frames if a <= f <= min(b, last)]
        o = max(s[0] for s in sel); x = max(s[1] for s in sel); y = max(s[2] for s in sel)
        print(f"{label:22s} {o * 100:15.2f}% {x:14.2f}px {y:13.2f}px")

if __name__ == "__main__":
    main()
