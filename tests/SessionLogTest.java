import com.imu.validator.*;
import java.io.*;import java.nio.file.*;import java.util.concurrent.*;
public class SessionLogTest {
 public static void main(String[] args)throws Exception {
  File dir=Files.createTempDirectory("imu-log-io").toFile();
  SessionLog log=new SessionLog(new android.content.Context(){public File getFilesDir(){return dir;}});
  CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
  SessionLog.IO.execute(()->{held.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}});held.await();
  // Actual event path must return while disk executor is blocked.
  for(int i=0;i<1000;i++)log.event(1,"notification","valid",true,"index",i);
  CountDownLatch done=new CountDownLatch(1);File[] snapshot={null};String[] error={""};
  log.snapshot((f,e)->{snapshot[0]=f;error[0]=e;done.countDown();});
  for(int i=1000;i<2000;i++)log.event(1,"notification","valid",true,"index",i);
  release.countDown();if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("snapshot timeout");
  log.close();SessionLog.IO.submit(()->{}).get(15,TimeUnit.SECONDS);
  if(!error[0].isEmpty()||!log.error().isEmpty())throw new AssertionError(error[0]+log.error());
  if(Files.readAllLines(snapshot[0].toPath()).size()!=1000)throw new AssertionError("inconsistent snapshot boundary");
  if(Files.readAllLines(log.file.toPath()).size()!=2000)throw new AssertionError("notifications lost");
  if(!Files.readAllLines(snapshot[0].toPath()).get(0).contains("attempt_id\":1"))throw new AssertionError("attempt context missing");
  SessionLog.IO.shutdown();
  try(java.util.stream.Stream<Path> paths=Files.walk(dir.toPath())){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}
  System.out.println("PASS actual SessionLog FIFO: blocked disk did not block producer; snapshot=1000, active=2000; no drops");
 }
}
