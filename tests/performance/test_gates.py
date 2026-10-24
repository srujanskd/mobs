import copy
import csv
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/performance' / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


session = module('check_session')
startup = module('check_startup')


class GateTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.file = Path(self.directory.name) / 'session.csv'
        self.budget = json.loads((ROOT / 'scripts/performance/budgets.json').read_text())
        self.budget.update(min_duration_s=10, warmup_s=2, min_samples=8)
        self.rows = [dict(elapsed_s=i, connection='connected', output_fps=30, dropped_frames=0,
                          thermal='none', battery_pct=100, plugged='false', width=1920, height=1080,
                          profile_fps=30, scene_error='false', rss_kb=102400, sample_kind='sample',
                          rear_camera='true', front_camera='false', rear_capture_fps=30, front_capture_fps=0) for i in range(11)]

    def run_gate(self):
        with self.file.open('w', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=list(self.rows[0]))
            writer.writeheader()
            writer.writerows(self.rows)
            terminal = dict(self.rows[-1], sample_kind="end", connection="idle")
            writer.writerow(terminal)
        return session.evaluate(self.file, self.budget)

    def test_healthy_session_passes_and_metrics_have_units(self):
        result = self.run_gate()
        self.assertTrue(result['passed'])
        self.assertEqual(30, result['metrics']['mean_fps'])
        self.assertEqual(0, result['metrics']['rss_growth_mb'])

    def test_each_regression_is_rejected(self):
        for key, value in [('output_fps', 20), ('thermal', 'severe'), ('connection', 'reconnecting'),
                           ('scene_error', 'true'), ('rear_capture_fps', 15), ('width', 1280), ('profile_fps', 15)]:
            with self.subTest(key=key):
                old = copy.deepcopy(self.rows)
                for row in self.rows:
                    row[key] = value
                self.assertFalse(self.run_gate()['passed'])
                self.rows = old

    def test_short_truncated_trace_cannot_pass(self):
        self.budget['min_duration_s'] = 1800
        self.assertFalse(self.run_gate()['passed'])

    def test_missing_nan_and_gapped_samples_fail_closed(self):
        for value in ('', 'nan', 'inf'):
            self.rows[4]['rss_kb'] = value
            with self.assertRaises(ValueError):
                self.run_gate()
        self.rows[4]['rss_kb'] = 102400
        self.rows[4]['elapsed_s'] = 20
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_drop_counter_reset_is_not_mistaken_for_an_improvement(self):
        self.rows[3]['dropped_frames'] = 100
        with self.assertRaises(ValueError):
            self.run_gate()

    def test_memory_growth_and_transport_drops_are_rejected(self):
        for i, row in enumerate(self.rows):
            row['rss_kb'] += i * 30 * 1024
            row['dropped_frames'] = i * 10
        result = self.run_gate()
        self.assertFalse(result['passed'])
        self.assertTrue(any('rss_growth_mb' in x for x in result['failures']))
        self.assertTrue(any('transport_drop_ratio' in x for x in result['failures']))

    def test_baseline_comparison_detects_relative_regression(self):
        result = self.run_gate()
        baseline = copy.deepcopy(result)
        result['metrics']['mean_fps'] = 29
        session.compare(result, baseline, self.budget)
        self.assertFalse(result['passed'])

    def test_endurance_requires_full_duration_unplugged_and_battery_reserve(self):
        self.budget.update(endurance_duration_s=10)
        self.run_gate()
        self.assertTrue(session.evaluate(self.file, self.budget, True)['passed'])
        self.rows[-1]['plugged'] = 'true'
        self.rows[-1]['battery_pct'] = 5
        self.run_gate()
        result = session.evaluate(self.file, self.budget, True)
        self.assertFalse(result['passed'])
        self.assertEqual(2, len(result['failures']))

    def test_missing_stop_marker_is_rejected_even_with_healthy_samples(self):
        self.run_gate()
        lines = self.file.read_text().splitlines()
        self.file.write_text("\n".join(lines[:-1]) + "\n")
        with self.assertRaisesRegex(ValueError, "clean stop marker"):
            session.evaluate(self.file, self.budget)

    def test_startup_requires_ten_finite_iterations(self):
        file = Path(self.directory.name) / 'startup.json'
        data = dict(context={'emulator': False, 'build': {'model': 'Pixel 7', 'fingerprint': 'google/panther/test-build'}}, benchmarks=[dict(name='coldStartup', metrics={'timeToInitialDisplayMs': {'runs': [500] * 10}})])
        file.write_text(json.dumps(data))
        self.assertEqual((500, 500), startup.extract(file)[1:])
        data['benchmarks'][0]['metrics']['timeToInitialDisplayMs']['runs'] = [500] * 9
        file.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            startup.extract(file)
        data['context']['emulator'] = True
        file.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            startup.extract(file)


if __name__ == '__main__':
    unittest.main()
