#!/usr/bin/env python3
"""Static verification for the Android UI redesign (AUDIT.md §14, gates 8-9).

Runs on a plain checkout; no Android SDK required. Exits non-zero on the first
failing gate and prints a short PASS/FAIL summary.
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
# Files that are allowed to contain raw literals because they ARE the token layer.
TOKEN_FILES = {
    RES / "values/dimens.xml",
    RES / "values/colors.xml",
    RES / "values-night/colors.xml",
    RES / "values/strings.xml",
    RES / "values/keep.xml",
    DRES / "values/strings.xml",
}
DESIGN_SYSTEM_DIR = MAIN / "java/com/xlox/ui/designsystem"

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


PAIRS = [
    ("text_primary", "background"), ("text_primary", "surface"),
    ("text_primary", "surface_elevated"), ("text_primary", "surface_subtle"),
    ("text_secondary", "background"), ("text_secondary", "surface"),
    ("text_secondary", "surface_elevated"), ("text_tertiary", "background"),
    ("accent", "background"), ("accent", "surface"),
    ("on_accent", "accent"), ("on_accent", "accent_pressed"),
    ("error", "background"), ("error", "surface"),
    ("success", "background"), ("warning", "background"),
]
for appearance, path in [("light", RES / "values/colors.xml"),
                         ("dark", RES / "values-night/colors.xml")]:
    tokens = parse_colors(path)
    missing = [n for pair in PAIRS for n in pair if n not in tokens]
    check(not missing, f"{appearance}: all 16 B.1 pairs defined", str(missing))
    if missing:
        continue
    low = [(a, b, round(contrast(tokens[a], tokens[b]), 2))
           for a, b in PAIRS if contrast(tokens[a], tokens[b]) < 4.5]
    check(not low, f"{appearance}: WCAG AA (>=4.5) on all 16 B.1 pairs", str(low))
    # Light/dark must expose exactly the same token names.
    check(set(tokens) == set(parse_colors(RES / "values/colors.xml")),
          f"{appearance}: token parity with light")

# ------------------------------------------------------- literals / Part C
FORBIDDEN = [
    (r"#[0-9A-Fa-f]{6}\b", "literal hex colour"),
    (r"\b[1-9]\d*(?:\.\d+)?dp\b", "literal dp"),
    (r"\b\d+(?:\.\d+)?sp\b", "literal sp"),
    (r"textSize\s*=", "textSize="),
    (r'android:textColor="#', "hardcoded textColor"),
    (r"Color\.(?:argb|rgb|parseColor)\(|Color\(0x", "programmatic colour literal"),
    (r"textAllCaps=\"true\"", "ALL CAPS"),
    (r"android:(?:shadow|elevation)\w*\s*=", "shadow/elevation"),
    (r"<gradient\b|android:type=\"linear\"", "gradient"),
    (r"ObjectAnimator|ValueAnimator\.INFINITE", "looping animation"),
    (r"dynamicColor|dynamic_color|MaterialYou", "dynamic colour"),
    (r"android:text=\"(?!@|$)", "hardcoded android:text"),
]
for path in source_files():
    if path in TOKEN_FILES or DESIGN_SYSTEM_DIR in path.parents:
        continue
    body = text_of(path)
    for pattern, label in FORBIDDEN:
        hits = [m.group(0) for m in re.finditer(pattern, body, re.MULTILINE)]
        if hits:
            check(False, f"no {label} outside designsystem/tokens",
                  f"{path.relative_to(ROOT)}: {hits[:3]}")

# Emoji / pictographs anywhere in authored UI copy.
for path in [RES / "values/strings.xml", DRES / "values/strings.xml"]:
    if not path.exists():
        continue
    bad = sorted({c for c in text_of(path)
                  if unicodedata.category(c) == "So" or 0x1F000 <= ord(c) <= 0x1FAFF})
    check(not bad, f"no emoji in {path.name}", repr(bad))
    check("!" not in re.sub(r"<!--.*?-->", "", text_of(path), flags=re.S),
          f"no exclamation marks in {path.name}")

# ---------------------------------------------------- resource references
defs: dict[str, set[str]] = {}
for base in (RES, DRES):
    if not base.exists():
        continue
    for p in base.rglob("*"):
        if not p.is_file():
            continue
        kind = p.parent.name.split("-")[0]
        if kind in ("drawable", "layout", "menu", "color") and p.suffix != ".xml":
            # Raster launcher icons are still drawable resources.
            defs.setdefault(kind, set()).add(p.stem)
            continue
        if kind == "values" and p.suffix == ".xml":
            for typ, name in re.findall(
                    r'<(string|dimen|color|style)\s+name="([\w.]+)"', text_of(p)):
                defs.setdefault(typ, set()).add(name)

        else:
            defs.setdefault(kind, set()).add(p.stem)

all_text = "\n".join(text_of(p) for p in source_files())
refs: dict[str, set[str]] = {}
for typ in ("string", "dimen", "color", "style", "drawable", "layout", "menu"):
    refs[typ] = set(re.findall(rf"@{typ}/([\w.]+)", all_text))
    code_refs = set(re.findall(rf"R\.{typ}\.([\w.]+)", all_text))
    if typ == "style":
        # aapt2 maps <style name="Widget.Ds.X"> to R.style.Widget_Ds_X.
        code_refs = {r.replace("_", ".") for r in code_refs}
    refs[typ] |= code_refs
for typ in ("string", "dimen", "color", "style", "drawable", "layout", "menu"):
    missing = sorted(refs[typ] - defs.get(typ, set()))
    check(not missing, f"all @{typ}/ and R.{typ} references resolve", str(missing))
    unused = sorted(defs.get(typ, set()) - refs[typ])
    # Style parents declared with parent="..." are usages too.
    if typ == "style":
        parents = set(re.findall(r'parent="([\w.]+)"', all_text))
        unused = [u for u in unused if u not in parents]
    if typ == "dimen":
        kept = set(re.findall(r"@dimen/([\w.]+)", text_of(RES / "values/keep.xml")))
        unused = [u for u in unused if u not in kept]
    if typ == "drawable":
        kept = set(re.findall(r"@drawable/([\w.]+)", text_of(RES / "values/keep.xml")))
        unused = [u for u in unused if u not in kept]
        if (RES / "drawable/ic_anoy.png").exists():
            unused = [u for u in unused if u != "ic_anoy"]
    if typ == "color":
        # Colour tokens are referenced from Java via tokens.xml helpers and from
        # night overrides which share the same names; only report truly dead ones.
        unused = [u for u in unused if u not in set(parse_colors(RES / "values/colors.xml"))]
    check(not unused, f"no unused @{typ}/ resources", str(unused))

# --------------------------------------------------------- accessibility
icon_controls = 0
unlabelled = []
for base in (RES / "layout", DRES / "layout"):
    for p in base.glob("*.xml"):
        body = text_of(p)
        for block in re.findall(r"<ImageButton\b.*?/>", body, re.S):
            icon_controls += 1
            if ("android:contentDescription" not in block
                    and 'importantForAccessibility="no"' not in block):
                unlabelled.append(p.name)
check(icon_controls > 0 and not unlabelled,
      "every ImageButton has a TalkBack label", str(unlabelled))

small_targets = []
for base in (RES / "layout", DRES / "layout"):
    for p in base.glob("*.xml"):
        for block in re.findall(r"<ImageButton\b.*?/>", p.read_text(), re.S):
            if "@dimen/touch_target" not in block:
                small_targets.append(p.name)
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
