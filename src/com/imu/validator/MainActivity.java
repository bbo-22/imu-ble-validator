package com.imu.validator;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.location.LocationManager;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** UI renders immutable snapshots; BLE runs on one worker, disk IO on a separate FIFO. */
public final class MainActivity extends Activity {
    static final String VERSION = "1.2.0";
    static final UUID SERVICE = UUID.fromString("00006801-afdd-43a1-9651-c6407a7c2515");
    static final UUID NOTIFY = UUID.fromString("00006803-afdd-43a1-9651-c6407a7c2515");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    final Handler ui = new Handler(Looper.getMainLooper());
    final HandlerThread bleThread = new HandlerThread("imu-ble");
    Handler worker;
    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    ScanCallback scanCallback;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic characteristic;
    InteractionFlow flow;
    SessionLog log;
    ReadingState reading = new ReadingState();
    DiagnosticSample latestValidSample;
    volatile DiagnosticSample.Context diagnosticContext=new DiagnosticSample.Context(null,0,2000,false,false,false);
    final AtomicLong attemptSequence = new AtomicLong();
    volatile Map<String, DeviceInfo> found = new ConcurrentHashMap<>();
    volatile long scanEpoch, scanDeadline;
    volatile boolean scanning, foreground;
    volatile String selectedKey = "";
    volatile Display display = new Display("未连接", "disconnected", "", "", false);
    long timeout = 2000, subscribedAt = -1, connectAt = -1, operationToken;
    long sessionValid, sessionInvalid, sessionChecksumErrors;
    int mtu = 23;
    String message = "未连接", diskError = "";
    boolean firstValid, sharing;
    DeviceInfo selected, pendingSelection;
    LinearLayout deviceList;
    ScrollView rootScroll;
    StableRows<Button> rows;
    TextView statusView, angleView, detailView, scanView, diagnosticView, markerFeedback;
    Button stopScanButton, connectionButton;
    EditText timeoutInput, experimentInput;

