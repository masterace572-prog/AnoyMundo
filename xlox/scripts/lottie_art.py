#!/usr/bin/env python3
"""Builds the Lottie resources shipped by the Anoy Android app.

    python3 scripts/lottie_art.py

`anoy_arc.json` and `anoy_check.json` are original small status animations.
`anoy_splash.json` is a black-and-white, rebranded derivative of the Techpost
animation selected by the owner. Its untouched source is kept at
`third_party/lottie/techpost.json`; see assets/licenses/LOTTIE.md.

The splash source is 30 fps and contains the vendor wordmark as vector paths.
The build script doubles its timeline to 60 fps, recolours it to the Anoy
black-and-white palette, and rebuilds the source's final word-vector slots as
Inter SemiBold outlines for "AnoyHax" (including its large `T` → `A` reveal).
This keeps all motion local and offline, with no overlay or added text layer.
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
SPLASH_GLYPHS = ROOT / "scripts/anoy_splash_glyphs.json"
FR = 60
STROKE = 1.75
WHITE = [1, 1, 1, 1]
BLACK = [0, 0, 0, 1]
TECHPOST_SHA256 = "4e50ebe64cc4d931082950639b13bdd2c0a92952c8254367b28638e72c5a8565"
INTER_SEMIBOLD_SHA256 = "6be4b4f246f52fa13471cbb80df1d9c520c269992059abaed894bbe5080aa7a3"

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


def static_shape_paths(node: Any) -> list[tuple[list[dict[str, Any]], int, dict[str, Any]]]:
    """Find static vector paths with the parent list and index needed to replace them."""
    found = []
    if isinstance(node, dict):
        for value in node.values():
            if isinstance(value, list):
                for index, item in enumerate(value):
                    if isinstance(item, dict) and item.get("ty") == "sh" \
                            and item.get("ks", {}).get("a") == 0 \
                            and isinstance(item.get("ks", {}).get("k", {}).get("v"), list):
                        found.append((value, index, item))
                    found.extend(static_shape_paths(item))
            elif isinstance(value, dict):
                found.extend(static_shape_paths(value))
    elif isinstance(node, list):
        for value in node:
            found.extend(static_shape_paths(value))
    return found


def bounds(paths: list[dict[str, Any]]) -> tuple[float, float, float, float]:
    points = [point for path in paths for point in path["v"]]
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return min(xs), min(ys), max(xs), max(ys)


def remap_contour(contour: dict[str, Any], source_bounds: tuple[float, float, float, float],
                  target_bounds: tuple[float, float, float, float]) -> dict[str, Any]:
    """Maps an Inter outline (y-up) into an existing Lottie glyph box (y-down)."""
    sx0, sy0, sx1, sy1 = source_bounds
    tx0, ty0, tx1, ty1 = target_bounds
    sw = max(1.0, sx1 - sx0)
    sh = max(1.0, sy1 - sy0)
    tw = tx1 - tx0
    th = ty1 - ty0

    def vertex(point: list[float]) -> list[float]:
        return [round(tx0 + (point[0] - sx0) * tw / sw, 3),
                round(ty0 + (sy1 - point[1]) * th / sh, 3)]

    def tangent(vector: list[float]) -> list[float]:
        return [round(vector[0] * tw / sw, 3), round(-vector[1] * th / sh, 3)]

    return {"i": [tangent(v) for v in contour["i"]],
            "o": [tangent(v) for v in contour["o"]],
            "v": [vertex(v) for v in contour["v"]], "c": True}


def load_glyphs() -> dict[str, list[dict[str, Any]]]:
    data = json.loads(SPLASH_GLYPHS.read_text())
    if data.get("source_sha256") != INTER_SEMIBOLD_SHA256 or data.get("units_per_em") != 2048:
        raise RuntimeError("Unexpected cached Inter glyph data")
    glyphs = data.get("glyphs", {})
    if set(glyphs) != set("AnoyHax"):
        raise RuntimeError("Cached splash glyphs must contain exactly AnoyHax")
    return glyphs


def replace_single_glyph_path(layer_data: dict[str, Any], char: str,
                              glyphs: dict[str, list[dict[str, Any]]]) -> None:
    """Swap the one source path used by the later standalone `T` → `A` reveal."""
    entries = static_shape_paths(layer_data.get("shapes", []))
    if not entries:
        raise RuntimeError(f"No editable vector path in source layer {layer_data.get('nm')}")
    source_paths = [entry[2]["ks"]["k"] for entry in entries]
    target_bounds = bounds(source_paths)
    glyph = glyphs[char]
    glyph_bounds = bounds(glyph)
    parent, index, original = entries[0]
    replacements = []
    for n, contour in enumerate(glyph):
        path = deepcopy(original)
        path["nm"] = f"Anoy {char} outline {n + 1}"
        path["ks"] = static(remap_contour(contour, glyph_bounds, target_bounds))
        replacements.append(path)
    parent[index:index + 1] = replacements
    # A source standalone mark carries a single path. Keep the replacement
    # self-contained if a future source revision adds hidden helper paths.
    for extra_parent, _, path in reversed(entries[1:]):
        if path in extra_parent:
            extra_parent.remove(path)


def vector_glyph_group(char: str, contours: list[dict[str, Any]],
                       target_bounds: tuple[float, float, float, float]) -> dict[str, Any]:
    """One edited source shape group: outline paths plus the source's white fill."""
    source_bounds = bounds(contours)
    items = []
    for number, contour in enumerate(contours):
        items.append({
            "ty": "sh", "nm": f"Anoy {char} outline {number + 1}", "d": 1,
            "ks": static(remap_contour(contour, source_bounds, target_bounds)),
        })
    items.extend([
        {"ty": "fl", "nm": "Fill", "c": static(WHITE), "o": static(100), "r": 1, "bm": 0},
        transform(),
    ])
    return {"ty": "gr", "nm": f"Edited source glyph {char}", "np": len(items) - 1,
            "cix": 2, "bm": 0, "ix": 1, "it": items, "hd": False}


