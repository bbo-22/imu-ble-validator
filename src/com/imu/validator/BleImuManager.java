package com.imu.validator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.location.LocationManager;
import android.os.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Reusable foreground BLE receiver. GATT operations stay on the original serial worker.
 * onState/onSample run on that worker; onNotice/onScanReset run on the main looper.
 * No Activity, UI widget, automatic reconnect or device initialization command.
 */
public final class BleImuManager implements AutoCloseable {
    public interface Listener {
        void onState(Display state);
        void onSample(DiagnosticSample sample);
        void onNotice(String text);
        void onScanReset(long token);
    }
    private final Context context; private final Listener listener; private final String version;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final HandlerThread bleThread=new HandlerThread("imu-ble");
    private final Handler worker;
    private BluetoothAdapter adapter; private BluetoothLeScanner scanner; private ScanCallback scanCallback;
    private BluetoothGatt gatt; private BluetoothGattCharacteristic characteristic;
    private InteractionFlow flow; private SessionLog log;
    private ReadingState reading=new ReadingState(); private DiagnosticSample latestValidSample;
    private DiagnosticSample.Context diagnosticContext=new DiagnosticSample.Context(null,0,2000,false,false,false);
    private final AtomicLong attemptSequence=new AtomicLong();
    private volatile Map<String,DeviceInfo> found=new ConcurrentHashMap<>();
    private volatile long scanEpoch,scanDeadline; private volatile boolean scanning,foreground;
    private volatile String selectedKey="";
    private volatile Display display=new Display("未连接","disconnected","","",false,diagnosticContext,"");
    private long timeout=2000,subscribedAt=-1,connectAt=-1,operationToken;
    private long sessionValid,sessionInvalid,sessionChecksumErrors;
    private int mtu=23; private String message="未连接",diskError="";
    private boolean firstValid; private DeviceInfo selected,pendingSelection;
    static final UUID SERVICE = UUID.fromString("00006801-afdd-43a1-9651-c6407a7c2515");
    static final UUID NOTIFY = UUID.fromString("00006803-afdd-43a1-9651-c6407a7c2515");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final class DeviceInfo {
        public final BluetoothDevice device; public final String key, name; public final int rssi;
        DeviceInfo(BluetoothDevice d, String k, String n, int r) { device=d;key=k;name=n;rssi=r; }
    }
    public static final class Display {
        public final String status, phase, angle, details, selectedKey; public final boolean busy;
        public final DiagnosticSample.Context data;
        Display(String s,String p,String a,String d,boolean b,DiagnosticSample.Context data,String key) {
            status=s;phase=p;angle=a;details=d;busy=b;this.data=data;selectedKey=key;
        }
    }

