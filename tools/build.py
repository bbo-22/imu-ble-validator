"""Dependency-free SDK build. Does not invoke adb or touch a device."""
import argparse,os,subprocess,shutil,zipfile,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def run(args):
    print('> '+' '.join(map(str,args)),flush=True)
    subprocess.run(list(map(str,args)),check=True,cwd=ROOT)
def main():
    p=argparse.ArgumentParser();p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',r'D:\Android\SDK'));p.add_argument('--java-home',default=os.environ.get('JAVA_HOME',r'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'));a=p.parse_args()
    sdk=Path(a.sdk);jdk=Path(a.java_home);os.environ['JAVA_HOME']=str(jdk);bt=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-34/android.jar';out=ROOT/'build'
    for n in ['classes','dex','gen','test-classes']: (out/n).mkdir(parents=True,exist_ok=True)
    for f in (out/'classes').rglob('*.class'):
        assert f.resolve().is_relative_to((ROOT/'build/classes').resolve())
        f.unlink()
    run([bt/'aapt2.exe','link','--manifest',ROOT/'AndroidManifest.xml','-I',android,'--java',out/'gen','-o',out/'base.apk'])
    sources=list((ROOT/'src').rglob('*.java'))
    run([jdk/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',str(bt/'core-lambda-stubs.jar')+os.pathsep+str(android),'-d',out/'classes',*sources])
    with zipfile.ZipFile(out/'classes.jar','w') as z:
        for f in (out/'classes').rglob('*.class'):z.write(f,f.relative_to(out/'classes').as_posix())
    core={'FrameParser','ReadingState','DiagnosticSample','YawComparison','InteractionFlow'}
    for name,types in [('imu-protocol-core.jar',core),('imu-ble-module.jar',core|{'BleImuManager','SessionLog','SessionFiles'})]:
        with zipfile.ZipFile(out/name,'w') as z:
            for f in (out/'classes/com/imu/validator').glob('*.class'):
                if f.stem.split('$')[0] in types:z.write(f,f.relative_to(out/'classes').as_posix())
    example=ROOT/'examples/ReceiverExample.java'
    if example.exists():
        (out/'example-check').mkdir(exist_ok=True)
        run([jdk/'bin/javac.exe','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',str(bt/'core-lambda-stubs.jar')+os.pathsep+str(android),'-classpath',out/'imu-ble-module.jar','-d',out/'example-check',example])
    run([bt/'d8.bat','--min-api','26','--lib',android,'--output',out/'dex',out/'classes.jar'])
    shutil.copyfile(out/'base.apk',out/'unsigned.apk')
    with zipfile.ZipFile(out/'unsigned.apk','a') as z:z.write(out/'dex/classes.dex','classes.dex')
    run([bt/'zipalign.exe','-f','4',out/'unsigned.apk',out/'aligned.apk'])
    key=ROOT/'debug.keystore'
    if not key.exists(): run([jdk/'bin/keytool.exe','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug,O=IMU Validator,C=CN'])
    apk=out/'imu-ble-validator-debug.apk'
    run([bt/'apksigner.bat','sign','--ks',key,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,out/'aligned.apk'])
    run([bt/'apksigner.bat','verify','--verbose',apk]);run([bt/'aapt.exe','dump','badging',apk])
    manifest=ET.parse(ROOT/'AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
    version=manifest.attrib[ns+'versionName'];version_code=int(manifest.attrib[ns+'versionCode'])
    versioned=out/f'imu-ble-validator-{version}-debug.apk';shutil.copyfile(apk,versioned)
    digest=hashlib.sha256(apk.read_bytes()).hexdigest();assert hashlib.sha256(versioned.read_bytes()).hexdigest()==digest
    summary={'apk':str(apk),'versioned_apk':str(versioned),'version_name':version,'version_code':version_code,'size':apk.stat().st_size,'sha256':digest,'compile_sdk':34,'target_sdk':34,'min_sdk':26,'build_tools':'36.0.0','jdk':str(jdk)}
    (ROOT/'reports/build-result.json').write_text(json.dumps(summary,indent=2),encoding='utf8');print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
