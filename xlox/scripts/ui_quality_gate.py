#!/usr/bin/env python3
"""Static verification for the Android v2 UI redesign.

Runs on a plain checkout; no Android SDK required. Exits non-zero when a gate
fails and prints a short PASS/FAIL summary.
"""
from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # xlox/
MAIN = ROOT / "app/src/main"
DEBUG = ROOT / "app/src/debug"
RES = MAIN / "res"
DRES = DEBUG / "res"

SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".ttf", ".otf", ".aar", ".so", ".jks"}
DESIGN_SYSTEM_DIR = MAIN / "java/com/xlox/ui/designsystem"

# The token layer and resource definitions are the only places raw values may
# appear. Everything else (Java + layouts) must resolve through tokens.
TOKEN_DIRS = (RES / "color", RES / "drawable", RES / "anim", DRES / "drawable")
TOKEN_FILES = {
    RES / "values/dimens.xml",
    RES / "values/colors.xml",
    RES / "values-night/colors.xml",
    RES / "values/strings.xml",
    RES / "values/styles.xml",
    RES / "values/attrs.xml",
    RES / "values/keep.xml",
    RES / "values-v23/styles.xml",
    DRES / "values/strings.xml",
}

failures: list[str] = []
checks = 0


def check(ok: bool, label: str, detail: str = "") -> None:
    global checks
    checks += 1
    if not ok:
        failures.append(f"{label}: {detail}" if detail else label)
    print(("PASS  " if ok else "FAIL  ") + label + (f"  {detail}" if detail and not ok else ""))


def source_files():
    out = []
    for base in (MAIN, DEBUG):
        if not base.exists():
            continue
        for p in sorted(base.rglob("*")):
            if p.is_file() and p.suffix not in SKIP_SUFFIXES:
                out.append(p)
    return out


