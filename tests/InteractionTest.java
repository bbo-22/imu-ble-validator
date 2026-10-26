import com.imu.validator.*;
import java.util.*;
public class InteractionTest {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static class Row {boolean attached=true;String key;Row(String key){this.key=key;}}
 public static void main(String[] args) throws Exception {
  List<String> events=new ArrayList<>();int[] connects={0},creations={0},updates={0};
  InteractionFlow flow=new InteractionFlow(new InteractionFlow.Port(){
   public void event(long a,String type,String reason){events.add(a+":"+type+":"+reason);}
   public void connect(long a,String key){connects[0]++;}
   public void stopScan(){ }
  });
  StableRows<Row> rows=new StableRows<>(new StableRows.Binder<Row>(){
   public Row create(String key){creations[0]++;return new Row(key);}
   public void update(Row row,String name,int rssi,boolean selected){updates[0]++;}
  });
  long scan=flow.startScan(100);rows.update("target","BDA446T-test",-50,false);Row pressed=rows.get("target");
  rows.touchDown("target");
  for(int i=0;i<100000;i++)rows.update(i%2==0?"target":"other-"+(i%30),"BDA446T-test",-50-i%30,false);
  check(creations[0]==1&&updates[0]==1,"no insertion/layout changes while pointer is down");
  rows.touchEnd("target");for(int i=0;i<30;i++)rows.update("other-"+i,"new",-60,false);
  check(creations[0]==31,"coalesced devices appear after pointer release");
  check(rows.get("target")==pressed&&pressed.attached,"pressed target survives scan flood");
  flow.select(1,"target",null);check(connects[0]==1,"scan click calls BLE once");check(!flow.scanning,"selection stops scan");
  check(!flow.scanExpired(scan,30000),"old scan deadline cannot affect connection");check(flow.phase.equals("connecting"),"phase preserved");
  flow.select(2,"target",null);check(connects[0]==1,"busy click rejected");check(events.stream().anyMatch(s->s.contains("2:connect_rejected:busy")),"rejection logged");
  flow.finish(1,"failed");flow.select(3,"target","permission_denied");check(connects[0]==1,"permission rejects BLE");check(events.stream().anyMatch(s->s.contains("3:connect_rejected:permission_denied")),"reason preserved");
  flow.select(4,"target",null);check(connects[0]==2,"retry after failure");check(!flow.transition(1,"discovering"),"old callback rejected");
  flow.finish(4,"cancelled");flow.select(5,"target",null);check(connects[0]==3,"retry after cancel");
  check(flow.finish(5,"timeout"),"timeout applies");flow.select(6,"target",null);check(connects[0]==4,"retry after timeout");
  check(events.indexOf("1:device_selected:target")<events.indexOf("1:connect_requested:target"),"entry logged before request");
  flow.finish(6,"failed");
  flow.selectChecked(7,"target",()->{check(events.get(events.size()-1).equals("7:device_selected:target"),"selection logged BEFORE permission evaluation");return "denied";});
  long newer=flow.startScan(50000);check(!flow.scanExpired(scan,90000),"restart token");check(flow.scanExpired(newer,70000),"current deadline");
  System.out.println("PASS interaction assertions="+checks+", updates=100000, rows="+rows.size());
 }
}
