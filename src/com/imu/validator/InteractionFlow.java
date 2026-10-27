package com.imu.validator;

/** Worker-thread state machine. Port is the only entry to the actual BLE adapter. */
public final class InteractionFlow {
    public interface Port {
        void event(long attempt, String type, String reason);
        void connect(long attempt, String key);
        void stopScan();
    }
    private final Port port;
    public String phase = "disconnected";
    public boolean scanning;
    public long attempt, scanToken, scanDeadline;
    public InteractionFlow(Port port) { this.port = port; }
    public static boolean acceptsCallback(Object current,Object incoming,long currentAttempt,long incomingAttempt,boolean active) {
        return current==incoming&&currentAttempt==incomingAttempt&&active;
    }
    public boolean busy() {
        return !(phase.equals("disconnected") || phase.equals("failed") || phase.equals("cancelled"));
    }
    public long startScan(long now) {
        scanning = true; scanDeadline = now + 20000; return ++scanToken;
    }
    public void stopScan() {
        scanning = false; ++scanToken; port.stopScan();
    }
    public boolean scanExpired(long token, long now) {
        if (!scanning || token != scanToken || now < scanDeadline) return false;
        stopScan(); return true;
    }
    public boolean select(long id, String key, String denied) {
        return selectChecked(id, key, () -> denied);
    }
    public boolean selectChecked(long id, String key, java.util.function.Supplier<String> check) {
        port.event(id, "device_selected", key); // before any state/permission rejection
        if (busy()) { port.event(id, "connect_rejected", "busy"); return false; }
        String denied=check.get();
        if (denied != null) {
            port.event(id, "connect_rejected", denied); return false;
        }
        attempt = id; stopScan(); phase = "connecting";
        port.event(id, "connect_requested", key);
        port.connect(id, key); return true;
    }
    public boolean transition(long id, String next) {
        if (id != attempt || !busy()) return false;
        phase = next; return true;
    }
    public boolean finish(long id, String outcome) {
        if (id != attempt) return false;
        phase = outcome.equals("failed") || outcome.equals("timeout") ? "failed" :
                outcome.equals("cancelled") ? "cancelled" : "disconnected";
        return true;
    }
}
