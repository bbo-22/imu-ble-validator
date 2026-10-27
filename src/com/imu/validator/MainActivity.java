package com.imu.validator;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.imu.validator.BleImuManager.DeviceInfo;
import com.imu.validator.BleImuManager.Display;

/** UI renders immutable snapshots; BLE runs on one worker, disk IO on a separate FIFO. */
public final class MainActivity extends Activity {
    static final String VERSION = "1.4.0";
    final Handler ui=new Handler(Looper.getMainLooper());
    BleImuManager manager;
    String selectedKey="";
    LinearLayout deviceList;
    ScrollView rootScroll;
    StableRows<Button> rows;
    TextView statusView, angleView, batteryView, detailView, scanView, diagnosticView, markerFeedback;
    Button stopScanButton, connectionButton;
    EditText timeoutInput, experimentInput;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        manager=new BleImuManager(this,new BleImuManager.Listener(){
            public void onState(Display state) { }
            public void onSample(DiagnosticSample sample) { }
            public void onNotice(String text){Toast.makeText(MainActivity.this,text,Toast.LENGTH_SHORT).show();}
            public void onScanReset(long token){resetRows();}
        },VERSION);
        buildUi();
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
            manager.requestScan();
            requestMissingPermissions();
        });
        stopScanButton = button(root,"停止扫描",manager::requestStopScan);
        connectionButton = button(root,"未连接",manager::requestDisconnect);
        button(root,"导出最近一次会话",() -> exportLogs(true));
        button(root,"选择历史会话",() -> exportLogs(false));
        angleView = new TextView(this);angleView.setTextSize(30);root.addView(angleView);
        batteryView=new TextView(this);batteryView.setText("电量：--");batteryView.setTextSize(20);root.addView(batteryView);
        TextView batteryNote=new TextView(this);batteryNote.setText("暂定字段，待非满电样本验证");root.addView(batteryNote);
        LinearLayout diagnosticPanel=new LinearLayout(this);diagnosticPanel.setOrientation(1);
        button(root,"展开 / 收起 三轴诊断",()->diagnosticPanel.setVisibility(diagnosticPanel.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
        diagnosticView=new TextView(this);diagnosticView.setTypeface(android.graphics.Typeface.MONOSPACE);diagnosticView.setTextSize(14);diagnosticPanel.addView(diagnosticView);
        TextView diagnosticNote=new TextView(this);
        diagnosticNote.setText("Roll/Pitch/Yaw的尺度与方向由当前设备的实物实验支持；完整欧拉角旋转顺序、任意姿态轴约定未确认。相对偏航角Yaw（本次连接）：重新连接后参考可能重置，不代表北向航向。Pitch接近±90°时，不独立解释Roll/Yaw。加速度为候选g，A/B/C不等于物理X/Y/Z。+180°与−180°为同一方向。显示设备原始值，无本地清零。");
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
                manager.setDataTimeout(t); hideKeyboard();
            } catch(Exception e) {Toast.makeText(this,"请输入200–60000毫秒",Toast.LENGTH_SHORT).show();}
        });
        TextView listTitle = new TextView(this);listTitle.setText("点击设备即连接；★仅为名称候选，连接后验证服务");root.addView(listTitle);
        deviceList = new LinearLayout(this);deviceList.setOrientation(1);root.addView(deviceList);
        resetRows();
    }
    void markExperiment(String action) {
        // Both timestamps and immutable sample are captured at the UI click entry, before queueing.
        final long wall=System.currentTimeMillis(),mono=SystemClock.elapsedRealtimeNanos();
        final DiagnosticSample.Context context=manager.snapshot().data;
        final String experiment=experimentInput.getText().toString().trim();
        String reason=context.invalidReason(mono);
        markerFeedback.setText("已点击标记："+action+" · "+(experiment.isEmpty()?"未命名实验":experiment)+(reason.isEmpty()?" · 有效样本":" · 数据无效："+reason));
        manager.marker(context,experiment,action,wall,mono,error->markerFeedback.setText("标记未保存："+error));
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
                DeviceInfo info=manager.device(key);int index=0;
                for(;index<deviceList.getChildCount();index++) {
                    String other=(String)deviceList.getChildAt(index).getTag();
                    if(compareDevices(info,manager.device(other))<0)break;
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
        DeviceInfo info = manager.device(key);
        long wall=System.currentTimeMillis(), mono=SystemClock.elapsedRealtimeNanos();
        selectedKey=key;
        if(!manager.snapshot().busy){
            String feedback="正在连接 "+(info==null?key:info.name);
            statusView.setText(feedback);Toast.makeText(this,feedback,Toast.LENGTH_SHORT).show();
            rootScroll.smoothScrollTo(0,0);
        }
        manager.select(key,info,wall,mono);
    }

    void requestMissingPermissions() {
        String[] p=BleImuManager.requiredPermissions();
        List<String> missing=new ArrayList<>();for(String s:p)if(checkSelfPermission(s)!=PackageManager.PERMISSION_GRANTED)missing.add(s);
        if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),10);
    }
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] r) {
        super.onRequestPermissionsResult(c,p,r);
        manager.permissionResult(p,r);
    }

    final Runnable renderLoop=new Runnable(){public void run(){
        Display d=manager.snapshot();selectedKey=d.selectedKey;setTextIfChanged(statusView,"状态："+d.status+"\n连接阶段："+d.phase);
        setTextIfChanged(scanView,manager.isScanning()?"扫描中 · 剩余 "+Math.max(0,(manager.scanDeadlineMs()-SystemClock.elapsedRealtime()+999)/1000)+" 秒 · "+manager.deviceCount()+" 个设备":"扫描已停止 · "+manager.deviceCount()+" 个设备");
        stopScanButton.setEnabled(manager.isScanning());
        connectionButton.setEnabled(d.busy);connectionButton.setText(d.phase.equals("connecting")?"取消连接":d.busy?"断开连接":"未连接");
        DiagnosticSample.Context context=d.data;long nowNs=SystemClock.elapsedRealtimeNanos();
        boolean live=context.invalidReason(nowNs).isEmpty();
        setTextIfChanged(angleView,live?String.format(Locale.ROOT,"%+.2f°",context.sample.angle1):"失效 / 尚无有效数据");
        setTextIfChanged(batteryView,DiagnosticSample.batteryDisplay(context,nowNs));
        setTextIfChanged(diagnosticView,DiagnosticSample.display(context,nowNs));setTextIfChanged(detailView,d.details);
        List<DeviceInfo> devices=manager.devices();devices.sort(MainActivity.this::compareDevices);
        for(DeviceInfo info:devices)rows.update(info.key,info.name,info.rssi,info.key.equals(selectedKey));
        ui.postDelayed(this,400);
    }};
    void setTextIfChanged(TextView view,String text){if(!text.contentEquals(view.getText()))view.setText(text);}
    void hideKeyboard() {
        View focus=getCurrentFocus();if(focus!=null){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}
    }
    void exportLogs(boolean latest) {
        hideKeyboard();
        manager.prepareExport(null,log -> {SessionLog.IO.execute(() -> {
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
        manager.prepareExport(file.getName(),log -> {
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
        hideKeyboard();
        Uri uri=Uri.parse("content://com.imu.validator.logs/snapshots/"+file.getName());
        Intent send=new Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("IMU log snapshot",uri));startActivity(Intent.createChooser(send,"导出会话快照"));
    }
    @Override protected void onStart(){super.onStart();manager.setForeground(true);}
    @Override protected void onStop(){manager.background();super.onStop();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);manager.close();super.onDestroy();}
}
