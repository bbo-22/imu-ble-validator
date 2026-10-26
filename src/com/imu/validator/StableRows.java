package com.imu.validator;

import java.util.LinkedHashMap;
import java.util.Map;

/** The UI owns these row objects for the entire scan. Updating RSSI never removes a row. */
public final class StableRows<T> {
    public interface Binder<T> {
        T create(String key);
        void update(T row, String name, int rssi, boolean selected);
    }
    private final Map<String, T> rows = new LinkedHashMap<>();
    private final java.util.Set<String> touching = new java.util.HashSet<>();
    private final Binder<T> binder;
    public StableRows(Binder<T> binder) { this.binder = binder; }
    public void update(String key, String name, int rssi, boolean selected) {
        // No structural OR text layout updates anywhere in the list between DOWN and UP/CANCEL.
        // The caller retains latest device snapshots and retries after release.
        if (!touching.isEmpty()) return;
        T row = rows.get(key);
        if (row == null) { row = binder.create(key); rows.put(key, row); }
        binder.update(row, name, rssi, selected);
    }
    public T get(String key) { return rows.get(key); }
    public void touchDown(String key) { touching.add(key); }
    public void touchEnd(String key) { touching.remove(key); }
    public int size() { return rows.size(); }
}
