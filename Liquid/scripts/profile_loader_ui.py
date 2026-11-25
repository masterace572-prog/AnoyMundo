#!/usr/bin/env python3
"""Non-destructive, opt-in frame timing capture for the loader UI on an attached device.

No APK install, uninstall, data clear, game launch, animation-scale change or live
service request. Keep the loader in front and manually exercise its page/capsule
swipes during capture. Android 23+ gfxinfo framestats is required. These timings
are device/build specific; a passing host test is NOT a measured 60fps result.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import time

PACKAGES = ('com.xlox', 'com.kennypvt')
LOADER_ACTIVITIES = ('MAct', 'LogAct', 'SplashActivity', 'AppUpdateActivity')
BUDGET_MS = 1000.0 / 60.0


def percentile(values: list[float], percent: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(percent / 100 * len(ordered)) - 1)], 3)


def parse_framestats(raw: str) -> dict:
    """Only complete, unflagged frames from known loader windows; no virtual games."""
    window = None
    in_block = False
    header = None
    durations = []
    seen = set()
    ignored = 0
    windows = set()
    for line in raw.splitlines():
        line = line.strip()
        if line.startswith('Window:'):
            window = line[len('Window:'):].strip()
            in_block = False
            header = None
            continue
        if line == '---PROFILEDATA---':
            in_block = not in_block
            header = None
            continue
        if not in_block or not window:
            continue
        # Window titles include com.xlox.MAct even for the Kenny installation.
        if not re.search(r'(?:/|\.)(' + '|'.join(LOADER_ACTIVITIES) + r')(?:$|[\s}])', window):
            continue
        fields = [part.strip() for part in line.split(',')]
        if 'IntendedVsync' in fields and 'FrameCompleted' in fields and 'Flags' in fields:
            header = fields
            continue
        if header is None:
            continue
        try:
            row = dict(zip(header, fields))
            flags = int(row['Flags'])
            start = int(row['IntendedVsync'])
            end = int(row['FrameCompleted'])
        except (KeyError, ValueError):
            ignored += 1
            continue
        if flags != 0 or start <= 0 or end <= start or end >= 2**63 - 1:
            ignored += 1
            continue
        key = (window, start, end)
        if key in seen:
            continue
        seen.add(key)
        durations.append((end - start) / 1_000_000.0)
        windows.add(window)
    count = len(durations)
    over = sum(value > BUDGET_MS for value in durations)
    return {
        'status': 'captured' if count >= 60 else 'insufficient_frames',
        'scope': 'rendered loader UI frames only; not game performance or an FPS guarantee',
        'target_hz': 60,
        'frame_budget_ms': round(BUDGET_MS, 3),
        'rendered_frames': count,
        'frames_over_budget': over,
        'over_budget_percent': round(over / count * 100, 2) if count else None,
        'p50_ms': percentile(durations, 50),
        'p90_ms': percentile(durations, 90),
        'p95_ms': percentile(durations, 95),
        'p99_ms': percentile(durations, 99),
        'max_ms': round(max(durations), 3) if count else None,
        'ignored_incomplete_or_flagged_rows': ignored,
        'windows': sorted(windows),
        'interpretation': 'Frame completion latency, not average FPS. Idle windows produce few frames. '
                          'Use the original APK on representative hardware and repeat under the same conditions.',
    }


def adb(*args: str) -> str:
    return subprocess.check_output(['adb', *args], text=True, timeout=20, stderr=subprocess.STDOUT)


def require_loader_focus(package: str) -> str:
    focus = adb('shell', 'dumpsys', 'window', 'windows')
    selected = [line.strip() for line in focus.splitlines() if 'mCurrentFocus=' in line]
    value = '\n'.join(selected)
    if not re.search(re.escape(package) + r'/(?:com\.xlox\.|\.)(' + '|'.join(LOADER_ACTIVITIES) + r')(?:$|[\s}])', value):
        raise RuntimeError('Keep a loader screen in the foreground (not a game, provider login or permission dialog).')
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--analyze', type=Path, help='Analyze an existing raw gfxinfo capture; no adb calls.')
    parser.add_argument('--package', choices=PACKAGES, default=PACKAGES[0])
    parser.add_argument('--seconds', type=int, default=15)
    parser.add_argument('--output', type=Path, default=Path('../qa-logs/loader-frame-timing'))
    args = parser.parse_args()
    if args.analyze:
        print(json.dumps(parse_framestats(args.analyze.read_text()), indent=2))
        return
    if not 3 <= args.seconds <= 120:
        parser.error('--seconds must be between 3 and 120')
    if not shutil.which('adb'):
        parser.error('adb is required for a device capture; no measurement has been made')
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    if sdk < 23:
        parser.error('gfxinfo framestats requires Android 23 or newer')
    before = require_loader_focus(args.package)
    model = adb('shell', 'getprop', 'ro.product.model').strip()
    build = adb('shell', 'dumpsys', 'package', args.package)
    version = [line.strip() for line in build.splitlines() if 'versionCode=' in line or 'versionName=' in line]
    adb('shell', 'dumpsys', 'gfxinfo', args.package, 'reset')
    print(f'Capture for {args.seconds}s on {model}. Drag the loader pages and capsule; do NOT tap Launch.', flush=True)
    time.sleep(args.seconds)
    after = require_loader_focus(args.package)
    raw = adb('shell', 'dumpsys', 'gfxinfo', args.package, 'framestats')
    result = parse_framestats(raw)
    result.update({'package': args.package, 'android_api': sdk, 'device': model,
                   'duration_seconds': args.seconds, 'version': version,
                   'foreground_before': before, 'foreground_after': after})
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'framestats.txt').write_text(raw)
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    print('Saved', args.output / 'summary.json')


if __name__ == '__main__':
    main()
