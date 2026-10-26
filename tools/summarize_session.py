"""Summarize manually marked stationary intervals. Python stdlib only; no axis claims."""
import argparse,csv,datetime,json,math,statistics as st,sys
from pathlib import Path

ANGLES=[f'angle{i}_deg_candidate' for i in (1,2,3)]
ACCELS=[f'accel{x}_g_candidate' for x in 'ABC']
STABLE={'基准静止','到位静止'}
def delta(reference,current): return (current-reference+180)%360-180
def percentile(values,p):
    a=sorted(values);x=(len(a)-1)*p;lo=int(x);hi=min(lo+1,len(a)-1)
    return a[lo]+(a[hi]-a[lo])*(x-lo)
def scalar(values):
    return dict(mean=st.mean(values),median=st.median(values),std=st.pstdev(values),min=min(values),max=max(values),peak_to_peak=max(values)-min(values)) if values else None
def circular(values):
    if not values:return None
    s=st.mean(math.sin(math.radians(v)) for v in values);c=st.mean(math.cos(math.radians(v)) for v in values);r=math.hypot(s,c)
    raw=dict(min=min(values),max=max(values))
    crossed=any(abs(b-a)>180 for a,b in zip(values,values[1:]))
    if r<1e-6:return dict(raw=raw,circular_mean_deg=None,resultant=r,ambiguous=True,crosses_wrap=crossed)
    center=math.degrees(math.atan2(s,c));residuals=[delta(center,v) for v in values]
    return dict(raw=raw,circular_mean_deg=center,circular_median_deg=delta(0,center+st.median(residuals)),
                resultant=r,ambiguous=r<.5,crosses_wrap=crossed,
                circular_fluctuation=dict(std_deg=st.pstdev(residuals),arc_span_deg=max(residuals)-min(residuals),
                    p05_offset_from_mean_deg=percentile(residuals,.05),p95_offset_from_mean_deg=percentile(residuals,.95)))
def wall(ms):
    if ms is None:return None
    return datetime.datetime.fromtimestamp(ms/1000,datetime.timezone(datetime.timedelta(hours=8))).isoformat()

def load(path):
    events=[];issues=[]
    for line_no,line in enumerate(Path(path).read_text(encoding='utf-8-sig').splitlines(),1):
        if not line.strip():continue
        try:
            e=json.loads(line)
            if not isinstance(e,dict):raise ValueError('event must be an object')
            clock=e.get('monotonic_ns')
            if not isinstance(clock,(int,float)) or isinstance(clock,bool) or not math.isfinite(clock) or clock<0:raise ValueError('missing/invalid monotonic_ns')
            e['_line']=line_no;e.setdefault('session_id',Path(path).stem);e.setdefault('attempt_id',0);events.append(e)
            if not isinstance(e['session_id'],str) or not isinstance(e['attempt_id'],int):
                events.pop();raise ValueError('invalid session_id/attempt_id')
        except (ValueError,TypeError) as ex:issues.append(dict(line=line_no,error=str(ex)))
    return events,issues

def notification_values(event):
    values={k:event.get(k) for k in ANGLES+ACCELS}
    # Legacy compatibility: reuse the existing Python decoder rather than copy offsets.
    if any(v is None for v in values.values()) and event.get('raw_hex'):
        parser_root=Path(__file__).resolve().parents[2]/'imu-protocol-analysis/src'
        if parser_root.is_dir():
            if str(parser_root) not in sys.path:sys.path.insert(0,str(parser_root))
            from frame import parse_frame
            f=parse_frame(event['raw_hex'])
            if f['valid']:
                for i,k in enumerate(ANGLES,1):values[k]=f[f'angle{i}_candidate']
                for i,k in enumerate(ACCELS,1):values[k]=f[f'accel{i}_candidate']
    if values[ANGLES[0]] is None:values[ANGLES[0]]=event.get('angle1')
    return values

