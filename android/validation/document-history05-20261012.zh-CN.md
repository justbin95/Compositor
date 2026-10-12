# HISTORY05：文档统一绘画与追加历史

2026-10-12，Asia/Hong_Kong。本地现有work接续`2b672b530638bdc905ed0105bee02adf26107a01`，开始时干净，fetch后无远端新提交；未新建工作区/分支、reset/clean或应用旧patch。业务源码提交：`70ad892bbfef43c8f2fca7727027068af2a5bb56`。后续记录提交只改文档。

## 实现与边界

- HISTORY-01 / FR-08 / TC-011部分、MAC-01/05：新增通用DocumentHistory，整个文档的笔刷、橡皮和图片追加共用一个时间顺序与Redo分支。全局最多20步，旧记录按时间淘汰；字节计入笔画before/after快照及追加前后manifest。集合/对象头和模型引用不在有效载荷计数内，不将其当作实际PSS。
- 共享历史预算为本次捕获的可编辑文档预算的1/12，保留原分块缓存/活动事务准入。启动时预算捕获失败不会阻断主页；零预算在实际编辑记录前重试，仍无预算则拒绝，笔画回滚/追加候选释放，旧Redo不丢。不是放宽动态内存保护。
- 追加候选经生产读取器及全部资产预检成功，且历史记录能准入才提交。撤销追加恢复旧manifest、旧图层/资产列表、原选择及结构脏状态；重做恢复追加后的状态。跨层笔画Undo/Redo切到受影响图层；当前显示同一层时只刷新变化块，保留画笔/橡皮模式和视口。
- 追加的私有资产在Undo时继续保留供Redo；资产所有权目录并入当前文档，避免Undo后关闭、替换或清空Redo分支遗失清理责任。配置重建保留同一历史与会话，正常重开ZIP历史为空。
- 追加成功/历史编辑清除已完成的旧ExportTask结果，避免异步图层预览重新显示前一次保存提示。这是进程内状态修正，未作为设备UI PASS。
- ZIP仍只保存当前manifest和已提交像素，源文件不写回，Mac工程格式未变化。删除/排序/重命名/变换等尚未实现的操作未纳入此阶段，不称为完整图层历史或MAC验收。

## 实际自动检查

Windows本地，JDK21、SDK/Build Tools36、NDK27.2.12479018、CMake3.22.1，均在仓库外。最终命令：

```powershell
.\android\scripts\build-local.ps1 -Tasks ':app:testDebugUnitTest',':editor-core:testDebugUnitTest',':app:lintDebug',':app:assembleDebug',':app:assembleDebugAndroidTest'
git -c core.whitespace=cr-at-eol diff --check
```

- 最终Gradle成功：1分4秒，130任务，29执行/101最新；app30/core18，共48项JVM测试，失败/错误/跳过均0。已有核心测试在最后资源清理构建中UP-TO-DATE，其报告来自此前同一核心源码的实际运行。
- 新增5项：混合绘画/追加顺序与新分支、失败apply不移动游标/字节淘汰、全局20步、独立栅格patch像素恢复，以及Activity追加Undo→重建→Redo→Undo→清Redo→关闭的manifest/结构脏状态/缓存目录清理。
- 首轮3项Activity测试因启动时预算读取失败而失败；修复安全初始化后全通过。另移除新增硬编码提示和旧未使用字符串，最终Lint0错误/6警告（API/依赖版本和ChromeOS ABI建议），未隐藏或放宽检查。
- ARM64应用构建、仪器APK编译打包通过；设备仪器测试本轮未执行。自动Activity重建不替代用户跳过的旋转验收。
- APK ZIP全部CRC通过，唯一原生库为arm64-v8a、DEFLATE method8，三个ELF LOAD对齐均0x4000；实际minSdk范围apksigner验证通过。保留INSTALL01默认兼容打包，不证明16KiB设备通过。

## 新包身份

- 本地文件：`android/build/deliverables/Compositor-Android-HISTORY05-20261012.apk`，3528279字节。
- APK SHA256：`023c30dd1f88a35db97a31cbb2c2dc2531e3417740b84770531839474973b9c7`。
- 业务源码：`70ad892bbfef43c8f2fca7727027068af2a5bb56`。
- 沿用LOCAL03仓库外持久测试密钥；证书SHA256：`7426b906481f4875d3459b343bc57aae793d6e737e3a85996eb97ff733800a0b`。与原始证书`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`不同，不同签名通常不能覆盖原版。用户持续测试签名授权适用；本轮未安装、卸载或清数据，APK/密码/密钥不提交。

## 设备、阻塞与下一步

通过既有脚本按每条connect→显式-s、串行5555只观察一次现场，发现设备在使用其他应用，停止UI操作；未切应用、启动/杀进程、安装或调整系统设置。观察产生的本轮两份非本应用XML/截图已按精确文件名从本地和测试目录移除，不纳入证据。没有推送夹具或改动已有作品/`.nomedia`。

| 检查 | 状态 |
| --- | --- |
| HISTORY05安装、真实跨层/追加Undo/Redo、SAF保存后像素 | NOT_RUN，设备使用中 |
| 仪器测试 | NOT_RUN，本轮仅编译 |
| 旋转/系统窗口设置 | NOT_RUN，继续用户跳过 |
| Mac对照与最终PARITY | NOT_RUN，无Mac环境 |
| 持久恢复、安全文件夹保存、200次中断故障注入 | 未实现/NOT_RUN |

旧LOCAL03/INPUT04的PASS保持对应旧包，不迁移到HISTORY05。自动测试不是完整TC-011通过，TC-012/020未运行。删除了Redo的追加资产及隐藏层会话目前保留至文档关闭，尚无提前回收；长期追加/撤销/新分支的磁盘与汇总内存压力需要后续资源治理，不能称为容量验收。

下一步：设备可用时先观察/保护未保存作品，再安装本轮同测试签名包，验统一历史及保存像素；独立推进安全内部恢复副本轮换、故障注入和无引用私有资源回收，再推进全图分块合成、完整绘画与PARITY-00～07。最终目标未完成。
