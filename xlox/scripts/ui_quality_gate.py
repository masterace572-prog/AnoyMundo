#!/usr/bin/env python3
"""Static checks for the Android black/blue UI (v2.0).

Runs on a plain checkout (no Android SDK needed). Exits non-zero when a gate
fails and prints a PASS/FAIL line for every gate.

Gates
  1. Every resource referenced from Java / XML exists.
  2. Format strings get the right number of arguments in getString().
  3. No app logo anywhere: no ic_anoy / logo images, no bitmap resources.
  4. Colours only come from the palette in values/colors.xml.
  5. Text never gets its own background (TextView / text styles).
  6. Window and screens are black, primary text is white, buttons are blue.
  7. Layouts contain no hardcoded user-visible text or raw text sizes.
  8. Removed pieces stay removed (Material, splashscreen, FacebookActivity, designsystem).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.dom import minidom

ROOT = Path(__file__).resolve().parents[1]  # xlox/
APP = ROOT / "app"
MAIN = APP / "src/main"
RES = MAIN / "res"
JAVA = MAIN / "java"

failures: list[str] = []
checks = 0


def gate(name: str, problems: list[str]) -> None:
    global checks
    checks += 1
    if problems:
        failures.append(name)
        print(f"FAIL  {name}")
        for p in problems[:25]:
            print(f"        - {p}")
        if len(problems) > 25:
            print(f"        ... and {len(problems) - 25} more")
    else:
        print(f"PASS  {name}")


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT))


xml_files = sorted(RES.rglob("*.xml")) + [MAIN / "AndroidManifest.xml"]
java_files = sorted(JAVA.rglob("*.java"))
layouts = sorted(RES.glob("layout*/*.xml"))

# ---------------------------------------------------------------- inventory
defined: dict[str, set[str]] = {}
strings: dict[str, str] = {}
palette: dict[str, str] = {}


def define(kind: str, name: str) -> None:
    defined.setdefault(kind, set()).add(name)


parse_errors = []
for f in sorted(RES.glob("values*/*.xml")):
    try:
        doc = minidom.parse(str(f))
    except Exception as exc:  # noqa: BLE001
        parse_errors.append(f"{rel(f)}: {exc}")
        continue
    for node in doc.documentElement.childNodes:
        if node.nodeType != node.ELEMENT_NODE:
            continue
        kind = node.tagName
        name = node.getAttribute("name")
        if kind == "item":
            kind = node.getAttribute("type")
        if kind == "string":
            strings[name] = "".join(c.toxml() for c in node.childNodes)
        if kind == "color" and f.parent.name == "values":
            palette[name] = "".join(c.toxml() for c in node.childNodes).strip()
        define(kind, name)
for d in RES.iterdir():
    kind = d.name.split("-")[0]
    if kind == "values" or not d.is_dir():
        continue
    for f in d.iterdir():
        define(kind, f.stem)
for f in layouts:
    for m in re.finditer(r"@\+id/(\w+)", f.read_text()):
        define("id", m.group(1))

for f in xml_files:
    try:
        minidom.parse(str(f))
    except Exception as exc:  # noqa: BLE001
        parse_errors.append(f"{rel(f)}: {exc}")
gate("All XML files are well-formed", parse_errors)

# ------------------------------------------------------- 1. references exist
missing = []
for f in java_files:
    for m in re.finditer(r"\bR\.(\w+)\.(\w+)", f.read_text()):
        kind, name = m.groups()
        if kind != "styleable" and name not in defined.get(kind, set()):
            missing.append(f"{rel(f)}: R.{kind}.{name}")
for f in xml_files:
    for m in re.finditer(r'"@(\w+)/([\w.]+)"', f.read_text()):
        kind, name = m.groups()
        if kind in ("android", "tools"):
            continue
        if name not in defined.get(kind, set()):
            missing.append(f"{rel(f)}: @{kind}/{name}")
for f in sorted(RES.glob("values*/styles.xml")):
    for m in re.finditer(r'<style name="([\w.]+)"(?: parent="([^"]*)")?', f.read_text()):
        name, parent = m.groups()
        if parent is None and "." in name and name.rsplit(".", 1)[0] not in defined.get("style", set()):
            missing.append(f"{rel(f)}: implicit parent of {name}")
gate("Every referenced resource exists", missing)

# ---------------------------------------------------- 2. format arguments


def needed_args(text: str) -> int:
    positional = re.findall(r"%(\d+)\$", text)
    if positional:
        return max(int(x) for x in positional)
    return len(re.findall(r"%(?!%)[-#+ 0,(]*\d*(?:\.\d+)?[sdfx]", text))


def call_args(src: str, start: int) -> int:
    depth, count, i = 1, 1, start
    while i < len(src) and depth:
        c = src[i]
        if c == '"':
            i += 1
            while src[i] != '"':
                i += 2 if src[i] == "\\" else 1
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 1:
            count += 1
        i += 1
    return count


arg_problems = []
for f in java_files:
    src = f.read_text()
    for m in re.finditer(r"getString\(\s*R\.string\.(\w+)\s*([,)])", src):
        name = m.group(1)
        if name not in strings:
            continue
        got = call_args(src, m.end()) if m.group(2) == "," else 0
        need = needed_args(strings[name])
        if got != need:
            arg_problems.append(f"{rel(f)}: {name} needs {need} args, got {got}")
gate("Format strings receive the right number of arguments", arg_problems)

# ------------------------------------------------------------ 3. no logo
logo = []
for p in MAIN.rglob("*"):
    if p.is_file() and re.search(r"ic_anoy|logo", p.name, re.I):
        logo.append(rel(p))
    if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"} and "res" in p.parts:
        logo.append(f"bitmap resource {rel(p)}")
for f in xml_files + java_files:
    if re.search(r"ic_anoy|@drawable/logo|R\.drawable\.logo|@mipmap/", f.read_text()):
        logo.append(f"{rel(f)} references a logo/mipmap")
gate("The app logo is not used anywhere", logo)

# ------------------------------------------------------- 4. palette only
raw_colour = []
allowed_raw = {RES / "values/colors.xml"}
for f in xml_files:
    if f in allowed_raw:
        continue
    for m in re.finditer(r'"(#[0-9A-Fa-f]{3,8})"', f.read_text()):
        raw_colour.append(f"{rel(f)}: {m.group(1)}")
for f in java_files:
    for m in re.finditer(r"Color\.(parseColor|rgb|argb)\(|0x[Ff]{2}[0-9A-Fa-f]{6}", f.read_text()):
        raw_colour.append(f"{rel(f)}: {m.group(0)}")
gate("Colours come only from values/colors.xml", raw_colour)

# --------------------------------------------- 5. no background behind text
text_bg = []
text_tags = ("TextView", "EditText", "CheckedTextView")
for f in layouts:
    doc = minidom.parse(str(f))
    for tag in text_tags:
        for el in doc.getElementsByTagName(tag):
            if tag == "EditText":
                continue  # the input field shape is a control, not text styling
            bg = el.getAttribute("android:background")
            if bg and bg not in ("@null", "@android:color/transparent"):
                text_bg.append(f"{rel(f)}: <{tag} id={el.getAttribute('android:id')}> background={bg}")
styles_src = (RES / "values/styles.xml").read_text()
for m in re.finditer(r'<style name="(Text\.[\w.]*)"[^>]*>(.*?)</style>', styles_src, re.S):
    if re.search(r'name="android:background"', m.group(2)):
        text_bg.append(f"style {m.group(1)} sets a background")
for f in java_files:
    src = f.read_text()
    for m in re.finditer(r"(\w+)\.setBackground(Color|Resource|Drawable)?\(", src):
        var = m.group(1).lower()
        if any(k in var for k in ("text", "label", "title", "message", "value", "note", "footer", "error")):
            text_bg.append(f"{rel(f)}: {m.group(0)}")
gate("Text has no background of its own", text_bg)

# --------------------------------------------- 6. black / white / blue theme


def colour(name: str) -> str:
    return palette.get(name, "").upper()


theme = []
if colour("black") not in ("#000000", "#FF000000"):
    theme.append(f"@color/black is {colour('black')}")
if colour("white") not in ("#FFFFFF", "#FFFFFFFF"):
    theme.append(f"@color/white is {colour('white')}")
if not colour("blue").startswith(("#0A84FF", "#FF0A84FF", "#007AFF")):
    theme.append(f"@color/blue is {colour('blue')} (expected iOS blue)")
if not re.search(r'<style name="AppTheme"[^>]*>.*?name="android:windowBackground">@color/black<', styles_src, re.S):
    theme.append("AppTheme windowBackground is not @color/black")
if not re.search(r'name="android:textColorPrimary">@color/(white|text_primary)<', styles_src):
    theme.append("primary text colour is not white")
if colour("text_primary") and colour("text_primary") not in ("#FFFFFF", "#FFFFFFFF"):
    theme.append(f"text_primary is {colour('text_primary')}")
btn = (RES / "drawable/bg_button_primary.xml").read_text() if (RES / "drawable/bg_button_primary.xml").exists() else ""
if "@color/blue" not in btn or "radius" not in btn:
    theme.append("bg_button_primary is not a rounded blue shape")
for f in layouts:
    src = f.read_text()
    # Root element only. No background at all is fine: the theme window is black
    # (checked above), and not repainting it avoids overdraw.
    from xml.dom import minidom
    root = minidom.parseString(src.encode()).documentElement
    root_bg = root.getAttribute("android:background")
    if root_bg and root_bg not in ("@color/black", "@drawable/bg_dialog"):
        theme.append(f"{rel(f)}: root background {root_bg}")
gate("Black background, white text, rounded blue buttons", theme)

# -------------------------------------------- 7. layouts use resources only
literals = []
for f in layouts:
    src = f.read_text()
    for m in re.finditer(r'android:(text|hint|contentDescription)="([^"@?][^"]*)"', src):
        literals.append(f"{rel(f)}: {m.group(1)}=\"{m.group(2)}\"")
    for m in re.finditer(r'android:textSize="([^"@?][^"]*)"', src):
        literals.append(f"{rel(f)}: textSize=\"{m.group(1)}\" (use a Text.* style or @dimen)")
gate("Layouts use string resources and the type scale", literals)

# -------------------------------------------------- 8. removed stays removed
removed = []
gradle = (APP / "build.gradle").read_text()
for dep in ("com.google.android.material", "core-splashscreen", "dynamicanimation"):
    if dep in gradle:
        removed.append(f"app/build.gradle still depends on {dep}")
if "buildConfig true" not in gradle:
    removed.append("app/build.gradle must enable buildFeatures.buildConfig (BuildConfig is used)")
for f in java_files + xml_files:
    src = f.read_text()
    for bad in ("FacebookActivity", "designsystem", "SplashScreen.installSplashScreen", "com.google.android.material"):
        if bad in src:
            removed.append(f"{rel(f)} mentions {bad}")
manifest = (MAIN / "AndroidManifest.xml").read_text()
if not re.search(r'\.SplashActivity"[^>]*>\s*<intent-filter>\s*<action android:name="android.intent.action.MAIN"', manifest):
    removed.append("SplashActivity is not the launcher activity")
gate("Removed components stay removed; splash is the launcher", removed)

print()
print(f"{checks - len(failures)}/{checks} gates passed")
sys.exit(1 if failures else 0)
