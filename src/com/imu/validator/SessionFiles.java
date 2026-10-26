package com.imu.validator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/** Filesystem-only naming/history logic, independently testable on the JVM. */
public final class SessionFiles {
    private static final Pattern MODERN = Pattern.compile("session-(\\d+)_.*\\.jsonl");
    private static final Map<String, Pattern> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    public static synchronized File reserve(File dir, long now) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create sessions");
        File counter = new File(dir, ".sequence"); long n = 0;
        if (counter.exists()) {
            try { n = Long.parseLong(new String(Files.readAllBytes(counter.toPath()), StandardCharsets.UTF_8).trim()); }
            catch (NumberFormatException e) { throw new IOException("Corrupt session sequence", e); }
        }
        File[] existing = dir.listFiles(); if (existing == null) throw new IOException("Cannot list sessions");
        for (File f : existing) { Matcher m = MODERN.matcher(f.getName()); if (m.matches()) n = Math.max(n, Long.parseLong(m.group(1))); }
        String time = format(now, "yyyyMMdd-HHmmss"); File out;
        do { n++; out = new File(dir, String.format(Locale.ROOT, "session-%06d_%s.jsonl", n, time)); }
        while (!out.createNewFile());
        File temp = new File(dir, ".sequence.tmp");
        Files.write(temp.toPath(), Long.toString(n).getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), counter.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return out;
    }
    public static String format(long time, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f.format(new Date(time));
    }
    private static String value(String line, String key) {
        Pattern p=FIELDS.computeIfAbsent(key,k->Pattern.compile("\"" + k + "\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|[-0-9]+|true|false)"));
        Matcher m = p.matcher(line);
        if (!m.find()) return "";
        String s = m.group(1); return s.startsWith("\"") ? s.substring(1, s.length()-1).replace("\\\"", "\"").replace("\\\\", "\\") : s;
    }
    public static final class Entry {
        public File file; public long sequence, time, valid;
        public String device = "未选择设备", result = "无连接结果";
        public String label() { return (sequence > 0 ? "第" + sequence + "次" : "旧版会话") + " · " + format(time, "yyyy-MM-dd HH:mm:ss") + "\n" + device + " · " + result + " · 有效帧" + valid; }
    }
    public static Entry read(File file) throws IOException {
        Entry e = new Entry(); e.file = file; e.time = file.lastModified();
        Matcher m = MODERN.matcher(file.getName()); if (m.matches()) e.sequence = Long.parseLong(m.group(1));
        boolean timeFound = false;
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String wall = value(line, "wall_time_ms");
                if (!timeFound && !wall.isEmpty()) { e.time = Long.parseLong(wall); timeFound = true; }
                String event = value(line, "event");
                if (event.equals("connect_requested") || event.equals("selected_device")) {
                    String name = value(line, "device_name"); if (name.isEmpty()) name = value(line, "name"); if (!name.isEmpty()) e.device = name;
                }
                if (event.equals("notification") && value(line, "valid").equals("true")) e.valid++;
                if (event.equals("ui_state") || event.equals("state")) {
                    String s = value(line, "state"); if (!s.isEmpty()) e.result = s;
                }
                if (event.equals("session_summary")) {
                    String v = value(line, "valid"); if (!v.isEmpty()) e.valid = Long.parseLong(v);
                    String result = value(line, "result"); if (!result.isEmpty()) e.result = result;
                }
            }
        } catch (NumberFormatException ex) { e.result += "（部分元数据无效）"; }
        return e;
    }
    public static List<Entry> history(File dir) throws IOException {
        File[] files = dir.listFiles((d, n) -> n.startsWith("session-") && n.endsWith(".jsonl"));
        List<Entry> entries = new ArrayList<>(); if (files == null) return entries;
        for (File f : files) entries.add(read(f));
        entries.sort((a,b) -> {
            if (a.sequence > 0 && b.sequence > 0) return Long.compare(b.sequence, a.sequence);
            if ((a.sequence > 0) != (b.sequence > 0)) return a.sequence > 0 ? -1 : 1;
            return Long.compare(b.time, a.time);
        });
        return entries;
    }
}
