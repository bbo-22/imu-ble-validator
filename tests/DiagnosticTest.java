import com.imu.validator.*;import java.util.*;
public class DiagnosticTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static Map<String,Object> map(Object[] p){Map<String,Object> m=new HashMap<>();for(int i=0;i<p.length;i+=2)m.put(p[i].toString(),p[i+1]);return m;}
 public static void main(String[] args){
  byte[] b=new byte[30];b[0]=0x68;b[1]=0x1d;int[] signed={-1234,2345,-17900,-1000,500,-250};
  for(int i=0;i<6;i++){b[5+i*2]=(byte)signed[i];b[6+i*2]=(byte)(signed[i]>>8);}for(int i=1;i<29;i++)b[29]+=b[i];
  FrameParser.Frame f=FrameParser.parse(b);check(f.valid&&f.length==30,"valid observed frame");check(f.angle2Raw==2345&&f.angle3Raw==-17900&&f.accelARaw==-1000&&f.accelBRaw==500&&f.accelCRaw==-250,"signed offsets");
  check(Math.abs(f.accelNorm-Math.sqrt(1+.25+.0625))<1e-10,"vector norm");
  check(FrameParser.shortestAngleDelta(179,-179)==2,"forward wrap");check(FrameParser.shortestAngleDelta(-179,179)==-2,"reverse wrap");
  DiagnosticSample sample=new DiagnosticSample(f,10000,1000000000L,7,"session-7");
  DiagnosticSample.Context context=new DiagnosticSample.Context(sample,7,2000,true,true,true);
  Map<String,Object> m=map(context.marker("实验A","到位静止",10100,1100000000L));
  check(m.get("click_wall_time_ms").equals(10100L)&&m.get("click_monotonic_ns").equals(1100000000L),"click clocks preserved");
  check(m.get("sample_monotonic_ns").equals(1000000000L)&&m.get("sample_age_ms").equals(100.0),"exact sample clock and age");
  check(m.get("sample_attempt_id").equals(7L)&&m.get("sample_session_id").equals("session-7"),"sample session association");
  check(m.get("angle2_deg_candidate").equals(23.45),"marker snapshot copied");f.angle2=99;check(m.get("angle2_deg_candidate").equals(23.45),"sample immutable");
  check(DiagnosticSample.display(context,1100000000L).contains("23.45"),"aligned valid display");
  check(!DiagnosticSample.display(context,3000000000L).contains("23.45"),"timeout clears all axes");
  Map<String,Object> stale=map(context.marker("A","基准静止",13000,3000000000L));check(stale.get("angle1_deg_candidate")==null&&stale.get("angle2_deg_candidate")==null&&stale.get("angle3_deg_candidate")==null,"stale angles null");
  for(DiagnosticSample.Context invalid:new DiagnosticSample.Context[]{new DiagnosticSample.Context(sample,7,2000,false,false,true),new DiagnosticSample.Context(null,7,2000,true,true,false),new DiagnosticSample.Context(sample,8,2000,true,true,true),new DiagnosticSample.Context(sample,7,2000,true,true,false)}){
   String display=DiagnosticSample.display(invalid,1100000000L);check(display.split("失效",-1).length-1==7,"all seven real-time fields invalid together");check(map(invalid.marker("A","开始转动",1,1100000000L)).get("angle2_deg_candidate")==null,"invalid marker remains recordable");
  }
  System.out.println("PASS diagnostic assertions="+checks);
 }
}
