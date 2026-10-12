# 2026-10-12 本地开发与新包验证

补充：同日后续已连接无线ADB并实际安装本页新APK；设备结果以[LOCAL02无线实测](wireless-adb-20261012.zh-CN.md)为准。以下“未连接/未安装”是构建完成时历史状态，不代表后续仍未测。默认2048设备预算拒绝FAIL，1024与追加/输出/重开部分通过；旋转、Mac和完整恢复保持未完成。

时区：Asia/Hong_Kong。仓库：`C:\Users\ASUS\Documents\Compositor`，分支work。初始目录为空，克隆得到用户指定0216a30502b25000076b0375a5b3d2fe730b8eca；没有reset/clean、重建已有工作区、旧patch或旧打包ZIP操作。固定Mac基线仍为22c7b8d5b23bb1e27a5746912061b23757c4f554。

## 本阶段实现和证据

| 工作包 | 实施 | 自动证据 | 设备/Mac状态 |
| --- | --- | --- | --- |
| IMAGE-APPEND-01 / FR-02 / TC-002部分 / MAC-01/13部分 | Add Image Layer通过SAF选PNG/JPEG，复用生产导入并追加顶层，原像素大小保持，默认居中。复制manifest后通过生产读取器与全部资产预检；保持文档ID、源URI、旧笔迹会话和按层历史，不写回源。候选失败/取消只回收本次导入；成功后追加缓存归当前项目拥有 | 奇数尺寸居中、文档尺寸/PPI/旧层/原manifest/资产引用保持；新增资产随Save Copy写出，删除私有原资产后重开像素正确；重名及10层限制拒绝；缺文件失败保持当前工程/会话/未保存状态并释放最终缓存 | 未连接设备；实际SAF追加、旧笔迹/历史交互、EXIF/透明边缘与Mac对照待测；结构撤销和排序未完成 |
| NEW-02 / FR-01 / TC-001部分 | 默认2048平方；Custom/2048/1920×1080/1080×1920；透明/白/黑背景，保留PPI/预算/取消保护。结构修改计入未保存提示；设置对话框可滚动 | 2048透明逐像素及ZIP重开既有检查保留；17×9白/黑逐像素精确；非法尺寸/预算/编码取消无候选残留 | 设备创建→绘画→撤销→Save Copy→重开待测；任意背景色与文档历史未完成 |
| BRUSH-02 / FR-05 / MAC-05部分 | Brush Settings：1–1024源像素、透明度、硬度、颜色；Eraser：预乘RGBA destination-out。共用分块/事务/历史/PNG/ZIP链路；参数随配置重建保留，切工具终止未完成笔画 | 跨256块橡皮撤销/重做、RGBA预乘不变量；软笔中心/边缘/取消；旧硬笔/坐标/历史/预算/导出回归保留 | 软笔与Mac的重叠/间距规则尚未冻结；设备压感/掌触、取色、平滑、直线与完整颜色面板待做 |
| BUILD-LOCAL / INSTALL01兼容 | 原密钥未找到；创建仓库外持久测试密钥；脚本使用外部配置且显式四变量优先。默认压缩native/安装提取、16KiB ELF、v1/v2/v3签名保留 | 本轮新APK签名/指纹/哈希/压缩/ELF字段见产物段 | 新APK未安装，旧INSTALL01安装PASS不继承；不同签名不自动覆盖/卸载/清数据 |

本阶段未实施安全文件夹保存、自动恢复、文档统一历史或整图分块合成。Save Copy仍是独立ZIP副本；图片/新建/追加素材只在缓存，进程死亡/清缓存/卸载可能失去未保存内容。不要把配置重建保留当成持久恢复。

## 实际环境与命令

Windows x64；Temurin完整JDK21在`C:\Android\jdk21`，Android SDK在`C:\Android\Sdk`。本机原有JDK17/SDK34，已安装Platform36、Build Tools36.0.0和NDK27.2.12479018，沿用CMake3.22.1。Gradle9.6.0 wrapper校验值保留；AGP9.4.0，没有降低版本或关闭TLS。

```powershell
./android/scripts/build-local.ps1 -Tasks ':app:testDebugUnitTest',':editor-core:testDebugUnitTest',':app:compileDebugAndroidTestKotlin',':app:externalNativeBuildDebug',':app:lintDebug',':app:assembleDebug'
git -c core.whitespace=cr-at-eol diff --check
C:/Android/Sdk/platform-tools/adb.exe devices -l
```

