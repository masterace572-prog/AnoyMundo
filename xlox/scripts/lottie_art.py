#!/usr/bin/env python3
"""Builds the Lottie resources shipped by the Anoy Android app.

    python3 scripts/lottie_art.py

`anoy_arc.json` and `anoy_check.json` are original small status animations.
`anoy_splash.json` is a black-and-white, rebranded derivative of the Techpost
animation selected by the owner. Its untouched source is kept at
`third_party/lottie/techpost.json`; see assets/licenses/LOTTIE.md.

The splash source is 30 fps and contains the vendor wordmark as vector paths.
The build script doubles its timeline to 60 fps, recolours the source to the
Anoy black-and-white palette, covers the third-party wordmark before it is
shown, and adds an animated Lottie text layer reading "Anoy Hax". This keeps
all animation local and offline, and never displays another product's name.
"""
from __future__ import annotations

import hashlib
import json
import math
from copy import deepcopy
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "app/src/main/res/raw"
TECHPOST_SOURCE = ROOT / "third_party/lottie/techpost.json"
FR = 60
STROKE = 1.75
WHITE = [1, 1, 1, 1]
BLACK = [0, 0, 0, 1]
TECHPOST_SHA256 = "4e50ebe64cc4d931082950639b13bdd2c0a92952c8254367b28638e72c5a8565"

# Ease out without overshoot (cubic-bezier 0.2, 0, 0, 1) and a gentle in-out.
EASE_OUT = ({"x": [0.2], "y": [0.0]}, {"x": [0.0], "y": [1.0]})
EASE_IN_OUT = ({"x": [0.37], "y": [0.0]}, {"x": [0.63], "y": [1.0]})
LINEAR = ({"x": [0.0], "y": [0.0]}, {"x": [1.0], "y": [1.0]})


def r(v: float) -> float:
    return round(v, 3)


def static(v: Any) -> dict[str, Any]:
    return {"a": 0, "k": v}


def anim(frames: list[tuple[int, float]], ease=EASE_OUT) -> dict[str, Any]:
    """Scalar keyframes [(frame, value), ...]."""
    keys = []
    for i, (t, v) in enumerate(frames):
        k: dict[str, Any] = {"t": t, "s": [v]}
        if i < len(frames) - 1:
            k["o"], k["i"] = ease
        keys.append(k)
    return {"a": 1, "k": keys}


def transform(**kw: Any) -> dict[str, Any]:
    return {
        "ty": "tr", "nm": "Transform",
        "p": static(kw.get("p", [0, 0])), "a": static([0, 0]), "s": static([100, 100]),
        "r": static(kw.get("r", 0)), "o": static(100), "sk": static(0), "sa": static(0),
    }


def layer_transform(*, opacity: dict[str, Any] | None = None, position: Any = None,
                    anchor: Any = None, scale: Any = None) -> dict[str, Any]:
    return {
        "o": opacity or static(100),
        "r": static(0),
        "p": static(position or [0, 0, 0]),
        "a": static(anchor or [0, 0, 0]),
        "s": scale or static([100, 100, 100]),
    }


def stroke(colour: list[float], opacity: int = 100) -> dict[str, Any]:
    return {"ty": "st", "nm": "Stroke", "c": static(colour), "o": static(opacity),
            "w": static(STROKE), "lc": 2, "lj": 2, "ml": 4, "bm": 0}


def trim(start: dict[str, Any], end: dict[str, Any], offset: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"ty": "tm", "nm": "Trim", "s": start, "e": end, "o": offset or static(0), "m": 1}


