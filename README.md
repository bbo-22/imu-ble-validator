# 独立 Android BLE IMU 接收验证 APP

项目：`C:\Users\xub\Desktop\test\imu-ble-validator`。包名 `com.imu.validator`，界面名称“IMU 接收验证”。Java原生Activity，无第三方Android依赖。minSdk 26、compile/targetSdk 34，支持Android 10（API29）。仅前台扫描/接收，离开Activity、打开系统分享或销毁页面时断开；不做后台接收或自动重连。

## 构建与离线测试

在 `C:\Users\xub\Desktop\test` 的PowerShell执行：

```powershell
python imu-ble-validator/tools/test.py
python imu-ble-validator/tools/build.py
```

已实际完成构建、zipalign、apksigner验证（v2/v3有效）及APK清单检查。准确结果/摘要见 `reports/build-result.json`，完整输出见 `reports/build-output.txt`。使用现有JDK21、SDK34、Build Tools36.0.0；未发现Gradle命令及用户缓存，直接调用官方SDK构建工具，构建不需网络。`build.py --sdk <路径> --java-home <路径>` 可指定工具链。debug.keystore是本验证项目专用调试密钥，密码android，仅用于调试APK。

APK：`C:\Users\xub\Desktop\test\imu-ble-validator\build\imu-ble-validator-debug.apk`。

本轮没有执行任何adb设备命令，没有安装、启动APP或操作真实蓝牙。用户人工安装命令：

```powershell
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 install -r 'C:\Users\xub\Desktop\test\imu-ble-validator\build\imu-ble-validator-debug.apk'
```

## 手机操作

1. 安装后从桌面打开“IMU 接收验证”。关闭其他可能占用传感器的APP。手动开启手机蓝牙；Android10还需打开系统位置服务。
2. 点“扫描 / 重新扫描”，允许使用期间的精确位置权限，授权后再次点击扫描。Android12+改为请求附近设备权限。APP不申请后台定位、广泛存储或网络权限。
3. 扫描持续20秒，列表显示名称、当前扫描地址和RSSI。★突出名称包含BDA446T的候选；名称不能唯一确认身份。人工对照实物选择，点击才连接。地址来自本次扫描，没有复制HCI日志MAC。
4. 观察“连接→服务发现→MTU→远端CCCD确认→首通知”。必须发现6801服务及6803特征，检查Notify与2902描述符；动态使用API对象，不硬编码属性句柄。MTU请求128，仅在回调status=0且实际MTU≥33后继续；本地通知开关成功后还要等待远端CCCD写入回调成功。任何一步失败/超时都会记录并释放连接，不标为准备就绪。
5. 收到有效帧后显示安装平面倾角。标签面竖直、正向可读、箭头向下为本次基准；正对标签，顺时针增大。未清零、未补偿安装角，不是铲斗角。默认2秒无通知即失效，输入200–60000毫秒并点应用可修改；无效帧也立即使显示失效。失效时隐藏旧角度，保留最近hex和错误。
6. 可手动断开、重新扫描和选择重连。切换页面会断开；返回后需人工重连，无无限重连。
7. 点“导出会话日志”，选择本次会话文件，系统分享至文件保存工具、邮件等。导出操作先断开并写摘要；保留所有历史会话，可逐个分享。会话存储在APP内部files/sessions，无存储权限。卸载会清除日志，请先导出。

## 代码与日志

`FrameParser.java`独立于Android，处理unsigned byte、signed int16、uint16及累加校验，保留raw hex和失败原因；未知字段仅用偏移命名。byte1=1D按当前观察格式检查，不宣布通用长度规则。前缀2–4保留、不擅自赋语义。`ReadingState.java`用单调时间处理频率、失效和断开。

`MainActivity.java`所有GATT状态在主Handler串行化；连接15秒、发现12秒、MTU/CCCD各10秒超时。只在对应回调成功后启动下一操作。session generation加GATT实例检查阻止旧回调污染；断开立即使数据失效，调用disconnect/close释放。手动断开记录请求原因并立即close，不等待已关闭GATT的断开回调；远端断开回调保留实际status/new_state。没有任何6802写入。

通知回调先复制字节并记录墙钟毫秒和elapsedRealtimeNanos，再进入串行处理；每条通知写JSONL并flush，UI每200ms刷新，不按UI刷新率抽样丢帧。内部闪存IO可能拖慢主线程，属于轻量验证APP的限制；写入异常会持续显示在界面，不能视为日志完整。系统杀进程/断电可能丢失尚未处理回调，不保证后台连续采集。

JSONL事件包括所选设备、连接状态码、发现的全部服务/特征/属性/描述符、MTU回调、CCCD结果、通知、首帧延迟、超时、错误及session_summary。每行均有wall_time_ms和monotonic_ns；通知时间在回调入口采集。angle1是当前观察格式的传感器倾角；未知23–26输出hex，计数单位未确定。接收频率为全部通知的会话平均频率，包含无效帧，使用单调时间。日志保存在私有目录，分享使用只读ContentProvider临时URI授权。

## 实际验证与证据范围

测试从现有三份HCI导出的frames.json读取全部17151条真实通知，再调用现有Python frame.parse_frame生成期望值，逐条核对Java原始hex、angle1_raw、angle1、counter。覆盖正角5887帧、负角11261帧、11次计数回绕；另验证错误校验、错误头、过短/超长、负数与uint16、超时边界、断开及无效帧失效。85779条断言通过，见reports/test-result.txt及test-vectors/real-frames.tsv。现有Python16项测试重新运行通过，未修改协议分析工具。

APK签名/打包及纯Java逻辑已离线验证；Android权限弹窗、扫描、厂商BLE栈、实际MTU/订阅、通知到达、UI显示和系统分享尚未真机验证。不能将离线测试通过当作真机联调成功。

## 真机失败时最小补充信息

无需重做动作实验。保留失败会话JSONL，提供失败步骤、界面状态/错误、Android版本、是否开启位置服务及蓝牙。若崩溃，人工执行下面命令后提供文本：

```powershell
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 logcat -d -b crash > 'C:\Users\xub\Desktop\test\imu-ble-validator\reports\device-crash.txt'
# APP分享不可用时，可读取debug应用内部文件列表，再将具体文件复制出来
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 shell run-as com.imu.validator ls files/sessions
& 'D:\Android\SDK\platform-tools\adb.exe' -s N6HBB21921005841 exec-out run-as com.imu.validator cat files/sessions/替换为实际文件名.jsonl > 'C:\Users\xub\Desktop\test\imu-ble-validator\reports\device-session.jsonl'
```

若CCCD成功但无通知，只需静止连接一次，导出超时会话；先根据MTU、CCCD状态和发现结果定位，不发送猜测初始化命令。仅在APP日志无法定位时再补一段“扫描→连接→失败”的HCI日志。

## 官方API依据

- [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)：Android10前台扫描请求FINE_LOCATION及系统位置开关；Android12+请求SCAN/CONNECT，使用neverForLocation；本APP不在后台扫描，不申请BACKGROUND_LOCATION。
- [BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)：discoverServices、requestMtu、setCharacteristicNotification、writeDescriptor及close语义；请求返回值只表示操作启动。
- [BluetoothGattCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback)：根据onMtuChanged、onDescriptorWrite确认结果；兼容API33新增带value的通知回调，Android10使用旧回调并即时复制数据。

SDK34编译时保留旧setValue/writeDescriptor API以兼容Android10（编译有弃用警告，属于预期）。Android14可能将MTU请求改为517，因此只检查实际回调值≥33，不要求恰为128。
