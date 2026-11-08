#!/usr/bin/env python3
"""Authors the two in-app Lottie animations as plain JSON (no AE needed).

    python3 xlox/scripts/lottie_art.py            # writes app/src/main/res/raw/*.json
    python3 xlox/scripts/lottie_art.py --preview  # also renders PNG frames to /tmp/lottie-preview

Art direction (UI brief): monochrome white strokes on black, blue #0A84FF used
once as an accent, simple shapes, 60 fps, each file well under 60 KB. Original
work authored in this repository; see app/src/main/assets/licenses/LOTTIE.md.

Both belong to the update-check sheet. The splash is no longer a Lottie: it is
a typography film of real TextViews (SplashActivity), and the static wordmark
for the icon and the system splash comes from scripts/brand_wordmark.py.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "app/src/main/res/raw"

FPS = 60
WHITE = [1, 1, 1, 1]
BLUE = [10 / 255, 132 / 255, 1, 1]

# Easing (cubic-bezier handles). FastOutSlowIn = (0.4, 0, 0.2, 1).
FOSI = ((0.4, 0.0), (0.2, 1.0))
EASE_OUT = ((0.0, 0.0), (0.2, 1.0))
EASE_IN = ((0.4, 0.0), (1.0, 1.0))
LINEAR = ((0.0, 0.0), (1.0, 1.0))
SINE = ((0.37, 0.0), (0.63, 1.0))


# --------------------------------------------------------------- primitives
def static(v):
    return {"a": 0, "k": v}


def anim(*keys):
    """keys: (frame, value, easing_to_next) ... last easing ignored."""
    out = []
    for i, (t, v, ease) in enumerate(keys):
        v = v if isinstance(v, list) else [v]
        k = {"t": t, "s": v}
        if i < len(keys) - 1:
            (ox, oy), (ix, iy) = ease
            k["o"] = {"x": [ox], "y": [oy]}
            k["i"] = {"x": [ix], "y": [iy]}
        out.append(k)
    return {"a": 1, "k": out}


def tr(p=(0, 0), s=None, r=None, o=None, a=(0, 0)):
    return {
        "ty": "tr",
        "p": static(list(p)),
        "a": static(list(a)),
        "s": s if s is not None else static([100, 100]),
        "r": r if r is not None else static(0),
        "o": o if o is not None else static(100),
        "sk": static(0),
        "sa": static(0),
        "nm": "Transform",
    }


def ellipse(d, p=(0, 0)):
    """Circle as an explicit 4-segment cubic path that starts at 3 o'clock and
    runs clockwise (screen coordinates). Trim paths on a plain 'el' shape
    depend on each renderer's idea of the start point; an explicit path makes
    trims identical in lottie-android and in the preview renderer."""
    r = d / 2
    k = r * 0.5523
    cx, cy = p
    return {
        "ty": "sh", "d": 1, "nm": "Circle",
        "ks": static({
            # Open path whose last vertex repeats the first: every renderer
            # trims an open path the same way (closing segments differ).
            "v": [[cx + r, cy], [cx, cy + r], [cx - r, cy], [cx, cy - r], [cx + r, cy]],
            "i": [[0, 0], [k, 0], [0, k], [-k, 0], [0, -k]],
            "o": [[0, k], [-k, 0], [0, -k], [k, 0], [0, 0]],
            "c": False,
        }),
    }


def line(x1, y1, x2, y2):
    return {
        "ty": "sh",
        "d": 1,
        "nm": "Line",
        "ks": static({"i": [[0, 0], [0, 0]], "o": [[0, 0], [0, 0]],
                      "v": [[x1, y1], [x2, y2]], "c": False}),
    }


def polyline(points):
    n = len(points)
    return {
        "ty": "sh",
        "d": 1,
        "nm": "Polyline",
        "ks": static({"i": [[0, 0]] * n, "o": [[0, 0]] * n,
                      "v": [list(p) for p in points], "c": False}),
    }


def stroke(color=WHITE, width=2.5, opacity=None):
    return {"ty": "st", "c": static(color), "o": opacity if opacity is not None else static(100),
            "w": static(width), "lc": 2, "lj": 2, "ml": 4, "bm": 0, "nm": "Stroke"}


def fill(color=WHITE, opacity=None):
    return {"ty": "fl", "c": static(color), "o": opacity if opacity is not None else static(100),
            "r": 1, "bm": 0, "nm": "Fill"}


def trim(s=None, e=None, o=None):
    # Order inside a group: shape, stroke/fill, trim, transform (After Effects
    # order: trim affects every path above it, including stroked ones).
    return {"ty": "tm", "s": s if s is not None else static(0), "e": e if e is not None else static(100),
            "o": o if o is not None else static(0), "m": 1, "nm": "Trim"}


def group(name, items, transform=None):
    return {"ty": "gr", "nm": name, "it": items + [transform or tr()]}


def layer(ind, name, shapes, op, pos, scale=None, opacity=None, rot=None, parent=None):
    lyr = {
        "ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ao": 0, "bm": 0,
        "ks": {
            "o": opacity if opacity is not None else static(100),
            "r": rot if rot is not None else static(0),
            "p": static([pos[0], pos[1], 0]),
            "a": static([0, 0, 0]),
            "s": scale if scale is not None else static([100, 100, 100]),
        },
        "shapes": shapes, "ip": 0, "op": op, "st": 0,
    }
    if parent is not None:
        lyr["parent"] = parent
    return lyr


def comp(name, w, h, op, layers):
    return {"v": "5.7.4", "fr": FPS, "ip": 0, "op": op, "w": w, "h": h, "nm": name,
            "ddd": 0, "assets": [], "layers": layers, "markers": []}


# ---------------------------------------------------------------- anoy_sync
def anoy_sync():
    """1.2 s seamless loop for 'Checking for updates': one white arc orbits a
    faint track while its length breathes. No colour."""
    W = H = 72
    C = (36, 36)
    D = 44
    OP = 72
    return comp("anoy_sync", W, H, OP, [
        layer(1, "arc", [group("arc", [
            ellipse(D),
            stroke(WHITE, 3),
            trim(s=static(0), e=anim((0, 18, SINE), (36, 42, SINE), (OP, 18, LINEAR))),
        ])], OP, C, rot=anim((0, 0, LINEAR), (OP, 360, LINEAR))),
        layer(2, "track", [group("track", [ellipse(D), stroke(WHITE, 3, static(14))])], OP, C),
    ])


# ------------------------------------------------------------- anoy_success
def anoy_success():
    """0.7 s one-shot 'done': ring draws, check draws with a tiny spring, one
    blue ring pulses outward once (the single accent moment)."""
    W = H = 72
    C = (36, 36)
    D = 44
    OP = 42
    check = [(-8.5, 0.5), (-2.5, 6.5), (9, -6)]
    return comp("anoy_success", W, H, OP, [
        layer(1, "check", [group("check", [
            polyline(check),
            stroke(WHITE, 3),
            trim(e=anim((10, 0, FOSI), (26, 100, LINEAR))),
        ])], OP, C,
            # Tiny spring: 86 -> 106 -> 100.
            scale=anim((10, [86, 86, 100], EASE_OUT), (24, [106, 106, 100], SINE), (34, [100, 100, 100], LINEAR))),
        layer(2, "ring", [group("ring", [
            ellipse(D),
            stroke(WHITE, 3),
            trim(e=anim((0, 0, FOSI), (18, 100, LINEAR))),
        ], tr(r=static(-90)))], OP, C),
        layer(3, "pulse", [group("pulse", [ellipse(D), stroke(BLUE, 2)])], OP, C,
              scale=anim((18, [100, 100, 100], EASE_OUT), (40, [138, 138, 100], LINEAR)),
              opacity=anim((18, 0, LINEAR), (21, 70, EASE_OUT), (40, 0, LINEAR))),
    ])


ART = {"anoy_sync": anoy_sync, "anoy_success": anoy_success}


def main() -> int:
    RAW.mkdir(parents=True, exist_ok=True)
    for name, build in ART.items():
        data = json.dumps(build(), separators=(",", ":"))
        (RAW / f"{name}.json").write_text(data)
        print(f"[lottie-art] {name}.json  {len(data) / 1024:.1f} KB")
        assert len(data) < 60 * 1024, f"{name} exceeds 60 KB"
    if "--preview" in sys.argv:
        preview()
    return 0


def preview() -> None:
    """Renders sample frames to SVG with python-lottie (pip install lottie)."""
    from lottie.exporters.svg import export_svg
    from lottie.parsers.tgs import parse_tgs

    out = Path("/tmp/lottie-preview")
    out.mkdir(exist_ok=True)
    frames = {"anoy_sync": [0, 18, 36, 54],
              "anoy_success": [0, 8, 16, 22, 28, 34, 41]}
    for name, fs in frames.items():
        anim_obj = parse_tgs(str(RAW / f"{name}.json"))
        for f in fs:
            export_svg(anim_obj, str(out / f"{name}_{f:03d}.svg"), frame=f)
    print(f"[lottie-art] preview SVGs in {out}")


if __name__ == "__main__":
    sys.exit(main())