def ellipse(cx: float, cy: float, radius: float, start_deg: float = 0.0) -> dict[str, Any]:
    """4-segment Bezier circle, clockwise from 12 o'clock, with no closing edge."""
    k = 0.5523 * radius
    v, i, o = [], [], []
    for n in range(5):
        t = math.radians(start_deg + 90 * n)
        x, y = cx + radius * math.sin(t), cy - radius * math.cos(t)
        tx, ty = math.cos(t), math.sin(t)
        v.append([r(x), r(y)])
        o.append([r(k * tx), r(k * ty)])
        i.append([r(-k * tx), r(-k * ty)])
    return {"ty": "sh", "nm": "Circle", "d": 1,
            "ks": static({"c": False, "v": v, "i": i, "o": o})}


def polyline(points: list[tuple[float, float]]) -> dict[str, Any]:
    n = len(points)
    return {"ty": "sh", "nm": "Path", "d": 1, "ks": static({
        "c": False, "v": [[r(x), r(y)] for x, y in points],
        "i": [[0, 0]] * n, "o": [[0, 0]] * n,
    })}


def group(name: str, items: list[dict[str, Any]]) -> dict[str, Any]:
    return {"ty": "gr", "nm": name, "it": items + [transform()]}


def layer(name: str, shapes: list[dict[str, Any]], op: int, *, pos: tuple[float, float],
          rot: dict[str, Any] | None = None, ind: int = 1) -> dict[str, Any]:
    return {
        "ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ao": 0, "bm": 0,
        "ks": {"o": static(100), "r": rot if rot is not None else static(0),
               "p": static([pos[0], pos[1], 0]), "a": static([0, 0, 0]), "s": static([100, 100, 100])},
        "shapes": shapes, "ip": 0, "op": op, "st": 0,
    }


def comp(name: str, size: int, op: int, layers: list[dict[str, Any]]) -> dict[str, Any]:
    return {"v": "5.7.4", "fr": FR, "ip": 0, "op": op, "w": size, "h": size, "nm": name,
            "ddd": 0, "assets": [], "layers": layers}


RADIUS = 7.5


def arc() -> dict[str, Any]:
    op = 144  # 2.4 s
    shapes = [group("arc", [
        ellipse(0, 0, RADIUS),
        stroke(WHITE, 70),
        trim(static(0), anim([(0, 25), (72, 38), (144, 25)], EASE_IN_OUT)),
    ])]
    spin = anim([(0, 0), (op, 360)], LINEAR)
    return comp("anoy_arc", 24, op, [layer("arc", shapes, op, pos=(12, 12), rot=spin)])


def check() -> dict[str, Any]:
    op = 42  # 0.7 s
    ring = group("ring", [
        ellipse(0, 0, RADIUS),
        stroke([10 / 255, 132 / 255, 1, 1]),
        trim(static(0), anim([(0, 0), (22, 100)])),
    ])
    tick = group("tick", [
        polyline([(-3.4, 0.2), (-1.0, 2.6), (3.6, -2.2)]),
        stroke([10 / 255, 132 / 255, 1, 1]),
        trim(static(0), anim([(10, 0), (34, 100)])),
    ])
    return comp("anoy_check", 24, op, [layer("check", [tick, ring], op, pos=(12, 12))])


