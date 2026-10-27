# IMU 接收验证 v1.3.0

独立Java原生Android APP，包名`com.imu.validator`，versionCode=4；页面标题显示v1.3.0。minSdk26/targetSdk34，支持Android10。连续接收、手动重连、IMU断电及手机蓝牙关闭四项均为**用户验收通过**，不是本轮自动执行。以用户当前10434a6代码为基线，保留通信逻辑，提取为复用管理器；不修改业务项目、不自动连接设备。

## 本轮固化与复用（1.3）

- [当前协议说明](../imu-protocol-analysis/reports/07-protocol-consolidated.md)：字段、字节序、比例、实物正负方向、证据等级与未知边界。
- [Yaw参考核查](reports/yaw-reference-audit.md)：两份真实手机日志的三个尝试首5帧均为00 00=0°；1.2日志4998条Yaw与原始值一致。没有本地减初值；不能断言由连接、订阅还是设备其他机制触发。
- [接入说明](docs/integration.md)、[调用示例](examples/ReceiverExample.java)、[业务接入前清单](docs/business-preintegration-checklist.md)。核心解析/样本复用原实现，BLE流程从Activity移入BleImuManager，APP仍用同一份代码。
- 构建额外产出`build/imu-protocol-core.jar`与`build/imu-ble-module.jar`。示例单独编译，不运行、不自动扫描/连接。
- Yaw展示为“相对偏航角Yaw（本次连接）”：重新连接后参考可能重置，不是北向航向。新连接清空旧样本，无本地参考或跨断线叠加；YawComparison明确拒绝跨session/attempt比较。
- [验证与审查](reports/module-validation-v1.3.md)：原GATT方法的机械迁移等价性、真实帧、会话边界和资源释放测试。原始日志和历史报告保留；Python解析器仅增加同值roll/pitch/yaw别名。

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

## 三轴诊断与人工实验

主angle1继续保留。展开“三轴诊断”后同时显示Roll(angle1)、Pitch(angle2)、Yaw(angle3)、加速度A/B/C及向量模长，保留两位小数。安装基准为标签面竖直、文字正向、箭头朝下：正对标签面，标签平面内顺时针Roll为正；从基准倾至标签面朝下Pitch为正、朝上为负；基准附近绕竖直轴，按用户实验视角标签面朝左转Yaw为正、朝右转为负。上述尺度/方向获当前设备实物实验支持，不能推广成厂商完整坐标系或任意姿态欧拉顺序；Pitch近±90°时不要独立解释Roll/Yaw。加速度仍为候选g，A/B/C不直接叫物理X/Y/Z；Yaw参考机制和长期漂移未知。

主角度与全部诊断字段使用同一有效性判断：断开、超时、尚未订阅、最近帧无效时统一显示失效。原始角度保留设备的±180表示，+180与−180是相同方向。本轮暂不启用可选的“本地参考”功能，避免引入跨会话参考行为。离线圆周波动只是相对统计中心的偏差，不是用户参考角差或三维相对姿态。

手机实验：

1. 连接并确认收到有效数据，展开诊断区。设备扫描列表已恢复到页面最下方。
2. 选择或输入实验名称（最多60字符），例如A绕标签面法线、B绕标签面内横向、C绕竖直方向。编辑名称或打开选择对话框不会主动断开；保持APP前台，切到其他APP/系统分享仍按既有策略断开。
3. 基准姿态稳定后点击“基准静止”；动作前点击“开始转动”；到位后点击“到位静止”；开始返回时点击“返回基准”。返回后若还需统计基准稳定段，再点击“基准静止”。建议稳定段保留至少5–10秒，供默认去掉前2秒后统计。
4. 每次点击立即有文字反馈。无数据也能标记，但角度为null并记录disconnected/no_valid_sample/data_timeout等原因。反馈“已点击”表示事件已提交后台写入，写盘异常仍显示在日志错误处。
5. 完成后导出最近会话。当前会话先生成一致快照，再打开系统分享；不因UI限频丢弃通知。

## JSONL schema 2 与离线汇总

沿用JSONL事件、session_id、attempt_id、墙钟/单调时间和旧angle1字段，每行新增schema_version=2。notification保留angle1/angle2/angle3兼容字段及angle1_raw/angle2_raw/angle3_raw、angle1_deg_candidate/angle2_deg_candidate/angle3_deg_candidate、accelA/B/C_raw与accelA/B/C_g_candidate、accel_norm_g_candidate及length；新增roll_deg/pitch_deg/yaw_deg同值别名；不向设备写任何校准或清零命令。

marker含experiment_name、action、点击时的wall_time_ms/monotonic_ns及click_*别名、sample_wall_time_ms/sample_monotonic_ns/sample_age_ms、sample_session_id/sample_attempt_id、data_valid/invalid_reason与三个候选角度。点击入口立即复制最近已处理有效样本及其上下文，随后排队的磁盘写入不会改写时间或换成另一个attempt的样本。样本时间来自通知回调入口；无效时保留可用的样本时间和年龄供诊断，但三个角度均为null。

把手机导出的会话保存为`imu-ble-validator/reports/device-session.jsonl`后，在工作根目录执行：