def text_of(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def in_token_layer(path: Path) -> bool:
    return path in TOKEN_FILES or DESIGN_SYSTEM_DIR in path.parents or any(
        d in path.parents for d in TOKEN_DIRS)


# ---------------------------------------------------------------- contrast
def parse_colors(path: Path) -> dict[str, str]:
    return {
        name: value.upper()
        for name, value in re.findall(
            r'<color name="([\w]+)">(#[\dA-Fa-f]{6,8})</color>', text_of(path)
        )
    }


def luminance(hex_color: str) -> float:
    channels = [int(hex_color.lstrip("#")[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    linear = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
              for c in channels]
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


def contrast(a: str, b: str) -> float:
    x, y = sorted((luminance(a), luminance(b)), reverse=True)
    return (x + 0.05) / (y + 0.05)


# "accent" is a fill token; its text counterpart is "accent_ink". The fill is
# always paired with "on_accent", which is checked explicitly below.
PAIRS = [
    ("text_primary", "background"), ("text_primary", "surface"),
    ("text_primary", "surface_elevated"), ("text_primary", "surface_recessed"),
    ("text_secondary", "background"), ("text_secondary", "surface"),
    ("text_secondary", "surface_recessed"), ("text_tertiary", "background"),
    ("icon", "surface"), ("accent_ink", "background"),
    ("on_accent", "accent"), ("on_accent", "accent_pressed"),
    ("success_ink", "background"), ("warning_ink", "background"),
    ("error_ink", "background"), ("success_ink", "surface_recessed"),
]
light = parse_colors(RES / "values/colors.xml")
for appearance, path in [("light", RES / "values/colors.xml"),
                         ("dark", RES / "values-night/colors.xml")]:
    tokens = parse_colors(path)
    check(set(tokens) == set(light), f"{appearance}: token parity with light",
          str(sorted(set(tokens) ^ set(light))))
    missing = [n for pair in PAIRS for n in pair if n not in tokens]
    check(not missing, f"{appearance}: all 16 contrast pairs defined", str(missing))
    if missing:
        continue
    low = [(a, b, round(contrast(tokens[a], tokens[b]), 2))
           for a, b in PAIRS if contrast(tokens[a], tokens[b]) < 4.5]
    check(not low, f"{appearance}: WCAG AA (>=4.5) on all 16 pairs", str(low))

# ------------------------------------------------------- literals / leftovers
FORBIDDEN = [
    (r"#[0-9A-Fa-f]{6}\b", "literal hex colour"),
    (r"\b[1-9]\d*(?:\.\d+)?dp\b", "literal dp"),
    (r"\b\d+(?:\.\d+)?sp\b", "literal sp"),
    (r"textSize\s*=", "textSize="),
    (r'android:textColor="#', "hardcoded textColor"),
    (r"Color\.(?:argb|rgb|parseColor)\(|Color\(0x", "programmatic colour literal"),
    (r"android:text=\"(?!@|$)", "hardcoded android:text"),
    (r"android:(?:shadow\w*|elevation)\s*=", "shadow/elevation"),
    (r"ObjectAnimator\b|ValueAnimator\.INFINITE", "looping animation"),
    (r"dynamicColor|dynamic_color|MaterialYou", "dynamic colour"),
    (r"<emoji|[\U0001F300-\U0001FAFF]", "emoji"),
]
for path in source_files():
    if in_token_layer(path):
        continue
    body = text_of(path)
    body = re.sub(r"<!--.*?-->", "", body, flags=re.S)
    body = re.sub(r"^\s*//.*$", "", body, flags=re.M)
    for pattern, label in FORBIDDEN:
        hits = [m.group(0) for m in re.finditer(pattern, body, re.MULTILINE)]
        if hits:
            check(False, f"no {label} outside tokens/designsystem",
                  f"{path.relative_to(ROOT)}: {hits[:3]}")

for path in [RES / "values/strings.xml", DRES / "values/strings.xml"]:
    if not path.exists():
        continue
    copy = re.sub(r"<!--.*?-->", "", text_of(path), flags=re.S)
    bad = sorted({c for c in copy
                  if unicodedata.category(c) == "So" or 0x1F000 <= ord(c) <= 0x1FAFF})
    check(not bad, f"no emoji in {path.parent.parent.name}/{path.name}", repr(bad))
    check("!" not in copy, f"no exclamation marks in {path.name}")

# ---------------------------------------------------- resource references
defs: dict[str, set[str]] = {}
for base in (RES, DRES):
    if not base.exists():
        continue
    for p in base.rglob("*"):
        if not p.is_file():
            continue
        kind = p.parent.name.split("-")[0]
        if kind in ("drawable", "layout", "menu", "color", "anim", "font") and p.suffix != ".xml":
            defs.setdefault(kind, set()).add(p.stem)
            continue
        if p.suffix != ".xml":
            continue
        if kind == "values":
            for typ, name in re.findall(
                    r'<(string|dimen|color|style)\s+name="([\w.]+)"', text_of(p)):
                defs.setdefault(typ, set()).add(name)
        else:
            defs.setdefault(kind, set()).add(p.stem)

all_text = "\n".join(text_of(p) for p in source_files())
TYPES = ("string", "dimen", "color", "style", "drawable", "layout", "menu",
         "anim", "font")
refs: dict[str, set[str]] = {}
for typ in TYPES:
    refs[typ] = set(re.findall(rf"@{typ}/([\w.]+)", all_text))
    code_refs = set(re.findall(rf"R\.{typ}\.([\w.]+)", all_text))
    if typ == "style":
        # aapt2 maps <style name="Widget.Ds.X"> to R.style.Widget_Ds_X.
        code_refs = {r.replace("_", ".") for r in code_refs}
    refs[typ] |= code_refs

kept_text = text_of(RES / "values/keep.xml")
kept = {k: set(re.findall(rf"@{k}/([\w.]+)", kept_text)) for k in TYPES}
for typ in TYPES:
    missing = sorted(refs[typ] - defs.get(typ, set()))
    check(not missing, f"all @{typ}/ and R.{typ} references resolve", str(missing))
    unused = sorted(defs.get(typ, set()) - refs[typ] - kept.get(typ, set()))
    if typ == "style":
        parents = set(re.findall(r'parent="([\w.]+)"', all_text))
        unused = [u for u in unused if u not in parents]
    if typ == "color":
        unused = [u for u in unused if u not in set(light)]
    if typ == "drawable" and (RES / "drawable/ic_anoy.png").exists():
        unused = [u for u in unused if u != "ic_anoy"]
    check(not unused, f"no unused @{typ}/ resources", str(unused))

# --------------------------------------------------- vector path grammar
# Android's PathParser rejects malformed argument counts at *inflation* time,
# which lint does not always catch. Validate every pathData command here.
_CMD = set("MmLlHhVvCcSsQqTtAaZz")
_ARITY = {"M": 2, "L": 2, "T": 2, "H": 1, "V": 1,
          "C": 6, "S": 4, "Q": 4, "A": 7, "Z": 0}
_NUM = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def path_error(data: str):
    tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?", data)
    i, cmd = 0, None
    while i < len(tokens):
        if tokens[i] in _CMD:
            cmd = tokens[i].upper()
            i += 1
            if cmd == "Z":
                continue
        if cmd is None:
            return "number before any command"
        need = _ARITY[cmd]
        if i + need > len(tokens) or any(t in _CMD for t in tokens[i:i + need]):
            return f"{cmd} needs {need} floats near {''.join(tokens[max(0, i - 1):i + need])}"
        i += need
        if cmd == "M":
            cmd = "L"
        elif cmd == "m":
            cmd = "l"
    return None


bad_paths = []
for p in sorted((RES / "drawable").glob("ds_ic_*.xml")):
    for data in re.findall(r'android:pathData="([^"]*)"', p.read_text()):
        err = path_error(data)
        if err:
            bad_paths.append(f"{p.name}: {err}")
check(not bad_paths, "every Lucide vector pathData parses", str(bad_paths))

# ---------------------------------------------------- spring animation sanity
# A bare SpringForce has an UNSET (Double.MAX_VALUE) rest position; installing
# it on a SpringAnimation throws at start() and crashes every screen launch.
motion = DESIGN_SYSTEM_DIR / "DsMotion.java"
motion_text = text_of(motion) if motion.exists() else ""
check("new SpringForce(finalPosition)" in motion_text,
      "every spring force has an explicit final position")
check("startSpring(" in motion_text,
      "spring starts are wrapped in a non-fatal fallback")

# --------------------------------------------------------- accessibility
icon_controls = 0
unlabelled = []
small_targets = []
for base in (RES / "layout", DRES / "layout"):
    for p in base.glob("*.xml"):
        body = text_of(p)
        for block in re.findall(r"<ImageButton\b.*?/>", body, re.S):
            icon_controls += 1
            if ("android:contentDescription" not in block
                    and 'importantForAccessibility="no"' not in block):
                unlabelled.append(p.name)
            if "@dimen/touch_target" not in block:
                small_targets.append(p.name)
check(icon_controls > 0 and not unlabelled,
      "every ImageButton has a TalkBack label", str(unlabelled))
check(not small_targets, "icon buttons use the 48dp target token", str(small_targets))

# ------------------------------------------------------------ final summary
print()
print(f"{checks - len(failures)}/{checks} checks passed")
if failures:
    print("\nFAILURES:")
    for f in failures:
        print(" -", f)
    sys.exit(1)
print("UI quality gates: PASS")