def summarize(events,trim_seconds=2,min_samples=20,gap_ms=2000,angle_span=5,accel_std=.05):
    intervals=[];warnings=[]
    if any(b['monotonic_ns']<a['monotonic_ns'] for a,b in zip(events,events[1:])):
        warnings.append('file_order_not_monotonic: interval selection uses monotonic timestamps, original line numbers retained')
    groups={}
    for e in events:groups.setdefault((e['session_id'],e['attempt_id']),[]).append(e)
    for (session,attempt),group in groups.items():
        group=sorted(group,key=lambda e:(e['monotonic_ns'],e['_line']))
        markers=[e for e in group if e.get('event')=='marker']
        if not markers:continue
        for i,marker in enumerate(markers):
            if marker.get('action') not in STABLE:continue
            following=markers[i+1] if i+1<len(markers) else None
            start=marker['monotonic_ns']+int(trim_seconds*1e9)
            end=following['monotonic_ns'] if following else max(e['monotonic_ns'] for e in group)+1
            segment=[e for e in group if start<=e['monotonic_ns']<end]
            packets=[e for e in segment if e.get('event')=='notification']
            flags=[];valid=[];missing=0
            for e in packets:
                if not e.get('valid',False) or e.get('subscribed') is False:continue
                try:values=notification_values(e)
                except (ValueError,KeyError,TypeError):missing+=1;continue
                if not all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in values.values()):missing+=1;continue
                valid.append((e,values))
            if following is None:flags.append('open_interval_no_next_marker')
            if start>=end:flags.append('empty_interval_after_start_trim')
            if marker.get('data_valid') is False:flags.append('marker_data_invalid:'+str(marker.get('invalid_reason','unknown')))
            if len(valid)<min_samples:flags.append('insufficient_samples')
            if missing:flags.append('missing_or_unreadable_axis_fields')
            if len(packets)-len(valid)-missing:flags.append('invalid_or_unsubscribed_notifications')
            if any(e.get('event') in ('data_timeout','disconnect_requested','disconnect_completed') for e in segment):flags.append('data_invalid_or_disconnected_in_interval')
            times=[start]+[e['monotonic_ns'] for e,v in valid]+[end]
            maxgap=max([0]+[b-a for a,b in zip(times,times[1:])])/1e6
            if maxgap>gap_ms:flags.append('data_gap_exceeds_timeout')
            angles={k:circular([v[k] for e,v in valid]) for k in ANGLES}
            accels={k:scalar([v[k] for e,v in valid]) for k in ACCELS}
            norm=scalar([math.sqrt(sum(v[k]**2 for k in ACCELS)) for e,v in valid])
            if any(v and v.get('crosses_wrap') for v in angles.values()):flags.append('angle_crosses_180_boundary')
            if any(v and v.get('ambiguous') for v in angles.values()):flags.append('circular_direction_ambiguous')
            if any(v and v.get('circular_fluctuation',{}).get('arc_span_deg',0)>angle_span for v in angles.values()) or any(v and v['std']>accel_std for v in accels.values()):flags.append('motion_or_drift_candidate')
            intervals.append(dict(session_id=session,attempt_id=attempt,experiment_name=marker.get('experiment_name',''),action=marker['action'],
                marker_line=marker['_line'],next_marker_line=following['_line'] if following else None,
                marker_wall_time=wall(marker.get('wall_time_ms')),analysis_start_ns=start,analysis_end_ns=end,trim_start_seconds=trim_seconds,
                total_notifications=len(packets),valid_samples=len(valid),missing_field_samples=missing,max_gap_ms=maxgap,
                flags=flags,raw_angle_statistics=angles,acceleration_statistics=accels,accel_norm_statistics=norm,
                relative_field_statistics=None))
    if not intervals:warnings.append('no_stable_markers: old/unmarked logs are readable, but no automatic action labels are invented')
    return dict(schema='imu-marked-summary-v1',axis_definitions='candidates; no axis/rotation order confirmation',
                note='Circular fluctuations are offsets from statistical center, not a user reference or 3D relative pose. Local reference is not enabled.',
                thresholds=dict(min_samples=min_samples,gap_ms=gap_ms,angle_arc_span_deg=angle_span,accel_axis_std_g=accel_std),warnings=warnings,intervals=intervals)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('session',type=Path);p.add_argument('--trim-seconds',type=float,default=2)
    p.add_argument('--min-samples',type=int,default=20);p.add_argument('--gap-ms',type=float,default=2000);p.add_argument('--motion-angle-span',type=float,default=5);p.add_argument('--motion-accel-std',type=float,default=.05)
    p.add_argument('--output',type=Path);p.add_argument('--csv',type=Path);a=p.parse_args()
    if a.trim_seconds<0 or a.min_samples<1 or min(a.gap_ms,a.motion_angle_span,a.motion_accel_std)<=0:p.error('thresholds must be positive; trim may be zero')
    events,issues=load(a.session);result=summarize(events,a.trim_seconds,a.min_samples,a.gap_ms,a.motion_angle_span,a.motion_accel_std);result['parse_issues']=issues
    data=json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False)
    if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(data,encoding='utf8')
    else:print(data)
    if a.csv:
        a.csv.parent.mkdir(parents=True,exist_ok=True)
        with a.csv.open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.writer(f);w.writerow(['session','attempt','experiment','action','N','flags','angle1_circular_mean_deg','angle2_circular_mean_deg','angle3_circular_mean_deg','accelA_mean_g','accelB_mean_g','accelC_mean_g'])
            for r in result['intervals']:w.writerow([r['session_id'],r['attempt_id'],r['experiment_name'],r['action'],r['valid_samples'],';'.join(r['flags'])]+[(r['raw_angle_statistics'][k] or {}).get('circular_mean_deg') for k in ANGLES]+[(r['acceleration_statistics'][k] or {}).get('mean') for k in ACCELS])
    if issues:print(f'{len(issues)} explicit JSONL parse issue(s); see parse_issues',file=sys.stderr);return 2
    return 0
if __name__=='__main__':raise SystemExit(main())
