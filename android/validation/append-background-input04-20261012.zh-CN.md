# INPUT04：成功追加的绘画历史保护与背景实测

2026-10-12，Asia/Hong_Kong，约10:40–10:49。接续干净的work `524718cd0d9c8e984a1cd7a81416be137cde798d`，fetch后远端没有新变化，未reset/clean、重建工作区或应用离线patch。本轮是LOCAL03既有代码的追加设备验收，没有修改业务源码或重新构建APK。

## 被测版本及边界

- 业务源码：`8cd86f3b6d8367a9831b59c4753ea257c62b276a`。
- APK：`Compositor-Android-LOCAL03-20261012.apk`，SHA256 `112de5f06d73b77d841090d06e88fdf2d73ab0f7763c8f49deb2306908531317`。本轮沿用设备已安装的LOCAL03，不重复推送/安装未变化APK。
- 证书SHA256：`7426b906481f4875d3459b343bc57aae793d6e737e3a85996eb97ff733800a0b`。这是仓库外持久测试密钥，与原始证书不同；不同签名通常不能直接覆盖原版。用户的新测试签名授权及INSTALL01兼容打包继续适用，没有卸载或清数据。
- 设备2608BPX34C，Android17。全程`192.168.100.107:5555`，每条ADB命令先connect并串行执行；没有配对、重启设备、修改系统窗口设置或旋转。
- 43项JVM、Lint及3项仪器测试是LOCAL03前一阶段结果，本轮没有重跑，不能冒充本轮新增自动检查。

## 保护现有作品

开始时应用没有打开文档。通过SAF打开上阶段2048作品，导出基准后新增一笔，并在追加前Save Copy保护现场；追加和Redo后再保存两层副本，确认完成后才通过未保存提示切换新建白/黑工程。两个背景也分别保存。

旧LOCAL03作品`Download/Compositor/ADB-20261012-LOCAL03/Untitled-Edited.comp (1).zip`结束哈希仍为`06e3b64564525e970916d8bea9e1a095174d93e29de425241721c9999f7202cb`。测试文件统一位于`Download/Compositor/ADB-20261012-INPUT04`；父目录`.nomedia`确认0字节且保留。复用LOCAL02已有EXIF6夹具，不重发原工程或未变化APK，不触碰三个旧打包ZIP。

## 实际结果

| 项目 | 结果 | 核验范围 |
| --- | --- | --- |
| 2048工程新增笔画并保存保护副本 | PASS | 与基准相比12314个像素变化；ZIP CRC及背景资产逐像素等于现场导出 |
| 系统SAF追加EXIF6 JPEG | PASS | 从文件目录选择已有夹具；顶层Imported Image，240×320，原点904,864，在2048画布居中；四个内部色块采样与Pillow EXIF转正参考最大通道差0 |
| 成功追加保留旧层笔迹/Undo/Redo | PASS | 返回Background，Undo可用；追加后的PNG与追加前完全一致；Undo逐像素恢复基准；Redo逐像素恢复新增笔画。仅验证当前会话的按层历史，不声称ZIP持久化撤销历史 |
| Save Copy保留文档身份及旧层资产 | PASS | 新包两层，旧documentID和完整旧层manifest不变，旧背景PNG逐像素一致；新资产独立嵌入ZIP |
| 白色新建、PNG导出、ZIP保存 | PASS | 手工17×9、72ppi、White；PNG和ZIP资产全部153像素均RGBA(255,255,255,255) |
| 黑色新建、PNG导出、ZIP保存 | PASS | 手工17×9、72ppi、Black；PNG和ZIP资产全部153像素均RGBA(0,0,0,255) |
| 原工程及`.nomedia`保护 | PASS | 结束远端哈希与本地证据一致，旧2048工程哈希不变，`.nomedia`仍存在 |
| EXIF1/3/8完整SAF流程 | NOT_RUN | 前阶段生产仪器覆盖1/3/6/8，本轮真实SAF只测试6，不推广成所有方向均已设备验收 |
| 预设选择、2048白/黑、自动恢复、文档历史、全图分块、Mac | 未完成/NOT_RUN | 小尺寸白黑测试不是大容量、任意背景色、结构撤销或恢复验收 |
| 旋转 | NOT_RUN | 用户跳过，没有重新安排 |

现场Save Copy之后再次打开新文档仍提示未保存，这是当前保存独立副本、继续保留原文档编辑状态的行为；不将其视为自动恢复或原位置保存。追加后状态文字仍可能保留前次保存提示，图层列表/维度/像素和实际后续保存用于验证，不以提示文字证明新增层已保存。

## 输出身份与证据

| 拉回名称 | 手机INPUT04下文件 | SHA256 |
| --- | --- | --- |
| base.png / after-undo.png | Background-Export.png / Background-Export (3).png | 15de0a793bdfb7c4bd93f3cd3431e23d926f78f43ff8d66d432489f8be4238ca |
| live-stroke.png / after-append.png / after-redo.png | Background-Export (1)/(2)/(4).png | 2490cd059c429e152c82ff06a3abc9b14dd2059511fa253fdb2a24a4525cbecc |
| live-protected.zip | Untitled-Edited.comp.zip | f8a06d392416ff1128682bfd6c3c98794a5be8991796f4c47eae03c4c99b4096 |
| appended-protected.zip | Untitled-Edited.comp (1).zip | 525003d9e3e3e5b066e28d1f990dbe9cc3c949638ba078bb736964e5e20d8560 |
| white.png | Background-Export (5).png | 08efcfb05bdbdb64863204b47c87a53e1a607034a7611b5c4e03f1d1f520b9aa |
| white-project.zip | Untitled-Edited.comp (2).zip | 3756b84a2098e03a29a1011c638252152460c3a045e35fc8f76a26364cb0ea26 |
| black.png | Background-Export (6).png | 2450679ab6ca15dcf893848407f3f688041f91d84f30fef32e191a1e752eb289 |
| black-project.zip | Untitled-Edited.comp (3).zip | d2e2b6b90d246075d300522bf9ff8fef45c6edd8b0770b3b51c0099fe02eb706 |

忽略目录`android/build/adb-parity-input-20261012`保留本地ADB记录、界面XML/截图、输出、`verify_input.py`、夹具和`pixel-verification.json`；逐像素/manifest/ZIP断言均通过。归档只收本应用画面、自制夹具和输出，不含系统最近个人照片列表、其他个人目录截图或原始命令日志。APK、证据ZIP及工具链均不提交。

证据归档：`android/build/deliverables/Compositor-INPUT04-ADB-20261012.zip`，1726952字节，SHA256 `df9810dd76a5708018e730ee38b8f976065290c7dc5afd021170e08fc01a7e86`。ZIP CRC通过，已推送到手机INPUT04目录并核对相同哈希；内含归档前的本记录、版本身份、14份本应用/新建对话框截图及XML、自制EXIF6夹具、验证脚本和输出，不含个人图片选择器画面。

下一步继续文档级结构/绘画历史、内部恢复与安全保存，再推进全图分块合成、完整绘画工具及16节Mac功能对齐。待完善追加成功提示和剩余SAF方向/预设设备覆盖；最终目标尚未完成。
