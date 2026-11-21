#!/usr/bin/env python3
"""SDK-free Liquid UI gates. Compilation, lint and emulator checks run separately."""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from normalize_vector_paths import normalize_path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
JAVA = ROOT / 'app/src/main/java'
A = '{http://schemas.android.com/apk/res/android}'
P = '{http://schemas.android.com/apk/res-auto}'
xml_files = sorted(RES.rglob('*.xml')) + [ROOT / 'app/src/main/AndroidManifest.xml']
java_files = sorted(JAVA.rglob('*.java'))
layouts = sorted(RES.glob('layout*/*.xml'))
failures = []
checks = 0


def gate(name, problems):
    global checks
    checks += 1
    if problems:
        failures.append(name)
        print('FAIL', name)
        for problem in problems:
            print('    ', problem)
    else:
        print('PASS', name)


def rel(path):
    return str(path.relative_to(ROOT))


defined = {}
strings = {}
palette = {}
parsed = {}
errors = []
for path in xml_files:
    try:
        parsed[path] = ET.parse(path).getroot()
    except ET.ParseError as error:
        errors.append(f'{rel(path)}: {error}')
for path, root in parsed.items():
    if path.parent.name.startswith('values'):
        for el in root:
            kind = el.get('type') if el.tag == 'item' else el.tag
            defined.setdefault(kind, set()).add(el.get('name'))
            if el.tag == 'string':
                strings[el.get('name')] = ''.join(el.itertext())
            if el.tag == 'color' and path.parent.name == 'values':
                palette[el.get('name')] = el.text.strip()
    else:
        defined.setdefault(path.parent.name.split('-')[0], set()).add(path.stem)
    for el in root.iter():
        value = el.get(A + 'id', '')
        if value.startswith('@+id/'):
            defined.setdefault('id', set()).add(value[5:])
for directory in RES.iterdir():
    if directory.is_dir() and not directory.name.startswith('values'):
        for path in directory.iterdir():
            defined.setdefault(directory.name.split('-')[0], set()).add(path.stem)
gate('All Android XML is well formed', errors)

errors = []
for path in java_files:
    for kind, name in re.findall(r'(?<![\w.])R\.(\w+)\.(\w+)', path.read_text()):
        pool = defined.get(kind, set())
        if kind == 'style': pool = {item.replace('.', '_') for item in pool}
        if kind != 'styleable' and name not in pool:
            errors.append(f'{rel(path)}: R.{kind}.{name}')
for path in xml_files:
    for kind, name in re.findall(r'"@(\w+)/([\w.]+)"', path.read_text()):
        if name not in defined.get(kind, set()):
            errors.append(f'{rel(path)}: @{kind}/{name}')
gate('Every referenced local resource exists', errors)

styles = {el.get('name'): el for el in parsed[RES / 'values/styles.xml'] if el.tag == 'style'}
errors = []
for name, el in styles.items():
    parent = el.get('parent')
    if parent is None and '.' in name and name.rsplit('.', 1)[0] not in styles:
        errors.append('Missing implicit style parent: ' + name)
gate('Component style inheritance is complete', errors)

errors = []
for path in layouts:
    for el in parsed[path].iter():
        for attr in ('text', 'hint', 'contentDescription', 'textSize'):
            value = el.get(A + attr, '')
            if value and not value.startswith(('@', '?')):
                errors.append(f'{rel(path)}: literal {attr}')
        if el.get(A + 'ellipsize') in ('middle', 'start') and el.get(A + 'maxLines') == '1' and el.get(A + 'singleLine') != 'true':
            errors.append(f'{rel(path)}: unsafe single-line ellipsize')
        if el.get(A + 'addStatesFromChildren') == 'true' and any(c.get(A + 'duplicateParentState') == 'true' for c in el.iter()):
            errors.append(f'{rel(path)}: cyclic drawable state propagation')
gate('Layouts use scalable type/resources and safe state/ellipsize attributes', errors)

errors = []
for path in xml_files:
    if path != RES / 'values/colors.xml' and re.search(r'"#[0-9a-fA-F]{3,8}"', path.read_text()):
        errors.append(rel(path) + ': non-token colour')
for path in java_files:
    if re.search(r'Color\.(parseColor|rgb|argb)\(|0x[Ff]{2}[0-9a-fA-F]{6}', path.read_text()):
        errors.append(rel(path) + ': non-token colour')
gate('Palette is centralised; no arbitrary per-screen colours', errors)


def rgb(token):
    value = palette[token][-6:]
    return tuple(int(value[i:i + 2], 16) / 255 for i in (0, 2, 4))


def luminance(values):
    return sum((v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4) * weight
               for v, weight in zip(values, (.2126, .7152, .0722)))


def contrast(a, b):
    low, high = sorted((luminance(a), luminance(b)))
    return (high + .05) / (low + .05)


errors = []
for foreground in ('text_primary', 'text_secondary', 'text_tertiary', 'success', 'warning', 'danger'):
    for background in ('background', 'surface_1', 'surface_2', 'surface_3', 'hero_top', 'hero_bottom'):
        ratio = contrast(rgb(foreground), rgb(background))
        if ratio < 4.5:
            errors.append(f'{foreground} / {background}: {ratio:.2f}:1, need 4.5:1')
# Check the brightest primary-button sheen, not just the unadorned fill.
alpha = int(palette['button_sheen'][1:3], 16) / 255
button = tuple(c * (1 - alpha) + alpha for c in rgb('accent'))
if contrast(rgb('on_accent'), button) < 4.5:
    errors.append('Primary button sheen reduces label contrast below 4.5:1')
