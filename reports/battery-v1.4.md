# 1.4 电量增量交付（2026-10-27）

## 范围与证据

基线1330e55，APP初始Git状态干净，1.3.0/code4；Python保留的上一轮电量实现及边界测试经核对后复用。只修改test内两个IMU项目。未修改业务项目，未操作ADB、安装、启动或连接IMU。既有连续接收/手动重连/IMU断电/手机蓝牙关闭仍为**用户验收通过**，不是本轮自动测试。

byte25指30字节ATT通知**数据值**偏移25，以uint8读取。17151真实帧全部100、用户观察原APP100；因此按用户决定暂作百分比映射，缺少非100对照，不能写为厂商确认。23/24/26保持未知，历史unknown_23_26_hex仍原样输出。无新增设备命令、电池查询、归零、报警或充电推断。

## 修改文件与行为

- FrameParser.java、外部src/frame.py：增加batteryRaw/batteryPercent/fieldValid/reason；Java原始值Integer可空，Python对应snake_case。先执行原有格式校验，0–100有效；101–255仅电量无效且百分比null，保持姿态有效。非法长度不读取byte25，raw=null；坏校验/格式保留30字节帧的raw但百分比null、frame_invalid。
- DiagnosticSample.java：不可变样本复制电量及字段有效性；batteryDisplay沿用原Context，不修改其连接、超时、attempt/session等规则。字段值合理不是实时有效。
- MainActivity.java：400ms原渲染循环新增电量，不另设轮询。无实时样本--，超范围未知+raw/reason，近旁明确“暂定字段，待非满电样本验证”。原姿态显示、marker、列表和生命周期保留。
- BleImuManager.java只增加四个通知日志键值，没有改变扫描/GATT/订阅/清理/旧回调处理，也没有增加write。SessionLog仍异步FIFO，schema 2仅做可选字段扩展；导出快照自然包含新增字段。
- summarize_session.py：raw_hex存在时用原Python解析器恢复（校验失败则不能输出有效电量）；没有原始帧且缺新字段时battery_fields_missing，完整新字段严格校验后读取。历史统计按session/attempt输出，明确不代表当前实时值。
- manifest/MainActivity：1.4.0/code5；build.py保持工具链/包名/签名，另保存从manifest实际版本生成的APK副本，验证两文件SHA相同。
- README、协议07、集成说明更新。协议分析目录未纳入Git，APP patches目录保存对应四文件的可应用增量，实际文件也已修改；不另建Git仓库、不复制运行时解析代码。

## 实际验证

`python tools/test.py`：

- Java以17151真实向量核对姿态、计数和新电量，325893条断言；batteryRaw/Percent全部100。
- BatteryTest 84条断言：0/1/50/99/100及101/127/128/255无符号边界；坏校验、坏格式、短/超长帧；断开/超时/新session/新attempt/无样本/未订阅/最近无效帧均隐藏电量；合法超范围电量不隐藏姿态。
- 原Diagnostic21、SessionBoundary18、Interaction20（100000次更新）、SessionFiles8及close回归继续通过。
- SessionLog实际FIFO/快照测试保留原2000条通知及marker，新增4个电量序列化案例，验证0/100/255/null/field_invalid；快照边界和点击时钟未改变。
- Python汇总14项（原8+电量兼容6）通过。协议项目19项（原16+电量3）通过。

`tools/verify_battery_captures.py`从指定imu-capture目录重新读取三份完整HCI，通过动态身份/GATT筛选通知；不读取imu2-raw/imu3-raw副本。6267/5797/5087帧均100，634587项历史导出字段逐项不变，新增JSON/CSV四字段全部核验，原始文件SHA前后相同且与历史记录一致。详细见battery-captures.json；新导出在协议项目exports/battery-v1.4，原导出不覆盖。

所有非100合法帧是显式重算校验的**合成测试数据**，不作为实测协议证据。Java与Python从真实帧得到同值；未来原APP自然显示非100时记下显示值和时间，对照同一时段完整通知byte25即可，无需专门耗尽电池或重做动作实验。

## 构建与人工检查

运行`python tools/build.py`使用现有JDK21/SDK34/Build Tools36.0.0，实际编译APK、模块JAR及调用示例，zipalign、v2/v3签名验证完成。最终版本/大小/SHA以build-result.json为准，完整输出build-output.txt。固定APK为build/imu-ble-validator-debug.apk，版本副本为build/imu-ble-validator-1.4.0-debug.apk。build和debug.keystore按现有.gitignore排除，不进入提交。

ADB路径D:\Android\SDK\platform-tools\adb.exe已检查存在，但未运行。用户手动install -r后确认v1.4.0，连接静止观察电量与暂定说明，手动断开后应为--，重连未收有效帧前仍--。本轮离线测试和构建成功不是新增真机联调成功，非100语义仍待后续自然样本验证。

## 独立审查

按/implement要求，以1330e55到当前工作树（含新增文件和外部协议四文件）进行Standards与Spec独立双轴审查：两轴均通过，0项缺陷。Spec审查另独立执行新增Python电量9项（APP6+协议3），全部通过。没有仓库自定义编码规范，不借本次电量改动扩大重构。
