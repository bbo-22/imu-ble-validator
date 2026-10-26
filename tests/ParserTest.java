import com.imu.validator.*;import java.nio.file.*;import java.util.*;
public class ParserTest {
 static int checks;
 static void check(boolean v,String msg){checks++;if(!v)throw new AssertionError(msg);}
 public static void main(String[] args)throws Exception{
  int n=0,pos=0,neg=0,wraps=0,previous=-1;String file="";
  for(String line:Files.readAllLines(Paths.get(args[0]))){if(line.startsWith("#"))continue;String[] a=line.split("\t");FrameParser.Frame f=FrameParser.parse(FrameParser.bytes(a[1]));check(f.valid,"real frame valid "+n);check(f.hex.equals(a[1]),"hex");check(f.angleRaw==Integer.parseInt(a[2]),"signed angle");check(f.counter==Integer.parseInt(a[3]),"unsigned counter");check(Math.abs(f.angle-Double.parseDouble(a[4]))<1e-10,"converted angle");
   int[] raw={f.angle2Raw,f.angle3Raw,f.accelARaw,f.accelBRaw,f.accelCRaw};double[] scaled={f.angle2,f.angle3,f.accelA,f.accelB,f.accelC};
   for(int i=0;i<5;i++){check(raw[i]==Integer.parseInt(a[5+i]),"multi-axis raw "+i);check(Math.abs(scaled[i]-Double.parseDouble(a[10+i]))<1e-10,"multi-axis scaled "+i);}
   if(file.equals(a[0])&&previous>f.counter){check(FrameParser.delta(previous,f.counter)==50,"wrap delta");wraps++;}file=a[0];previous=f.counter;if(f.angleRaw<0)neg++;if(f.angleRaw>0)pos++;n++;
  }
  check(n==17151,"all captures");check(neg>0&&pos>0&&wraps==11,"sign and wraps");
  byte[] b=new byte[30];b[0]=0x68;b[1]=0x1d;b[5]=(byte)0xff;b[6]=(byte)0xff;b[27]=(byte)0xff;b[28]=(byte)0xff;for(int i=1;i<29;i++)b[29]+=b[i];
  FrameParser.Frame f=FrameParser.parse(b);check(f.valid&&f.angleRaw==-1&&f.counter==65535,"byte signs");
  b[7]^=1;check(!FrameParser.parse(b).valid&&FrameParser.parse(b).error.contains("checksum"),"bad checksum");b[0]=0;check(FrameParser.parse(b).error.contains("header"),"bad header");
  for(int size:new int[]{0,29,31})check(!FrameParser.parse(new byte[size]).valid,"bad length");
  check(FrameParser.delta(65520,34)==50,"counter wrap");
  ReadingState s=new ReadingState();s.connected=true;s.subscribed=true;f.valid=true;s.receive(f,1000);check(s.live(1500,2000),"live");check(!s.live(3000,2000),"timeout boundary");s.disconnect();check(!s.live(1500,2000),"disconnect stale");s.connected=true;s.subscribed=true;f.valid=false;s.receive(f,1600);check(!s.live(1601,2000),"invalid packet invalidates");
  System.out.println("PASS: "+n+" real vectors, positive="+pos+", negative="+neg+", wraps="+wraps+", assertions="+checks);
 }
}
