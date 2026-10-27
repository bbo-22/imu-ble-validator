"""Generate expected vectors with the existing Python decoder, test pure Java."""
import sys,json,subprocess,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'imu-protocol-analysis/src'))
from frame import parse_frame
def main():
    lines=['# capture\thex\tangle1_raw\tcounter_uint16\tangle1\tangle2_raw\tangle3_raw\taccelA_raw\taccelB_raw\taccelC_raw\tangle2\tangle3\taccelA\taccelB\taccelC'];counts={}
    for suffix in ['101013','112137','144426']:
        p=ROOT.parent/'imu-protocol-analysis/exports'/f'btsnoop_hci_20261026_{suffix}-frames.json';rows=json.loads(p.read_text(encoding='utf8'));counts[suffix]=len(rows)
        for row in rows:
            f=parse_frame(row['raw_hex']);assert f['valid'];assert f['angle1_raw']==row['angle1_raw']
            extra=[f[k] for k in ['angle2_raw','angle3_raw','accel1_raw','accel2_raw','accel3_raw','angle2_candidate','angle3_candidate','accel1_candidate','accel2_candidate','accel3_candidate']]
            lines.append(f"{suffix}\t{f['raw_hex']}\t{f['angle1_raw']}\t{f['counter_raw']}\t{f['angle1_candidate']}\t"+'\t'.join(map(str,extra)))
    vectors=ROOT/'test-vectors/real-frames.tsv';vectors.write_text('\n'.join(lines)+'\n',encoding='utf8')
    out=ROOT/'build/test-classes';out.mkdir(parents=True,exist_ok=True)
    sources=[ROOT/'src/com/imu/validator'/n for n in ['FrameParser.java','ReadingState.java','DiagnosticSample.java','YawComparison.java','InteractionFlow.java','StableRows.java','SessionFiles.java','SessionLog.java']]
    sources+=list((ROOT/'tests/stubs').rglob('*.java'))
    sources+=[ROOT/'tests'/n for n in ['ParserTest.java','DiagnosticTest.java','SessionBoundaryTest.java','InteractionTest.java','SessionFilesTest.java','SessionLogTest.java']]
    subprocess.run(['javac','-encoding','UTF-8','-d',str(out),*map(str,sources)],check=True)
    r=subprocess.run(['java','-cp',str(out),'ParserTest',str(vectors)],text=True,capture_output=True);print(r.stdout,r.stderr);r.check_returncode()
    outputs=[r.stdout]
    legacy=ROOT/'tests/fixtures/legacy-v1-session.jsonl'
    for test,args in [('DiagnosticTest',[]),('SessionBoundaryTest',[]),('InteractionTest',[]),('SessionFilesTest',[str(legacy)]),('SessionLogTest',[])]:
        result=subprocess.run(['java','-cp',str(out),test,*args],text=True,capture_output=True,timeout=30)
        print(result.stdout,result.stderr);result.check_returncode();outputs.append(result.stdout)
    py=subprocess.run([sys.executable,'-m','unittest','discover','-s',str(ROOT/'tests'),'-p','test_summary.py','-v'],capture_output=True,text=True)
    print(py.stdout,py.stderr);py.check_returncode();outputs.append(py.stdout+py.stderr)
    (ROOT/'reports/test-result.txt').write_text('\n'.join(outputs)+'\ncounts='+json.dumps(counts)+'\nvector_sha256='+hashlib.sha256(vectors.read_bytes()).hexdigest(),encoding='utf8')
if __name__=='__main__':main()
