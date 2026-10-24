#!/usr/bin/env python3
"""Gate Macrobenchmark cold startup JSON. Never suppress emulator or thermal errors."""
import argparse
import json
import math
import statistics
import sys
from pathlib import Path


def extract(path):
    data = json.loads(Path(path).read_text())
    if data.get('context', {}).get('emulator', False):
        raise ValueError('Performance gates require a physical device')
    build = data.get('context', {}).get('build', {})
    if not build.get('fingerprint') or not build.get('model'):
        raise ValueError('Benchmark device metadata is missing')
    identity = str(build).lower()
    if any(marker in identity for marker in ('generic', 'emulator', 'sdk_gphone', 'sdk_phone')):
        raise ValueError('Performance gates require a physical device')
    cases = [case for case in data['benchmarks'] if case['name'] == 'coldStartup']
    if len(cases) != 1:
        raise ValueError('Expected exactly one coldStartup benchmark')
    case = cases[0]
    if case.get('warmupIterations', 0) < 0 or case.get('suppressedErrors'):
        raise ValueError('Benchmark has suppressed validation errors')
    runs = case['metrics']['timeToInitialDisplayMs']['runs']
    if len(runs) < 10 or any(not isinstance(x, (int, float)) or not math.isfinite(x) or x <= 0 for x in runs):
        raise ValueError('Expected at least 10 valid cold startup iterations')
    return data, statistics.median(runs), sorted(runs)[math.ceil(len(runs) * 0.95) - 1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('json', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    failures = []
    metrics = {}
    try:
        data, median, p95 = extract(args.json)
        budget = json.loads(Path(__file__).with_name('budgets.json').read_text())['startup']
        metrics = {'median_ms': median, 'p95_ms': p95}
        if median > budget['max_median_ms'] or p95 > budget['max_p95_ms']:
            failures.append('Cold startup exceeds the absolute budget')
        if args.baseline:
            baseline, old_median, old_p95 = extract(args.baseline)
            for key in ('build',):
                if data['context'].get(key) != baseline['context'].get(key):
                    raise ValueError('Baseline device/OS differs from the current run')
            if median > old_median * (1 + budget['max_regression_fraction']) or p95 > old_p95 * (1 + budget['max_regression_fraction']):
                failures.append('Cold startup regressed against the same-device baseline')
    except (ValueError, OSError, KeyError, TypeError) as exc:
        failures.append(str(exc))
    result = {'passed': not failures, 'metrics': metrics, 'failures': failures}
    report = json.dumps(result, indent=2, allow_nan=False)
    print(report)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report + '\n')
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