def scale_timeline(node: Any) -> None:
    """Change a 30 fps Lottie timeline to an equivalent 60 fps timeline."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key in {"ip", "op", "st", "t"} and isinstance(value, (int, float)):
                node[key] = value * 2
            else:
                scale_timeline(value)
    elif isinstance(node, list):
        for value in node:
            scale_timeline(value)


def set_colour(prop: dict[str, Any], colour: list[float]) -> None:
    """Recolour a static or keyframed Lottie fill/stroke property in place."""
    if prop.get("a") == 0 and isinstance(prop.get("k"), list):
        prop["k"] = colour
    elif prop.get("a") == 1 and isinstance(prop.get("k"), list):
        for frame in prop["k"]:
            if not isinstance(frame, dict):
                continue
            for key in ("s", "e"):
                if isinstance(frame.get(key), list):
                    frame[key] = colour


def recolour(node: Any, colour: list[float]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "c" and isinstance(value, dict):
                set_colour(value, colour)
            else:
                recolour(value, colour)
    elif isinstance(node, list):
        for value in node:
            recolour(value, colour)


def full_screen_blackout() -> dict[str, Any]:
    """Masks the source wordmark before it appears, so only Anoy branding shows."""
    return {
        "ddd": 0, "ind": 92, "ty": 4, "nm": "Anoy brand blackout", "sr": 1, "ao": 0, "bm": 0,
        "ks": layer_transform(), "ip": 268, "op": 482, "st": 0,
        "shapes": [
            {"ty": "rc", "nm": "Black field", "d": 1,
             "p": static([0, 0]), "s": static([1080, 2040]), "r": static(0)},
            {"ty": "fl", "nm": "Fill", "c": static(BLACK), "o": static(100), "r": 1, "bm": 0},
            transform(),
        ],
    }


def anoy_brand_text() -> dict[str, Any]:
    """A true Lottie text layer, not a native TextView, for the rebranded final state."""
    opacity = {"a": 1, "k": [
        {"t": 268, "s": [0], "h": 1},
        {"t": 302, "s": [100], "o": {"x": [0.2], "y": [0]}, "i": {"x": [0], "y": [1]}},
        {"t": 482, "s": [100]},
    ]}
    scale = {"a": 1, "k": [
        {"t": 268, "s": [94, 94, 100], "h": 1},
        {"t": 320, "s": [100, 100, 100]},
    ]}
    document = {
        "sz": [1080, 150], "ps": [-540, -100], "s": 112,
        "f": "Inter-SemiBold", "t": "Anoy Hax", "j": 2, "tr": -3,
        "lh": 136, "ls": 0, "fc": [1, 1, 1], "of": False,
    }
    return {
        "ddd": 0, "ind": 91, "ty": 5, "nm": "Anoy Hax", "sr": 1, "ao": 0, "bm": 0,
        "ks": layer_transform(opacity=opacity, position=[540, 1020, 0], scale=scale),
        "ip": 268, "op": 482, "st": 0,
        "t": {"d": {"k": [{"s": document, "t": 268}]}, "p": {},
              "m": {"g": 1, "a": static([0, 0])}},
    }


def techpost_splash() -> dict[str, Any]:
    raw = TECHPOST_SOURCE.read_bytes()
    actual = hashlib.sha256(raw).hexdigest()
    if actual != TECHPOST_SHA256:
        raise RuntimeError(f"Unexpected Techpost source checksum: {actual}")
    data = json.loads(raw)
    if (data.get("fr"), data.get("w"), data.get("h")) != (30, 1080, 2040):
        raise RuntimeError("Unexpected Techpost source metadata")

    data = deepcopy(data)
    scale_timeline(data)
    data["fr"] = FR
    data["nm"] = "anoy_splash"

    # The source's green backgrounds become true black. Every foreground shape
    # becomes white to match the app's restrained, monochrome splash language.
    for source_layer in data["layers"]:
        recolour(source_layer, BLACK if source_layer.get("nm") in {"BG", "BG 2"} else WHITE)

    # At frame 134 (2.23 s) the original starts assembling its own wordmark.
    # A black Lottie layer cleanly takes over then our Lottie text layer arrives.
    data["layers"] = [anoy_brand_text(), full_screen_blackout()] + data["layers"]
    data["fonts"] = {"list": [{
        "fName": "Inter-SemiBold", "fFamily": "Inter", "fStyle": "SemiBold", "ascent": 75,
    }]}
    data["assets"] = []
    return data


def write(name: str, data: dict[str, Any]) -> None:
    f = RAW / f"{name}.json"
    f.write_text(json.dumps(data, separators=(",", ":")) + "\n")
    print(f"{f.name}: {f.stat().st_size} bytes, {data['op']} frames @ {data['fr']} fps")


def main() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    write("anoy_arc", arc())
    write("anoy_check", check())
    write("anoy_splash", techpost_splash())


if __name__ == "__main__":
    main()
