import com.imu.validator.*;
import java.io.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
public class SessionFilesTest {
 static int checks;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  File dir=Files.createTempDirectory("imu-session-test").toFile();
  File first=SessionFiles.reserve(dir,1793005574558L);check(first.getName().equals("session-000001_20261026-170614.jsonl"),"readable Beijing name");
  Files.write(first.toPath(),Arrays.asList("{\"event\":\"session_start\",\"wall_time_ms\":1793005574558}","{\"event\":\"connect_requested\",\"device_name\":\"BDA446T-test\"}","{\"event\":\"device_selected\",\"device_name\":\"REJECTED-B\"}","{\"event\":\"connect_rejected\",\"reason\":\"busy\"}","{\"event\":\"session_summary\",\"result\":\"连接超时\",\"valid\":12}"),StandardCharsets.UTF_8);
  File second=SessionFiles.reserve(dir,1793005574558L);check(!first.equals(second)&&second.getName().contains("000002"),"same-second no overwrite");
  Files.delete(new File(dir,".sequence").toPath());File third=SessionFiles.reserve(dir,1700000000000L);check(third.getName().contains("000003"),"recovery and clock rollback");
  SessionFiles.Entry one=SessionFiles.read(first);check(one.sequence==1&&one.valid==12&&one.device.equals("BDA446T-test")&&one.result.equals("连接超时"),"readable metadata");
  File legacy=new File(dir,"session-1793005574557-1895376226639258203.jsonl");Files.copy(Paths.get(args[0]),legacy.toPath());
  SessionFiles.Entry old=SessionFiles.read(legacy);check(old.sequence==0&&old.time==1793005574558L&&old.valid==0,"real v1 history compatibility");
  check(old.result.contains("扫描结束"),"legacy state from content");
  List<SessionFiles.Entry> history=SessionFiles.history(dir);check(history.get(0).file.equals(third),"sequence order survives clock changes");check(history.size()==4,"legacy retained");
  try(java.util.stream.Stream<Path> paths=Files.walk(dir.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}
  System.out.println("PASS session files assertions="+checks);
 }
}
