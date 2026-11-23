#!/usr/bin/env python3
"""Generates Anoy's Lottie files (res/raw/*.json). Source of truth: edit here
and re-run, never edit the JSON by hand.

    python3 scripts/lottie_art.py

Splash status only (2.3.8) - quiet motion, stroke only, no fills, no overshoot:

  anoy_arc.json    24 x 24, 2.4 s loop. One white arc (r 7.5, 70 % opacity)
                   turning once per loop while its length breathes 25-38 %.
                   Splash "Checking for updates…".
  anoy_check.json  24 x 24, 0.7 s one-shot. Blue #0A84FF ring (same r 7.5 as
                   the arc, so arc -> check reads as one motion) draws from
                   12 o'clock, the tick follows; done by 0.57 s, then holds.
                   Splash "Up to date".

Strokes are 1.75 units: 1.75 dp at the 24 dp design size.
"""
from __future__ import annotations

import json
import math
from pathlib import Path

RAW = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
FR = 60
STROKE = 1.75
WHITE = [1, 1, 1, 1]
BLUE = [10 / 255, 132 / 255, 1, 1]  # #0A84FF, the palette's accent

# Ease out without overshoot (cubic-bezier 0.2, 0, 0, 1) and a gentle in-out.
EASE_OUT = ({"x": [0.2], "y": [0.0]}, {"x": [0.0], "y": [1.0]})
EASE_IN_OUT = ({"x": [0.37], "y": [0.0]}, {"x": [0.63], "y": [1.0]})
LINEAR = ({"x": [0.0], "y": [0.0]}, {"x": [1.0], "y": [1.0]})


def r(v: float) -> float:
    return round(v, 3)


def static(v):
    return {"a": 0, "k": v}


def anim(frames: list[tuple[int, float]], ease=EASE_OUT):
    """Scalar keyframes [(frame, value), ...]."""
    keys = []
    for i, (t, v) in enumerate(frames):
        k = {"t": t, "s": [v]}
        if i < len(frames) - 1:
            k["o"], k["i"] = ease
        keys.append(k)
    return {"a": 1, "k": keys}


def transform(**kw):
    return {
        "ty": "tr", "nm": "Transform",
        "p": static(kw.get("p", [0, 0])), "a": static([0, 0]), "s": static([100, 100]),
        "r": static(kw.get("r", 0)), "o": static(100), "sk": static(0), "sa": static(0),
    }


def stroke(colour, opacity=100):
    return {"ty": "st", "nm": "Stroke", "c": static(colour), "o": static(opacity),
            "w": static(STROKE), "lc": 2, "lj": 2, "ml": 4, "bm": 0}


def trim(start, end, offset=static(0)):
    return {"ty": "tm", "nm": "Trim", "s": start, "e": end, "o": offset, "m": 1}


def ellipse(cx, cy, radius, start_deg=0.0):
    """4-segment bezier circle, drawn clockwise from start_deg (0 = 12
    o'clock). An explicit path (not "el" + trim offset) renders the same in
    every Lottie player when trimmed."""
    k = 0.5523 * radius
    v, i, o = [], [], []
    for n in range(5):  # open path ending on its first point: no closing segment
        t = math.radians(start_deg + 90 * n)
        x, y = cx + radius * math.sin(t), cy - radius * math.cos(t)
        tx, ty = math.cos(t), math.sin(t)  # clockwise tangent (y down)
        v.append([r(x), r(y)])
        o.append([r(k * tx), r(k * ty)])
        i.append([r(-k * tx), r(-k * ty)])
    return {"ty": "sh", "nm": "Circle", "d": 1, "ks": static({"c": False, "v": v, "i": i, "o": o})}


def polyline(points):
    n = len(points)
    return {"ty": "sh", "nm": "Path", "d": 1, "ks": static({
        "c": False, "v": [[r(x), r(y)] for x, y in points],
        "i": [[0, 0]] * n, "o": [[0, 0]] * n,
    })}


def group(name, items):
    return {"ty": "gr", "nm": name, "it": items + [transform()]}


def layer(name, shapes, op, *, pos, rot=None, ind=1):
    return {
        "ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ao": 0, "bm": 0,
        "ks": {"o": static(100), "r": rot if rot is not None else static(0),
               "p": static([pos[0], pos[1], 0]), "a": static([0, 0, 0]), "s": static([100, 100, 100])},
        "shapes": shapes, "ip": 0, "op": op, "st": 0,
    }


def comp(name, size, op, layers):
    return {"v": "5.7.4", "fr": FR, "ip": 0, "op": op, "w": size, "h": size, "nm": name,
            "ddd": 0, "assets": [], "layers": layers}


RADIUS = 7.5  # arc and check ring share it


def arc():
    op = 144  # 2.4 s
    shapes = [group("arc", [
        ellipse(0, 0, RADIUS),
        stroke(WHITE, 70),
        trim(static(0), anim([(0, 25), (72, 38), (144, 25)], EASE_IN_OUT)),
    ])]
    spin = anim([(0, 0), (op, 360)], LINEAR)
    return comp("anoy_arc", 24, op, [layer("arc", shapes, op, pos=[12, 12], rot=spin)])


def check():
    op = 42  # 0.7 s
    ring = group("ring", [
        ellipse(0, 0, RADIUS),
        stroke(BLUE),
        trim(static(0), anim([(0, 0), (22, 100)])),
    ])
    # Tick, optically centred in the ring (its weight sits left of centre).
    tick = group("tick", [
        polyline([(-3.4, 0.2), (-1.0, 2.6), (3.6, -2.2)]),
        stroke(BLUE),
        trim(static(0), anim([(10, 0), (34, 100)])),
    ])
    return comp("anoy_check", 24, op, [layer("check", [tick, ring], op, pos=[12, 12])])


def main() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    for name, data in (("anoy_arc", arc()), ("anoy_check", check())):
        f = RAW / f"{name}.json"
        f.write_text(json.dumps(data, separators=(",", ":")) + "\n")
        print(f"{f.name}: {f.stat().st_size} bytes, {data['op']} frames @ {FR} fps")


if __name__ == "__main__":
    main()
