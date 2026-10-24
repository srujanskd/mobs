#!/usr/bin/env python3
"""Fail closed on incomplete streaming measurements. Uses only Python's standard library."""
import argparse
import csv
import json
import math
import statistics
import sys
from pathlib import Path

REQUIRED = {
    'elapsed_s', 'connection', 'output_fps', 'dropped_frames', 'thermal', 'battery_pct',
    'plugged', 'width', 'height', 'profile_fps', 'scene_error', 'rss_kb', 'sample_kind',
    'rear_camera', 'front_camera', 'rear_capture_fps', 'front_capture_fps',
}


def number(row, key):
    try:
        value = float(row[key])
    except (ValueError, KeyError, TypeError) as exc:
        raise ValueError(f'Missing or invalid {key}') from exc
    if not math.isfinite(value):
        raise ValueError(f'Nonfinite {key}')
    return value


def evaluate(path, budget, endurance=False):
    with Path(path).open(newline='') as file:
        reader = csv.DictReader(file)
        if not REQUIRED.issubset(reader.fieldnames or []):
            raise ValueError('CSV is missing required measurement columns. Use a current benchmark build.')
        if len(set(reader.fieldnames)) != len(reader.fieldnames):
            raise ValueError('Duplicate CSV columns')
        rows = list(reader)
    if len(rows) < 2:
        raise ValueError('At least two samples are required')
    terminal = rows[-1]
    if terminal['sample_kind'] != 'end' or terminal['connection'] not in ('idle', 'disconnected'):
        raise ValueError('Session lacks a clean stop marker. Crashed, failed, and truncated runs cannot pass.')
    rows = rows[:-1]
    if len(rows) < 2 or any(row['sample_kind'] != 'sample' for row in rows):
        raise ValueError('Unexpected session markers')
    times = [number(row, 'elapsed_s') for row in rows]
    if times[0] > 2 or times[0] < 0:
        raise ValueError('Session must include its first sample')
    gaps = [end - start for start, end in zip(times, times[1:])]
    if any(gap <= 0 or gap > budget['max_sample_gap_s'] for gap in gaps):
        raise ValueError('Samples are unordered, duplicated, or have recording gaps')
    for row in rows:
        for key in ('rear_camera', 'front_camera', 'scene_error', 'plugged'):
            if row[key] not in ('true', 'false'):
                raise ValueError(f'Invalid boolean reading: {key}')
    failures = []
    end_time = number(terminal, 'elapsed_s')
    if not 0 <= end_time - times[-1] <= budget['max_sample_gap_s']:
        raise ValueError('Terminal marker has an invalid timestamp')
    duration = end_time - times[0]
    minimum = budget['endurance_duration_s'] if endurance else budget['min_duration_s']
    if duration < minimum:
        failures.append(f'Duration {duration:.1f}s is below {minimum}s')
    for row in rows:
        if tuple(number(row, key) for key in ('width', 'height', 'profile_fps')) != (1920, 1080, 30):
            failures.append('Output profile changed from 1920x1080 at 30 fps')
            break
    if any(row['scene_error'] != 'false' for row in rows):
        failures.append('Scene capture reported an error')
    if any(row['connection'] == 'failed' for row in rows):
        failures.append('A terminal transport failure occurred')
    samples = [row for row in rows if number(row, 'elapsed_s') >= budget['warmup_s']]
    if len(samples) < budget['min_samples']:
        raise ValueError('Insufficient samples after warm-up')
    for facing in ('rear', 'front'):
        active = [row for row in samples if row[facing + '_camera'] == 'true']
        if active:
            capture_fps = statistics.mean(number(row, facing + '_capture_fps') for row in active)
            if capture_fps < budget['min_mean_fps']:
                failures.append(f'{facing} camera captured only {capture_fps:.2f} fps')
    fps = [number(row, 'output_fps') for row in samples]
    if any(value < 0 or value > 120 for value in fps):
        raise ValueError('Impossible output frame-rate sample')
    mean_fps = statistics.mean(fps)
    p05_fps = sorted(fps)[int((len(fps) - 1) * 0.05)]
    disconnected = sum(row['connection'] != 'connected' for row in samples) / len(samples)
    drops = [number(row, 'dropped_frames') for row in rows]
    if any(value < 0 for value in drops) or any(b < a for a, b in zip(drops, drops[1:])):
        raise ValueError('Drop counter reset or invalid cumulative value')
    produced = sum(number(a, 'output_fps') * (number(b, 'elapsed_s') - number(a, 'elapsed_s')) for a, b in zip(samples, samples[1:]))
    drop_ratio = (number(samples[-1], 'dropped_frames') - number(samples[0], 'dropped_frames')) / max(produced, 1)
    rss = [number(row, 'rss_kb') / 1024 for row in samples]
    if any(value <= 0 for value in rss):
        raise ValueError('RSS readings must be positive')
    window = min(60, len(rss) // 2)
    memory_growth = statistics.median(rss[-window:]) - statistics.median(rss[:window])
    severe_fraction = sum(row['thermal'] in ('severe', 'critical', 'emergency', 'shutdown') for row in samples) / len(samples)
    if any(row['thermal'] not in ('none', 'light', 'moderate', 'severe', 'critical', 'emergency', 'shutdown') for row in samples):
        raise ValueError('Unknown thermal status')
    metrics = dict(duration_s=duration, mean_fps=mean_fps, p05_fps=p05_fps,
                   disconnected_fraction=disconnected, transport_drop_ratio=drop_ratio,
                   rss_growth_mb=memory_growth, severe_thermal_fraction=severe_fraction)
    for key, limit_key, below in (
        ('mean_fps', 'min_mean_fps', True), ('p05_fps', 'min_p05_fps', True),
        ('disconnected_fraction', 'max_disconnected_fraction', False),
        ('transport_drop_ratio', 'max_transport_drop_ratio', False),
        ('rss_growth_mb', 'max_rss_growth_mb', False),
        ('severe_thermal_fraction', 'max_severe_thermal_fraction', False),
    ):
        value, limit = metrics[key], budget[limit_key]
        if (value < limit if below else value > limit):
            failures.append(f'{key}={value:.4f} violates {limit_key}={limit}')
    if endurance:
        if any(row['plugged'] != 'false' for row in rows + [terminal]):
            failures.append('Endurance measurement includes external power')
        battery = [number(row, 'battery_pct') for row in rows + [terminal]]
        if any(not 0 <= value <= 100 for value in battery):
            raise ValueError('Battery readings outside 0..100')
        if battery[0] < budget['min_start_battery_pct']:
            failures.append('Endurance run did not start fully charged')
        if battery[-1] < budget['min_end_battery_pct']:
            failures.append('Battery reserve is below the endurance target')
        metrics['battery_drain_pct_per_hour'] = (battery[0] - battery[-1]) * 3600 / duration
    return {'passed': not failures, 'metrics': metrics, 'failures': failures}


def compare(result, baseline, budget):
    """Compare only matched physical-device runs; callers must retain the run manifest."""
    for metric, tolerance in budget['baseline_regression'].items():
        old = baseline['metrics'][metric]
        new = result['metrics'][metric]
        if metric.endswith('fps'):
            regressed = new < old * (1 - tolerance)
        else:
            regressed = new > old + tolerance
        if regressed:
            result['failures'].append(f'{metric} regressed against the matched baseline: {old:.4f} -> {new:.4f}')
    result['passed'] = not result['failures']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv', type=Path)
    parser.add_argument('--budget', type=Path, default=Path(__file__).with_name('budgets.json'))
    parser.add_argument('--baseline-csv', type=Path)
    parser.add_argument('--endurance', action='store_true')
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--baseline-manifest', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        budget = json.loads(args.budget.read_text())
        result = evaluate(args.csv, budget, args.endurance)
        if args.manifest:
            manifest = json.loads(args.manifest.read_text())
            import hashlib
            if not manifest.get('physical_device') or not manifest.get('completed_at'):
                raise ValueError('Run manifest does not describe a completed physical-device run')
            if manifest.get('csv_sha256') != hashlib.sha256(args.csv.read_bytes()).hexdigest():
                raise ValueError('CSV does not match the recorded run')
            expected_cameras = {'rear': ('true', 'false'), 'front': ('false', 'true'),
                                'dual': ('true', 'true'), 'static': ('false', 'false')}.get(manifest.get('case'))
            if expected_cameras:
                with args.csv.open(newline='') as file:
                    samples = [row for row in csv.DictReader(file) if row['sample_kind'] == 'sample' and number(row, 'elapsed_s') >= budget['warmup_s']]
                if any((row['rear_camera'], row['front_camera']) != expected_cameras for row in samples):
                    raise ValueError('Recorded camera sources do not match the run case')
        if args.baseline_csv:
            if not args.manifest or not args.baseline_manifest:
                raise ValueError('Baseline comparisons require both run manifests')
            previous = json.loads(args.baseline_manifest.read_text())
            for key in ('physical_device', 'fingerprint', 'serial', 'case', 'network', 'brightness_pct', 'brightness_setting', 'brightness_mode'):
                if manifest.get(key) != previous.get(key):
                    raise ValueError(f'Baseline conditions differ: {key}')
            if abs(manifest['ambient_c'] - previous['ambient_c']) > 2:
                raise ValueError('Baseline ambient temperature differs by more than 2 C')
            if previous.get('csv_sha256') != hashlib.sha256(args.baseline_csv.read_bytes()).hexdigest():
                raise ValueError('Baseline CSV does not match its manifest')
            if not previous.get('completed_at'):
                raise ValueError('Baseline manifest is incomplete')
            baseline = evaluate(args.baseline_csv, budget, args.endurance)
            if not baseline['passed']:
                raise ValueError('Baseline does not pass the absolute gates')
            compare(result, baseline, budget)
    except (ValueError, OSError, KeyError, csv.Error) as exc:
        result = {'passed': False, 'metrics': {}, 'failures': [str(exc)]}
    report = json.dumps(result, indent=2, allow_nan=False)
    print(report)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report + '\n')
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
