"""Run the actual AutoCloseable method against a deterministic scanner resource port."""
import subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
s=(ROOT/'src/com/imu/validator/BleImuManager.java').read_text(encoding='utf8')
start=s.index('    @Override public void close()');brace=s.index('{',start);end=brace+1;depth=1
while depth:depth+=(s[end]=='{')-(s[end]=='}');end+=1
method=s[start:end]
text='''import com.imu.validator.InteractionFlow;
public class CloseProbe implements AutoCloseable {
 static class Handler {boolean removed;void post(Runnable r){r.run();}void removeCallbacksAndMessages(Object o){removed=true;}}
 static class ThreadPort {boolean quit;void quitSafely(){quit=true;}}
 static class Log {boolean closed;void close(){closed=true;}}
 Handler ui=new Handler(),worker=new Handler();ThreadPort bleThread=new ThreadPort();Log log=new Log();
 Object gatt=null;boolean scannerActive=true;int stops;
 InteractionFlow flow=new InteractionFlow(new InteractionFlow.Port(){
  public void event(long a,String t,String r){}public void connect(long a,String k){}
  public void stopScan(){scannerActive=false;stops++;}
 });
 void disconnect(String reason,boolean user){gatt=null;}void summary(){}
'''+method+'''
 public static void main(String[] args){
  CloseProbe p=new CloseProbe();long token=p.flow.startScan(0);p.close();
  System.out.println("scannerActive="+p.scannerActive+" tokenInvalid="+(p.flow.scanToken!=token)+" stopped="+p.stops+" quit="+p.bleThread.quit);
  if(p.scannerActive||p.flow.scanning||p.flow.scanToken==token||p.stops!=1)throw new AssertionError("Direct close leaked active scanner");
  if(!p.log.closed||!p.worker.removed||!p.bleThread.quit)throw new AssertionError("Remaining resources not cleaned");
 }
}'''
out=ROOT/'build/close-probe';out.mkdir(parents=True,exist_ok=True);(out/'CloseProbe.java').write_text(text,encoding='utf8')
subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(ROOT/'src/com/imu/validator/InteractionFlow.java'),str(out/'CloseProbe.java')],check=True)
r=subprocess.run(['java','-cp',str(out),'CloseProbe'],capture_output=True,text=True);print(r.stdout,r.stderr)
(ROOT/'reports/manager-close-result.txt').write_text(r.stdout+r.stderr,encoding='utf8');raise SystemExit(r.returncode)
