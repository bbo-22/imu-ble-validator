package com.imu.validator;
import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** FIFO disk writer; every notification is retained independently of UI rendering. */
public final class SessionLog {
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();
    public final File file;
    public final String sessionId;
    private BufferedWriter writer;
    private volatile String error = "";
    public SessionLog(Context context) throws IOException {
        file = SessionFiles.reserve(new File(context.getFilesDir(), "sessions"), System.currentTimeMillis());
        sessionId = file.getName().replace(".jsonl", "");
        IO.execute(() -> {
            try { writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8")); }
            catch (Exception ex) { error = ex.toString(); }
        });
    }
    public String error() { return error; }
    public void event(long attempt, String type, Object... pairs) {
        eventAt(attempt, type, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), pairs);
    }
    public void eventAt(long attempt, String type, long wall, long mono, Object... pairs) {
        IO.execute(() -> {
            try {
                JSONObject o = new JSONObject(); o.put("event", type); o.put("session_id", sessionId);
                o.put("attempt_id", attempt); o.put("wall_time_ms", wall); o.put("monotonic_ns", mono);
                for (int i=0;i<pairs.length;i+=2) o.put(pairs[i].toString(), pairs[i+1]);
                if (writer == null) throw new IOException("Session writer unavailable");
                writer.write(o.toString()); writer.newLine(); writer.flush();
            } catch (Exception ex) { error = ex.toString(); }
        });
    }
    public interface SnapshotCallback { void done(File snapshot, String error); }
    public void snapshot(SnapshotCallback cb) {
        IO.execute(() -> {
            try {
                if (writer != null) writer.flush();
                if (!error.isEmpty()) throw new IOException(error);
                File snapshots = new File(file.getParentFile(), "snapshots");
                if (!snapshots.isDirectory() && !snapshots.mkdirs()) throw new IOException("Cannot create snapshots");
                File out = File.createTempFile(file.getName().replace(".jsonl", "") + "_snapshot-", ".jsonl", snapshots);
                Files.copy(file.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                cb.done(out, "");
            } catch (Exception ex) { cb.done(null, ex.toString()); }
        });
    }
    public void close() {
        IO.execute(() -> { try { if (writer != null) writer.close(); } catch (Exception ex) { error = ex.toString(); } });
    }
}
