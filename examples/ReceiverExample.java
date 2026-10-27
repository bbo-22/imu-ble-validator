package com.imu.example;

import android.content.Context;
import android.os.SystemClock;
import com.imu.validator.*;
import java.util.List;
import java.util.function.Consumer;

/** Compile-checked example, never auto-scans or connects. Host owns permission dialogs/lifecycle. */
public final class ReceiverExample implements AutoCloseable {
    private final BleImuManager receiver;
    public ReceiverExample(Context context,Consumer<BleImuManager.Display> state,
                           Consumer<DiagnosticSample> samples,Consumer<String> notice){
        receiver=new BleImuManager(context,new BleImuManager.Listener(){
            public void onState(BleImuManager.Display value){state.accept(value);} // BLE worker
            public void onSample(DiagnosticSample value){samples.accept(value);} // BLE worker, every notification
            public void onNotice(String text){notice.accept(text);} // main looper
            public void onScanReset(long token){ } // main looper; reset host's stable list here
        },"host-example");
    }
    public void onStart(){receiver.setForeground(true);}
    public void onStop(){receiver.background();}
    public void scanAfterUserClick(){receiver.requestScan();} // host must already hold requiredPermissions()
    public List<BleImuManager.DeviceInfo> nearby(){return receiver.devices();}
    public void selectAfterUserClick(BleImuManager.DeviceInfo device){
        receiver.select(device.key,device,System.currentTimeMillis(),SystemClock.elapsedRealtimeNanos());
    }
    public void cancelOrDisconnect(){receiver.requestDisconnect();}
    public DiagnosticSample.Context currentData(){return receiver.snapshot().data;}
    @Override public void close(){receiver.close();}
}
