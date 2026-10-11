#!/usr/bin/env python3
"""Inspect the two frozen optimized APKs through the real shopping entry point."""
from pathlib import Path
import argparse, hashlib, json, sys, urllib.request
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'projects/commerce-app/tools'))
from capture_checkpoint_appearance import CaptureSession

def main():
    project = Path(__file__).resolve().parents[1]
    out = project / 'evidence/release-smoke'
    assert not out.exists(), 'Never overwrite retained smoke evidence.'
    out.mkdir()
    args = argparse.Namespace(package='staff.android.costs', serial='emulator-5554', output=out,
                              settle_timeout=45, max_scrolls=16)
    session = CaptureSession(args)
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report = {'status': 'STARTING', 'scope': 'API36 emulator optimized lean/archive APK shopping and HTTP smoke; not energy or timing.',
              'scriptSha256': sha(Path(__file__)), 'rows': []}
    def save():
        report['commands'] = session.commands
        report['tapGuards'] = session.tap_guards
        (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    def admin(path, payload=None):
        req = urllib.request.Request('http://127.0.0.1:8874' + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response: return json.load(response)
    try:
        report['settings'] = session.settings()
        assert 'StaffAndroid_API_36' in session.adb('emu', 'avd', 'name').decode().splitlines()
        session.adb('reverse', 'tcp:8874', 'tcp:8874')
        for flavor in ['lean', 'archive']:
            apk = project / f'evidence/runtime-build2/artifacts/app/build/outputs/apk/{flavor}/release/app-{flavor}-release.apk'
            row = {'flavor': flavor, 'apk': str(apk.relative_to(project)), 'apkSha256': sha(apk), 'frames': []}
            report['rows'].append(row)
            session.adb('install', '-r', apk)
            assert session.adb('shell', 'pm', 'clear', args.package).decode().strip() == 'Success'
            admin('/admin/reset', {})
            installed = session.adb('shell', 'pm', 'path', args.package).decode().strip().removeprefix('package:')
            dest = out / (flavor + '-installed.apk')
            session.adb('pull', installed, dest)
            assert sha(dest) == row['apkSha256']
            session.adb('shell', 'am', 'start', '-W', '-n', args.package + '/.MainActivity')
            raw = session.settled(exact=['shop'])
            row['frames'].append(session.capture(flavor + '-shop', raw))
            raw = session.tap('open_saved', raw)
            raw = session.settled(exact=['saved_screen', 'price_camera'], contains=['12,999'])
            row['frames'].append(session.capture(flavor + '-saved', raw))
            raw = session.tap('View Camera', raw)
            raw = session.settled(exact=['detail'])
            row['frames'].append(session.capture(flavor + '-detail', raw))
            row['serverRecords'] = admin('/admin/records')
            row['status'] = 'SMOKE_PASSED_PIXELS_NOT_YET_REVIEWED'
            session.adb('shell', 'am', 'force-stop', args.package)
            save()
            print(flavor + ' release smoke passed', flush=True)
        report['status'] = 'PASS_SHOPPING_HTTP_SMOKE_PIXELS_NOT_YET_REVIEWED'
    except Exception as error:
        report['status'] = 'FAILED'
        report['error'] = repr(error)
        raise
    finally:
        session.adb('shell', 'am', 'force-stop', args.package)
        save()

if __name__ == '__main__': main()
