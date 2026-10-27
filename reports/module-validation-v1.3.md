# 1.3协议固化与模块验证（2026-10-27）

## 输入与范围

工作开始时基线为用户提交10434a6（v1.2.0），工作区干净；保留用户新增IDE文件与构建方式。只修改test中的两个IMU项目，没有修改hi-mc/业务代码、原始手机日志、原始HCI或厂商APK。本轮未自动安装/启动APP、连接真实IMU或发送设备命令。

连续接收、手动重连、IMU断电、手机蓝牙关闭均记录为**用户验收通过**。Roll/Pitch/Yaw的具体方向来自用户实物实验，完整坐标和欧拉顺序仍未知，不能把本轮离线回归写成新增真机联调。

## 复用与行为保持

- FrameParser、ReadingState、DiagnosticSample、InteractionFlow直接复用，FrameParser和Python frame.py只增加同值RPY别名；原angle字段保留，没有改变原始值或引入归零。
- 从Activity提取BleImuManager，保存原BLE worker、状态机、扫描token、GATT实例/attempt过滤、超时及订阅顺序。Activity仅处理UI/权限弹窗/稳定行/分享，应用也调用同一份模块。
- check_ble_preservation.py对比10434a6与新管理器，startScan、stopScanner、arm、beginGatt、callbacks、disconnect、fail、summary八个核心方法体在允许的Context/notice/scan-reset适配后完全一致；报告ble-extraction-preservation.json。
- 回调身份条件等价提为InteractionFlow.acceptsCallback，truth-table测试旧GATT对象、旧attempt和非活动连接均不通过。
- 所有通知仍日志落盘，每条onSample在BLE线程报告；onState提供connected/subscribed/有效样本上下文，UI限频读取。新连接创建ReadingState并清空latestValidSample，没有本地参考状态。

## 测试与构建

运行`python imu-ble-validator/tools/test.py`：保留真实17151帧多轴核对，另验证RPY别名完全等于历史向量，308742条解析断言。诊断21项、连接边界18项、交互20项/100000次压力、会话历史8项及原FIFO快照/marker时钟测试继续通过。Python圆周/标记汇总8项继续通过。原imu-protocol-analysis的16项测试继续通过。

新增连接边界：不同session或attempt的Yaw比较被拒绝；同参考历史样本±179最短差正确；旧连接样本不能被新Context视为实时；Pitch≥85°保护只作保守诊断阈值，不确认奇异点物理参数。protocolValid不等同当前live，实时显示必须检查Context。

`tools/build.py`实际运行SDK编译/DEX/对齐/签名，并以模块JAR编译examples/ReceiverExample.java。生成debug APK、纯Java core.jar及Android BLE模块JAR，无新框架/构建体系。versionName1.3.0、versionCode4，相同包名/调试密钥，install -r保留数据。完整结果以build-result.json/build-output.txt/test-result.txt为准。

## 独立close缺陷的诊断与修复

审查发现：新管理器若被宿主在扫描中直接close，而未先background，原close取消worker计时器但未停止scanner。此为新复用边界缺陷；验证APP的既有onStop停扫通常掩盖它，不解释此前Yaw现象。

按diagnosing-bugs构建tight loop：test_manager_close.py抽取**当前真实close方法**，以确定性Handler/扫描资源端口执行。最小场景为一个活动scan→直接close。修复前`scannerActive=true tokenInvalid=false stopped=0 quit=true`，断言失败（manager-close-before.txt）；证明清理不完整，不是Android蓝牙授权问题。

排序假设：①close遗漏stopScan；②代码依赖宿主先background；③清掉20秒计时器导致无法兜底。只补close中的flow.stopScan，先停扫并作废token，再沿用队列/GATT/日志/线程清理。修复后`scannerActive=false tokenInvalid=true stopped=1 quit=true`，测试通过（manager-close-result.txt），并纳入完整test.py。本测试替身不代表真机scanner行为已自动联调。

## Standards 审查

以10434a6...0950ed6做独立审查，发现1项P2：独立close不停止扫描。已按上述复现与回归修复；无其他明确行为问题。没有已记录的仓库编码标准/ADR，未为了异味建议扩大重构。

## Spec 审查

另一审查独立发现同一P2资源释放缺口，并提示Yaw表中1.2行的“1.1未显示”残留文字，均已修正。Yaw原始证据和参考边界、协议兼容字段、旧样本/回调隔离符合本轮范围。Standards和Spec各1项P2（同一问题），修复后待复核结论记录在本报告后续更新中。

## 尚待人工确认

本轮模块提取后的APP需人工确认扫描、选择、重连、断开、输入与日志分享仍符合原验收；不能将方法等价性或测试替身当作真机结果。Yaw重置的精确触发点、长期漂移、完整欧拉顺序和业务安装/机械需求仍未知，不阻塞当前交付。
