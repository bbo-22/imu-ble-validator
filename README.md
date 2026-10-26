# IMU 接收验证 v1.1.0

独立Java原生Android APP，包名`com.imu.validator`，versionCode=2；页面标题显示版本。minSdk26/targetSdk34，支持Android10。保留已验证的6801服务、6803通知特征、Notify/CCCD校验、MTU回调≥33检查及30字节帧解析；未修改Python协议工具或业务项目。

## 构建、测试与安装

在 `C:\Users\xub\Desktop\test` 执行：

```powershell
python imu-ble-validator/tools/test.py
python imu-ble-validator/tools/build.py
```

使用现有JDK21、SDK34及Build Tools36.0.0，无Gradle/第三方Android框架依赖。构建脚本只运行本机SDK工具，不操作设备。调试签名继续使用原有debug.keystore，包名相同。

APK：`C:\Users\xub\Desktop\test\imu-ble-validator\build\imu-ble-validator-debug.apk`

实际构建结果、大小、SHA-256及签名输出见`reports/build-result.json`和`reports/build-output.txt`。用户人工更新命令（保留应用数据及旧日志；不要先卸载）：

```powershell
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 install -r 'C:\Users\xub\Desktop\test\imu-ble-validator\build\imu-ble-validator-debug.apk'
```

本轮没有执行adb设备命令、安装或启动APP，也没有连接IMU。测试通过和构建成功不代表真实IMU联调成功。

## 本轮修复

- 设备行按扫描标识复用：广播回调只覆盖设备快照，UI每400ms合并RSSI；不会逐广播移除行。新增行按候选优先、设备标识顺序插入，已存在行不因RSSI或后到名称移动；任意设备行从DOWN到UP/CANCEL期间暂停整份列表布局/文字更新，松开后展示合并结果。
- 扫描有实时剩余秒数和设备数，可主动停止/重新扫描。独立scan_state和gatt_phase；旧扫描token及GATT实例/attempt检查保留并加强。
- 扫描中可点设备：即时提示“正在连接 设备名”、突出目标并滚到状态/取消按钮处，随后停扫和连接。重复请求保留拒绝原因，不取消已有连接。
- 连接、服务发现、MTU、CCCD、等待首有效帧各有有限超时；失败原因保留，可再次点击重试。连接期间按钮为“取消连接”，其他活动连接阶段为“断开连接”，未连接时禁用并显示“未连接”。
- BLE处理独立HandlerThread，磁盘写入独立FIFO执行器，UI读取不可变快照。每条原始通知仍写入JSONL，不逐帧打印logcat。
- 会话名如`session-000012_20261026-171005.jsonl`，序号写入私有目录并扫描已有文件恢复。正常重启递增，同秒/时钟回拨不覆盖。一次Activity会话可含多个连接尝试，用attempt_id区分；点击不再偷偷切换日志。
- “导出最近一次会话”与“选择历史会话”，显示序号、北京时间、设备、结果和有效帧数；兼容1.0内容。弹出列表时收起键盘。当前文件通过FIFO边界生成快照后分享，原文件继续写入；打开系统分享离开前台仍会按既有策略断开。快照记录的是导出边界以前的数据。

## 人工验收

1. 更新后确认标题为 **v1.1.0**。开启手机蓝牙与位置服务；Android10授权使用期间精确定位后再点扫描。Android12+请求附近设备权限。
2. 扫描倒计时运行时，点`BDA446T-26040035`。应立即看到“正在连接”，扫描停止，按钮变为取消连接。
3. 应继续看到服务发现→MTU→CCCD确认→等待首个有效帧→正在接收；若失败，应有明确原因/超时，约15秒连接超时后可重试。服务发现12秒、MTU/CCCD/首有效帧各10秒。没有收到首有效帧不能显示为接收成功。
4. 连接期间再次点设备应提示已有连接任务；取消后可重试。重扫会废止旧扫描计时器，主动停止后保留可点击结果。失败后离开/返回页面不应抹掉失败说明。
5. 有效数据时倾角可显示；无效帧、断开、超过可配置数据超时均使角度失效。默认2000ms，可设置200–60000ms。未清零/补偿，不是铲斗角。
6. 点最近会话导出，或历史列表选择旧1.0文件。分享文件名带可读序号时间（旧文件保持原名），列表应新在前。一次验收只需静止连接，无需重复动作实验。

## 诊断事件与故障反馈

每行有session_id、attempt_id、wall_time_ms、monotonic_ns。`device_row_click`另保留触摸点击入口时钟；`device_selected`在连接拒绝判断和权限校验之前发出。

事件链：scan_requested / started / stopped / failed；device_selected；connect_requested / rejected / connect_gatt_result；connection_state_changed；service_discovery_started / completed；mtu_requested / changed；local_notification_enable；subscription_started / completed；first_valid_notification；notification；operation_timeout；disconnect_requested / completed；exception；ui_state；session_summary。

`connect_gatt_result.returned_instance`只表示调用返回对象；status回调、服务/MTU/CCCD及首有效帧才分别证明后续阶段。disconnect_completed为本地disconnect/close完成，显式标记local_gatt_closed，不伪称收到远端断开确认。真实远端断开状态码来自connection_state_changed。

若仍失败，导出最新会话JSONL，并说明界面版本/最后状态。设备点击入口无device_selected：继续检查触摸/UI路径；有device_selected但connect_rejected：按reason处理；connect_requested但无connect_gatt_result：检查调用异常；已返回GATT但无回调：查看connecting超时；后续按发现、MTU、订阅、首帧逐级定位。如果崩溃，可人工执行：

```powershell
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 logcat -d -b crash > 'C:\Users\xub\Desktop\test\imu-ble-validator\reports\device-crash.txt'
# 分享不可用时，列出内部文件并替换实际文件名读取
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 shell run-as com.imu.validator ls files/sessions
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 exec-out run-as com.imu.validator cat files/sessions/替换为实际文件名.jsonl > 'C:\Users\xub\Desktop\test\imu-ble-validator\reports\device-session.jsonl'
```

## 离线验证与边界

`tools/test.py`：全部17151帧Java/Python移植交叉核验（85779条断言）；高频100000次扫描更新行身份、点击到可注入BLE端口、重复拒绝、旧scan token、旧attempt、取消/失败/超时后重试；日志序号/防覆盖/回拨/历史兼容；实际SessionLog在磁盘阻塞替身下无同步阻塞、2000条无丢失及1000条快照边界。测试用Android/JSON最小替身，不打包进APK。

根因证据及未确认项见`reports/diagnosis-v1.1.md`。`tools/repro_click.py`刻意重放冻结1.0方法：默认应失败（connect_calls=0），加`--no-refresh`控制组通过（connect_calls=1）。它是诊断模型，未替代Android真机触摸分发或厂商BLE栈测试。最终回归走新代码公共边界。

内部存储只读ContentProvider按临时URI授权分享，无广泛存储权限；不发6802初始化/校准命令。UI限频不丢通知；系统杀进程、存储失败或长期磁盘吞吐低于通知输入仍可能影响完整性，日志错误会显示。保持APP前台；没有自动重连或后台连接服务。

官方依据：[Android BLE权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[BluetoothGattCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback)。