def over(token, background):
    alpha = int(palette[token][1:3], 16) / 255
    return tuple(f * alpha + b * (1-alpha) for f, b in zip(rgb(token), background))

for tint in ('glass_tint', 'glass_sheet_tint'):
    worst = over('glass_light', over(tint, (1, 1, 1)))
    for foreground in ('text_primary', 'text_secondary', 'text_tertiary', 'success', 'warning', 'danger'):
        if contrast(rgb(foreground), worst) < 4.5:
            errors.append(f'{foreground} / worst-case {tint}: contrast below 4.5:1')
for fill in ('accent', 'accent_pressed'):
    lit = over('button_sheen', over('ripple_primary', rgb(fill)))
    if contrast(rgb('on_accent'), lit) < 4.5:
        errors.append(fill + ': ripple plus sheen reduces white label contrast below 4.5:1')

if contrast(rgb('field_outline'), rgb('field_bg')) < 3:
    errors.append('Key-field boundary contrast below 3:1')
if contrast(rgb('focus_ring'), rgb('field_bg_focus')) < 3:
    errors.append('Keyboard focus contrast below 3:1')
gate('WCAG text >=4.5:1; field and focus boundaries >=3:1', errors)

errors = []
dimens = {el.get('name'): el.text for el in parsed[RES / 'values/dimens.xml']}
for name in ('touch_target', 'button_height', 'button_height_sheet', 'field_height', 'row_height'):
    if float(dimens[name][:-2]) < 48:
        errors.append(name + ': target below 48dp')
for path in layouts:
    for el in parsed[path].iter():
        if el.tag in ('Button', 'ProgressBar'):
            errors.append(rel(path) + ': unthemed widget')
        if el.tag in ('com.google.android.material.button.MaterialButton', 'com.xlox.ui.GlassButton'):
            if not el.get('style', '').startswith('@style/Widget.Anoy.Button'):
                errors.append(rel(path) + ': button without the shared style')
        if el.tag == 'ImageButton' and not el.get(A + 'contentDescription'):
            errors.append(rel(path) + ': unnamed icon button')
        if el.tag == 'EditText' and el.get(A + 'minHeight') != '@dimen/touch_target':
            errors.append(rel(path) + ': input target below 48dp')
gate('Shared controls, labelled icons and >=48dp targets', errors)

errors = []
for path in RES.glob('drawable/ic_*.xml'):
    if path.stem.startswith('ic_launcher'): continue  # Original brand outlines, not interface icons.
    root = parsed.get(path)
    if root is None: continue
    if root.get(A + 'viewportWidth') != '24' or root.get(A + 'viewportHeight') != '24':
        errors.append(path.name + ': non-24px icon')
    for el in root.findall('path'):
        if el.get(A + 'strokeWidth') and el.get(A + 'strokeWidth') != '2':
            errors.append(path.name + ': inconsistent stroke')
        try:
            data = el.get(A + 'pathData', '')
            if normalize_path(data) != data:
                errors.append(path.name + ': unsafe compact/leading-dot Android path')
        except ValueError as error:
            errors.append(path.name + ': ' + str(error))
for needed in ('LUCIDE-ISC.txt', 'OFL-Inter.txt', 'OFL-JetBrainsMono.txt', 'LOTTIE.md'):
    if not (ROOT / 'app/src/main/assets/licenses' / needed).is_file(): errors.append(needed)
gate('Consistent 24px / 2px icon family and bundled asset licences', errors)

errors = []
glass = (JAVA / 'com/xlox/ui/GlassLayout.java').read_text()
policy = (JAVA / 'com/xlox/ui/Liquid.java').read_text()
splash = (JAVA / 'com/xlox/SplashActivity.java').read_text()
if 'setRenderEffect(' in glass: errors.append('Never blur a parent containing readable controls')
for required in ('BackdropBlur.blur', 'SAMPLE = 8f', 'removeOnScrollChangedListener', 'OutOfMemoryError', 'releaseBitmap'):
    if required not in glass: errors.append('Glass safety mechanism missing: ' + required)
for required in ('isPowerSaveMode()', 'high_text_contrast_enabled', 'preferredEffects'):
    if required not in policy: errors.append('Opaque fallback missing: ' + required)
if 'Ui.reducedMotion(this)' not in splash or 'film.cancel()' not in splash:
    errors.append('Splash must respect removed animations and lifecycle')
if 'INFINITE' in splash: errors.append('Decorative splash animation must not loop indefinitely')
for path in java_files:
    if 'RenderScript' in path.read_text() and 'import android.renderscript' in path.read_text():
        errors.append(rel(path) + ': deprecated blur dependency')
gate('Backdrop-only blur; bounded allocations; solid/reduced-motion/lifecycle fallbacks', errors)

errors = []
for name in ('anoy_arc', 'anoy_check'):
    path = RES / 'raw' / (name + '.json')
    animation = json.loads(path.read_text())
    if path.stat().st_size >= 60 * 1024 or animation.get('assets') or animation.get('fr') != 60:
        errors.append(name + ': animation exceeds the local, 60fps, <60KB budget')
gate('Small local motion assets; no downloaded fonts or animation media', errors)

print(f'\n{checks - len(failures)}/{checks} Liquid UI gates passed')
sys.exit(bool(failures))
