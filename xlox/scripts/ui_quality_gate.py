#!/usr/bin/env python3
"""Static checks for the Android UI (2.1 brief, updated for the 2.3.0 spec).

Runs on a plain checkout (no Android SDK needed). Exits non-zero when a gate
fails and prints a PASS/FAIL line for every gate.

Gates
  1. Every resource referenced from Java / XML exists.
  2. Format strings get the right number of arguments in getString().
  3. No app logo anywhere: no ic_anoy / logo images, no bitmap resources.
  4. Colours only come from the palette in values/colors.xml.
  5. Text never gets a filled background (2.3.0: no chips at all; only the
     danger error block and the hairline help circle, which are controls).
  6. Window and screens are black, primary text is off-white #F5F5F7, buttons
     are blue with the 16 dp radius.
  7. Layouts contain no hardcoded user-visible text or raw text sizes.
  8. Brief rules: no purple / teal / bg_tile_*, no emoji, no default spinner,
     no Toast, no dynamic colour; Material / splashscreen / Lottie present at
     minSdk-21-compatible versions; FacebookActivity / designsystem stay gone.
  9. Lottie files: 60 fps, under 60 KB, provenance + licences in assets/licenses.
 10. Every MaterialButton uses an Anoy button style.
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
    for m in re.finditer(r"(?<![\w.])R\.(\w+)\.(\w+)", f.read_text()):
        kind, name = m.groups()
        pool = defined.get(kind, set())
        if kind == "style":
            pool = {n.replace(".", "_") for n in pool}
        if kind != "styleable" and name not in pool:
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

# ------------------------------------------------- 2b. vector paths parse
bad_paths = []
for f in xml_files:
    src = f.read_text()
    if "<vector" not in src:
        continue
    for m in re.finditer(r'android:pathData="([^"]*)"', src):
        d = m.group(1)
        if re.search(r"NaN|undefined|Infinity", d) or not re.match(r"^\s*[Mm]", d):
            bad_paths.append(f"{rel(f)}: {d[:60]}")
gate("Vector drawables have valid path data", bad_paths)

# ----------------------------------- 2c. layout attributes that crash at inflation
from xml.dom import minidom as _md
crashy = []
for f in xml_files:
    if "/layout" not in str(f):
        continue
    def walk(node):
        for c in node.childNodes:
            if c.nodeType != c.ELEMENT_NODE:
                continue
            if c.getAttribute("android:addStatesFromChildren") == "true":
                for k in c.getElementsByTagName("*"):
                    if k.getAttribute("android:duplicateParentState") == "true":
                        crashy.append(f"{rel(f)}: addStatesFromChildren parent with duplicateParentState child")
            walk(c)
    walk(_md.parse(str(f)))
gate("No layout attribute combinations that crash on inflation", crashy)

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
# 2.3.0: the error block (danger tint, r16) and the 32 dp hairline "?" circle.
TEXT_BG_ALLOWED = ("@drawable/bg_error_block", "@drawable/bg_help_circle")
text_tags = ("TextView", "EditText", "CheckedTextView")
for f in layouts:
    doc = minidom.parse(str(f))
    for tag in text_tags:
        for el in doc.getElementsByTagName(tag):
            if tag == "EditText":
                continue  # the input field shape is a control, not text styling
            bg = el.getAttribute("android:background")
            if bg in TEXT_BG_ALLOWED:
                continue  # a control shape, not text styling
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
for chip in sorted(RES.glob("drawable/bg_chip_*.xml")):
    text_bg.append(f"{rel(chip)}: 2.3.0 has no chips or pills - status is a 6 dp dot + text")
gate("Text has no filled background of its own", text_bg)

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
if colour("text_primary") and colour("text_primary") not in ("#F5F5F7", "#FFF5F5F7"):
    theme.append(f"text_primary is {colour('text_primary')}")
m_btn = re.search(r'<style name="Widget.Anoy.Button" [^>]*>(.*?)</style>', styles_src, re.S)
btn_bg = (RES / "color/button_bg.xml").read_text() if (RES / "color/button_bg.xml").exists() else ""
if not m_btn or "@color/button_bg" not in m_btn.group(1) or "@dimen/button_radius" not in m_btn.group(1):
    theme.append("Widget.Anoy.Button is not a button_bg tinted pill with button_radius")
if "@color/blue" not in btn_bg:
    theme.append("color/button_bg is not blue")
if not re.search(r'<dimen name="button_radius">16dp<', (RES / "values/dimens.xml").read_text()):
    theme.append("button_radius must be 16dp (2.3.0 spec)")
for f in layouts:
    src = f.read_text()
    # Root element only. No background at all is fine: the theme window is black
    # (checked above), and not repainting it avoids overdraw.
    from xml.dom import minidom
    root = minidom.parseString(src.encode()).documentElement
    root_bg = root.getAttribute("android:background")
    # Sheet layouts (dialog_*, sheet_*) inherit the sheet surface.
    if root_bg and root_bg != "@color/black" and not (
            f.name.startswith(("sheet_", "dialog_")) and root_bg == "@drawable/bg_sheet"):
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

# ----------------------------------------------------------- 8. brief rules
rules = []
gradle = (APP / "build.gradle").read_text()
need_deps = {
    "com.google.android.material:material:1.13.0": "Material 1.13.0 (newest with minSdk 21)",
    "com.airbnb.android:lottie:6.7.1": "Lottie 6.7.1",
    "androidx.core:core-splashscreen:1.0.1": "core-splashscreen 1.0.1 (1.2.0 needs minSdk 23)",
}
for dep, why in need_deps.items():
    if dep not in gradle:
        rules.append(f"app/build.gradle must depend on {why}")
if not re.search(r"minSdk 21\b", gradle) or not re.search(r"targetSdk 28\b", gradle):
    rules.append("minSdk must stay 21 and targetSdk 28")
if "buildConfig true" not in gradle:
    rules.append("app/build.gradle must enable buildFeatures.buildConfig (BuildConfig is used)")
EMOJI = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]")
for f in java_files + xml_files:
    src = f.read_text()
    for bad in ("FacebookActivity", "designsystem", "android.widget.Toast", "Toast.makeText", "DynamicColors",
                "<ProgressBar", "android.widget.ProgressBar", "bg_tile_", "purple", "teal"):
        if bad in src:
            rules.append(f"{rel(f)} mentions {bad}")
    if EMOJI.search(src):
        rules.append(f"{rel(f)} contains an emoji character")
    for hexc in re.findall(r"#(?:FF)?([0-9A-Fa-f]{6})\b", src):
        if hexc.upper() in {"AF52DE", "BF5AF2", "6750A4", "D0BCFF", "5AC8FA", "64D2FF", "30B0C7", "40C8E0"}:
            rules.append(f"{rel(f)} uses a purple/teal colour #{hexc}")
if "SplashScreen.installSplashScreen(this)" not in (JAVA / "com/xlox/SplashActivity.java").read_text():
    rules.append("SplashActivity must install the core-splashscreen")
if not re.search(r'<style name="AppTheme" parent="Theme.Material3.Dark.NoActionBar"', styles_src):
    rules.append("AppTheme must extend Theme.Material3.Dark.NoActionBar")
if (RES / "values-night").exists():
    rules.append("values-night must not exist (dark only)")
manifest = (MAIN / "AndroidManifest.xml").read_text()
if not re.search(r'\.SplashActivity"[^>]*>\s*<intent-filter>\s*<action android:name="android.intent.action.MAIN"', manifest):
    rules.append("SplashActivity is not the launcher activity")
gate("Brief rules: palette, no emoji / spinner / Toast / dynamic colour; deps pinned", rules)

# ------------------------------------------------------------ 9. Lottie budget
import json as _json
lottie = []
raw_dir = RES / "raw"
for name in ("anoy_sync", "anoy_success"):
    f = raw_dir / f"{name}.json"
    if not f.exists():
        lottie.append(f"missing res/raw/{name}.json")
        continue
    data = _json.loads(f.read_text())
    if f.stat().st_size >= 60 * 1024:
        lottie.append(f"{name}.json is {f.stat().st_size} bytes (limit 60 KB)")
    if data.get("fr") != 60:
        lottie.append(f"{name}.json frame rate is {data.get('fr')} (expected 60)")
    if data.get("assets"):
        lottie.append(f"{name}.json embeds assets (images)")
lic = MAIN / "assets/licenses"
for need in ("LOTTIE.md", "LUCIDE-ISC.txt"):
    if not (lic / need).exists():
        lottie.append(f"missing assets/licenses/{need}")
gate("Lottie: 60 fps, < 60 KB, no images; provenance and licences shipped", lottie)

# ---------------------------------------------------------- 10. button styles
btns = []
for f in layouts:
    doc = minidom.parse(str(f))
    for el in doc.getElementsByTagName("com.google.android.material.button.MaterialButton"):
        if not el.getAttribute("style").startswith("@style/Widget.Anoy.Button"):
            btns.append(f"{rel(f)}: {el.getAttribute('android:id')} has style {el.getAttribute('style')!r}")
    for tag in ("Button", "ProgressBar"):
        for el in doc.getElementsByTagName(tag):
            btns.append(f"{rel(f)}: plain <{tag}> {el.getAttribute('android:id')}")
gate("Buttons are MaterialButtons with Anoy styles", btns)

print()
print(f"{checks - len(failures)}/{checks} gates passed")
sys.exit(1 if failures else 0)
