# 1.0 点击无反馈：诊断与修复

## 输入与版本

检查开始时AndroidManifest仍为versionCode1/versionName1.0，页面无版本；未发现已实施的扫描优化。保留基线至`reports/baseline-v1/`及Git提交8a12e9e；实现按基线对比，没有覆盖其他业务项目。

用户提供JSONL只读核验，43条：session_start1、session_summary19、state15、timeout_configuration4、export4。SHA-256=`f05d8b482fec490cff9022756482a74fe8ea4abfe9163571905a0cb23518ad0e`。副本作为测试fixture。app_version1.0、sdk29；没有连接/通知事件。截图证明目标行可见，不证明点击处理器收到事件。

## 可重复反馈入口

`python imu-ble-validator/tools/repro_click.py`抽取冻结旧版renderDevices/name/candidate三个真实Java方法，以最小View生命周期替身执行：按下→一次扫描刷新→抬起。输出`same_target_attached=false connect_calls=0`，断言失败。移除中间刷新：`--no-refresh`，输出`same_target_attached=true connect_calls=1`。结果分别保存在repro-v1.txt/repro-v1-control.txt。

负载最小化至一个设备、一次刷新，仍会失败；去掉刷新即通过。真实旧方法确实删除按钮；替身仅模拟已移除触摸目标不会在抬起时触发click，未模拟完整Android触摸分发。[AOSP ViewGroup源码](https://android.googlesource.com/platform/frameworks/base/+/199acdf/core/java/android/view/ViewGroup.java)中removeAllViewsInLayout会对移除子View执行cancelTouchTarget，支持该机制。未经真机重放，不能将代码机制直接宣布为用户全部失败的唯一原因。

## 根因与证据

1. **代码直接确认，最强根因候选：每条广告重建按钮，存在丢失点击路径。** 基线MainActivity.java.txt:53每次onScanResult向主Handler排一个renderDevices任务；:57先removeAllViews，再新建所有Button。按钮在ACTION_DOWN/UP间被替换时监听器来不及触发。新StableRows复用对象，既有行始终附着，只有显式新扫描清列表。
2. **代码直接确认：主线程负载与同步I/O。** :53全量排序、布局和按钮分配按广告频率执行；SessionLog.java.txt:14–18在调用线程JSON编码、写入和flush，而状态/通知均在主线程。新回调只覆盖并发设备快照，UI400ms合并；BLE独立HandlerThread，日志独立FIFO写入器。未做真机CPU profile，因此两者各自耗时占比未知。
3. **日志直接确认：20秒截止回调严重延迟。** 两次扫描结束比各自最近一次扫描启动状态分别晚146.823秒、61.692秒。它们不是新扫描次数导致的简单20秒重置。支持队列/调度延迟；不能仅据这些数值证明某一线程具体阻塞点。
4. **代码直接确认：埋点与会话切分导致证据缺口。** :63–66在connect入口先permissions，停扫/断开，再newLog，随后才写selected_device；没有原始点击、connectGatt返回或拒绝日志。故本文件没有连接日志，既可能点击未到，也可能已切到另一个新文件；不能推出connectGatt必然未执行。
5. **代码直接确认：反馈/命名问题。** :54文字固定“20秒”没有倒计时；:107–109仅old!=null才改断开状态，但无GATT仍写summary，解释手动断开无明显反馈且summary重复；SessionLog用epoch+随机数命名，导出直接显示原文件名，缺少元数据摘要。

## 其他方向的核查结果

| 方向 | 旧代码检查与结论 |
| --- | --- |
| 行enabled/clickable/监听器 | :30标准Button，setOnClickListener明确绑定，无禁用逻辑；默认可点。不是已发现缺失监听器。 |
| 遮挡/父容器 | 仅ScrollView→LinearLayout→Button，无透明覆盖或自定义拦截；正常滚动可能取消点击，未见特有拦截。 |
| 闭包设备错误 | foreach中的ScanResult按迭代捕获，没有可变索引；未发现闭包统一指向最后设备。新版改捕获设备key与点击时不可变快照。 |
| 静默return | permissions失败会在state提示，但没有click/rejected事件，因此证据不充分；新版先device_selected再判断。 |
| 是否只能扫完连接 | 无此状态限制，扫描中原本允许连接。 |
| 停扫重置目标 | stopScan只增版本并stopScan，未清除所选ScanResult；未发现该根因。 |
| connectGatt | 原版捕获异常与null，但没有返回埋点，部分状态只写新文件。新版明确connect_gatt_result。 |
| 旧扫描计时覆盖 | 原版有version==scanVersion保护，连接前stopScan递增版本；未认定其已失效。保留token隔离并增加回归。 |
| 旧连接/生命周期 | 原版有gatt实例+generation保护；有效保护保留为实例+attempt。onStop主动断开，可能影响离开页面的连接；新版继续仅前台策略，并明确记录。 |
| phase=idle时扫描中 | phase原本只表示GATT空闲，不单独作为错误。新版分开scan_state和gatt_phase，UI分别显示。 |

## 新链路与状态职责

MainActivity.resetRows创建一次Button，监听器进入deviceClicked，捕获key、设备快照、点击墙钟/单调时钟，立即反馈并滚到状态。工作线程InteractionFlow.selectChecked首先写device_selected，之后才执行busy/权限拒绝；接受后停扫，write connect_requested，通过可注入Port进入beginGatt并写connect_gatt_result。onConnectionStateChange→发现→MTU→CCCD→first_valid_notification逐阶段推进。

连接attempt_id单调递增，session_id贯穿点击与后续回调，不再点击时切文件。重复连接记录connect_rejected:busy，不破坏正在进行的尝试。旧token、旧attempt和旧GATT实例均被隔离。连接15秒、发现12秒、MTU/CCCD/首有效帧各10秒；失败后保留失败说明并释放资源，可重新点击。取消和本地close显式记录，不伪造远端断开确认。

## 测试与剩余边界

- 新交互公共边界：StableRows压力更新100000次后同一行对象仍在；select到BLE替身调用一次，重复拒绝、权限拒绝前日志、旧scan token不覆盖连接、旧attempt不更新新尝试、取消/失败/超时后重试。
- 双轴审查补修：从任意行DOWN到UP/CANCEL之间，连新增行插入及其他行文字更新也全部暂缓，避免保持对象身份但布局坐标仍移动。回归断言触摸期间0次新增/更新、释放后合并设备出现。历史摘要只随connect_requested/旧selected_device更新设备名，另有连接A后点击B被busy拒绝的fixture，确认设备仍为A。
- SessionFiles：持久序号、同秒防覆盖、counter缺失由文件恢复、时钟回拨排序、真实1.0 fixture时间/结果读取。
- 实际SessionLog：暂停磁盘执行器后生产2000通知，未同步等待IO；快照恰好前1000条，活动文件继续到2000条，包含attempt/session时钟上下文。Android Context/SystemClock/JSON采用JVM最小替身，不测试系统分享UI。
- 三份真实日志17151帧/85779条解析断言继续通过；Python协议16项回归通过。FrameParser和ReadingState未修改。
- Android SDK实际编译、DEX、对齐、v2/v3签名验证通过，版本1.1.0/code2；结果见build-result.json。沿用原包名/调试密钥可install -r保留数据。

**尚未确认：** 真机触摸故障的精确发生比例、厂商UI/蓝牙栈行为、真实MTU/CCCD/首帧及系统分享。没有安装启动APP或连接IMU，不能称为真实连接成功。下一次只需扫描中点击一次，导出最新会话，按device_selected→connect_requested或rejected→connect_gatt_result→callback逐层定位；无需重做动作实验。

## Standards 审查

没有已记录标准的硬性违规。发现1项P2行为风险：排序插入新行可能移动按下目标，已用整份列表触摸期间暂缓更新修复。权限数组重复的判断性异味已集中requiredPermissions。MainActivity仍承担多种适配职责，保留为后续维护建议，本轮不扩大重构。

## Spec 审查

发现1项P2：被拒绝点击覆盖历史设备名，已仅采纳connect_requested的设备名并回归。两个轴线各1项P2行为问题均已修复；离线替身不替代真机触摸/GATT验证的限制继续保留。

## 技能执行记录

已读取diagnosing-bugs、implement；按implement使用tdd公共边界与code-review双轴审查。用户已明确指定可注入BLE/日志测试边界，视为测试范围授权。项目无GLOSSARY/ADR/既有编码标准或issue tracker；使用本次用户需求整理的spec-v1.1.md作为Spec来源，并以8a12e9e为本轮固定基线，不要求用户另行初始化流程。APP目录原非Git仓库，单独建立基线和修复提交，未触及业务项目仓库。
