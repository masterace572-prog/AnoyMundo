#!/usr/bin/env python3
"""Regression tests for the compact-SVG / pre-Marshmallow parser mismatch."""
import unittest
from normalize_vector_paths import normalize_path


class VectorPathTests(unittest.TestCase):
    def test_compact_arc_flags(self):
        self.assertEqual(normalize_path('M9 9.5a3 2.5 0 016 0c0 2-2 2-3 3.5'),
                         'M 9 9.5 a 3 2.5 0 0 1 6 0 c 0 2 -2 2 -3 3.5')

    def test_adjacent_fractions_and_signs(self):
        self.assertEqual(normalize_path('M.5.5l-.635-.024'), 'M 0.5 0.5 l -0.635 -0.024')

    def test_exponents_expand_without_rounding(self):
        self.assertEqual(normalize_path('M1e-3 2E2'), 'M 0.001 200')

    def test_repeated_arc_groups(self):
        self.assertEqual(normalize_path('M0 0a1 1 0 011 0 1 1 0 01-1 0z'),
                         'M 0 0 a 1 1 0 0 1 1 0 1 1 0 0 1 -1 0 z')

    def test_idempotent(self):
        path = normalize_path('M0 0c.5.5-.5-.5 10 20')
        self.assertEqual(normalize_path(path), path)

    def test_malformed_commands_rejected(self):
        for path in ('M 0', 'M 0 0 a 1 1 0 016', 'M0 0a1 1 0 3 1 2 2', 'L0 0', 'M0 0Z 2 2'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                normalize_path(path)


if __name__ == '__main__':
    unittest.main()
