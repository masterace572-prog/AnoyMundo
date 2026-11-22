#!/usr/bin/env python3
"""Regression tests for supplied artwork and APK verification (no Android SDK)."""
from contextlib import redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from PIL import Image
import kenny_icons as icons


class KennyArtworkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = icons.artwork()
        cls.images = icons.resources(cls.source)

    def test_original_image_is_preserved_not_redrawn(self):
        with Image.open(icons.SOURCE) as original:
            self.assertEqual(self.source.size, (640, 640))
            self.assertEqual(self.source.convert('RGB').tobytes(), original.convert('RGB').tobytes())
        # At native size the visible interior is untouched; only the mask changes.
        rounded = icons.badge(self.source, 640)
        for point in ((320, 1), (320, 320), (80, 320), (550, 320), (320, 630)):
            self.assertEqual(rounded.getpixel(point), self.source.getpixel(point))

    def test_unreviewed_source_change_is_rejected(self):
        with TemporaryDirectory() as directory:
            changed = Path(directory) / 'changed.jpg'
            changed.write_bytes(icons.SOURCE.read_bytes() + b'changed')
            with patch.object(icons, 'SOURCE', changed):
                with self.assertRaisesRegex(ValueError, 'source artwork changed'):
                    icons.artwork()

    def test_both_fallbacks_have_smooth_transparent_corners(self):
        for size in (48, 72, 96, 144, 192):
            for circle in (False, True):
                alpha = icons.badge(self.source, size, circle=circle).getchannel('A')
                self.assertEqual(alpha.getpixel((0, 0)), 0)
                self.assertEqual(alpha.getpixel((size // 2, size // 2)), 255)
                self.assertTrue(any(0 < value < 255 for value in alpha.tobytes()))
        square = icons.badge(self.source, 96).getchannel('A')
        circle = icons.badge(self.source, 96, circle=True).getchannel('A')
        self.assertGreater(square.getpixel((8, 8)), circle.getpixel((8, 8)))

    def test_all_densities_and_system_splash_safe_area(self):
        self.assertEqual(len(self.images), 20)
        icons.check_geometry(self.images)

    def test_unintentional_anoy_art_change_is_rejected(self):
        with patch.dict(icons.ANOY_ICONS, {'drawable/ic_launcher.xml': '0' * 64}):
            with self.assertRaisesRegex(AssertionError, 'Anoy icon changed'):
                icons.check_geometry(self.images)

    def test_pixel_check_detects_colour_and_canvas_changes(self):
        expected = icons.badge(self.source, 48)
        self.assertTrue(icons.equal_pixels(expected.copy(), expected))
        changed = expected.copy()
        changed.putpixel((24, 24), (255, 0, 0, 255))
        self.assertFalse(icons.equal_pixels(changed, expected))
        self.assertFalse(icons.equal_pixels(expected.resize((49, 49)), expected))

    def make_apk(self, path, *, missing=None, corrupt=None, shortened=True):
        entries = {}
        with ZipFile(path, 'w') as apk:
            for index, (source, expected) in enumerate(self.images.items()):
                if source == missing:
                    continue
                image = expected.copy()
                if source == corrupt:
                    image.putpixel((image.width // 2, image.height // 2), (0, 255, 0, 255))
                encoded = BytesIO()
                image.save(encoded, format='PNG', optimize=False)
                # Release AGP shortens paths; debug aapt commonly appends -v4.
                filename = f'res/{index:02x}.png' if shortened else f'res/{source.parent.name}-v4/{source.name}'
                apk.writestr(filename, encoded.getvalue())
                density = source.parent.name.removeprefix('drawable-')
                entries.setdefault(source.stem, []).append(f'    ({density}-v4) (file) {filename} type=PNG\n')
        return ''.join(f'  resource {0x7f080100 + index:#010x} com.kennypvt:drawable/{name}\n' + ''.join(configs)
                       for index, (name, configs) in enumerate(entries.items()))

    def test_resource_table_parser_keeps_names_and_density_variants_separate(self):
        table = '''  resource 0x7f080100 drawable/kenny_launcher_legacy_extra
    (mdpi-v4) (file) res/not-the-icon.png type=PNG
  resource 0x7f080101 com.kennypvt:drawable/kenny_launcher_legacy
    (mdpi-v4) (file) res/a.png type=PNG
    (240dpi) (file) "res/b.png" type=PNG
'''
        images = icons.packaged_images(table)
        self.assertEqual(images['kenny_launcher_legacy', 'mdpi'], ['res/a.png'])
        self.assertEqual(images['kenny_launcher_legacy', 'hdpi'], ['res/b.png'])
        self.assertEqual(images['kenny_launcher_legacy_extra', 'mdpi'], ['res/not-the-icon.png'])
        self.assertNotIn(('kenny_launcher_legacy', 'xhdpi'), images)

    def test_reencoded_and_renamed_apk_pngs_pass_by_pixels_not_file_hash(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / 'kenny.apk'
            for shortened in (True, False):
                table = self.make_apk(apk, shortened=shortened)
                with redirect_stdout(StringIO()):
                    icons.check_apk(apk, self.images, resource_dump=table)

    def test_apk_missing_one_density_fails(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / 'missing.apk'
            table = self.make_apk(apk, missing=next(iter(self.images)))
            with self.assertRaisesRegex(AssertionError, 'Missing or ambiguous packaged icon'):
                icons.check_apk(apk, self.images, resource_dump=table)

    def test_apk_reassigned_density_fails(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / 'wrong-density.apk'
            table = self.make_apk(apk).replace('res/00.png', 'res/04.png', 1)
            with self.assertRaisesRegex(AssertionError, 'Packaged icon density changed'):
                icons.check_apk(apk, self.images, resource_dump=table)

    def test_apk_ambiguous_density_fails(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / 'ambiguous.apk'
            table = self.make_apk(apk)
            table = table.replace('(mdpi-v4) (file) res/00.png type=PNG',
                                  '(mdpi-v4) (file) res/00.png type=PNG\n    (mdpi-v4) (file) res/01.png type=PNG', 1)
            with self.assertRaisesRegex(AssertionError, 'Missing or ambiguous packaged icon'):
                icons.check_apk(apk, self.images, resource_dump=table)

    def test_apk_changed_artwork_fails(self):
        with TemporaryDirectory() as directory:
            apk = Path(directory) / 'changed.apk'
            table = self.make_apk(apk, corrupt=next(iter(self.images)))
            with self.assertRaisesRegex(AssertionError, 'Packaged artwork changed'):
                icons.check_apk(apk, self.images, resource_dump=table)


if __name__ == '__main__':
    unittest.main()