```powershell
python imu-ble-validator/tools/summarize_session.py imu-ble-validator/reports/device-session.jsonl --output imu-ble-validator/reports/device-summary.json --csv imu-ble-validator/reports/device-summary.csv
# 可调整稳定段开头避让、最低样本数、数据空隙阈值（与手机超时设置对应）
python imu-ble-validator/tools/summarize_session.py imu-ble-validator/reports/device-session.jsonl --trim-seconds 3 --min-samples 30 --gap-ms 2000 --output imu-ble-validator/reports/device-summary.json
```

脚本仅用Python标准库。按session/attempt分组，每个“基准静止/到位静止”到同组下一个动作标记形成左闭右开区间，默认剔除开头2秒；末尾没有下一标记时统计到最后事件并标记open_interval_no_next_marker。保留原始行号，按单调时间排序，墙钟仅供展示，避免系统校时改变分段。

JSON包含三个角度的圆周均值/圆周中位、原始最小/最大、关于圆周中心的标准差/弧跨度/P5和P95偏差；加速度及模长输出均值/中位/标准差/范围。CSV便于快速查看；完整波动与flags在JSON。跨±180、圆周方向不明确、样本不足、无效帧、断开、数据空隙和运动/漂移候选都会明确标记。运动候选默认阈值：任一角度弧跨度>5°或任一加速度轴标准差>0.05g，可用--motion-angle-span/--motion-accel-std调整，属于诊断启发式，不是物理轴确认或融合姿态判定。

relative_field_statistics固定为null，因为没有启用本地参考。旧版无marker日志可读，但会明确输出no_stable_markers，不虚构动作区间；旧通知缺少多轴字段时，在当前工作目录结构下复用已有Python帧解析器从raw_hex恢复，解析器不可用则标记缺失。坏JSONL记录保留在parse_issues并返回码2，仍输出能够完成的汇总。

可重复的离线演示（真实HCI值、**合成标记，并非手机实验**）：

```powershell
python imu-ble-validator/tools/make_summary_example.py
python imu-ble-validator/tools/summarize_session.py imu-ble-validator/reports/examples/real-replay-marked.jsonl --output imu-ble-validator/reports/examples/summary.json --csv imu-ble-validator/reports/examples/summary.csv
```

## 沿用的1.1稳定功能

- 设备行按扫描标识复用：广播回调只覆盖设备快照，UI每400ms合并RSSI；不会逐广播移除行。新增行按候选优先、设备标识顺序插入，已存在行不因RSSI或后到名称移动；任意设备行从DOWN到UP/CANCEL期间暂停整份列表布局/文字更新，松开后展示合并结果。
- 扫描有实时剩余秒数和设备数，可主动停止/重新扫描。独立scan_state和gatt_phase；旧扫描token及GATT实例/attempt检查保留并加强。
- 扫描中可点设备：即时提示“正在连接 设备名”、突出目标并滚到状态/取消按钮处，随后停扫和连接。重复请求保留拒绝原因，不取消已有连接。
- 连接、服务发现、MTU、CCCD、等待首有效帧各有有限超时；失败原因保留，可再次点击重试。连接期间按钮为“取消连接”，其他活动连接阶段为“断开连接”，未连接时禁用并显示“未连接”。
- BLE处理独立HandlerThread，磁盘写入独立FIFO执行器，UI读取不可变快照。每条原始通知仍写入JSONL，不逐帧打印logcat。
- 会话名如`session-000012_20261026-171005.jsonl`，序号写入私有目录并扫描已有文件恢复。正常重启递增，同秒/时钟回拨不覆盖。一次Activity会话可含多个连接尝试，用attempt_id区分；点击不再偷偷切换日志。
- “导出最近一次会话”与“选择历史会话”，显示序号、北京时间、设备、结果和有效帧数；兼容1.0内容。弹出列表时收起键盘。当前文件通过FIFO边界生成快照后分享，原文件继续写入；打开系统分享离开前台仍会按既有策略断开。快照记录的是导出边界以前的数据。

## 人工验收

1. 更新后确认标题为 **v1.3.0**。开启手机蓝牙与位置服务；Android10授权使用期间精确定位后再点扫描。Android12+请求附近设备权限。
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

`tools/test.py`：全部17151帧的六个字段Java/Python移植交叉核验（308742条断言）；高频100000次扫描更新行身份、点击到可注入BLE端口、重复拒绝、旧scan token、旧attempt、取消/失败/超时后重试；日志序号/防覆盖/回拨/历史兼容；实际SessionLog在磁盘阻塞替身下无同步阻塞，2000条通知加1条marker无丢失，快照边界1000条通知加marker，精确保留点击时钟。新增诊断与离线分段/圆周统计测试。测试用Android/JSON最小替身，不打包进APK。

根因证据及未确认项见`reports/diagnosis-v1.1.md`。`tools/repro_click.py`刻意重放冻结1.0方法：默认应失败（connect_calls=0），加`--no-refresh`控制组通过（connect_calls=1）。它是诊断模型，未替代Android真机触摸分发或厂商BLE栈测试。最终回归走新代码公共边界。

内部存储只读ContentProvider按临时URI授权分享，无广泛存储权限；不发6802初始化/校准命令。UI限频不丢通知；系统杀进程、存储失败或长期磁盘吞吐低于通知输入仍可能影响完整性，日志错误会显示。保持APP前台；没有自动重连或后台连接服务。

官方依据：[Android BLE权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[BluetoothGattCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback)。
