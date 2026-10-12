# 2026-10-12 LOCAL02 无线 ADB 实测

时间为 Asia/Hong_Kong，约09:11–09:45。业务源码 `96c992d58de52b5220aaaa3f251833efedf445db`；测试开始时仓库文档提交 `07232ce15a7d12be838162ac4a20b609964effb0`。使用本地阶段的新 APK，未沿用 INSTALL01 的 PASS。

- APK SHA256：`9ff32936295f5665b5136d72618266eac352401af806617878b02eaa4ff7b946`。
- 新证书 SHA256：`7426b906481f4875d3459b343bc57aae793d6e737e3a85996eb97ff733800a0b`，不同于原证书；不同签名通常不能直接覆盖旧安装。本次安装前查询未发现本包安装或进程，因此安装新包，没有卸载、清数据或处理旧作品。
- 手机：2608BPX34C，Android17，4096字节页，物理1672×2364、density440；测试使用当时已有横向窗口2364×1672。无线配对成功，连接端口由45061变成43705后重新发现并连接。配对码不入文档。
- 安装 `adb install --streaming -r` 和启动成功；保留压缩原生库、安装提取及16KiB ELF的 INSTALL01 默认打包。这里只证明4KiB页设备实际安装/启动，不证明16KiB设备通过。
- 所有推送文件和本轮导出均在 `/sdcard/Download/Compositor/ADB-20261012-LOCAL02`。在推送图片前创建父目录 `/sdcard/Download/Compositor/.nomedia`；结束再次确认存在且0字节。图片不进入系统媒体扫描，SAF可从“浏览→我的手机”选择。不要移除它，不要写到相册目录。此措施不代表其他应用绝不会自行扫描，也不处理用户此前已有索引。

## 实际结果

| 项目 | 状态 | 实际证据/边界 |
| --- | --- | --- |
| 新APK安装、启动 | PASS | 全新本包安装成功，实际启动，无旧INSTALL01 PASS继承 |
| 生产PNG/EXIF解码 | PASS | `am instrument -w com.wonderassembly.compositor.test/androidx.test.runner.AndroidJUnitRunner`，`ImageImportPipelineTest:..`、`OK (2 tests)`，包含透明PNG及EXIF1/3/6/8；09:44复跑0.141秒。生产解码测试不等于完整SAF方向UI验收 |
| 默认2048透明画布 | FAIL | 动态预算拒绝：The canvas exceeds the current memory budget. Choose smaller dimensions.；安全拒绝无崩溃，但默认规格在本机未达到。没有放宽预算；需要分块路径/容量实测后修复 |
| 1024透明画布与Save Copy | PASS | 新建1024×1024、72ppi、透明Background；独立ZIP成功。输入文件名受中文IME改变，实际文件 `new024。com拍。.zip`，不是编码损坏证据 |
| 追加透明PNG并保存 | PASS | SAF追加320×240源图片为第二层，保留Background、1024画布、文档ID/PPI；ZIP生产读取器验证成功；manifest位置352,392；ZIP CRC正确，Background全透明，追加资产RGBA逐像素等于源fixture |
| 透明整画布PNG导出 | PASS | `Compositor-Canvas.png`1024×1024；逐像素等于透明画布上在352,392直接放置fixture，包含alpha128红色及alpha255绿色，未缩小 |
| 绘画、撤销、重做 | PASS（基本流程） | 合成测试图片上单指笔画提交，Undo禁用/Redo启用，再Redo反向状态正确。未测压力、掌触、跨块极限或20步淘汰；橡皮/参数面板设备验收仍待做 |
| 带笔迹ZIP和图层PNG | PASS | `Untitled-Edited.comp (1).zip`保存后保留原manifest/透明Background，活动图层6404像素改变；`Imported Image-Export.png`逐像素等于ZIP对应图层 |
| 保存副本重开与再导出 | PASS | 从SAF选择上述ZIP，生产读取器/预览显示2层；选择Imported Image再导出`Imported Image-Export (1).png`，逐像素等于重开前导出。重新打开后历史为空，不宣称历史持久化 |
| 旋转/Activity设备重建 | NOT_RUN | 用户跳过，未触发旋转，不将仪器测试替代该验收 |
| 安全原位保存、自动恢复、故障注入、分块全图、Mac对齐 | NOT_RUN/未完成 | 本轮仅验证独立ZIP副本和小尺寸导出；不能关闭FR/PARITY全量条目 |

## 文件与证据

| 输出 | SHA256 |
| --- | --- |
| Untitled-Edited.comp.zip（追加后、绘画前） | daf51b6ca46f669bfd84de43c9279609932ddec2a9bd1dbb6ae8ad45a726050b |
| Untitled-Edited.comp (1).zip（绘画后） | f46c6a627886a5419f324f9ca5d517ae568b71043233b87c8660f836178e6405 |
| Compositor-Canvas.png | e29cb38e5777af712ab8a3fb27bf823277932802ce3b8f440d9007a92e169503 |
| Imported Image-Export.png | 4c78237845d6deca13eb0b8827ef895ec90c304d226865444f8051affecbbdbc |

本地原始证据在 Git 忽略的 `android/build/adb-wireless-20261012/`，包含命令、XML、截图、合成fixture、拉回输出和像素核验脚本/JSON。只打包本应用和合成fixture相关截图；文件选择器最近相册及其他应用截图不加入交付证据。无线连接中用户切换过其他应用，立即返回测试应用，没有执行其业务操作。原始旧ADB附件未索取或改动；旧comp没有重发，三个旧ZIP未提交或删除。

下一步：先解决默认新建容量与分块模型的准入一致性，补追加失败/取消和原图笔迹保留、橡皮/软笔设备对照；继续文档历史、安全保存恢复、整图分块及Mac功能对齐。保存副本不能替代持久恢复。本轮新结果覆盖本地阶段当时“设备未连接”的状态，历史记录保留。
