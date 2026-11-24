#!/usr/bin/env python3
"""Offline parser tests only. These fixtures are NOT a frame-time benchmark."""
import unittest

from profile_loader_ui import parse_framestats


HEADER = 'Flags,IntendedVsync,Vsync,FrameCompleted,\n'


def block(window, rows):
    return 'Window: ' + window + '\n---PROFILEDATA---\n' + HEADER + ''.join(rows) + '---PROFILEDATA---\n'


def row(start, duration, flags=0):
    return f'{flags},{start},{start},{start + duration},\n'


class FrameStatsParserTest(unittest.TestCase):
    def test_complete_frames_report_latency_percentiles_not_average_fps(self):
        stats = block('com.xlox/com.xlox.MAct', [row(1_000_000_000, 10_000_000),
                                               row(2_000_000_000, 18_000_000),
                                               row(3_000_000_000, 30_000_000)])
        result = parse_framestats(stats)
        self.assertEqual(result['rendered_frames'], 3)
        self.assertEqual(result['frames_over_budget'], 2)
        self.assertEqual(result['p50_ms'], 18)
        self.assertEqual(result['p95_ms'], 30)
        self.assertEqual(result['status'], 'insufficient_frames')
        self.assertNotIn('fps', result)

    def test_virtual_game_and_web_provider_windows_are_excluded(self):
        data = block('com.xlox/com.xlox.MAct', [row(1_000_000_000, 12_000_000)])
        data += block('com.pubg.imobile/MainActivity', [row(2_000_000_000, 25_000_000)])
        data += block('com.xlox/com.xlox.GameWebActivity', [row(3_000_000_000, 50_000_000)])
        self.assertEqual(parse_framestats(data)['rendered_frames'], 1)

    def test_kenny_host_activity_uses_the_shared_java_namespace(self):
        data = block('com.kennypvt/com.xlox.LogAct', [row(1_000_000_000, 16_000_000)])
        self.assertEqual(parse_framestats(data)['rendered_frames'], 1)

    def test_flagged_unfinished_and_malformed_rows_do_not_skew_the_measurement(self):
        data = block('com.xlox/com.xlox.SplashActivity', [
            row(1_000_000_000, 10_000_000), row(2_000_000_000, 99_000_000, 1),
            '0,3000000000,3000000000,0,\n', '0,4000000000,4000000000,9223372036854775807,\n',
            'not,a,frame,\n'])
        result = parse_framestats(data)
        self.assertEqual(result['rendered_frames'], 1)
        self.assertEqual(result['ignored_incomplete_or_flagged_rows'], 4)
        self.assertEqual(result['p95_ms'], 10)

    def test_duplicate_ring_buffer_rows_are_counted_once(self):
        same = row(1_000_000_000, 12_000_000)
        data = block('com.xlox/com.xlox.MAct', [same, same])
        self.assertEqual(parse_framestats(data)['rendered_frames'], 1)

    def test_no_complete_frames_does_not_report_zero_jank_as_a_success(self):
        result = parse_framestats('No graphics info for this process.\n')
        self.assertEqual(result['status'], 'insufficient_frames')
        self.assertIsNone(result['over_budget_percent'])
        self.assertIsNone(result['p95_ms'])

    def test_modern_extra_columns_and_trailing_commas_are_accepted(self):
        data = 'Window: com.xlox/com.xlox.MAct\n---PROFILEDATA---\n'
        data += 'Flags,FrameTimelineVsyncId,IntendedVsync,FrameCompleted,GpuCompleted,\n'
        for i in range(60):
            data += f'0,{i},{1000000000 + i * 20000000},{1010000000 + i * 20000000},0,\n'
        data += '---PROFILEDATA---\n'
        result = parse_framestats(data)
        self.assertEqual(result['rendered_frames'], 60)
        self.assertEqual(result['status'], 'captured')
        self.assertEqual(result['frames_over_budget'], 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