- 第一轮检查成功；新增生命周期测试曾在准备画布时被Robolectric模拟内存拒绝，未进入追加流程。修正为测试显式固定预算；生产动态预算不变。失败运行不记通过。新增预设数字格式/未使用提示的Lint告警已修正，没有关闭检查。
- 定稿测试报告在`android/app/build/test-results/testDebugUnitTest`与`android/editor-core/build/test-results/testDebugUnitTest`；编译/单测不证明真实ImageDecoder、SAF或设备性能通过。
- `adb devices -l`为空；没有安装、启动、卸载、清数据、杀进程或旋转设备。原始失败附件未索取，使用仓库已有核验记录；Mac没有可运行环境。

## 签名授权与持久交接

2026-10-12用户最新授权：优先原密钥，环境迁移导致不可用时可交付新测试密钥APK，不必再次确认，覆盖旧文档“证书不符不得交付”限制。原指纹为342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a。

检查了本机签名环境变量、Android默认密钥及Codex工作目录的keystore文件；没有找到原密钥/配置。默认debug.keystore的SHA256为34e1e096db59d2b2d791751d5f497f0fc954102767d35d410202dba5ac03da7a，也与原证书不同，没有用它代替原密钥。

本工程专用新密钥持久路径：`C:\Users\ASUS\.android\compositor-signing\compositor-test.jks`；配置：同目录`signing.json`。二者均在仓库外，后续聊天沿用，不打印/提交密码。构建脚本不生成新密钥；读取此配置，或优先使用显式提供的四个COMPOSITOR_DEBUG变量。不要上传包含签名配置的目录。

不同签名通常不能直接覆盖旧版；先用旧版Save Copy并核对作品可重开，再由用户选择迁移方式。没有自动卸载、改包名或清数据。

## 待完成队列

1. 文档级历史及图层管理：新增/删除/排序/变换的统一撤销，新建任意背景色；追加超过预算的可确认缩小导入。
2. 安全保存/内部恢复：完成修改的快照、30秒/后台保存、最近3工程与先前有效版本、故障200次、保存期间继续编辑的版本标记、SAF提供方保护。
3. 整图分块合成/输出和多层总预算，再做设备4K/10层性能；已有稀疏笔刷不等于整图分块已完成。
4. 完整绘画和开发计划16节PARITY-00～07。Mac逐项入口/参数/文件语义索引、实际像素/界面对照、PSD/RAW/文字/调整等仍未关闭。
5. 有设备先保护作品；用新提交对应新APK重测ADB修复与新增功能，重新记录哈希。旋转保持跳过，未经用户重新安排不执行或记通过。

## 定稿检查与产物

- 定稿完整Gradle命令成功（59秒，107任务，31执行/76最新）。app29项/core14项，共43项JVM单测，失败/错误/跳过均0；两项设备测试Kotlin编译通过，未运行。
- Kotlin/Java及ARM64 NDK构建通过；Lint 0错误/6警告，均为API/依赖版本建议，未隐藏或降低检查。设备性能与JNI运行未测。
- APK：3,525,075字节；SHA256 `9ff32936295f5665b5136d72618266eac352401af806617878b02eaa4ff7b946`。
- 新证书SHA256 `7426b906481f4875d3459b343bc57aae793d6e737e3a85996eb97ff733800a0b`，与原证书不同。实际API31范围apksigner验证v3通过；另限定SDK23验证v1、SDK24–27验证v2通过，后两者只是签名方案静态检查，不宣称支持低于API31。旧校验器提示META-INF元数据未受JAR保护及不识别v3方案ID，实际API范围验证正常。
- `aapt2 dump badging`：包名com.wonderassembly.compositor，0.1.0/versionCode1，minSdk31/targetSdk36，ARM64。`zipalign -c -P 16 4`退出0。`scripts/verify-apk.py`：全部ZIP CRC通过，唯一ARM64库ZIP DEFLATE method8，三个ELF LOAD alignment均0x4000且offset/vaddr模16KiB一致。不代表16KiB设备安装/运行通过。
- 新APK稳定交付位置：`android/build/deliverables/Compositor-Android-LOCAL02-20261012.apk`（Git忽略）；对应源码完整提交：`96c992d58de52b5220aaaa3f251833efedf445db`。APK在提交前从该同一份定稿源码构建，提交后仅补文档身份；业务源码没有改变。样例comp未变化、不重发，三个旧ZIP没有加入提交或删除。
