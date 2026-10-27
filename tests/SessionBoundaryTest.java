import com.imu.validator.*;
public class SessionBoundaryTest {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static DiagnosticSample sample(int yaw,int pitch,long attempt,String session,long mono){
  byte[] b=new byte[30];b[0]=0x68;b[1]=0x1d;
  b[9]=(byte)yaw;b[10]=(byte)(yaw>>8);b[7]=(byte)pitch;b[8]=(byte)(pitch>>8);
  for(int i=1;i<29;i++)b[29]+=b[i];
  return new DiagnosticSample(FrameParser.parse(b),123456,mono,attempt,session);
 }
 public static void main(String[] args){
  DiagnosticSample a=sample(17900,0,1,"S",1000000000L),b=sample(-17900,0,1,"S",1100000000L);
  check(a.rollDeg==a.angle1&&a.pitchDeg==a.angle2&&a.yawDeg==a.angle3,"aliases preserve output");
  check(a.rawHex.substring(18,22).equals("ec45")&&a.yawRaw==17900,"raw frame retained");
  check(YawComparison.between(a,b).deltaDeg==2&&YawComparison.between(b,a).deltaDeg==-2,"same-connection signed wrap");
  DiagnosticSample reconnect=sample(0,0,2,"S",1200000000L);
  check(!YawComparison.between(a,reconnect).valid&&YawComparison.between(a,reconnect).reason.equals("cross_connection_yaw_reference_unavailable"),"reconnect comparison explicitly refused");
  check(!YawComparison.between(a,sample(0,0,1,"T",1200000000L)).valid,"different session same attempt refused");
  check(!YawComparison.between(a,sample(0,9000,1,"S",1200000000L)).valid,"pitch singularity guard");
  DiagnosticSample.Context old=new DiagnosticSample.Context(a,2,2000,true,true,true,"S");
  check(old.invalidReason(1200000000L).equals("different_attempt"),"old sample invalid after reconnect");
  check(new DiagnosticSample.Context(a,1,2000,true,true,true,"T").invalidReason(1200000000L).equals("different_session"),"same attempt another session invalid");
  DiagnosticSample.Context empty=new DiagnosticSample.Context(null,2,2000,true,false,false,"S");check(!empty.invalidReason(1200000000L).isEmpty(),"new connection has no inherited live sample");
  check(DiagnosticSample.display(old,1200000000L).split("失效",-1).length-1==7,"all fields invalid together");
  Object gatt1=new Object(),gatt2=new Object();
  for(Object incoming:new Object[]{gatt1,gatt2})for(long id:new long[]{1,2})for(boolean active:new boolean[]{false,true})
   check(InteractionFlow.acceptsCallback(gatt2,incoming,2,id,active)==(incoming==gatt2&&id==2&&active),"callback identity truth table");
  System.out.println("PASS session boundary assertions="+checks);
 }
}
