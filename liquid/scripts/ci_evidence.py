#!/usr/bin/env python3
"""Small, credential-free CI diagnostics readable via GitHub's checks API.

Full-resolution evidence remains in normal workflow artifacts. Downscaled UI
fixture review images are also attached as notice annotations, so reviewers in
network-restricted environments can inspect the actual rendered output without
committing generated artifacts or granting this workflow repository-write access.
"""
from __future__ import annotations

import argparse
import base64
from io import BytesIO
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def escaped(text):
    return str(text).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def lint(root):
    found = 0
    for path in sorted((root / 'app/build/reports').glob('lint-results-*.xml')):
        for issue in ET.parse(path).getroot().findall('issue'):
            if issue.get('severity') not in {'Error', 'Fatal'}:
                continue
            location = issue.find('location')
            place = ''
            if location is not None:
                name = location.get('file', '')
                marker = '/liquid/'
                name = 'liquid/' + name.split(marker, 1)[-1] if marker in name else name
                place = f"file={escaped(name)},line={location.get('line', '1')},"
            message = issue.get('message', '') + '\n' + issue.get('explanation', '')
            print(f"::error {place}title=Android lint {issue.get('id')}::{escaped(message[:6000])}")
            found += 1
    if not found:
        for name in ('assemble.log', 'lint.log'):
            path = root.parent / 'qa-logs/liquid-build' / name
            if path.exists():
                lines = path.read_text(errors='replace').splitlines()
                print(f'::notice title=Build diagnostic {name}::{escaped(chr(10).join(lines[-55:])[-7000:])}')


def ui(root):
    from PIL import Image
    report = root / 'results.json'
    if report.exists():
        result = json.loads(report.read_text())
        failures = [x['message'] for x in result.get('checks', []) if not x.get('pass')]
        summary = {'api': result.get('api'), 'screens': len(result.get('screens', [])),
                   'checks': len(result.get('checks', [])), 'failures': failures}
        print('::notice title=Liquid UI results::' + escaped(json.dumps(summary)))
    selected = [
        'anoy-splash', 'anoy-login', 'anoy-home', 'anoy-settings',
        'anoy-library-required', 'anoy-apk-update-normal', 'anoy-login-keyboard',
        'anoy-home-font200', 'anoy-login-font200-bottom', 'anoy-settings-font200',
        'anoy-library-font200-actions', 'kenny-splash', 'kenny-home', 'last-screen',
    ]
    for name in selected:
        path = root / (name + '.png')
        if not path.exists():
            continue
        image = Image.open(path).convert('RGB')
        image.thumbnail((440, 1100))
        quality = 72
        while True:
            stream = BytesIO()
            image.save(stream, 'JPEG', quality=quality, optimize=True)
            if len(stream.getvalue()) <= 36000 or quality <= 30:
                break
            quality -= 7
        data = base64.b64encode(stream.getvalue()).decode('ascii')
        print(f'::notice title=LIQUID_IMAGE {name}.jpg::{data}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('kind', choices=('lint', 'ui'))
    parser.add_argument('--path', type=Path)
    args = parser.parse_args()
    if args.kind == 'lint':
        lint(args.path or Path.cwd())
    else:
        ui(args.path)
