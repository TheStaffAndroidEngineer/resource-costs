#!/usr/bin/env python3
"""Measure two retained bundles against one explicit device specification. No device writes."""
from pathlib import Path
import argparse, csv, datetime, hashlib, io, json, os, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--device-spec',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
build=a.build.resolve();spec=a.device_spec.resolve()
java=Path(os.environ['JAVA_HOME'])/'bin/java'
tool=ROOT/'tools/bundletool-all-1.18.3.jar'
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
commands=[]
def run(args, log):
    result=subprocess.run([str(x) for x in args],capture_output=True)
    (out/log).write_bytes(result.stdout+result.stderr)
    commands.append({'argv':[str(x) for x in args],'returncode':result.returncode,'log':log})
    result.check_returncode();return result.stdout.decode()
run([java,'-jar',tool,'version'],'bundletool-version.txt')
results=[]
for flavor in ['lean','archive']:
    bundle=build/f'artifacts/app/build/outputs/bundle/{flavor}Release/app-{flavor}-release.aab'
    apk=build/f'artifacts/app/build/outputs/apk/{flavor}/release/app-{flavor}-release.apk'
    apkset=out/f'{flavor}.apks'
    run([java,'-Xmx512m','-jar',tool,'build-apks',f'--bundle={bundle}',f'--output={apkset}',f'--device-spec={spec}',
         f'--ks={Path.home()/".android/debug.keystore"}','--ks-pass=pass:android','--ks-key-alias=androiddebugkey','--key-pass=pass:android'],f'{flavor}-build-apks.txt')
    output=run([java,'-jar',tool,'get-size','total',f'--apks={apkset}',f'--device-spec={spec}'],f'{flavor}-estimated-download.csv')
    selected=[]
    with zipfile.ZipFile(apkset) as z:
        for name in z.namelist():
            if not name.endswith('.apk'):continue
            data=z.read(name)
            with zipfile.ZipFile(io.BytesIO(data)) as split:
                entries=[{'path':x.filename,'uncompressed_bytes':x.file_size,'zip_payload_bytes':x.compress_size,
                    'sha256':hashlib.sha256(split.read(x.filename)).hexdigest()} for x in split.infolist() if not x.is_dir()]
            selected.append({'path':name,'apk_bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'entries':entries})
    with zipfile.ZipFile(apk) as z:
        assets=[{'path':x.filename,'uncompressed_bytes':x.file_size,'zip_payload_bytes':x.compress_size,
                 'sha256':hashlib.sha256(z.read(x.filename)).hexdigest()} for x in z.infolist() if x.filename.startswith('assets/') and not x.is_dir()]
    rows=list(csv.DictReader(io.StringIO(output)))
    assert len(rows)==1,output
    results.append({'flavor':flavor,'bundle':str(bundle.relative_to(ROOT)),'bundle_sha256':sha(bundle),'bundle_bytes':bundle.stat().st_size,
        'standalone_apk':str(apk.relative_to(ROOT)),'standalone_apk_sha256':sha(apk),'standalone_apk_bytes':apk.stat().st_size,
        'apkset':str(apkset.relative_to(ROOT)),'apkset_sha256':sha(apkset),'apkset_archive_bytes':apkset.stat().st_size,
        'selected_apk_bytes':sum(x['apk_bytes'] for x in selected),'estimated_download':rows[0],'selected_apks':selected,'standalone_assets':assets})
manifest={'status':'MEASURED_REVIEW_REQUIRED','date':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'build':str((build/'manifest.json').relative_to(ROOT)),'build_sha256':sha(build/'manifest.json'),
    'device_spec':str(spec.relative_to(ROOT)),'device_spec_sha256':sha(spec),'bundletool_sha256':sha(tool),
    'tool_script_sha256':sha(Path(__file__)),'commands':commands,'results':results,
    'scope':'Same device specification and product-equivalent optimized flavors. Bundletool compressed download estimates, not Play billing or installed footprint. Duplicate assets intentionally model an inventory mistake. No energy measurement.'}
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps([{k:r[k] for k in ['flavor','bundle_bytes','standalone_apk_bytes','selected_apk_bytes','estimated_download']} for r in results],indent=2))
