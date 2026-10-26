# v1.2 多轴诊断与实验标记验证

基线f4f260a（用户已确认1.1真机连接稳定）。本轮未自动安装、启动APP或连接IMU，未写6802命令，未修改业务项目/原始日志/APK。Git中保存修改；唯一已有未提交改动是生成的build-result.json所记JDK/摘要，本轮构建正常更新该生成报告。

## 修改范围

FrameParser复用当前30字节校验，新增angle2/3和A/B/C signedLE解码与模长，保留angleRaw/angle兼容字段以及原始hex/length。MainActivity仅扩展UI、通知日志数据及最新有效样本快照；扫描/权限/连接/MTU/订阅/断开/导出流程保留，设备列表移到底部。DiagnosticSample将已解析值复制为不可变样本和上下文，主角度与诊断区采用同一有效性判定。

Roll/Pitch/Yaw是候选标签；显示三个值不代表物理轴或旋转顺序已确认。angle1有安装平面实验支持，Pitch解释按用户补充具有较强支持；Yaw参考方向和漂移未知，没有北向/方位显示。±180原样保留，不启用可选本地参考。shortestAngleDelta仅用于明确验证角差数学和测试，不改变原始值。

## 测试证据

- 三份HCI导出6267/5797/5087条通知，共17151帧。Java原始整数/候选换算与现有Python frame.py六个字段逐帧核对，257289条断言通过。既有长度/头部/校验/负数/uint16回绕测试保留。
- DiagnosticTest：三轴signed int16、候选单位、模长、179→−179为+2及反向−2；不可变样本不受Frame后续变化影响；sample/session/attempt/点击时钟和年龄保持；断开、超时、无效帧、其他attempt均清空全部7个实时字段，marker角度为null。
- SessionLogTest实际FIFO写入实现：磁盘执行器暂停时提交1000通知、marker、快照、后1000通知；快照恰好1000通知+marker，原文件2000+marker。marker位于对应队列边界，wall_time_ms/monotonic_ns和sample时钟不被写盘时间替换，schema2存在。
- 原InteractionTest20项断言/100000次扫描压力、SessionFilesTest8项历史命名兼容继续通过；相关BLE状态机、稳定行、ReadingState未修改。
- Python summary测试覆盖±180圆周均值、空方向、开头2秒避让、无效/缺字段/断开/空隙、明显运动候选、attempt隔离、乱序时间及43条旧日志读取、坏JSON显式issues。

最新完整输出见test-result.txt；实际编译/DEX/对齐/签名与版本结果见build-output.txt/build-result.json。APK使用相同包名与原调试密钥，versionCode3/versionName1.2.0，适合adb install -r保留数据。

## 可重复离线示例

make_summary_example.py使用第三份真实HCI数值，为14:45:16/35/50、14:46:01添加**合成标记**。summarize_session.py默认去掉稳定段开头2秒后，基准段339样本、angle1圆周中位约+0.34°；到位段183样本、圆周中位约+41.99°，第二段存在运动/漂移候选标记。此处窗口与此前05报告不同，不能调整以迎合旧中位数，也不代表新增手机动作实验。

## 真机验收范围

确认标题1.2.0、扫描结果在页面底部；连接后展开诊断，三角度同时可见且为候选轴名；断开/超时全部失效。保持前台，输入实验名并标记基准→转动→到位→返回，不应仅因输入框或名称对话框主动断开。导出后检查marker点击时间、样本年龄和所属attempt，再运行汇总。1.1基础稳定性由用户提供真机验收事实；1.2新增UI/标记/系统分享未在本轮真机执行，离线测试和构建通过不等于新增功能已完成真机验收。

## Standards 审查

按implement要求，以f4f260a...944597c做独立代码质量审查；没有已记录的仓库标准/ADR，固定异味基线未发现需修复问题。重点检查了有符号解码集中化、点击快照不可变性、整体失效与圆周统计。未做无关重构。

## Spec 审查

另一独立审查按spec-v1.2.md及用户需求核对，未发现明确实现偏差。审查提示需补齐README版本/汇总命令/实验步骤，现已更新。Standards与Spec各0项行为问题；真实Android布局/键盘/触摸与BLE时延仍属于待人工验收范围。
