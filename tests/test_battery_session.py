import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from summarize_session import notification_battery,summarize

REAL='681d00847f1100190000000400feff18fc0500f3ff0500840a6410e20443'

class BatterySessionTest(unittest.TestCase):
    def test_legacy_raw(self):
        f=notification_battery(dict(raw_hex=REAL,valid=True))
        self.assertEqual(f['battery_raw'],100);self.assertEqual(f['battery_percent'],100)
        self.assertTrue(f['battery_field_valid']);self.assertEqual(f['battery_source'],'raw_hex')
    def test_no_raw_no_default(self):
        for e in ({},{'valid':True},{'valid':True,'battery_raw':100}):
            f=notification_battery(e)
            self.assertIsNone(f['battery_raw']);self.assertIsNone(f['battery_percent'])
            self.assertFalse(f['battery_field_valid']);self.assertEqual(f['battery_invalid_reason'],'battery_fields_missing')
    def test_raw_overrides_logged_fields_and_invalid_checksum(self):
        b=bytearray.fromhex(REAL);b[25]=50  # Synthetic corruption, checksum NOT recomputed.
        f=notification_battery(dict(raw_hex=b.hex(),valid=True,battery_percent=100,battery_field_valid=True))
        self.assertEqual(f['battery_raw'],50);self.assertFalse(f['battery_field_valid'])
        self.assertIsNone(f['battery_percent']);self.assertEqual(f['battery_invalid_reason'],'frame_invalid')
    def test_logged_only_validation(self):
        f=notification_battery(dict(valid=True,battery_raw=0,battery_percent=0,battery_field_valid=True,battery_invalid_reason=''))
        self.assertTrue(f['battery_field_valid']);self.assertEqual(f['battery_percent'],0)
        f=notification_battery(dict(valid=True,battery_raw=255,battery_percent=None,battery_field_valid=False,battery_invalid_reason='outside_candidate_range_0_100'))
        self.assertEqual(f['battery_raw'],255);self.assertIsNone(f['battery_percent'])
        for raw in (True,255,100):
            f=notification_battery(dict(valid=True,battery_raw=raw,battery_percent=101,battery_field_valid=True,battery_invalid_reason=''))
            self.assertFalse(f['battery_field_valid']);self.assertIsNone(f['battery_percent'])
    def test_malformed_and_short_raw(self):
        for raw in ('not hex',17,'68'):
            f=notification_battery(dict(raw_hex=raw))
            self.assertFalse(f['battery_field_valid']);self.assertIsNone(f['battery_percent'])
    def test_summary_without_markers_legacy_and_new(self):
        events=[dict(event='notification',session_id='s',attempt_id=1,monotonic_ns=1,_line=1,raw_hex=REAL,valid=True),
                dict(event='notification',session_id='s',attempt_id=2,monotonic_ns=2,_line=2,valid=True)]
        r=summarize(events);groups=r['battery_summary']['attempts']
        self.assertEqual(r['intervals'],[]);self.assertEqual(groups[0]['percent_distribution'],{'100':1})
        self.assertEqual(groups[1]['percent_distribution'],{});self.assertEqual(groups[1]['invalid_reasons'],{'battery_fields_missing':1})

if __name__=='__main__':unittest.main()
