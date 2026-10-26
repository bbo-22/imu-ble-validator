"""Execute v1 renderDevices verbatim against a minimal touch-target lifetime harness.
The fake View only models Android's detached touch target; no device operations.
"""
import re,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'reports/baseline-v1/MainActivity.java.txt').read_text(encoding='utf8')
def method(name):
    match=re.search(r' (?:void|String|boolean) '+name+r'\(',source)
    if not match: raise ValueError(name)
    start=match.start(); brace=source.index('{',match.end()); depth=1;i=brace+1
    while depth:
        depth+=(source[i]=='{')-(source[i]=='}');i+=1
    return source[start:i]
out=root/'build/repro';out.mkdir(parents=True,exist_ok=True)
program='''import java.util.*;
public class Repro {
 static class Button { boolean attached; Runnable click; String text; }
 static class LinearLayout { List<Button> rows=new ArrayList<>(); void removeAllViews(){for(Button b:rows)b.attached=false;rows.clear();} }
 static class Device {String getAddress(){return "test-device";} String getName(){return "BDA446T-test";} }
 static class ScanRecord {String getDeviceName(){return "BDA446T-test";} }
 static class ScanResult {Device getDevice(){return new Device();} ScanRecord getScanRecord(){return new ScanRecord();} int getRssi(){return -50;} }
 LinearLayout devices=new LinearLayout();Map<String,ScanResult> results=new LinkedHashMap<>();int calls;
 void button(LinearLayout p,String text,Runnable click){Button b=new Button();b.attached=true;b.click=click;b.text=text;p.rows.add(b);}
 void connect(ScanResult r){calls++;}
'''+method('renderDevices')+method('name')+method('candidate')+'''
 public static void main(String[] args){Repro r=new Repro();r.results.put("test-device",new ScanResult());r.renderDevices();Button pressed=r.devices.rows.get(0);
 // ACTION_DOWN target, one new advertisement, ACTION_UP on original target.
 r.renderDevices();if(pressed.attached)pressed.click.run();
 System.out.println("same_target_attached="+pressed.attached+" connect_calls="+r.calls);
 if(r.calls!=1)throw new AssertionError("Target detached between DOWN/UP: click lost"); }
}'''
if '--no-refresh' in sys.argv:
    program=program.replace('r.renderDevices();if(pressed.attached)', 'if(pressed.attached)')
(out/'Repro.java').write_text(program,encoding='utf8')
subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(out/'Repro.java')],check=True)
r=subprocess.run(['java','-cp',str(out),'Repro'],capture_output=True,text=True)
(root/('reports/repro-v1-control.txt' if '--no-refresh' in sys.argv else 'reports/repro-v1.txt')).write_text(r.stdout+r.stderr,encoding='utf8')
print(r.stdout+r.stderr);raise SystemExit(r.returncode)
