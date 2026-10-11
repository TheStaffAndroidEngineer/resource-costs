#!/usr/bin/env python3
"""Capture C24 native appearance with fresh XML guards; root owns execution.

This deliberately resets only the chapter fixture app between display configurations.
It restores display settings, the chapter reverse mapping and fixture configuration.
Captured pixels are not a visual pass or an energy/performance measurement.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'projects/commerce-app/tools'))
from capture_checkpoint_appearance import (CaptureSession, area, bounds, find_pane,
    intersection, matches, pane_key, panes, signature)


def visible_label(raw, label, package):
    tree = ET.fromstring(raw)
    parents = {child: parent for parent in tree.iter() for child in parent}
    for node in tree.iter('node'):
        if node.get('package') != package or not matches(node, label):
            continue
        original = bounds(node)
        visible = original
        parent = node
        while parent in parents:
            parent = parents[parent]
            if parent.get('scrollable') == 'true':
                visible = intersection(visible, bounds(parent))
        if area(original) and visible == original:
            return True
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', '--output', dest='output', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--expected-avd', default='StaffAndroid_API_36')
    parser.add_argument('--candidate', default='candidate1')
    parser.add_argument('--configs', nargs='+', choices=['light', 'dark', 'large', 'wide'],
                        default=['light', 'dark', 'large', 'wide'])
    parser.add_argument('--only-lab', action='store_true',
                        help='Capture only Shop and the Lab result journey, supplementing retained shopping frames.')
    parser.add_argument('--max-scrolls', type=int, default=16)
    parser.add_argument('--settle-timeout', type=float, default=45)
    args = parser.parse_args()
    args.package = 'staff.android.costs'
    if not 1 <= args.max_scrolls <= 24 or not 1 <= args.settle_timeout <= 120:
        parser.error('Use bounded scroll and settle limits.')
    apk = args.apk.resolve()
    if not apk.is_file(): parser.error('Supplied APK does not exist.')
    if args.output.exists(): parser.error('Use a new evidence directory; never overwrite captures.')
    args.output.mkdir(parents=True)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    session = CaptureSession(args)
    original = original_fixture = original_reverse = None
    failure = None
    report = {'status': 'STARTING', 'package': args.package, 'serial': args.serial,
              'candidate': args.candidate, 'apk': str(apk), 'apkSha256': sha(apk),
              'captureScriptSha256': sha(Path(__file__)),
              'captureMode': 'lab-only' if args.only_lab else 'full',
              'rows': [], 'fixtureActions': [], 'restorationErrors': [],
              'scope': 'API36 debug appearance: Shop, Saved fresh/changed/loading/cached failure/connection failure, uncached failure/retry, empty selection, Camera detail, optional background toggle and Lab. Each display configuration explicitly resets this fixture app. Not energy, representative timing, every device or TalkBack certification.'}

    def save():
        (args.output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')

    def admin(path, payload=None):
        request = urllib.request.Request('http://127.0.0.1:8874' + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response)
        report['fixtureActions'].append({'path': path, 'payload': payload})
        return result

    def configure(**values):
        return admin('/admin/config', values)

    def exact(raw, label):
        return any(matches(node, label) for node in ET.fromstring(raw).iter('node'))

    def idle():
        deadline = time.monotonic() + args.settle_timeout
        while time.monotonic() < deadline:
            raw = session.settled(exact=['saved_screen'])
            if not exact(raw, 'refresh_loading'):
                return raw
        raise RuntimeError('Price refresh did not finish within bounded wait.')

    def launch(row, saved=False, empty=False, reset=False):
        session.adb('shell', 'am', 'force-stop', args.package)
        if reset:
            result = session.adb('shell', 'pm', 'clear', args.package).decode().strip()
            if result != 'Success': raise RuntimeError('Fixture app reset failed: ' + result)
        command = ['shell', 'am', 'start', '-W', '-n', args.package + '/.MainActivity', '-f', '0x10008000']
        if saved: command += ['--es', 'route', 'saved', '--ez', 'auto', 'false']
        if empty: command += ['--ez', 'empty', 'true']
        session.adb(*command)
        row['launches'].append({'route': 'saved' if saved else 'shop', 'auto': False if saved else None,
                                'empty': empty, 'resetFixtureApp': reset})
        return session.settled(exact=['saved_screen' if saved else 'shop'])

    def reveal(raw, label):
        if visible_label(raw, label, args.package): return raw
        keys = [pane_key(p) for p in panes(ET.fromstring(raw), args.package)]
        for key in keys:
            for direction in ['up', 'down']:
                for _ in range(args.max_scrolls):
                    if visible_label(raw, label, args.package): return raw
                    pane = find_pane(ET.fromstring(raw), args.package, key)
                    if pane is None: break
                    before = signature(pane)
                    raw = session.swipe(pane, direction)
                    after = find_pane(ET.fromstring(raw), args.package, key)
                    if visible_label(raw, label, args.package): return raw
                    if after is None or signature(after) == before: break
        raise RuntimeError('Could not reveal status after bounded pane search: ' + label)

    def edge(raw, direction):
        # Avoid a redundant boundary swipe when the required edge content is
        # already fully visible. These checks use the current viewport XML.
        if direction == 'up' and visible_label(raw, 'Common Goods', args.package):
            return raw
        if direction == 'down':
            if exact(raw, 'saved_screen') and visible_label(raw, 'open_lab', args.package):
                return raw
            if exact(raw, 'detail') and visible_label(raw, 'Remove from saved', args.package):
                return raw
            if exact(raw, 'Resource investigation') and visible_label(raw, 'Restore saved selection', args.package):
                return raw
        keys = [pane_key(p) for p in panes(ET.fromstring(raw), args.package)]
        for key in keys:
            _, raw = session.seek(key, direction, raw=raw)
        return raw

    def capture(row, suffix, raw, condition):
        frame = session.capture(row['config'] + '-' + suffix, raw)
        frame['condition'] = condition
        row['frames'].append(frame)
        save()
        return raw

    def camera_price(raw, expected):
        raw = reveal(raw, 'price_camera')
        values = [node.get('text', '') for node in ET.fromstring(raw).iter('node') if matches(node, 'price_camera')]
        if len(values) != 1 or re.sub(r'\D', '', values[0]) != str(expected):
            raise RuntimeError('Unexpected visible Camera price: ' + repr(values))
        return raw

    def lab_journey(row, raw):
        raw = capture(row, 'lab-top', raw, 'Separate Resource investigation panel.')
        raw = session.tap('Restore saved selection', raw)
        raw = session.tap('Run three callers', raw)
        # The dialog is a separate Compose window and does not export this
        # Text's testTag as a resource ID. Match the actual observed text.
        result_text = 'Three callers joined the shared refresh. Inspect the server record.'
        raw = reveal(raw, result_text)
        raw = session.settled(exact=[result_text])
        raw = capture(row, 'lab-result', raw, 'Controlled shared-caller action finished; request accounting is a separate evidence campaign.')
        raw = capture(row, 'lab-lower', edge(raw, 'down'), 'Lab lower controls after bounded scrolling; full panel may already fit.')
        return session.tap('Close', raw)

    def finish_row(row):
        row['status'] = 'CAPTURED_NOT_REVIEWED'
        (args.output / (row['config'] + '-fixture-records.json')).write_text(json.dumps(admin('/admin/records'), indent=2) + '\n')
        save(); print(row['config'] + ' captured', flush=True)

    def tap_without_settle(label, raw):
        # Transient loading needs one immediate fresh XML read, not two stable reads.
        raw = reveal(raw, label)
        raw = session.xml()
        target = session.target(ET.fromstring(raw), label)
        if target is None: raise RuntimeError('Fresh enabled target missing before transient capture.')
        x, y, right, bottom = bounds(target)
        session.adb('shell', 'input', 'tap', (x + right) // 2, (y + bottom) // 2)
        return session.xml()

    try:
        avd = session.adb('emu', 'avd', 'name').decode().strip()
        if args.expected_avd not in avd.splitlines(): raise RuntimeError('Unexpected emulator: ' + avd)
        report['avd'] = avd
        original = session.settings(); report['originalSettings'] = original
        original_reverse = session.adb('reverse', '--list').decode()
        report['originalReverse'] = original_reverse
        mappings = [line.split() for line in original_reverse.splitlines() if line.strip()]
        prior = [parts for parts in mappings if len(parts) >= 3 and parts[-2] == 'tcp:8874']
        if len(prior) > 1: raise RuntimeError('Ambiguous original tcp8874 reverse mapping.')
        original_fixture = admin('/admin/records')
        (args.output / 'fixture-before.json').write_text(json.dumps(original_fixture, indent=2) + '\n')
        session.adb('reverse', 'tcp:8874', 'tcp:8874')
        session.adb('install', '-r', apk)
        installed = session.adb('shell', 'pm', 'path', args.package).decode().strip().removeprefix('package:')
        session.adb('pull', installed, args.output / 'installed.apk')
        if sha(args.output / 'installed.apk') != report['apkSha256']:
            raise RuntimeError('Installed package differs from supplied APK.')
        for config in args.configs:
            session.adb('shell', 'am', 'force-stop', args.package)
            session.adb('shell', 'wm', 'size', '1920x1200' if config == 'wide' else 'reset')
            session.adb('shell', 'wm', 'density', '240' if config == 'wide' else 'reset')
            session.adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0' if config == 'large' else '1.0')
            session.adb('shell', 'cmd', 'uimode', 'night', 'yes' if config == 'dark' else 'no')
            row = {'config': config, 'settings': session.settings(), 'status': 'STARTING', 'frames': [], 'launches': []}
            report['rows'].append(row)
            assert float(row['settings']['font']) == (2.0 if config == 'large' else 1.0)
            assert 'Night mode: ' + ('yes' if config == 'dark' else 'no') in row['settings']['night']
            if config == 'wide':
                assert 'Override size: 1920x1200' in row['settings']['size'] and 'Override density: 240' in row['settings']['density']
            else:
                assert 'Override' not in row['settings']['size'] and 'Override' not in row['settings']['density']
            configure(revision=1, status=200, delay_ms=0, malformed=False)
            raw = launch(row, reset=True)
            raw = capture(row, 'shop-top', raw, 'Fresh fixture app; local products, no checked prices yet.')
            if args.only_lab:
                raw = session.tap('Lab', raw)
                raw = lab_journey(row, raw)
                finish_row(row)
                continue
            raw = session.tap('open_saved', raw); raw = idle()
            raw = capture(row, 'saved-ready-top', edge(raw, 'up'), 'Automatic first Saved entry returned the revision1 snapshot.')
            raw = camera_price(raw, 12999)
            raw = capture(row, 'saved-camera-ready', raw, 'Camera visibly costs ₹12,999 from the validated snapshot.')
            raw = edge(raw, 'down')
            raw = capture(row, 'saved-lower', raw, 'Lower Saved list and optional background check policy.')
            raw = session.tap('background_refresh', raw)
            raw = capture(row, 'background-enabled', raw, 'Optional connected/battery-not-low checks enabled by the visible switch.')
            raw = session.tap('background_refresh', raw)
            raw = session.tap('View Camera', raw)
            raw = capture(row, 'detail-top', raw, 'Camera detail reached from Saved.')
            raw = capture(row, 'detail-lower', edge(raw, 'down'), 'Camera description, last-check qualification and saved control.')
            raw = session.tap('Back', raw)
            raw = edge(raw, 'up')
            configure(delay_ms=4500)
            raw = tap_without_settle('refresh_prices', raw)
            if not exact(raw, 'refresh_loading'):
                raise RuntimeError('The actual loading state elapsed before the fresh XML capture; no loading acceptance claimed.')
            raw = capture(row, 'saved-loading', raw, 'Actual HTTP refresh pending; cached rows remain. This transient capture uses one immediate fresh XML read.')
            raw = idle(); configure(delay_ms=0)
            raw = capture(row, 'saved-revalidated', edge(raw, 'up'), 'Explicit Refresh revalidated unchanged snapshot; not a zero-exchange claim.')
            configure(revision=2)
            raw = session.tap('refresh_prices', raw); raw = idle()
            raw = capture(row, 'saved-changed-top', edge(raw, 'up'), 'Changed representation accepted after explicit Refresh.')
            raw = camera_price(raw, 11999)
            raw = capture(row, 'saved-camera-changed', raw, 'Same Camera now visibly costs ₹11,999.')
            configure(status=503)
            raw = session.tap('refresh_prices', raw); raw = idle()
            raw = reveal(raw, 'refresh_error')
            raw = capture(row, 'saved-cached-error', raw, 'Service503 retains the last successful snapshot and offers Try again.')
            raw = camera_price(raw, 11999)
            raw = capture(row, 'saved-cached-error-price', raw, 'Prior checked Camera price remains while refresh has failed.')
            configure(status=200)
            session.adb('reverse', '--remove', 'tcp:8874')
            raw = session.tap('refresh_prices', raw); raw = idle()
            raw = capture(row, 'saved-disconnected', reveal(raw, 'refresh_error'), 'Only chapter server forwarding is disconnected; old snapshot remains. Device-wide connectivity is unchanged.')
            session.adb('reverse', 'tcp:8874', 'tcp:8874')
            raw = session.tap('refresh_prices', raw); raw = idle()
            raw = capture(row, 'saved-recovered', edge(raw, 'up'), 'Try again succeeds after chapter connection is restored.')
            configure(status=503)
            raw = launch(row, saved=True, reset=True)
            raw = session.tap('refresh_prices', raw); raw = idle()
            raw = capture(row, 'saved-uncached-error', reveal(raw, 'refresh_error'), 'No saved snapshot exists; failed refresh is a recovery state rather than an empty selection.')
            configure(status=200)
            raw = session.tap('refresh_prices', raw); raw = idle()
            raw = capture(row, 'saved-uncached-retry', edge(raw, 'up'), 'Try again obtains the first usable snapshot after the uncached failure.')
            raw = launch(row, saved=True, empty=True)
            raw = capture(row, 'saved-empty-top', raw, 'Explicit debug empty selection; cached price snapshot is a separate state.')
            raw = capture(row, 'saved-empty-message', reveal(raw, 'saved_empty'), 'True empty selection offers Explore the collection.')
            raw = session.tap('open_lab', raw)
            raw = lab_journey(row, raw)
            finish_row(row)
        if sha(apk) != report['apkSha256']: raise RuntimeError('APK changed during capture.')
        if sha(Path(__file__)) != report['captureScriptSha256']:
            raise RuntimeError('Capture script changed during acquisition.')
        report['status'] = 'CAPTURED_NOT_REVIEWED'
    except Exception as error:
        failure = error; report['status'] = 'FAILED'; report['error'] = str(error)
        try: report['failureWitness'] = session.failure_witness()
        except Exception as witness_error: report['failureWitness'] = {'error': str(witness_error)}
    finally:
        commands = [('shell', 'am', 'force-stop', args.package)]
        if original:
            commands.append(('shell', 'settings', 'delete', 'system', 'font_scale') if original['font'] in ('null', '') else ('shell', 'settings', 'put', 'system', 'font_scale', original['font']))
            commands.append(('shell', 'cmd', 'uimode', 'night', original['night'].split(':')[-1].strip()))
            for kind in ['size', 'density']:
                match = re.search(r'Override ' + kind + r':\s*(\S+)', original[kind], re.I)
                commands.append(('shell', 'wm', kind, match.group(1) if match else 'reset'))
        for command in commands:
            try: session.adb(*command)
            except Exception as error: report['restorationErrors'].append(str(error))
        if original_reverse is not None:
            try:
                current = session.adb('reverse', '--list').decode()
                if any(len(p) >= 3 and p[-2] == 'tcp:8874' for p in map(str.split, current.splitlines())):
                    session.adb('reverse', '--remove', 'tcp:8874')
                for parts in map(str.split, original_reverse.splitlines()):
                    if len(parts) >= 3 and parts[-2] == 'tcp:8874': session.adb('reverse', parts[-2], parts[-1])
                report['restoredReverse'] = session.adb('reverse', '--list').decode()
                if sorted(report['restoredReverse'].splitlines()) != sorted(original_reverse.splitlines()):
                    report['restorationErrors'].append('Reverse mappings differ from original.')
            except Exception as error: report['restorationErrors'].append(str(error))
        if original_fixture:
            try: report['restoredFixtureState'] = configure(**original_fixture['state'])
            except Exception as error: report['restorationErrors'].append(str(error))
        try:
            report['restoredSettings'] = session.settings()
            if original and report['restoredSettings'] != original:
                report['restorationErrors'].append('Display settings differ from original.')
        except Exception as error: report['restorationErrors'].append(str(error))
        if report['restorationErrors'] and not failure: report['status'] = 'CAPTURED_RESTORE_FAILED'
        save()
        (args.output / 'commands.json').write_text(json.dumps(session.commands, indent=2) + '\n')
        (args.output / 'tap-guards.json').write_text(json.dumps(session.tap_guards, indent=2) + '\n')
    if failure: raise failure
    if report['restorationErrors']: raise RuntimeError('Restoration errors; inspect result.json.')


if __name__ == '__main__': main()
