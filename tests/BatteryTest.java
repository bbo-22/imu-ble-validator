import com.imu.validator.*;

/** Checksum-adjusted synthetic fixtures are tests, NOT measured battery evidence. */
public class BatteryTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static byte[] synthetic(int battery){
  byte[] b=new byte[30];b[0]=0x68;b[1]=0x1d;int[] raw={-1234,2345,-17900};
  for(int i=0;i<3;i++){b[5+2*i]=(byte)raw[i];b[6+2*i]=(byte)(raw[i]>>8);}
  b[25]=(byte)battery;for(int i=1;i<29;i++)b[29]+=b[i];return b;
 }
 public static void main(String[] args){
  for(int raw:new int[]{0,1,50,99,100,101,127,128,255}){
   FrameParser.Frame f=FrameParser.parse(synthetic(raw));
   check(f.valid,"battery range never changes frame validity "+raw);
   check(f.batteryRaw==raw,"unsigned battery "+raw);
   check(f.batteryFieldValid==(raw<=100),"field validity "+raw);
   check(raw<=100?f.batteryPercent==raw:f.batteryPercent==null,"nullable percentage "+raw);
   check(f.batteryInvalidReason.equals(raw<=100?"":"outside_candidate_range_0_100"),"range reason "+raw);
   check(f.rollDeg==-12.34&&f.pitchDeg==23.45&&f.yawDeg==-179,"attitude retained "+raw);
   check(f.unknown23to26.equals(f.hex.substring(46,54)),"historical raw fragment retained");
  }
  byte[] b=synthetic(100);b[25]=50;FrameParser.Frame bad=FrameParser.parse(b);
  check(!bad.valid&&!bad.checksumOk&&bad.error.contains("checksum"),"battery covered by checksum");
  check(bad.batteryRaw==50&&bad.batteryPercent==null&&!bad.batteryFieldValid&&bad.batteryInvalidReason.equals("frame_invalid"),"corrupt frame battery invalid");
  for(int index:new int[]{0,1}){
   b=synthetic(100);b[index]=0;bad=FrameParser.parse(b);
   check(!bad.valid&&bad.batteryPercent==null&&!bad.batteryFieldValid,"format gates battery");
  }
  for(int length:new int[]{0,1,25,26,29,31}){
   bad=FrameParser.parse(new byte[length]);
   check(!bad.valid&&bad.batteryRaw==null&&bad.batteryPercent==null&&!bad.batteryFieldValid,"invalid length safe "+length);
  }
  DiagnosticSample s=new DiagnosticSample(FrameParser.parse(synthetic(100)),1000,1000000000L,1,"s1");
  DiagnosticSample.Context live=new DiagnosticSample.Context(s,1,2000,true,true,true,"s1");
  check(DiagnosticSample.batteryDisplay(live,1000000001L).equals("电量：100%"),"live display");
  check(DiagnosticSample.batteryDisplay(live,3000000000L).equals("电量：--"),"timeout boundary");
  for(DiagnosticSample.Context c:new DiagnosticSample.Context[]{
   new DiagnosticSample.Context(s,1,2000,false,false,true,"s1"),
   new DiagnosticSample.Context(s,2,2000,true,true,true,"s1"),
   new DiagnosticSample.Context(s,1,2000,true,true,true,"s2"),
   new DiagnosticSample.Context(null,2,2000,true,true,false,"s1"),
   new DiagnosticSample.Context(s,1,2000,true,true,false,"s1"),
   new DiagnosticSample.Context(s,1,2000,true,false,true,"s1")}){
   check(DiagnosticSample.batteryDisplay(c,1000000001L).equals("电量：--"),"no stale battery on disconnect/reconnect/invalid");
  }
  DiagnosticSample unknown=new DiagnosticSample(FrameParser.parse(synthetic(255)),1000,1000000000L,1,"s1");
  DiagnosticSample.Context c=new DiagnosticSample.Context(unknown,1,2000,true,true,true,"s1");
  String display=DiagnosticSample.batteryDisplay(c,1000000001L);
  check(c.invalidReason(1000000001L).isEmpty(),"bad battery does not invalidate sample");
  check(display.contains("电量：未知")&&display.contains("255")&&display.contains("outside_candidate_range_0_100"),"unknown with raw/reason");
  check(DiagnosticSample.display(c,1000000001L).contains("23.45"),"attitude still displayed");
  System.out.println("PASS battery synthetic/state assertions="+checks+" (synthetic data, not device evidence)");
 }
}
