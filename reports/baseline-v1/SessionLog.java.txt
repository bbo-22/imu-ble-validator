package com.imu.validator;
import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
/** Each notification is written before throttled UI rendering. IO errors surface. */
public final class SessionLog {
 public final File file; private final BufferedWriter writer;
 public SessionLog(Context c) throws IOException {
  File dir=new File(c.getFilesDir(),"sessions"); if(!dir.exists()&&!dir.mkdirs()) throw new IOException("mkdir sessions");
  file=File.createTempFile("session-"+System.currentTimeMillis()+"-",".jsonl",dir);
  writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),"UTF-8"));
 }
 public synchronized void event(String type,Object... pairs) throws Exception { eventAt(type,System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos(),pairs); }
 public synchronized void eventAt(String type,long wall,long mono,Object... pairs) throws Exception {
  JSONObject o=new JSONObject(); o.put("event",type); o.put("wall_time_ms",wall); o.put("monotonic_ns",mono);
  for(int i=0;i<pairs.length;i+=2) o.put(pairs[i].toString(),pairs[i+1]);
  writer.write(o.toString()); writer.newLine(); writer.flush();
 }
 public synchronized void close() throws IOException { writer.close(); }
}
