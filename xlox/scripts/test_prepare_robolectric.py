#!/usr/bin/env python3
"""Offline unit fixtures for host-test runtime preparation; no Android/APK substitutes."""
import hashlib
from io import BytesIO
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import BadZipFile, ZipFile

import prepare_robolectric as runtime


class PreparationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        output = BytesIO()
        with ZipFile(output, 'w') as fixture:
            # Only a downloader unit fixture, never a framework used by UI tests.
            fixture.writestr('android/view/View.class', b'fixture')
            fixture.writestr('android/app/Activity.class', b'fixture')
        self.body = output.getvalue()
        self.digest = hashlib.sha512(self.body).hexdigest()
        self.name = 'android-all-instrumented-' + runtime.VERSIONS[0] + '.jar'

    def fake_fetch(self, url, target, maximum):
        target.write_bytes((self.digest + '\n').encode() if url.endswith('.sha512') else self.body)

    def test_download_is_verified_and_promoted_without_partial_files(self):
        with patch.object(runtime, 'fetch', side_effect=self.fake_fetch):
            runtime.prepare(self.directory, runtime.VERSIONS[0])
        self.assertEqual((self.directory / self.name).read_bytes(), self.body)
        self.assertEqual(len(list(self.directory.iterdir())), 2)

    def test_verified_cache_never_uses_the_network(self):
        with patch.object(runtime, 'fetch', side_effect=self.fake_fetch):
            runtime.prepare(self.directory, runtime.VERSIONS[0])
        with patch.object(runtime, 'fetch', side_effect=AssertionError('network must not be used')):
            runtime.prepare(self.directory, runtime.VERSIONS[0])

    def test_corrupt_cache_is_not_trusted(self):
        (self.directory / self.name).write_bytes(b'corrupt')
        (self.directory / (self.name + '.sha512')).write_text(self.digest)
        with patch.object(runtime, 'fetch', side_effect=self.fake_fetch) as fetch:
            runtime.prepare(self.directory, runtime.VERSIONS[0])
        self.assertEqual(fetch.call_count, 2)
        self.assertEqual((self.directory / self.name).read_bytes(), self.body)

    def test_checksum_failure_never_promotes_a_jar(self):
        def corrupt(url, target, maximum):
            self.fake_fetch(url, target, maximum)
            if url.endswith('.jar'):
                target.write_bytes(b'wrong bytes')
        with patch.object(runtime, 'fetch', side_effect=corrupt):
            with self.assertRaises(RuntimeError):
                runtime.prepare(self.directory, runtime.VERSIONS[0])
        self.assertFalse((self.directory / self.name).exists())
        self.assertFalse(list(self.directory.glob('*.part')))

    def test_rate_limit_falls_back_once_to_the_other_repository(self):
        def throttled(url, target, maximum):
            if url.startswith(runtime.REPOSITORIES[0]):
                raise OSError('HTTP 429')
            self.fake_fetch(url, target, maximum)
        with patch.object(runtime, 'fetch', side_effect=throttled) as fetch:
            runtime.prepare(self.directory, runtime.VERSIONS[0])
        self.assertEqual(fetch.call_count, 3)

    def test_invalid_zip_and_missing_framework_classes_are_rejected(self):
        path = self.directory / 'invalid.jar'
        path.write_bytes(b'not a zip')
        with self.assertRaises(BadZipFile):
            runtime.verify(path, runtime.file_hash(path))
        with ZipFile(path, 'w') as fixture:
            fixture.writestr('unrelated', b'not Android')
        with self.assertRaises(KeyError):
            runtime.verify(path, runtime.file_hash(path))

    def test_download_size_is_bounded(self):
        with patch.object(runtime.urllib.request, 'urlopen', return_value=BytesIO(b'12345')):
            with self.assertRaises(ValueError):
                runtime.fetch('https://example.test/runtime.jar', self.directory / 'partial', 4)


if __name__ == '__main__':
    unittest.main(verbosity=2)
