"""Generate a clearly labelled OFFLINE example using real HCI values and synthetic markers."""
import json,datetime
from pathlib import Path
from summarize_session import ANGLES,ACCELS
ROOT=Path(__file__).resolve().parents[1]
rows=json.loads((ROOT.parent/'imu-protocol-analysis/exports/btsnoop_hci_20261026_144426-frames.json').read_text(encoding='utf8'))
zone=datetime.timezone(datetime.timedelta(hours=8))
def unix_ns(clock):return int(datetime.datetime.fromisoformat('2026-10-26T'+clock).replace(tzinfo=zone).timestamp())*1000000000
origin=unix_ns('14:45:16')
markers=[('14:45:16','基准静止'),('14:45:35','开始转动'),('14:45:50','到位静止'),('14:46:01','开始转动')]
events=[]
for r in rows:
    ns=(r['timestamp_raw']-0x00dcddb30f2f8000)*1000
    if origin<=ns<unix_ns('14:46:01'):
        e=dict(event='notification',monotonic_ns=ns-origin+1000000000,wall_time_ms=ns//1000000,
               raw_hex=r['raw_hex'],valid=r['valid'],subscribed=True,angle1=r['angle1_candidate'])
        for i,k in enumerate(ANGLES,1):e[k]=r[f'angle{i}_candidate']
        for i,k in enumerate(ACCELS,1):e[k]=r[f'accel{i}_candidate']
        events.append(e)
for time,action in markers:
    ns=unix_ns(time)
    events.append(dict(event='marker',monotonic_ns=ns-origin+1000000000,wall_time_ms=ns//1000000,
                       action=action,experiment_name='离线示例：合成标记（非手机点击）',data_valid=True))
events.sort(key=lambda e:e['monotonic_ns'])
for e in events:e.update(schema_version=2,session_id='offline-real-hci-synthetic-markers',attempt_id=1)
out=ROOT/'reports/examples';out.mkdir(parents=True,exist_ok=True)
(out/'real-replay-marked.jsonl').write_text('\n'.join(json.dumps(e,ensure_ascii=False) for e in events)+'\n',encoding='utf8')
print(out/'real-replay-marked.jsonl')
