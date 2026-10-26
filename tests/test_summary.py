import json,math,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from summarize_session import circular,delta,load,summarize,ANGLES,ACCELS

def event(t,kind,line=1,**values):
    return dict(monotonic_ns=int(t*1e9),wall_time_ms=1793005574558+int(t*1000),session_id='session-test',attempt_id=1,event=kind,_line=line,**values)
def packet(t,angle=179,**kw):
    values={k:angle for k in ANGLES};values.update(dict(zip(ACCELS,[0,0,-1])))
    return event(t,'notification',valid=True,subscribed=True,**values,**kw)

class SummaryTest(unittest.TestCase):
    def test_wrap(self):
        self.assertEqual(delta(179,-179),2);self.assertEqual(delta(-179,179),-2)
        s=circular([179,-179]);self.assertAlmostEqual(abs(s['circular_mean_deg']),180)
        self.assertEqual(s['circular_fluctuation']['arc_span_deg'],2);self.assertTrue(s['crosses_wrap'])
    def test_intervals_trim_and_candidates(self):
        trace=[event(0,'marker',action='基准静止',experiment_name='A',data_valid=True),packet(.5),packet(2.5),packet(3,-179),event(4,'marker',action='开始转动')]
        r=summarize(trace,min_samples=2)['intervals'][0]
        self.assertEqual(r['valid_samples'],2);self.assertEqual(r['analysis_start_ns'],2000000000)
        self.assertAlmostEqual(abs(r['raw_angle_statistics'][ANGLES[0]]['circular_mean_deg']),180)
        self.assertIsNone(r['relative_field_statistics']);self.assertIn('angle_crosses_180_boundary',r['flags'])
    def test_invalid_missing_and_gap(self):
        bad=packet(3);bad['valid']=False
        missing=event(4,'notification',valid=True)
        trace=[event(0,'marker',action='到位静止',data_valid=False,invalid_reason='data_timeout'),bad,missing,event(10,'disconnect_completed'),event(12,'marker',action='返回基准')]
        flags=summarize(trace)['intervals'][0]['flags']
        for flag in ['insufficient_samples','missing_or_unreadable_axis_fields','data_invalid_or_disconnected_in_interval','data_gap_exceeds_timeout','invalid_or_unsubscribed_notifications']:
            self.assertIn(flag,flags)
    def test_motion(self):
        trace=[event(0,'marker',action='到位静止'),packet(2,0),packet(3,40),event(4,'marker',action='开始转动')]
        self.assertIn('motion_or_drift_candidate',summarize(trace,min_samples=2)['intervals'][0]['flags'])
    def test_attempts_and_out_of_file_order(self):
        start=event(0,'marker',action='基准静止');end=event(4,'marker',action='开始转动');other=packet(2);other['attempt_id']=2
        r=summarize([packet(3),start,other,end],min_samples=1)
        self.assertEqual(r['intervals'][0]['valid_samples'],1);self.assertTrue(r['warnings'])
    def test_legacy_read_and_malformed_reported(self):
        p=Path(__file__).parent/'fixtures/legacy-v1-session.jsonl';events,issues=load(p)
        self.assertEqual(len(events),43);self.assertEqual(issues,[]);self.assertEqual(summarize(events)['intervals'],[])
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'bad.jsonl';p.write_text('{broken}\n'+json.dumps(event(0,'marker',action='基准静止')),encoding='utf8')
            events,issues=load(p);self.assertEqual(len(issues),1);self.assertEqual(len(events),1)
    def test_ambiguous_circle(self):
        self.assertIsNone(circular([0,180])['circular_mean_deg']);self.assertTrue(circular([0,180])['ambiguous'])

if __name__=='__main__':unittest.main()
