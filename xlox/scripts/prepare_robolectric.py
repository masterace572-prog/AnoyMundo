#!/usr/bin/env python3
"""Cache verified, pinned host-test Android runtimes; never package them in an APK.

Robolectric 4.14.1 DefaultSdkProvider uses instrumentation revision 7. Tests run
on API 21/28; its binary-resource loader also needs the API 35 framework. Fetch
once, sequentially, rather than retrying Maven Central inside every test case.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
from zipfile import BadZipFile, ZipFile

VERSIONS = (
    '5.0.2_r3-robolectric-r0-i7',
    '9-robolectric-4913185-2-i7',
    '15-robolectric-12650502-i7',
)
REPOSITORIES = (
    'https://maven-central.storage-download.googleapis.com/maven2',
    'https://repo.maven.apache.org/maven2',
)
MAX_JAR_BYTES = 350 * 1024 * 1024


def file_hash(path: Path) -> str:
    digest = hashlib.sha512()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: Path, expected: str) -> None:
    if not re.fullmatch(r'[0-9a-fA-F]{128}', expected):
        raise ValueError('Invalid published SHA-512')
    if path.stat().st_size > MAX_JAR_BYTES or file_hash(path) != expected.lower():
        raise ValueError('Android test runtime checksum/size mismatch')
    with ZipFile(path) as jar:
        for name in ('android/view/View.class', 'android/app/Activity.class'):
            jar.getinfo(name)


def fetch(url: str, target: Path, maximum: int) -> None:
    request = urllib.request.Request(url, headers={'User-Agent': 'AnoyMundo-host-tests/1.0'})
    with urllib.request.urlopen(request, timeout=90) as response, target.open('wb') as output:
        total = 0
        while True:
            chunk = response.read(min(1024 * 1024, maximum + 1 - total))
            if not chunk:
                break
            total += len(chunk)
            if total > maximum:
                raise ValueError('Android test runtime download is too large')
            output.write(chunk)


def prepare(directory: Path, version: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    name = 'android-all-instrumented-' + version + '.jar'
    target = directory / name
    checksum = directory / (name + '.sha512')
    if target.is_file() and checksum.is_file():
        try:
            verify(target, checksum.read_text().strip())
            print('Verified cached host Android runtime:', name, flush=True)
            return
        except (OSError, ValueError, KeyError, BadZipFile):
            pass
    part = directory / (name + '.part')
    checksum_part = directory / (name + '.sha512.part')
    errors = []
    for repository in REPOSITORIES:
        base = repository + '/org/robolectric/android-all-instrumented/' + version + '/' + name
        try:
            fetch(base + '.sha512', checksum_part, 1024)
            expected = checksum_part.read_text().strip().split()[0]
            if not re.fullmatch(r'[0-9a-fA-F]{128}', expected):
                raise ValueError('Invalid published SHA-512')
            fetch(base, part, MAX_JAR_BYTES)
            verify(part, expected)
            part.replace(target)
            checksum.write_text(expected.lower() + '\n')
            print('Verified downloaded host Android runtime:', name, target.stat().st_size, 'bytes', flush=True)
            return
        except (OSError, ValueError, KeyError, IndexError, BadZipFile) as error:
            errors.append(repository + ': ' + str(error))
        finally:
            part.unlink(missing_ok=True)
            checksum_part.unlink(missing_ok=True)
    raise RuntimeError('Unable to prepare ' + name + '\n' + '\n'.join(errors))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dir', type=Path, required=True, help='external/ignored host-test runtime cache')
    args = parser.parse_args()
    for version in VERSIONS:
        prepare(args.dir.resolve(), version)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        if os.environ.get('GITHUB_ACTIONS'):
            detail = str(error).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
            print('::error title=Host Android runtime preparation::' + detail, flush=True)
        raise
