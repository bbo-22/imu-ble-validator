package com.imu.validator;
import android.Manifest; import android.app.*; import android.os.*; import android.content.*; import android.content.pm.PackageManager; import android.bluetooth.*; import android.bluetooth.le.*; import android.location.LocationManager; import android.net.Uri; import android.view.*; import android.widget.*;
import java.util.*; import java.io.*;

public final class MainActivity extends Activity {
 static final UUID SERVICE=UUID.fromString("00006801-afdd-43a1-9651-c6407a7c2515"), NOTIFY=UUID.fromString("00006803-afdd-43a1-9651-c6407a7c2515"), CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
 final Handler h=new Handler(Looper.getMainLooper()); BluetoothAdapter adapter; BluetoothLeScanner scanner; ScanCallback scanCb; BluetoothGatt gatt; BluetoothGattCharacteristic characteristic;
 SessionLog log; ReadingState reading=new ReadingState(); TextView statusView,angleView,detail; LinearLayout devices; EditText timeoutInput;
 String status="请扫描并选择设备", phase="idle", logError=""; long timeout=2000,subscribedAt=-1,connectStartedAt=-1; int actualMtu=23, generation=0,opToken=0; boolean foreground;
 final Map<String,ScanResult> results=new LinkedHashMap<>(); long scanVersion;
 void record(String type,Object... pairs) { try { if(log!=null) log.event(type,pairs); } catch(Exception e){logError="日志写入失败："+e;} }
 void state(String s) { status=s; record("state","state",s,"phase",phase); }
 void newLog() {
  if(log!=null) try{log.close();}catch(Exception e){logError=e.toString();}
  try { log=new SessionLog(this); logError=""; record("session_start","app_version","1.0","sdk",Build.VERSION.SDK_INT); } catch(Exception e){log=null;logError=e.toString();}
 }
 @Override public void onCreate(Bundle b) {
  super.onCreate(b); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); adapter=manager==null?null:manager.getAdapter();
  ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(1); root.setPadding(24,16,24,24); scroll.addView(root); setContentView(scroll);
  TextView title=new TextView(this); title.setText("IMU 接收验证"); title.setTextSize(25); root.addView(title);
  TextView note=new TextView(this); note.setText("标签竖直、正向可读、箭头向下为基准。正对标签：顺时针倾角增大，逆时针减小。传感器倾角，不是铲斗角。仅前台接收，离开页面即断开。"); root.addView(note);
  button(root,"扫描 / 重新扫描",()->scan()); button(root,"手动断开",()->disconnect("manual_disconnect")); button(root,"导出会话日志",()->share());
  timeoutInput=new EditText(this); timeoutInput.setInputType(2); timeoutInput.setText("2000"); timeoutInput.setHint("数据超时毫秒 (200–60000)"); root.addView(timeoutInput);
  button(root,"应用数据超时",()->{try {long t=Long.parseLong(timeoutInput.getText().toString()); if(t<200||t>60000) throw new Exception(); timeout=t; record("timeout_configuration","timeout_ms",t);}catch(Exception e){state("超时范围200–60000毫秒");}});
  statusView=new TextView(this); root.addView(statusView); angleView=new TextView(this); angleView.setTextSize(32); root.addView(angleView); detail=new TextView(this); detail.setTextIsSelectable(true); root.addView(detail);
  TextView listTitle=new TextView(this); listTitle.setText("附近BLE设备（点击才连接；★仅表示名称候选）"); root.addView(listTitle); devices=new LinearLayout(this); devices.setOrientation(1); root.addView(devices);
  newLog(); h.post(ticker);
 }
 void button(LinearLayout root,String label,Runnable r) { Button b=new Button(this);b.setText(label);b.setOnClickListener(v->r.run());root.addView(b); }
 final Runnable ticker=new Runnable(){public void run(){
  long now=SystemClock.elapsedRealtime(); boolean live=reading.live(now,timeout);
  statusView.setText("状态："+status+"\nMTU："+actualMtu+"；订阅："+reading.subscribed+"\n"+logError);
  angleView.setText(live&&reading.latest!=null ? String.format(Locale.ROOT,"%+.2f°",reading.latest.angle) : "失效 / 尚无有效数据");
  detail.setText("有效 "+reading.validCount+"；无效 "+reading.invalidCount+"；校验错误 "+reading.checksumErrors+String.format(Locale.ROOT,"\n接收频率（会话平均） %.2f Hz",reading.frequency(now))+"\n最后通知距今："+(reading.last<0?"无":(now-reading.last)+" ms")+"\n首通知延迟："+(reading.first<0||subscribedAt<0?"待接收":(reading.first-subscribedAt)+" ms")+"\n最近原始hex："+(reading.latest==null?"无":reading.latest.hex)+"\n解析错误："+(reading.latest==null?"无":reading.latest.error));
  if(reading.subscribed&&now-(reading.last<0?subscribedAt:reading.last)>=timeout&&!"no_data".equals(phase)){ phase="no_data"; state("订阅已确认，但通知超时；数据失效"); record("data_timeout","timeout_ms",timeout); }
  h.postDelayed(this,200);
 }};
 boolean permissions() {
  String[] required=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
  ArrayList<String> missing=new ArrayList<>();for(String p:required)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);
  if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),10);state("请授权后再次点击扫描");return false;}return true;
 }
 public void onRequestPermissionsResult(int c,String[] p,int[] r){super.onRequestPermissionsResult(c,p,r);state("权限结果已返回，请点击扫描；拒绝时可在系统应用权限中开启");record("permission_result","permissions",Arrays.toString(p),"results",Arrays.toString(r));}
 void stopScan(){scanVersion++;if(scanner!=null&&scanCb!=null)try{scanner.stopScan(scanCb);}catch(Exception e){record("scan_stop_error","error",e.toString());}scanCb=null;}
 void scan(){
  if(!permissions())return; disconnect("rescan"); stopScan(); results.clear();devices.removeAllViews();
  try{
   if(adapter==null||!adapter.isEnabled()){state("请先在系统设置开启蓝牙");return;}
   if(Build.VERSION.SDK_INT<=30&&!((LocationManager)getSystemService(LOCATION_SERVICE)).isLocationEnabled()){state("Android 10扫描需要开启系统位置服务");return;}
   scanner=adapter.getBluetoothLeScanner();if(scanner==null){state("BLE扫描器不可用");return;}
   final long version=++scanVersion;
   scanCb=new ScanCallback(){public void onScanResult(int type,ScanResult result){h.post(()->{if(version!=scanVersion)return;results.put(result.getDevice().getAddress(),result);renderDevices();});}public void onScanFailed(int code){h.post(()->{if(version==scanVersion){state("扫描失败 code="+code);stopScan();}});}};
   scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCb);state("扫描中（20秒）");h.postDelayed(()->{if(version==scanVersion){stopScan();state("扫描结束，请点击设备连接");}},20000);
  }catch(Exception e){state("扫描异常："+e);record("exception","error",e.toString());}
 }
 void renderDevices(){devices.removeAllViews();ArrayList<ScanResult> sorted=new ArrayList<>(results.values());Collections.sort(sorted,(a,b)->Boolean.compare(candidate(b),candidate(a))); for(ScanResult r:sorted){String name=name(r);button(devices,(candidate(r)?"★ ":"")+name+"\n"+r.getDevice().getAddress()+"  RSSI "+r.getRssi(),()->connect(r));}}
 String name(ScanResult r){String n=r.getScanRecord()==null?null:r.getScanRecord().getDeviceName();if(n==null)n=r.getDevice().getName();return n==null?"(未命名)":n;}
 boolean candidate(ScanResult r){return name(r).contains("BDA446T");}
 void arm(String expected,long ms){phase=expected;final int token=++opToken;h.postDelayed(()->{if(token==opToken&&expected.equals(phase)){record("operation_timeout","operation",expected);fail(expected+"超时");}},ms);}
 boolean accept(BluetoothGatt g,int gen){return g==gatt&&gen==generation;}
 void fail(String why){state(why);disconnect("failure: "+why);}
 void connect(ScanResult result){
  if(!permissions())return;stopScan();disconnect("new_selection");newLog();if(log==null){state("无法创建会话日志，停止连接");return;}
  reading=new ReadingState();actualMtu=23;subscribedAt=-1;connectStartedAt=SystemClock.elapsedRealtime();characteristic=null;int gen=++generation;
  record("selected_device","name",name(result),"address",result.getDevice().getAddress(),"rssi",result.getRssi());state("正在连接选定设备");arm("connecting",15000);
  try{gatt=result.getDevice().connectGatt(this,false,callback(gen),BluetoothDevice.TRANSPORT_LE);}catch(Exception e){fail("connectGatt异常 "+e);}
  if(gatt==null)fail("connectGatt返回null");
 }
 BluetoothGattCallback callback(final int gen){return new BluetoothGattCallback(){
  public void onConnectionStateChange(BluetoothGatt g,int statusCode,int newState){h.post(()->{
   if(!accept(g,gen))return;record("connection_state","status_code",statusCode,"new_state",newState);
   if(statusCode!=0||newState==BluetoothProfile.STATE_DISCONNECTED){disconnect("remote status="+statusCode+" state="+newState);return;}
   if(newState==BluetoothProfile.STATE_CONNECTED&&"connecting".equals(phase)){opToken++;reading.connected=true;state("已连接，服务发现中");arm("discovering",12000);try{if(!g.discoverServices())fail("服务发现请求未启动");}catch(Exception e){fail(e.toString());}}
  });}
  public void onServicesDiscovered(BluetoothGatt g,int code){h.post(()->{
   if(!accept(g,gen)||!"discovering".equals(phase))return;opToken++;record("services_result","status_code",code);
   if(code!=0){fail("服务发现失败 "+code);return;}
   for(BluetoothGattService s:g.getServices()){record("service","uuid",s.getUuid(),"type",s.getType(),"instance",s.getInstanceId());for(BluetoothGattCharacteristic c:s.getCharacteristics()){record("characteristic","service",s.getUuid(),"uuid",c.getUuid(),"properties",c.getProperties(),"instance",c.getInstanceId());for(BluetoothGattDescriptor d:c.getDescriptors())record("descriptor","service",s.getUuid(),"characteristic",c.getUuid(),"uuid",d.getUuid());}}
   BluetoothGattService s=g.getService(SERVICE);characteristic=s==null?null:s.getCharacteristic(NOTIFY);
   if(characteristic==null||(characteristic.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0||characteristic.getDescriptor(CCCD)==null){fail("目标服务/Notify属性/CCCD不完整，身份验证失败");return;}
   state("服务验证通过，协商MTU");arm("mtu",10000);try{boolean started=g.requestMtu(128);record("mtu_request","requested",128,"started",started);if(!started)fail("MTU请求未启动");}catch(Exception e){fail(e.toString());}
  });}
  public void onMtuChanged(BluetoothGatt g,int mtu,int code){h.post(()->{
   if(!accept(g,gen))return;actualMtu=mtu;record("mtu_callback","actual_mtu",mtu,"status_code",code);
   if(!"mtu".equals(phase)){if(code!=0||mtu<33)fail("MTU后续变更不可用");return;}opToken++;
   if(code!=0||mtu<33){fail("MTU协商失败或小于33："+mtu+" status="+code);return;}
   try{boolean local=g.setCharacteristicNotification(characteristic,true);record("local_notification_enable","success",local);if(!local){fail("本地通知启用失败");return;}
    BluetoothGattDescriptor d=characteristic.getDescriptor(CCCD);d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);state("等待远端CCCD写入确认");arm("cccd",10000);if(!g.writeDescriptor(d))fail("CCCD写入请求未启动");
   }catch(Exception e){fail(e.toString());}
  });}
  public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int code){h.post(()->{
   if(!accept(g,gen)||!"cccd".equals(phase)||!CCCD.equals(d.getUuid())||!NOTIFY.equals(d.getCharacteristic().getUuid()))return;opToken++;record("cccd_result","uuid",d.getUuid(),"status_code",code);
   if(code!=0){fail("CCCD订阅失败 "+code);return;}reading.subscribed=true;subscribedAt=SystemClock.elapsedRealtime();phase="subscribed";state("订阅已确认，等待首通知");
  });}
  @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){received(g,c,c.getValue(),gen);}
  @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){received(g,c,value,gen);}
 };}
 void received(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int gen){
  final long wall=System.currentTimeMillis(),nano=SystemClock.elapsedRealtimeNanos();final byte[] copy=value==null?new byte[0]:value.clone();
  h.post(()->{if(!accept(g,gen)||c!=characteristic)return;FrameParser.Frame f=FrameParser.parse(copy);reading.receive(f,nano/1000000);
   try{if(log!=null)log.eventAt("notification",wall,nano,"raw_hex",f.hex,"valid",f.valid,"error",f.error,"angle1_raw",copy.length==30?f.angleRaw:org.json.JSONObject.NULL,"angle1",copy.length==30?f.angle:org.json.JSONObject.NULL,"counter_uint16",copy.length==30?f.counter:org.json.JSONObject.NULL,"checksum_ok",f.checksumOk,"subscribed",reading.subscribed,"unknown_23_26_hex",f.unknown23to26);}catch(Exception e){logError="日志写入失败："+e;}
   if(reading.validCount+reading.invalidCount==1)record("first_notification","since_connect_request_ms",reading.first-connectStartedAt,"since_subscription_ms",subscribedAt<0?org.json.JSONObject.NULL:reading.first-subscribedAt);
   if(reading.subscribed){phase="receiving";status=f.valid?"正在接收有效通知":"接收到无效帧；数据失效";}
  });
 }
 void disconnect(String why){opToken++;generation++;BluetoothGatt old=gatt;gatt=null;reading.disconnect();characteristic=null;phase="idle";
  if(old!=null){record("disconnect_requested","reason",why);try{old.disconnect();}catch(Exception e){record("disconnect_error","error",e.toString());}try{old.close();}catch(Exception e){record("close_error","error",e.toString());}status="已断开："+why;}
  record("session_summary","reason",why,"valid",reading.validCount,"invalid",reading.invalidCount,"checksum_errors",reading.checksumErrors,"actual_mtu",actualMtu,"last_monotonic_ms",reading.last);
 }
 void share(){
  File dir=new File(getFilesDir(),"sessions");File[] files=dir.listFiles((d,n)->n.endsWith(".jsonl"));if(files==null||files.length==0){state("暂无会话日志");return;}Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
  String[] labels=new String[files.length];for(int i=0;i<files.length;i++)labels[i]=files[i].getName();
  new AlertDialog.Builder(this).setTitle("选择会话（含扫描/失败会话）").setItems(labels,(dialog,index)->{disconnect("export_log");record("export","file",files[index].getName());Uri u=Uri.parse("content://com.imu.validator.logs/"+files[index].getName());Intent send=new Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM,u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("session log",u));startActivity(Intent.createChooser(send,"导出IMU会话日志"));}).show();
 }
 @Override protected void onStart(){super.onStart();foreground=true;}
 @Override protected void onStop(){foreground=false;stopScan();disconnect("activity_stopped");super.onStop();}
 @Override protected void onDestroy(){h.removeCallbacksAndMessages(null);if(log!=null)try{log.close();}catch(Exception ignored){}super.onDestroy();}
}
