#!/usr/bin/env python3
"""Capture a physical-phone run manifest and pull only the new session's measurements."""
import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path
import subprocess
import sys


def adb(serial, *args):
    return subprocess.check_output(['adb', '-s', serial, *args], text=True).strip()


def sessions(serial):
    command = 'ls /sdcard/Android/data/dev.mobs/files/sessions/session-*.csv 2>/dev/null || true'
    return set(adb(serial, 'shell', command).splitlines())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['begin', 'end'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--case', choices=['rear', 'front', 'static', 'dual', 'full-scene'])
    parser.add_argument('--network', choices=['wifi', 'cellular'])
    parser.add_argument('--brightness-pct', type=int)
    parser.add_argument('--ambient-c', type=float)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    manifest_file = args.directory / 'manifest.json'
    if adb(args.serial, 'shell', 'getprop ro.kernel.qemu') == '1':
        parser.error('Physical phones are required for performance evidence')
    if args.action == 'begin':
        if any(value is None for value in (args.case, args.network, args.brightness_pct, args.ambient_c, args.apk)):
            parser.error('begin requires --case, --network, --brightness-pct, --ambient-c, and --apk')
        if not 1 <= args.brightness_pct <= 100 or not -20 <= args.ambient_c <= 60:
            parser.error('Brightness or temperature is outside the supported recording range')
        if manifest_file.exists():
            parser.error('Use a new directory for every run')
        args.directory.mkdir(parents=True, exist_ok=True)
        package = adb(args.serial, 'shell', 'dumpsys package dev.mobs')
        if re.search(r'\bDEBUGGABLE\b', package):
            parser.error('Install the optimized benchmark build before measuring')
        apk_hash = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        installed_path = adb(args.serial, 'shell', 'pm path dev.mobs').removeprefix('package:')
        if not re.fullmatch(r'/data/app/[a-zA-Z0-9_/+=.~-]+\.apk', installed_path):
            parser.error('Expected one installed Mobs base APK')
        installed_hash = adb(args.serial, 'shell', 'sha256sum', installed_path).split()[0]
        if apk_hash != installed_hash:
            parser.error('The supplied APK differs from the build installed on the phone')
        fingerprint = adb(args.serial, 'shell', 'getprop ro.build.fingerprint')
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], text=True).strip())
        manifest = dict(schema=1, physical_device=True, serial=args.serial, fingerprint=fingerprint,
                        case=args.case, network=args.network, brightness_pct=args.brightness_pct,
                        ambient_c=args.ambient_c, revision=revision, dirty=dirty,
                        apk_sha256=apk_hash,
                        brightness_setting=adb(args.serial, 'shell', 'settings get system screen_brightness'),
                        brightness_mode=adb(args.serial, 'shell', 'settings get system screen_brightness_mode'),
                        started_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                        previous_sessions=sorted(sessions(args.serial)))
        manifest_file.write_text(json.dumps(manifest, indent=2) + '\n')
        print('Manifest recorded. Start one stream on the phone, run the scenario, then stop normally.')
    else:
        manifest = json.loads(manifest_file.read_text())
        if manifest['serial'] != args.serial:
            parser.error('This run belongs to a different phone')
        if manifest.get('completed_at'):
            parser.error('This run has already been collected')
        new = sessions(args.serial) - set(manifest['previous_sessions'])
        if len(new) != 1:
            parser.error(f'Expected one new session, found {len(new)}. Keep each run separate.')
        session_file = new.pop()
        subprocess.run(['adb', '-s', args.serial, 'pull', session_file, str(args.directory / 'session.csv')], check=True)
        manifest['completed_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        manifest['csv_sha256'] = hashlib.sha256((args.directory / 'session.csv').read_bytes()).hexdigest()
        manifest_file.write_text(json.dumps(manifest, indent=2) + '\n')
        print('Collected session.csv. Run check_session.py to evaluate the run.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