    static final class DeviceInfo {
        final BluetoothDevice device; final String key, name; final int rssi;
        DeviceInfo(BluetoothDevice d, String k, String n, int r) { device=d;key=k;name=n;rssi=r; }
    }
    static final class Display {
        final String status, phase, angle, details; final boolean busy;
        Display(String s,String p,String a,String d,boolean b) {status=s;phase=p;angle=a;details=d;busy=b;}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        bleThread.start(); worker = new Handler(bleThread.getLooper());
        buildUi();
        worker.post(() -> {
            try { log = new SessionLog(this); }
            catch (Exception e) { diskError = e.toString(); }
            flow = new InteractionFlow(new InteractionFlow.Port() {
                public void event(long a,String event,String reason) {
                    recordFor(a,event,"reason",reason,"device_name",pendingSelection==null?"":pendingSelection.name,
                            "device_id",pendingSelection==null?"":pendingSelection.key,"gatt_phase",flow.phase);
                }
                public void stopScan() { stopScanner("selection_or_timer"); }
                public void connect(long a,String key) { selected=pendingSelection;beginGatt(a); }
            });
            record("session_start","app_version",VERSION,"sdk",Build.VERSION.SDK_INT);
            worker.post(publishLoop);
        });
        ui.post(renderLoop);
    }
    Button button(LinearLayout root,String text,Runnable action) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); root.addView(b); return b;
    }
    void buildUi() {
        ScrollView scroll = new ScrollView(this);rootScroll=scroll;
        LinearLayout root = new LinearLayout(this); root.setOrientation(1);root.setPadding(20,12,20,20);
        scroll.addView(root);setContentView(scroll);
        TextView title = new TextView(this);title.setText("IMU 接收验证 v"+VERSION);title.setTextSize(24);root.addView(title);
        statusView = new TextView(this);statusView.setText("未连接");statusView.setTextSize(20);root.addView(statusView);
        scanView = new TextView(this);root.addView(scanView);
        button(root,"扫描 / 重新扫描",() -> {
            statusView.setText("已收到扫描请求");
            worker.post(this::startScan);
            requestMissingPermissions();
        });
        stopScanButton = button(root,"停止扫描",() -> worker.post(() -> {
            flow.stopScan(); if (!flow.busy()) state("扫描已主动停止，请选择设备");
        }));
        connectionButton = button(root,"未连接",() -> worker.post(() -> disconnect("manual",true)));
        button(root,"导出最近一次会话",() -> exportLogs(true));
        button(root,"选择历史会话",() -> exportLogs(false));
        angleView = new TextView(this);angleView.setTextSize(30);root.addView(angleView);
        LinearLayout diagnosticPanel=new LinearLayout(this);diagnosticPanel.setOrientation(1);
        button(root,"展开 / 收起 三轴诊断",()->diagnosticPanel.setVisibility(diagnosticPanel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        diagnosticView=new TextView(this);diagnosticView.setTypeface(android.graphics.Typeface.MONOSPACE);diagnosticView.setTextSize(14);diagnosticPanel.addView(diagnosticView);
        TextView diagnosticNote=new TextView(this);
        diagnosticNote.setText("角度单位为候选°，加速度单位为候选g；A/B/C未命名为物理X/Y/Z。轴名和旋转顺序待验证；angle1安装平面方向有实验支持，Pitch解释有较强支持。Yaw不代表已确认的北向航向。+180°与−180°表示同一方向。当前仅显示原始值，不启用相对参考。");
        diagnosticPanel.addView(diagnosticNote);root.addView(diagnosticPanel);diagnosticPanel.setVisibility(View.GONE);
        experimentInput=new EditText(this);experimentInput.setSingleLine(true);experimentInput.setHint("实验名称（可输入或选择）");experimentInput.setText("实验A：绕标签面法线");
        experimentInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});root.addView(experimentInput);
        button(root,"选择实验名称",()->{
            String[] names={"实验A：绕标签面法线","实验B：绕标签面内横向","实验C：绕竖直方向"};
            new AlertDialog.Builder(this).setTitle("实验名称（不影响连接）").setItems(names,(d,i)->experimentInput.setText(names[i])).show();
        });
        String[] actions={"基准静止","开始转动","到位静止","返回基准"};
        for(int i=0;i<actions.length;i+=2){
            LinearLayout pair=new LinearLayout(this);pair.setOrientation(0);root.addView(pair);
            for(int j=i;j<i+2;j++){
                final String action=actions[j];Button marker=button(pair,action,()->markExperiment(action));
                marker.setLayoutParams(new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
            }
        }
        markerFeedback=new TextView(this);markerFeedback.setText("标记按钮可在无数据时使用；仅前台接收，输入实验名称不会主动断开连接。");root.addView(markerFeedback);
        detailView = new TextView(this);detailView.setTextIsSelectable(true);root.addView(detailView);
        TextView note = new TextView(this);note.setText("标签竖直、文字正向、箭头向下为基准。正对标签：顺时针增大，逆时针减小。这是传感器倾角，不是铲斗角。离开APP会断开。");root.addView(note);
        timeoutInput = new EditText(this);timeoutInput.setInputType(2);timeoutInput.setText("2000");timeoutInput.setHint("数据超时毫秒，200–60000");root.addView(timeoutInput);
        button(root,"应用数据超时",() -> {
            try { long t=Long.parseLong(timeoutInput.getText().toString());if(t<200||t>60000)throw new IllegalArgumentException();
                worker.post(() -> {timeout=t;record("timeout_configuration","timeout_ms",t);}); hideKeyboard();
            } catch(Exception e) {Toast.makeText(this,"请输入200–60000毫秒",Toast.LENGTH_SHORT).show();}
        });
        TextView listTitle = new TextView(this);listTitle.setText("点击设备即连接；★仅为名称候选，连接后验证服务");root.addView(listTitle);
        deviceList = new LinearLayout(this);deviceList.setOrientation(1);root.addView(deviceList);
        resetRows();
    }
    void markExperiment(String action) {
        // Both timestamps and immutable sample are captured at the UI click entry, before queueing.
        final long wall=System.currentTimeMillis(),mono=SystemClock.elapsedRealtimeNanos();
        final DiagnosticSample.Context context=diagnosticContext;
        final String experiment=experimentInput.getText().toString().trim();
        final Object[] payload=context.marker(experiment,action,wall,mono);
        String reason=context.invalidReason(mono);
        markerFeedback.setText("已点击标记："+action+" · "+(experiment.isEmpty()?"未命名实验":experiment)+(reason.isEmpty()?" · 有效样本":" · 数据无效："+reason));
        worker.post(()->{
            if(log==null){ui.post(()->markerFeedback.setText("标记未保存：会话日志不可用"));return;}
            log.eventAt(context.attempt,"marker",wall,mono,payload);
        });
    }
    void resetRows() {
        deviceList.removeAllViews(); // only explicit new scan; never on an advertisement
        rows = new StableRows<>(new StableRows.Binder<Button>() {
            public Button create(String key) {
                Button row = new Button(MainActivity.this);row.setTag(key);
                row.setEnabled(true);row.setClickable(true);
                row.setOnClickListener(v -> deviceClicked(key));
                row.setOnTouchListener((v,event) -> {
                    int action=event.getActionMasked();
                    if(action==MotionEvent.ACTION_DOWN)rows.touchDown(key);
                    else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)rows.touchEnd(key);
                    return false; // preserve Android's native click/scroll dispatch
                });
                // Insert only the NEW row; existing pressed views remain attached.
                DeviceInfo info=found.get(key);int index=0;
                for(;index<deviceList.getChildCount();index++) {
                    String other=(String)deviceList.getChildAt(index).getTag();
                    if(compareDevices(info,found.get(other))<0)break;
                }
                deviceList.addView(row,index);return row;
            }
            public void update(Button row,String name,int rssi,boolean selected) {
                if(row.isPressed())return; // don't remeasure the active touch target
                String text=(selected?"▶ ":name.contains("BDA446T")?"★ ":"")+name+"\n"+row.getTag()+"  RSSI "+rssi;
                if(!text.contentEquals(row.getText()))row.setText(text);
                row.setAlpha(selected?1f:0.85f);
            }
        });
    }
    int compareDevices(DeviceInfo a,DeviceInfo b) {
        if(a==null||b==null)return 0;
        int c=Boolean.compare(b.name.contains("BDA446T"),a.name.contains("BDA446T"));
        return c!=0?c:a.key.compareTo(b.key);
    }
    void deviceClicked(String key) {
        // Capture immutable selection at click time; no list index or mutable ScanResult closure.
        DeviceInfo info = found.get(key); long id = attemptSequence.incrementAndGet();
        long wall=System.currentTimeMillis(), mono=SystemClock.elapsedRealtimeNanos();
        selectedKey=key;
        if(!display.busy){
            String feedback="正在连接 "+(info==null?key:info.name);
            statusView.setText(feedback);Toast.makeText(this,feedback,Toast.LENGTH_SHORT).show();
            rootScroll.smoothScrollTo(0,0);
        }
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
                else ui.post(() -> Toast.makeText(this,reason,Toast.LENGTH_SHORT).show());
            }
        });
    }
    String[] requiredPermissions() {
        return Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    String permissionFailure() {
        String[] p=requiredPermissions();
        for(String permission:p)if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return "permission_denied:"+permission;
        if(!foreground)return "activity_not_foreground";
        if(adapter==null)return "bluetooth_not_supported";
        try {if(!adapter.isEnabled())return "bluetooth_disabled";}catch(SecurityException e){return "permission_denied:"+e;}
        return null;
    }
    void requestMissingPermissions() {
        String[] p=requiredPermissions();
        List<String> missing=new ArrayList<>();for(String s:p)if(checkSelfPermission(s)!=PackageManager.PERMISSION_GRANTED)missing.add(s);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),10);
    }
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] r) {
        super.onRequestPermissionsResult(c,p,r);
        worker.post(() -> {record("permission_result","permissions",Arrays.toString(p),"results",Arrays.toString(r));if(!flow.busy())state("权限结果已返回，请再次点击扫描");});
    }
    void recordFor(long attempt,String type,Object... pairs) { if(log!=null)log.event(attempt,type,pairs); }
    void record(String type,Object... pairs) { recordFor(flow==null?0:flow.attempt,type,pairs); }
    void state(String next) {
        String previous=message;message=next;
        record("ui_state","previous",previous,"state",next,"gatt_phase",flow==null?"initializing":flow.phase,"scan_state",scanning?"scanning":"stopped");
        publish();
    }
    void startScan() {
        record("scan_requested");
        if(flow.busy()){record("scan_failed","reason","connection_busy");ui.post(() -> Toast.makeText(this,"请先取消或断开连接",Toast.LENGTH_SHORT).show());return;}
        flow.stopScan();
        String denied=permissionFailure();
        if(denied!=null){record("scan_failed","reason",denied);state("扫描未启动："+denied);return;}
        try {
            if(Build.VERSION.SDK_INT<=30&&!((LocationManager)getSystemService(LOCATION_SERVICE)).isLocationEnabled()) {
                record("scan_failed","reason","location_service_disabled");state("请开启系统位置服务后扫描");return;
            }
            final Map<String,DeviceInfo> scanFound=new ConcurrentHashMap<>();found=scanFound;
            selectedKey="";final long token=flow.startScan(SystemClock.elapsedRealtime());
            scanEpoch=token;scanDeadline=flow.scanDeadline;scanning=true;
            ui.post(() -> {if(scanEpoch==token)resetRows();});
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
    boolean current(BluetoothGatt g,long attempt) {return g==gatt&&attempt==flow.attempt&&flow.busy();}
    void beginGatt(long attempt) {
        reading=new ReadingState();mtu=23;firstValid=false;subscribedAt=-1;connectAt=SystemClock.elapsedRealtime();
        latestValidSample=null;
        state("正在连接 "+selected.name);arm(attempt,"connecting",15000);
        try {
            gatt=selected.device.connectGatt(this,false,callbacks(attempt),BluetoothDevice.TRANSPORT_LE);
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
        diagnosticContext=new DiagnosticSample.Context(latestValidSample,flow==null?0:flow.attempt,timeout,reading.connected,reading.subscribed,reading.lastPacketValid);
        String angle=live&&reading.latest!=null?String.format(Locale.ROOT,"%+.2f°",reading.latest.angle):"失效 / 尚无有效数据";
        String error=diskError+(log==null?"":log.error());
        String details="有效 "+reading.validCount+"；无效 "+reading.invalidCount+"；校验错误 "+reading.checksumErrors+
                String.format(Locale.ROOT,"\n会话平均接收频率 %.2f Hz",reading.frequency(now))+
                "\n最后通知距今："+(reading.last<0?"无":(now-reading.last)+" ms")+
                "\n原始hex："+(reading.latest==null?"无":reading.latest.hex)+
                "\n解析错误："+(reading.latest==null?"无":reading.latest.error)+"\n日志错误："+error;
        display=new Display(message+"\nMTU "+mtu+"；订阅 "+reading.subscribed+"；attempt "+(flow==null?0:flow.attempt),flow==null?"initializing":flow.phase,angle,details,flow!=null&&flow.busy());
    }
    final Runnable publishLoop=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        if(flow!=null&&firstValid&&reading.subscribed&&now-reading.last>=timeout&&(flow.phase.equals("receiving")||flow.phase.equals("invalid_frame"))) {
            flow.transition(flow.attempt,"data_timeout");record("data_timeout","timeout_ms",timeout);state("通知超时，数据失效；可断开重试");
        }
        publish();worker.postDelayed(this,200);
    }};
    final Runnable renderLoop=new Runnable(){public void run(){
        Display d=display;setTextIfChanged(statusView,"状态："+d.status+"\n连接阶段："+d.phase);
        setTextIfChanged(scanView,scanning?"扫描中 · 剩余 "+Math.max(0,(scanDeadline-SystemClock.elapsedRealtime()+999)/1000)+" 秒 · "+found.size()+" 个设备":"扫描已停止 · "+found.size()+" 个设备");
        stopScanButton.setEnabled(scanning);
        connectionButton.setEnabled(d.busy);connectionButton.setText(d.phase.equals("connecting")?"取消连接":d.busy?"断开连接":"未连接");
        DiagnosticSample.Context context=diagnosticContext;long nowNs=SystemClock.elapsedRealtimeNanos();
        boolean live=context.invalidReason(nowNs).isEmpty();
        setTextIfChanged(angleView,live?String.format(Locale.ROOT,"%+.2f°",context.sample.angle1):"失效 / 尚无有效数据");
        setTextIfChanged(diagnosticView,DiagnosticSample.display(context,nowNs));setTextIfChanged(detailView,d.details);
        List<DeviceInfo> devices=new ArrayList<>(found.values());devices.sort(MainActivity.this::compareDevices);
        for(DeviceInfo info:devices)rows.update(info.key,info.name,info.rssi,info.key.equals(selectedKey));
        ui.postDelayed(this,400);
    }};
    void setTextIfChanged(TextView view,String text){if(!text.contentEquals(view.getText()))view.setText(text);}
    void hideKeyboard() {
        View focus=getCurrentFocus();if(focus!=null){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}
    }
    void exportLogs(boolean latest) {
        hideKeyboard();
        worker.post(() -> {summary();SessionLog.IO.execute(() -> {
            try {
                List<SessionFiles.Entry> entries=SessionFiles.history(new File(getFilesDir(),"sessions"));
                ui.post(() -> {
                    if(isFinishing()||isDestroyed())return;
                    if(entries.isEmpty()){Toast.makeText(this,"暂无会话",Toast.LENGTH_SHORT).show();return;}
                    if(latest){exportFile(entries.get(0).file);return;}
                    String[] labels=new String[entries.size()];for(int i=0;i<labels.length;i++)labels[i]=entries.get(i).label();
                    new AlertDialog.Builder(this).setTitle("会话（最新在前）").setItems(labels,(d,i)->exportFile(entries.get(i).file)).show();
                });
            }catch(Exception e){ui.post(() -> Toast.makeText(this,"历史日志读取失败："+e,Toast.LENGTH_LONG).show());}
        });});
    }
    void exportFile(File file) {
        worker.post(() -> {
            record("export_requested","file",file.getName());summary();
            if(log!=null&&file.equals(log.file)) {
                log.snapshot((snapshot,error)->ui.post(()->{if(snapshot==null)Toast.makeText(this,"快照失败："+error,Toast.LENGTH_LONG).show();else shareSnapshot(snapshot);}));
            }else SessionLog.IO.execute(() -> {
                try {
                    File dir=new File(getFilesDir(),"sessions/snapshots");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("mkdir snapshots");
                    File out=File.createTempFile(file.getName().replace(".jsonl", "")+"_snapshot-",".jsonl",dir);
                    Files.copy(file.toPath(),out.toPath(),StandardCopyOption.REPLACE_EXISTING);ui.post(()->shareSnapshot(out));
                }catch(Exception e){ui.post(()->Toast.makeText(this,"快照失败："+e,Toast.LENGTH_LONG).show());}
            });
        });
    }
    void shareSnapshot(File file) {
        if(isFinishing()||isDestroyed())return;
        hideKeyboard();sharing=true;
        Uri uri=Uri.parse("content://com.imu.validator.logs/snapshots/"+file.getName());
        Intent send=new Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("IMU log snapshot",uri));startActivity(Intent.createChooser(send,"导出会话快照"));
    }
    @Override protected void onStart(){super.onStart();foreground=true;sharing=false;}
    @Override protected void onStop(){
        foreground=false;
        worker.post(()->{
            if(flow!=null){
                boolean wasScanning=scanning;flow.stopScan();
                if(gatt!=null)disconnect("activity_stopped",false);
                else if(wasScanning&&!flow.busy())state("未连接；扫描因离开页面停止");
                summary();
            }
        });
        super.onStop();
    }
    @Override protected void onDestroy(){
        ui.removeCallbacksAndMessages(null);
        worker.post(()->{worker.removeCallbacksAndMessages(null);if(log!=null){summary();log.close();}bleThread.quitSafely();});super.onDestroy();
    }
}
