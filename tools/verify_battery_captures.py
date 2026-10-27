"""Read the three authoritative HCI files; compare all old decoded fields and new battery exports."""
import csv,hashlib,json,sys
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ANALYSIS=ROOT.parent/'imu-protocol-analysis'
sys.path.insert(0,str(ANALYSIS/'src'))
from tool import extract
from frame import parse_frame

def main():
    results=[];total=0
    for suffix,expected in [('101013',6267),('112137',5797),('144426',5087)]:
        path=ROOT.parent/'imu-capture'/f'btsnoop_hci_20261026_{suffix}.log'
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        output=ANALYSIS/'exports/battery-v1.4'
        meta,_,_,_,rows=extract(path,output)
        assert not meta['issues'],meta['issues']
        old=json.loads((ANALYSIS/'exports'/f'{path.stem}-frames.json').read_text(encoding='utf8'))
        assert len(rows)==len(old)==expected
        distribution=Counter();unchanged=0
        for current,previous in zip(rows,old):
            raw=bytes.fromhex(current['raw_hex'])
            assert len(raw)==30 and (sum(raw[1:29])&255)==raw[29]
            assert current['battery_raw']==raw[25]==100
            assert current['battery_percent']==100 and current['battery_field_valid'] and current['battery_invalid_reason']==''
            # Old exports include HCI record/time/session plus all historical decoded fields.
            for key,value in previous.items():
                assert key in current and current[key]==value,(path.name,current['record'],key)
                unchanged+=1
            distribution[raw[25]]+=1
        exported=json.loads((output/f'{path.stem}-frames.json').read_text(encoding='utf8'))
        assert exported==rows
        with (output/f'{path.stem}-frames.csv').open(encoding='utf-8-sig',newline='') as f:
            csvrows=list(csv.DictReader(f))
        assert len(csvrows)==expected and all(r['battery_raw']=='100' and r['battery_percent']=='100' and r['battery_field_valid']=='True' and r['battery_invalid_reason']=='' for r in csvrows)
        assert hashlib.sha256(path.read_bytes()).hexdigest()==digest,'input changed'
        results.append(dict(file=path.name,sha256=digest,frames=len(rows),battery_raw_distribution=dict(distribution),
                            unchanged_field_comparisons=unchanged,json_csv_verified=True,issues=meta['issues']))
        total+=len(rows)
    assert total==17151
    report=dict(total_frames=total,evidence='All observed byte[25] values are 100. Non-100 percentage semantics remain unverified.',
                captures=results,exports=str(output))
    (ROOT/'reports/battery-captures.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
