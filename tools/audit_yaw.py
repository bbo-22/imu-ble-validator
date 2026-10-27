"""Read-only raw Yaw audit, grouped by session and connection attempt."""
import argparse,hashlib,json,struct,sys
from collections import Counter,defaultdict
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'imu-protocol-analysis/src'))
from frame import parse_frame

def audit(path,first=5):
    blob=path.read_bytes();events=[];issues=[];groups=defaultdict(list)
    for line,text in enumerate(blob.decode('utf-8-sig').splitlines(),1):
        if not text.strip():continue
        try:
            event=json.loads(text);event['_line']=line;events.append(event)
        except ValueError as ex:issues.append(dict(line=line,error=str(ex)));continue
        if event.get('event')!='notification':continue
        try:
            frame=parse_frame(event.get('raw_hex',''))
            if not frame['valid']:
                issues.append(dict(line=line,error='notification format invalid',frame_errors=frame['errors']));continue
            b=bytes.fromhex(event['raw_hex']);raw=struct.unpack_from('<h',b,9)[0]
            assert raw==frame['yaw_raw']
            fields={k:event[k] for k in ('angle3','angle3_deg_candidate','yaw_deg') if k in event}
            mismatch={k:v for k,v in fields.items() if v is None or abs(v-raw/100)>1e-9}
            groups[(event.get('session_id',path.stem),event.get('attempt_id',0))].append(dict(
                line=line,wall_time_ms=event.get('wall_time_ms'),monotonic_ns=event.get('monotonic_ns'),
                raw_hex=event['raw_hex'],yaw_bytes_le=b[9:11].hex(' '),yaw_raw=raw,yaw_deg=raw/100,
                logged_values=fields,mismatches=mismatch))
        except (ValueError,TypeError,KeyError) as ex:issues.append(dict(line=line,error=str(ex)))
    attempts=[]
    for (session,attempt),rows in groups.items():
        attempts.append(dict(session_id=session,attempt_id=attempt,valid_notifications=len(rows),first=rows[:first],
            first_yaw_near_zero=all(abs(r['yaw_deg'])<=1 for r in rows[:first]),
            logged_yaw_comparisons=sum(bool(r['logged_values']) for r in rows),
            logged_yaw_mismatch_rows=[r['line'] for r in rows if r['mismatches']],
            all_yaw_range=[min(r['yaw_deg'] for r in rows),max(r['yaw_deg'] for r in rows)]))
    return dict(file=str(path.resolve()),filename=path.name,sha256=hashlib.sha256(blob).hexdigest(),size=len(blob),
                event_counts=dict(Counter(e.get('event') for e in events)),
                app_versions=[e.get('app_version') for e in events if e.get('event')=='session_start'],
                connection_requests=[dict(line=e['_line'],attempt_id=e.get('attempt_id'),wall_time_ms=e.get('wall_time_ms')) for e in events if e.get('event')=='connect_requested'],
                attempts=attempts,issues=issues)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('logs',type=Path,nargs='+');p.add_argument('--output',type=Path,required=True);p.add_argument('--first',type=int,default=5);a=p.parse_args()
    if a.first<1:p.error('--first must be positive')
    result=[audit(path,a.first) for path in a.logs];a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({r['filename']:{'attempts':len(r['attempts']),'yaw_mismatches':sum(len(g['logged_yaw_mismatch_rows']) for g in r['attempts']),'issues':len(r['issues'])} for r in result},indent=2))
    return 2 if any(r['issues'] for r in result) else 0
if __name__=='__main__':raise SystemExit(main())
