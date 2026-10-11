# 本地 Codex / ADB 测试交接

## 1. 手机准备

1. 在手机开发者选项启用USB调试，用数据线连接电脑，解锁并确认调试授权。小米系统如果拒绝ADB模拟点击，可启用“USB调试（安全设置）”；无需关闭安装安全扫描。
2. 电脑安装Android SDK Platform Tools；运行`adb devices -l`，设备应显示`device`。`unauthorized`需要在手机确认，`offline`先处理连接。多设备时所有命令用`adb -s <设备序列号>`。
3. 测试前在应用里另存需要保留的临时笔迹；打开其他测试文档会替换当前文档。自动测试只使用专门测试目录，不修改个人照片或现有项目。

## 2. 从GitHub接续源码（推荐）

当前Android实现与验证文档随本轮提交纳入`work`，直接获取完整项目：

```sh
git clone --branch work https://github.com/justbin95/Compositor.git
cd Compositor
git log -1 --oneline
```

已有本地仓库时先检查`git status`；工作区干净且在work分支才执行`git pull --ff-only origin work`。本地有改动时先保留现有工作，并创建独立worktree接续；不执行reset/clean覆盖个人修改。记录实际提交哈希，测试结果也引用该哈希。

读取根AGENTS.md、Android PRD、开发计划及验证记录。样例使用仓库`android/samples/`，无需再次下载同样的comp。结果模板为`android/validation/adb-result-template.zh-CN.md`。

此前`Compositor-ADB-Handoff-20261011-061106.zip`是提交之前的离线快照，仅保留历史备份；**拉取新work后不再应用其中worktree.patch或untracked-files**。该包仍包含用户确认可安装的INSTALL01 APK，可用于对应版本的测试。APK SHA-256为`6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5`。

APK、SDK、构建缓存、签名密钥和密码不提交Git。密钥需单独加密迁移；本地构建沿用固定keystore和四个COMPOSITOR_DEBUG签名变量，证书SHA-256必须为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。密钥未迁移前使用已安装包或离线包内APK测试，不用不同证书覆盖。

若目标是继续开发而非只测现有APK：先按README配置JDK21/SDK36/NDK27.2.12479018/CMake3.22.1，接续计划中的失败修复、现有项目追加图层、新建、安全保存/恢复与分块合成。每步更新实现/检查/设备状态并提交源码，测试报告注明源码和APK哈希。不要将对旧APK的验证算作新代码通过。

## 3. 可直接复制给本地 Codex 的任务

```text
请从GitHub的work分支获取Compositor完整源码，记录当前提交哈希；已有本地改动时保留并使用独立worktree，不重置。读取仓库AGENTS.md、Android PRD/开发计划、android/validation/local-codex-adb-handoff.zh-CN.md、io-roundtrip-checklist.zh-CN.md、installation-diagnostics.zh-CN.md及adb-result-template.zh-CN.md。新work已包含本轮实现，不再应用旧离线包patch。

这轮是测试和证据收集。用已确认可安装的INSTALL01 APK（SHA-256 6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5）；保留固定证书。若已安装且需要保留内存笔迹，先让我在应用里Save Copy。不卸载、pm clear、杀掉未保存文档或覆盖个人文件，不重新签名或擅自修源码。

检查adb设备连接并选定序列号；创建唯一测试目录。按IO-02清单8组逐项操作，使用uiautomator dump读取当前控件和bounds再tap/swipe，不猜固定屏幕坐标。通过UI打开SAF文档，不用直接写入应用缓存绕过导入/保存流程。针对绘画、Undo/Redo、Composite、PNG/JPEG、ZIP保存/重开、取消、无效输入和超预算行为保存步骤与证据。

输入图片用自建、无个人信息的小型固定数据PNG/JPEG；准备透明区、半透明边缘、明显方向标记和EXIF Orientation样例。破坏性/删除原照片验证只对自己创建的输入副本执行。现有comp样例从仓库使用，不要求用户重复下载。设备测试目录以外的文件不修改。

导出目标统一选Download下专用测试目录，adb pull实际PNG/JPEG/ZIP后在电脑检查：尺寸、解码、Alpha、ZIP路径/CRC、manifest和源资产哈希、当前笔迹、Undo/Redo及保存重开前后像素一致性。照片方向先与ImageOps.exif_transpose的固定输入参考比较。记录实际像素差异；未冻结的Mac混合容差不能擅自判为全面通过。分享/追加到现有项目/缩小导入等未实现项，不假装支持。

双指、真实折叠、压力/掌触和某些系统权限弹窗如无法可靠自动化，记录需人工配合；系统分屏、转屏、低存储和删除拒绝不应靠危险修改全机设置或塞满手机模拟。任何PASS必须有实际步骤与证据；未执行记NOT_RUN，环境阻碍记BLOCKED，失败记FAIL。安装已由用户确认不等于文件功能已通过。

按adb-result-template.zh-CN.md生成RESULT.md、results.csv、commands.log，以及device/、screenshots/、ui/、logs/、fixtures/、exports/目录。日志只采集本轮相关时间段，优先应用PID；应用崩溃另采crash buffer并标注时间。完整bugreport仅在确有需要且用户同意时采集。上传前检查并移除个人账号/其他应用信息/设备序列号等不必要敏感内容。

每个FAIL给出最短复现、预期/实际、错误全文、对应截图/日志/输出文件。整理成Compositor-ADB-results-日期.zip，并生成SHA256；告诉我这个ZIP和RESULT.md的本地绝对路径，便于上传到云端原对话。先交测试结果，不把未经验证的修复混入此轮验收。
```

