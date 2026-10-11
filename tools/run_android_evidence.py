#!/usr/bin/env python3
"""Explicit installed-artifact run retaining app files and pre/post package identity."""
import os
from pathlib import Path
import argparse,datetime,hashlib,json,subprocess,urllib.request

ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--serial',default='emulator-5554');a=p.parse_args()
build=a.build.resolve();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
adb=[str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'),'-s',a.serial]
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
app=build/'artifacts/app/build/outputs/apk/lean/debug/app-lean-debug.apk'
test=build/'artifacts/app/build/outputs/apk/androidTest/lean/debug/app-lean-debug-androidTest.apk'
manifest={'status':'RUNNING','started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'source_build':str((build/'manifest.json').relative_to(ROOT)),'source_build_sha256':sha(build/'manifest.json'),
    'app_apk_sha256':sha(app),'test_apk_sha256':sha(test),'tool_sha256':sha(Path(__file__)), 'commands':[],
    'scope':'API36 emulator leanDebug; real HTTP and correctness, not energy or representative timing. This run explicitly resets only the chapter app.'}
def save(): (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
def run(args,name):
    r=subprocess.run(adb+[str(x) for x in args],capture_output=True,timeout=180)
    (out/name).write_bytes(r.stdout+r.stderr);manifest['commands'].append({'argv':adb+[str(x) for x in args],'exit_code':r.returncode,'output':name});save()
    r.check_returncode();return r.stdout
def readback(stage):
    remote=run(['shell','pm','path','staff.android.costs'],f'{stage}-path.txt').decode().strip()
    assert remote.startswith('package:') and '\n' not in remote,remote
    run(['pull',remote.removeprefix('package:'),out/f'{stage}-installed.apk'],f'{stage}-pull.txt')
    digest=sha(out/f'{stage}-installed.apk');assert digest==sha(app)
    manifest[f'{stage}_installed_sha256']=digest;save()
try:
    run(['reverse','tcp:8874','tcp:8874'],'reverse.txt')
    assert b'Success' in run(['install','-r',app],'install-app.txt')
    assert b'Success' in run(['shell','pm','clear','staff.android.costs'],'clear-app.txt')
    assert b'Success' in run(['install','-r',test],'install-test.txt')
    readback('before')
    for name,args in [('properties',['getprop']),('battery',['dumpsys','battery']),('thermal',['dumpsys','thermalservice']),('batterystats',['dumpsys','batterystats','staff.android.costs'])]:
        run(['shell']+args,f'before-{name}.txt')
    result=run(['shell','am','instrument','-w','-r','staff.android.costs.test/androidx.test.runner.AndroidJUnitRunner'],'instrumentation.txt')
    assert b'OK (10 tests)' in result and b'FAILURES!!!' not in result
    raw=run(['exec-out','run-as','staff.android.costs','cat','files/network-campaign.json'],'network-campaign.json')
    campaign=json.loads(raw);assert len(campaign['stages'])==8
    readback('after')
    for name,args in [('battery',['dumpsys','battery']),('thermal',['dumpsys','thermalservice']),('batterystats',['dumpsys','batterystats','staff.android.costs']),('package',['dumpsys','package','staff.android.costs']),('jobscheduler',['dumpsys','jobscheduler','staff.android.costs'])]:
        run(['shell']+args,f'after-{name}.txt')
    with urllib.request.urlopen('http://127.0.0.1:8874/admin/records') as r:(out/'server-records.json').write_bytes(r.read())
    manifest.update(status='EXECUTED_REVIEW_REQUIRED',tests=10,completed_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
except BaseException as failure:
    manifest.update(status='FAILED_OR_INCOMPLETE',failure=repr(failure));raise
finally:save()
print(json.dumps({'status':manifest['status'],'tests':manifest.get('tests'),'out':str(out)}))
