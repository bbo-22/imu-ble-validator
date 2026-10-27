# 协议分析目录的本次增量

`imu-protocol-analysis`不是Git仓库，APP仓库不能直接暂存父目录以外的文件。本提交以`protocol-battery-v1.4.patch`保留其本次实际修改：src/frame.py、tests/test_battery.py、README.md、reports/07-protocol-consolidated.md。补丁不是另一套运行时实现；APP/Python工具仍使用原目录中的唯一解析器。

当前工作目录中补丁**已经应用**，不要重复应用。补丁不含原始抓包、私人手机日志、vendor、导出或签名密钥。已有1.3协议工作目录恢复时，可在test根目录检查/应用：

```powershell
git apply --check --directory=imu-protocol-analysis imu-ble-validator/patches/protocol-battery-v1.4.patch
git apply --directory=imu-protocol-analysis imu-ble-validator/patches/protocol-battery-v1.4.patch
```

已应用目录可用`git apply --check --reverse --directory=imu-protocol-analysis ...`核对。这里只检查补丁，不自动回滚。原协议报告01–06及历史导出未修改。