## 4. ADB操作参考（由本地Codex选定路径与序列号）

```sh
adb devices -l
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getconf PAGE_SIZE
adb -s SERIAL shell wm size
adb -s SERIAL shell wm density
adb -s SERIAL shell dumpsys package com.wonderassembly.compositor

# 只有需要安装且已保护当前临时编辑时才执行；不卸载，不清数据。
adb -s SERIAL install -r /absolute/path/apk/Compositor-Android-INSTALL01.apk
adb -s SERIAL shell am start -n com.wonderassembly.compositor/.MainActivity

# 按实际页面检查bounds再操作；下面仅是命令格式，不是坐标脚本。
adb -s SERIAL shell uiautomator dump /sdcard/Download/Compositor-ADB-RUN/ui.xml
adb -s SERIAL pull /sdcard/Download/Compositor-ADB-RUN/ui.xml /absolute/path/results/ui/
adb -s SERIAL shell input tap X Y
adb -s SERIAL shell input swipe X1 Y1 X2 Y2 600
adb -s SERIAL exec-out screencap -p > /absolute/path/results/screenshots/step.png
adb -s SERIAL pull /sdcard/Download/Compositor-ADB-RUN/ /absolute/path/results/exports/
```

先创建上述专用目录再写入。Windows PowerShell旧版本的`>`可能破坏PNG二进制：本地Codex应使用Python subprocess以二进制写入screencap输出，或先在设备截图再adb pull。

普通`adb shell input swipe`只有单指，不构成双指捏合验收。Cancel按钮响应窗口可能很短，未实际点击到运行中的任务不能记取消通过。云盘SAF/删除拒绝、低存储和Mac往返没有对应环境时记BLOCKED/NOT_RUN，不以本地提供方替代。

## 5. 上传结果

本地Codex无法在没有连接工具的情况下自动把文件写入这个云端对话。它先给你ZIP路径：

1. 在**当前这个对话**附件按钮上传`Compositor-ADB-results-日期.zip`，并同时上传或粘贴`RESULT.md`。
2. 写：“这是本地Codex通过ADB执行的结果，请核对证据、更新开发计划并修复FAIL项。”
3. 包含实际导出的PNG/JPEG/comp ZIP，不能只传截图或一句“都通过”。大体积录像按失败项目分开上传，成功流程通常不必录全程。
4. 如本地Codex另做修复，把patch和提交哈希单独上传，并标明测试的是修复前哪个APK、修复后哪个APK，避免验收错版本。

云端收到文件后核对APK/输入/输出哈希、逐项结果与日志，再更新已实现/已验证/待验证状态；有复现证据的失败继续修复。