    public BleImuManager(Context context, Listener listener, String version) {
        this.context=context.getApplicationContext();this.listener=listener;this.version=version;
        BluetoothManager manager=(BluetoothManager)this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter=manager==null?null:manager.getAdapter();
        bleThread.start();worker=new Handler(bleThread.getLooper());
        worker.post(()->{
            try{log=new SessionLog(this.context);}catch(Exception e){diskError=e.toString();}
            flow=new InteractionFlow(new InteractionFlow.Port(){
                public void event(long a,String event,String reason){
                    recordFor(a,event,"reason",reason,"device_name",pendingSelection==null?"":pendingSelection.name,
                        "device_id",pendingSelection==null?"":pendingSelection.key,"gatt_phase",flow.phase);
                }
                public void stopScan(){stopScanner("selection_or_timer");}
                public void connect(long a,String key){selected=pendingSelection;beginGatt(a);}
            });
            record("session_start","app_version",version,"sdk",Build.VERSION.SDK_INT);
            worker.post(publishLoop);
        });
    }
    public Display snapshot(){return display;}
    public DeviceInfo device(String key){return found.get(key);}
    public List<DeviceInfo> devices(){return new ArrayList<>(found.values());}
    public int deviceCount(){return found.size();}
    public boolean isScanning(){return scanning;}
    public long scanDeadlineMs(){return scanDeadline;}
    public void setForeground(boolean value){foreground=value;}
    public void requestScan(){worker.post(this::startScan);}
    public void requestStopScan(){worker.post(()->{flow.stopScan();if(!flow.busy())state("扫描已主动停止，请选择设备");});}
    public void requestDisconnect(){worker.post(()->disconnect("manual",true));}
    public void setDataTimeout(long ms){
        if(ms<200||ms>60000)throw new IllegalArgumentException("timeout 200..60000 ms");
        worker.post(()->{timeout=ms;record("timeout_configuration","timeout_ms",ms);});
    }
    public void permissionResult(String[] permissions,int[] results){
        worker.post(()->{record("permission_result","permissions",Arrays.toString(permissions),"results",Arrays.toString(results));if(!flow.busy())state("权限结果已返回，请再次点击扫描");});
    }
    public void marker(DiagnosticSample.Context captured,String experiment,String action,long wall,long mono,Consumer<String> failure){
        Object[] payload=captured.marker(experiment,action,wall,mono);
        worker.post(()->{if(log==null){ui.post(()->failure.accept("会话日志不可用"));return;}log.eventAt(captured.attempt,"marker",wall,mono,payload);});
    }
    /** Callback executes on BLE worker after summary submission. Queue IO work onto SessionLog.IO. */
    public void prepareExport(String filename,Consumer<SessionLog> callback){worker.post(()->{if(filename!=null)record("export_requested","file",filename);summary();callback.accept(log);});}
    public void background(){
        foreground=false;
        worker.post(()->{if(flow!=null){boolean wasScanning=scanning;flow.stopScan();if(gatt!=null)disconnect("activity_stopped",false);else if(wasScanning&&!flow.busy())state("未连接；扫描因离开页面停止");summary();}});
    }
    @Override public void close(){
        ui.removeCallbacksAndMessages(null);
        worker.post(()->{if(flow!=null)flow.stopScan();worker.removeCallbacksAndMessages(null);if(gatt!=null)disconnect("manager_closed",false);if(log!=null){summary();log.close();}bleThread.quitSafely();});
    }
    public long select(String key,DeviceInfo info,long wall,long mono){
        long id=attemptSequence.incrementAndGet();selectedKey=key;
        worker.post(() -> {
            pendingSelection=info;
            // device_selected is emitted by flow BEFORE its rejection checks.
            recordFor(id,"device_row_click","device_id",key,"click_wall_time_ms",wall,"click_monotonic_ns",mono);
            final String[] denied={null};
            boolean accepted=flow.selectChecked(id,key,() -> {
                denied[0]=info==null?"device_no_longer_available":permissionFailure();
                if(denied[0]==null&&log==null)denied[0]="session_log_unavailable";
                return denied[0];
            });
            if(!accepted) {
                selectedKey=selected==null?"":selected.key;
                String reason=flow.busy()?"已有连接任务，请先取消/断开":denied[0];
                recordFor(id,"ui_feedback","state","连接请求被拒绝："+reason);
                if(!flow.busy())state("连接请求被拒绝："+reason);
                else ui.post(() -> listener.onNotice(reason));
            }
        });
        return id;
    }
    public static String[] requiredPermissions() {
        return Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    String permissionFailure() {
        String[] p=requiredPermissions();
        for(String permission:p)if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return "permission_denied:"+permission;
        if(!foreground)return "activity_not_foreground";
        if(adapter==null)return "bluetooth_not_supported";
        try {if(!adapter.isEnabled())return "bluetooth_disabled";}catch(SecurityException e){return "permission_denied:"+e;}
        return null;
    }
    void recordFor(long attempt,String type,Object... pairs) { if(log!=null)log.event(attempt,type,pairs); }
    void record(String type,Object... pairs) { recordFor(flow==null?0:flow.attempt,type,pairs); }
    void state(String next) {
        String previous=message;message=next;
        record("ui_state","previous",previous,"state",next,"gatt_phase",flow==null?"initializing":flow.phase,"scan_state",scanning?"scanning":"stopped");
        publish();
        listener.onState(display);
    }
    void startScan() {
        record("scan_requested");
        if(flow.busy()){record("scan_failed","reason","connection_busy");ui.post(() -> listener.onNotice("请先取消或断开连接"));return;}
        flow.stopScan();
        String denied=permissionFailure();
        if(denied!=null){record("scan_failed","reason",denied);state("扫描未启动："+denied);return;}
        try {
            if(Build.VERSION.SDK_INT<=30&&!((LocationManager)context.getSystemService(Context.LOCATION_SERVICE)).isLocationEnabled()) {
                record("scan_failed","reason","location_service_disabled");state("请开启系统位置服务后扫描");return;
            }
            final Map<String,DeviceInfo> scanFound=new ConcurrentHashMap<>();found=scanFound;
            selectedKey="";final long token=flow.startScan(SystemClock.elapsedRealtime());
            scanEpoch=token;scanDeadline=flow.scanDeadline;scanning=true;
            ui.post(() -> {if(scanEpoch==token)listener.onScanReset(token);});
            scanner=adapter.getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException("scanner unavailable");
            scanCallback=new ScanCallback() {
                @Override public void onScanResult(int type,ScanResult r) {
                    if(token!=scanEpoch||!scanning)return;
                    try {
                        String key=r.getDevice().getAddress();String name=r.getScanRecord()==null?null:r.getScanRecord().getDeviceName();
                        if(name==null)name=r.getDevice().getName();
                        DeviceInfo old=scanFound.get(key);if(name==null)name=old==null?"(未命名)":old.name;
                        // Coalesce RSSI before posting anything to UI/worker. No per-ad layout task.
                        scanFound.put(key,new DeviceInfo(r.getDevice(),key,name,r.getRssi()));
                    }catch(Exception e){worker.post(() -> exception("scan_result",e));}
                }
                @Override public void onScanFailed(int code) {worker.post(() -> {if(token!=scanEpoch)return;flow.stopScan();record("scan_failed","status_code",code);state("扫描失败 code="+code);});}
            };
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
            record("scan_started","scan_token",token,"duration_ms",20000);state("扫描中，可直接点击设备连接");
            worker.postDelayed(() -> {if(flow.scanExpired(token,SystemClock.elapsedRealtime())&&!flow.busy())state("扫描结束，请选择设备");},20000);
        }catch(Exception e){flow.stopScan();exception("scan_start",e);record("scan_failed","reason",e.toString());state("扫描失败："+e);}
    }
    void stopScanner(String reason) {
        boolean wasScanning=scanning;scanning=false;scanEpoch=flow.scanToken;
        if(scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception e){exception("scan_stop",e);}
        scanCallback=null;
        if(wasScanning)record("scan_stopped","reason",reason,"devices",found.size());
    }
    void exception(String operation,Exception e) {record("exception","operation",operation,"error",e.toString());}
    void arm(long attempt,String phase,long milliseconds) {
        flow.transition(attempt,phase);long token=++operationToken;
        worker.postDelayed(() -> {
            if(token!=operationToken||attempt!=flow.attempt||!phase.equals(flow.phase))return;
            record("operation_timeout","operation",phase,"timeout_ms",milliseconds);fail(phase+" 超时");
        },milliseconds);
    }
    boolean current(BluetoothGatt g,long attempt) {return InteractionFlow.acceptsCallback(gatt,g,flow.attempt,attempt,flow.busy());}
    void beginGatt(long attempt) {
        reading=new ReadingState();mtu=23;firstValid=false;subscribedAt=-1;connectAt=SystemClock.elapsedRealtime();
        latestValidSample=null;
        state("正在连接 "+selected.name);arm(attempt,"connecting",15000);
        try {
            gatt=selected.device.connectGatt(context,false,callbacks(attempt),BluetoothDevice.TRANSPORT_LE);
            record("connect_gatt_result","returned_instance",gatt!=null);
            if(gatt==null)fail("connectGatt返回null");
        }catch(Exception e){record("connect_gatt_result","returned_instance",false,"error",e.toString());exception("connectGatt",e);fail("连接调用失败："+e);}
    }
    BluetoothGattCallback callbacks(final long attempt) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g,int code,int newState) {worker.post(() -> {
                if(!current(g,attempt))return;
                record("connection_state_changed","status",code,"newState",newState);
                if(code!=0){fail("连接失败 status="+code+" state="+newState);return;}
                if(newState==BluetoothProfile.STATE_DISCONNECTED){disconnect("远端断开 status="+code,false);return;}
                if(newState==BluetoothProfile.STATE_CONNECTED&&flow.phase.equals("connecting")) {
                    reading.connected=true;++operationToken;arm(attempt,"discovering",12000);state("BLE已连接，正在发现服务");
                    try {boolean started=g.discoverServices();record("service_discovery_started","started",started);if(!started)fail("服务发现请求未启动");}
                    catch(Exception e){exception("discoverServices",e);fail("服务发现异常："+e);}
                }
            });}
            @Override public void onServicesDiscovered(BluetoothGatt g,int code) {worker.post(() -> {
                if(!current(g,attempt)||!flow.phase.equals("discovering"))return;
                ++operationToken;record("service_discovery_completed","status",code);
                if(code!=0){fail("服务发现失败 status="+code);return;}
                for(BluetoothGattService s:g.getServices()) {
                    record("service","uuid",s.getUuid().toString(),"type",s.getType(),"instance",s.getInstanceId());
                    for(BluetoothGattCharacteristic c:s.getCharacteristics()) {
                        record("characteristic","service",s.getUuid().toString(),"uuid",c.getUuid().toString(),"properties",c.getProperties(),"instance",c.getInstanceId());
                        for(BluetoothGattDescriptor d:c.getDescriptors())record("descriptor","characteristic",c.getUuid().toString(),"uuid",d.getUuid().toString());
                    }
                }
                BluetoothGattService s=g.getService(SERVICE);characteristic=s==null?null:s.getCharacteristic(NOTIFY);
                if(characteristic==null||(characteristic.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0||characteristic.getDescriptor(CCCD)==null){fail("目标服务、Notify属性或CCCD不完整");return;}
                arm(attempt,"mtu",10000);state("服务已验证，正在协商MTU");
                try{boolean started=g.requestMtu(128);record("mtu_requested","requested",128,"started",started);if(!started)fail("MTU请求未启动");}
                catch(Exception e){exception("requestMtu",e);fail("MTU异常："+e);}
            });}
            @Override public void onMtuChanged(BluetoothGatt g,int actual,int code) {worker.post(() -> {
                if(!current(g,attempt))return;mtu=actual;record("mtu_changed","actual_mtu",actual,"status",code);
                if(code!=0||actual<33){fail("MTU失败或不足33："+actual+" status="+code);return;}
                if(!flow.phase.equals("mtu"))return;
                ++operationToken;
                try {
                    boolean enabled=g.setCharacteristicNotification(characteristic,true);record("local_notification_enable","success",enabled);
                    if(!enabled){fail("本地通知启用失败");return;}
                    BluetoothGattDescriptor d=characteristic.getDescriptor(CCCD);d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    arm(attempt,"subscribing",10000);state("正在订阅，等待远端CCCD确认");
                    boolean started=g.writeDescriptor(d);record("subscription_started","started",started);if(!started)fail("CCCD请求未启动");
                }catch(Exception e){exception("subscribe",e);fail("订阅异常："+e);}
            });}
            @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code) {worker.post(() -> {
                if(!current(g,attempt)||!flow.phase.equals("subscribing")||!CCCD.equals(d.getUuid())||!NOTIFY.equals(d.getCharacteristic().getUuid()))return;
                ++operationToken;record("subscription_completed","status",code);
                if(code!=0){fail("CCCD订阅失败 status="+code);return;}
                reading.subscribed=true;subscribedAt=SystemClock.elapsedRealtime();arm(attempt,"waiting_first_valid",10000);state("订阅成功，等待首个有效帧");
            });}
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){receive(g,c,c.getValue(),attempt);}
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){receive(g,c,value,attempt);}
        };
    }
    void receive(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,long attempt) {
        final byte[] bytes=value==null?new byte[0]:value.clone();final long wall=System.currentTimeMillis(),mono=SystemClock.elapsedRealtimeNanos();
        worker.post(() -> {
            if(!current(g,attempt)||c!=characteristic)return;
            FrameParser.Frame f=FrameParser.parse(bytes);reading.receive(f,mono/1000000);
            if(f.valid)latestValidSample=new DiagnosticSample(f,wall,mono,attempt,log==null?"":log.sessionId);
            if(f.valid)sessionValid++;else{sessionInvalid++;if(f.error.contains("checksum"))sessionChecksumErrors++;}
            if(log!=null)log.eventAt(attempt,"notification",wall,mono,"raw_hex",f.hex,"valid",f.valid,"error",f.error,
                    "angle1_raw",bytes.length==30?f.angleRaw:org.json.JSONObject.NULL,"angle1",bytes.length==30?f.angle:org.json.JSONObject.NULL,
                    "counter_uint16",bytes.length==30?f.counter:org.json.JSONObject.NULL,"checksum_ok",f.checksumOk,"subscribed",reading.subscribed,"unknown_23_26_hex",f.unknown23to26,
                    "length",f.length,"angle2_raw",bytes.length==30?f.angle2Raw:null,"angle3_raw",bytes.length==30?f.angle3Raw:null,
                    "angle2",bytes.length==30?f.angle2:null,"angle3",bytes.length==30?f.angle3:null,
                    "roll_deg",bytes.length==30?f.rollDeg:null,"pitch_deg",bytes.length==30?f.pitchDeg:null,"yaw_deg",bytes.length==30?f.yawDeg:null,
                    "angle1_deg_candidate",bytes.length==30?f.angle:null,"angle2_deg_candidate",bytes.length==30?f.angle2:null,"angle3_deg_candidate",bytes.length==30?f.angle3:null,
                    "accelA_raw",bytes.length==30?f.accelARaw:null,"accelB_raw",bytes.length==30?f.accelBRaw:null,"accelC_raw",bytes.length==30?f.accelCRaw:null,
                    "accelA_g_candidate",bytes.length==30?f.accelA:null,"accelB_g_candidate",bytes.length==30?f.accelB:null,"accelC_g_candidate",bytes.length==30?f.accelC:null,
                    "accel_norm_g_candidate",bytes.length==30?f.accelNorm:null);
            if(f.valid&&reading.subscribed) {
                if(!firstValid){firstValid=true;++operationToken;record("first_valid_notification","since_connect_request_ms",reading.last-connectAt,"since_subscription_ms",reading.last-subscribedAt);}
                if(!flow.phase.equals("receiving")){flow.transition(attempt,"receiving");state("正在接收有效数据");}
            } else if(!f.valid&&reading.subscribed&&firstValid&&!flow.phase.equals("invalid_frame")) {
                flow.transition(attempt,"invalid_frame");state("收到无效帧，数据失效");
            }
            listener.onSample(new DiagnosticSample(f,wall,mono,attempt,log==null?"":log.sessionId));
            publish();
        });
    }
    void fail(String reason) {
        disconnect(reason,false);flow.finish(flow.attempt,"failed");state("失败："+reason);summary();
    }
    void disconnect(String reason,boolean user) {
        record("disconnect_requested","reason",reason,"had_gatt",gatt!=null);
        ++operationToken;BluetoothGatt old=gatt;gatt=null;characteristic=null;reading.disconnect();
        if(old!=null) {
            try{old.disconnect();}catch(Exception e){exception("disconnect",e);}
            try{old.close();}catch(Exception e){exception("close",e);}
        }
        flow.finish(flow.attempt,user?"cancelled":"disconnected");
        record("disconnect_completed","reason",reason,"completion","local_gatt_closed","remote_status_available",false);
        // Do not replace a persistent failure on lifecycle/export cleanup with an idle message.
        if(old!=null||user)state(user?"已取消/断开连接":"已断开："+reason);
        summary();publish();
    }
    void summary() {record("session_summary","result",message,"valid",sessionValid,"invalid",sessionInvalid,"checksum_errors",sessionChecksumErrors,"attempt_valid",reading.validCount,"actual_mtu",mtu);}
    void publish() {
        long now=SystemClock.elapsedRealtime();boolean live=reading.live(now,timeout);
        diagnosticContext=new DiagnosticSample.Context(latestValidSample,flow==null?0:flow.attempt,timeout,reading.connected,reading.subscribed,reading.lastPacketValid,log==null?"":log.sessionId);
        String angle=live&&reading.latest!=null?String.format(Locale.ROOT,"%+.2f°",reading.latest.angle):"失效 / 尚无有效数据";
        String error=diskError+(log==null?"":log.error());
        String details="有效 "+reading.validCount+"；无效 "+reading.invalidCount+"；校验错误 "+reading.checksumErrors+
                String.format(Locale.ROOT,"\n会话平均接收频率 %.2f Hz",reading.frequency(now))+
                "\n最后通知距今："+(reading.last<0?"无":(now-reading.last)+" ms")+
                "\n原始hex："+(reading.latest==null?"无":reading.latest.hex)+
                "\n解析错误："+(reading.latest==null?"无":reading.latest.error)+"\n日志错误："+error;
        display=new Display(message+"\nMTU "+mtu+"；订阅 "+reading.subscribed+"；attempt "+(flow==null?0:flow.attempt),flow==null?"initializing":flow.phase,angle,details,flow!=null&&flow.busy(),diagnosticContext,selectedKey);
    }
    final Runnable publishLoop=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        if(flow!=null&&firstValid&&reading.subscribed&&now-reading.last>=timeout&&(flow.phase.equals("receiving")||flow.phase.equals("invalid_frame"))) {
            flow.transition(flow.attempt,"data_timeout");record("data_timeout","timeout_ms",timeout);state("通知超时，数据失效；可断开重试");
        }
        publish();listener.onState(display);worker.postDelayed(this,200);
    }};
}
