# 复用与接入说明（1.3.0）

## 三个边界与产物

1. **协议解析：FrameParser**（纯Java）。输入单条通知bytes，输出长度、hex、校验结果/原因、raw整数与Roll/Pitch/Yaw（及兼容angle字段）。没有Activity、BLE、清零、安装补偿或机械计算。Python工具仍复用原frame.py，只添加同值别名。
2. **BLE连接管理：BleImuManager**。从已验收MainActivity机械提取原有流程；持有Application Context、单个BLE HandlerThread、InteractionFlow、扫描版本与GATT实例/attempt隔离。负责扫描、动态发现、MTU≥33、CCCD、阶段超时、取消、断开和close。稳定设备行仍是UI层StableRows，不在管理器内新建View。
3. **样本状态：DiagnosticSample / Context + ReadingState**。样本不可变，含rawHex、raw角度、Roll/Pitch/Yaw、接收wallMs/monoNs、session/attempt、protocolValid/error；Context含connected/subscribed/lastPacketValid/timeout及所属session，invalidReason(nowNs)统一决定是否实时有效。ReadingState在BLE线程统计。

构建后有`build/imu-protocol-core.jar`（纯Java，不含Android类型）及`build/imu-ble-module.jar`（上述核心+管理器+会话日志，需Android API26+）。现有APP使用同一src实现，不复制解析器。`examples/ReceiverExample.java`会在构建中单独编译，只依赖BLE模块JAR与android.jar，不进入APK、不自动扫描或连接。可直接以源码接入既有Java项目，保持当前构建方式；这不是新Gradle体系。

## 最小调用

纯协议调用不需要Android：

```java
FrameParser.Frame frame = FrameParser.parse(notificationBytes);
if (frame.valid) {
  double roll = frame.rollDeg, pitch = frame.pitchDeg, yaw = frame.yawDeg;
  // These are the device's raw decoded angles. No subtraction or installation compensation.
}
```

BLE接入示意（完整可编译版本在examples）：

```java
BleImuManager receiver = new BleImuManager(context, new BleImuManager.Listener() {
  public void onState(BleImuManager.Display state) {
    String reason = state.data.invalidReason(SystemClock.elapsedRealtimeNanos());
    // BLE worker: copy to main thread before rendering. Empty reason => all angles live.
  }
  public void onSample(DiagnosticSample sample) {
    // Every accepted notification, including invalid frames. Do not block this worker.
    // sample.protocolValid only means format/checksum, NOT current realtime validity.
  }
  public void onNotice(String text) { /* main looper: show rejection/error feedback */ }
  public void onScanReset(long token) { /* main looper: reset stable list for new scan */ }
}, "host-version");
receiver.setForeground(true);                 // host onStart
receiver.requestScan();                       // explicit user click, after permissions
// Render receiver.devices(); user explicitly selects an item:
receiver.select(device.key, device, System.currentTimeMillis(),
                SystemClock.elapsedRealtimeNanos());
// host onStop: receiver.background(); host onDestroy: receiver.close();
```

不要在构造监听器中依赖尚未赋值的receiver字段；可消费回调参数，或在主线程限频读取snapshot()。示例不代表自动获准连接任何设备，宿主应保留人工选择和身份验证。

## 权限与生命周期

- Android10/11：前台扫描需FINE_LOCATION运行时权限，并检查系统位置服务与蓝牙开关；旧BLUETOOTH/ADMIN权限maxSdk30。宿主负责权限弹窗，管理器失败时记录明确原因。
- Android12+：申请SCAN/CONNECT运行时权限；当前不推导位置，manifest用neverForLocation。不索取广泛存储权限，不在后台扫描。
- 宿主onStart调用setForeground(true)，onStop调用background()停止扫描/断开；onDestroy调用close()取消计时、关闭GATT、关闭会话写入并结束工作线程。close为终结操作，之后不要复用此实例，应创建新的管理器。
- 本轮维持仅前台策略；若业务需要前后台持续连接，需要下一轮明确设计前台服务、通知、权限和资源归属，不把验证器直接假装成后台服务。
- 保留原包名和debug.keystore，install -r可保留日志；卸载会删除应用私有数据。

## 状态、线程和清理约定

- onState在BLE线程报告每次状态迁移及周期快照；connected不等于subscribed，subscribed不等于收到有效帧。首有效帧与数据超时是独立状态。snapshot()为不可变快照；设备列表按扫描标识去重。
- onSample在BLE线程回调每条当前连接通知，协议无效帧也报告。wallMs/monoNs在原始蓝牙回调入口捕获，统计用单调时间，墙钟只供对照。
- listener禁止阻塞、sleep、同步磁盘IO或抛出异常；UI更新转主线程并限频。管理器中的原始通知日志由单独FIFO执行器完整写入，UI限频不抽样。
- 主线程回调只有onNotice/onScanReset；同一实例的GATT异步操作仍串行等待回调，不并发read/write。旧扫描token、旧GATT实例或旧attempt不会推进新连接。
- raw样本可留作历史，但断开/超时后必须调用Context.invalidReason判断实时性，不能把protocolValid当live。Context还拒绝其他session/attempt的样本。
- 数据超时可setDataTimeout(200..60000ms)；不会自动清零、重连。手动取消和断开会立即使实时数据失效并释放GATT。
- 日志、marker和导出走原有FIFO边界。marker必须在点击入口先捕获wall/mono与snapshot().data，然后调用marker(...)；prepareExport回调在BLE线程、摘要提交后执行，磁盘工作继续排入SessionLog.IO。它们不发送设备命令。

## Yaw边界

样本唯一连接键为(session, attempt)，attempt单独不全局唯一。新连接清空旧有效样本，没有本地初始Yaw参考。解析后的yawDeg始终等于bytes9–10 signedLE/100，禁止减初值、跨断线叠加或转成北向。

`YawComparison.between(a,b)`默认拒绝跨连接，返回valid=false、deltaDeg=null和原因；同连接历史样本使用最短单字段差。调用者仍需确认参考未改变；“同连接”不独立证明设备内部永不重置。它是历史诊断API，不自动证明样本当前live，也不是三维相对姿态。Pitch≥85°的保守保护返回pitch_near_90_roll_yaw_coupling；85°是本模块诊断阈值，不是厂商给定奇异点参数。APP未添加本地参考按钮，不维护跨连接参考。

## 接入前待确认

参见business-preintegration-checklist.md。本轮只交付复用边界和验证APP，不修改hi-mc或其他业务代码。