def glyph_layer_transform(start: int, end: int) -> dict[str, Any]:
    """A short, source-timeline fade for each directly edited word slot."""
    return {
        "o": {"a": 1, "k": [
            {"t": start, "s": [0], "h": 1},
            {"t": start + 5, "s": [100], "o": {"x": [0.2], "y": [0]},
             "i": {"x": [0], "y": [1]}},
            {"t": end, "s": [100]},
        ]},
        "r": static(0), "p": static([0, 0, 0]), "a": static([0, 0, 0]),
        "s": static([100, 100, 100]),
    }


def rebrand_wordmark(data: dict[str, Any]) -> None:
    """Replace the source artwork with seven collision-free vector glyph slots.

    Techpost's construction uses paired alpha-matte paths for counters in `p`
    and `o`. Swapping every one of those paths for a complete Inter glyph made
    the two complete outlines paint on top of one another. Instead, the seven
    *existing final-word layers* are directly rebuilt as independent vector
    slots. The original supporting/matte layers are disabled, never covered;
    no text layer or extra logo layer is introduced.
    """
    glyphs = load_glyphs()
    by_id = {layer.get("ind"): layer for layer in data["layers"]}

    # These are all of the original word-construction paths and their alpha
    # mattes. The checksum above pins this source topology. The underline (3)
    # and final standalone mark (4/5) are deliberately not in this range.
    for index in range(7, 81):
        source_layer = by_id.get(index)
        if source_layer is not None and source_layer.get("ty") == 4:
            source_layer["ip"] = source_layer["op"]
            source_layer["nm"] = "replaced source word construction"

    # The clean, final source slots run left-to-right. Reuse those exact vector
    # layers rather than adding artwork above the selected animation. Coordinates
    # are on the original 1080 x 2040 Lottie canvas and match its wordmark box.
    # The original standalone T starts its own reveal at source frame 180. The
    # word completes just before then, ensuring the clean AnoyHax word and the
    # later A reveal never occupy the canvas together.
    word_end = 181
    slots = [
        (27, "A", 136), (23, "n", 141), (21, "o", 143), (19, "y", 145),
        (15, "H", 147), (11, "a", 149), (9, "x", 151),
    ]
    x, baseline, scale, gap = 184.0, 1050.0, 0.085, 8.0
    for index, char, start in slots:
        glyph_bounds = bounds(glyphs[char])
        gx0, gy0, gx1, gy1 = glyph_bounds
        target_bounds = (x, baseline - gy1 * scale, x + (gx1 - gx0) * scale,
                         baseline - gy0 * scale)
        source_layer = by_id[index]
        source_layer["nm"] = f"AnoyHax {char} (edited source slot)"
        source_layer["ip"] = start
        source_layer["op"] = word_end
        source_layer["st"] = 0
        source_layer.pop("parent", None)
        source_layer.pop("tt", None)
        source_layer.pop("td", None)
        source_layer["ks"] = glyph_layer_transform(start, word_end)
        source_layer["shapes"] = [vector_glyph_group(char, glyphs[char], target_bounds)]
        x = target_bounds[2] + gap

    # Retain the selected animation's later standalone logo timing, editing its
    # two existing vector paths from T to A in-place as well.
    for index in (4, 5):
        source_layer = by_id[index]
        replace_single_glyph_path(source_layer, "A", glyphs)
        source_layer["nm"] = "A" if index == 4 else "A reveal matte"
    # Delay the source's standalone T animation until the word slots are gone;
    # its original keyframes begin at 180, so it still uses the native motion.
    by_id[4]["ip"] = word_end


def techpost_splash() -> dict[str, Any]:
    raw = TECHPOST_SOURCE.read_bytes()
    actual = hashlib.sha256(raw).hexdigest()
    if actual != TECHPOST_SHA256:
        raise RuntimeError(f"Unexpected Techpost source checksum: {actual}")
    data = json.loads(raw)
    if (data.get("fr"), data.get("w"), data.get("h")) != (30, 1080, 2040):
        raise RuntimeError("Unexpected Techpost source metadata")

    data = deepcopy(data)
    rebrand_wordmark(data)
    scale_timeline(data)
    data["fr"] = FR
    data["nm"] = "anoy_splash"

    # The source's green backgrounds become true black. Every edited vector
    # foreground becomes white; there is no added text, view or matte layer.
    for source_layer in data["layers"]:
        recolour(source_layer, BLACK if source_layer.get("nm") in {"BG", "BG 2"} else WHITE)
    data["assets"] = []
    data.pop("fonts", None)
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
