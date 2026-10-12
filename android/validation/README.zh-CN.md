# Android 验证与接续记录

最新开发：[RECOVERY06内部安全副本](recovery06-20261012.zh-CN.md)。冻结已提交像素、原子发布/轮换、恢复与清理入口；55JVM及200次Windows文件故障注入有证据，Android仪器仅编译。用户明确暂缓真机，本轮无ADB/安装，所有真实设备项记录在[待测清单](recovery06-device-checklist.zh-CN.md)，不继承旧包PASS。外部原位保存、Android恢复实测、分块与Mac未完成。

最新开发阶段：[HISTORY05 文档统一历史](document-history05-20261012.zh-CN.md)。48项JVM、Lint0错误/6建议、ARM64与仪器APK构建通过；新APK身份在记录中。设备使用中，本轮不安装/切应用，设备及Mac未运行；旧LOCAL03/INPUT04证据不用于此包。安全保存、恢复、分块和完整Mac对齐仍待推进。

最新设备补验：[INPUT04成功追加、历史保护与白黑背景](append-background-input04-20261012.zh-CN.md)。LOCAL03包不变，成功追加EXIF6 JPEG后旧笔迹和Undo/Redo像素保持、文档/旧层身份保留、新层居中方向正确；17×9白黑背景PNG与ZIP全部像素通过。作品副本和`.nomedia`保留；旋转未运行，其余SAF方向、预设、恢复、分块与Mac仍待推进。

最新实现/新包：[LOCAL03 2048准入修复](blank2048-local03-20261012.zh-CN.md)。43JVM、3真机仪器测试、默认2048创建/跨块绘画/保存重开像素闭环通过，关闭LOCAL02对应新建FAIL；其余容量、分块、恢复与Mac未完成，旋转仍NOT_RUN。

最新补验：[传统TCPIP 5555串行测试](tcpip-adb-20261012.zh-CN.md)。用户授权USB切换成功、之后恢复测试；软橡皮/软笔点/失败取消保护及保存像素通过。仅更新验证范围，默认2048容量FAIL/旋转NOT_RUN/完整恢复与Mac未完成。

最新设备实测：[2026-10-12 LOCAL02无线ADB](wireless-adb-20261012.zh-CN.md)。已用新APK安装/运行及验证小尺寸追加、绘画、保存副本重开和输出像素；默认2048容量FAIL。Download/Compositor/.nomedia已在图片推送前创建，后续必须保留。没有旋转验收，未继承旧INSTALL01 PASS。

最新接续：2026-10-12 Windows本地开发，追加图片、新建完善与绘画工具的实现/检查及新APK身份见[本地阶段验证](local-development-20261012.zh-CN.md)。下方旧快照保留作历史；旧包PASS不代表新包通过。原密钥不可用时交付新测试签名已获最新用户授权，覆盖旧限制；默认兼容打包保留，设备旋转仍跳过。

更新日期：2026-10-11。需求以仓库v0.4 PRD为准；最新状态以开发计划14.4/15.1和下方ADB回传为准。业务源码基线3a768ac，已包含临时分块笔刷、按层历史及PNG/JPEG/ZIP文件入口。用户先前的导航/基础笔刷/安装反馈保留；本地ADB结果已回传并发现失败，优先修复。以下按阶段保存历史记录，不能将旧“尚未验证”或构建成功当作当前完整验收。

## 最新ADB回传与接续（2026-10-11）

本地结果已回传：34子项，21 PASS、3 FAIL、5 BLOCKED、5 NOT_RUN。四个失败/调查拆包的外部及内部哈希全部核对一致。导入manifest和样例README不兼容已定位；图层PNG确实截断，配置变化中断最符合当前证据，仍需复现。优先执行开发计划14.4；修复尚未实施。旧文中“未回传/未验证”是当时记录，最新范围与证据边界见[ADB复核与优先修复计划](adb-results-20261011.zh-CN.md)。本轮仅文档提交，不运行功能测试或交付新APK。

## 构建与自动检查

- 环境：云端 Linux x86-64、系统预装OpenJDK 21运行时；本次使用临时Adoptium JDK 21.0.12.1、Android SDK Platform 36、Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1及Gradle Wrapper 9.6.0/AGP 9.4.0。临时JDK、SDK位于`/tmp`，系统CA信任库用于代理TLS验证。
- 此前完整命令 `./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug` 曾成功，测试 XML 时间为2026-10-10 08:52:31 UTC；这属于上一次代码状态，不是本次FIX-01的构建结果。
- JVM测试共10项：CanvasPreviewGeometryTest 3项、PreviewLayerOrderPlannerTest 2项、PreviewMaskPlannerTest 3项、PreviewLiveMaskPlannerTest 2项；失败和错误均为0。覆盖几何与图层/蒙版规则规划，尚不覆盖真实Canvas逐像素输出、文件失败保护、绘画或保存。
- FIX-02后运行 `:app:lintDebug` 与 `:app:assembleDebug` 均成功。Lint为0错误、3条升级建议：目标API、Gradle与compileSdk升级提示。报告位置为 `app/build/reports/lint-results-debug.xml`；测试报告为 `app/build/test-results/testDebugUnitTest/`。
- 本次文档/Git检查：`git diff --check`通过；原PRD保持远端v0.4原文。没有提交构建目录、APK、临时工具链、签名密钥或检测到的凭据。Windows Wrapper通过Git属性保留CRLF工作区换行。
- FIX-01构建检查：`JAVA_HOME=/tmp/compositor-openjdk21 ANDROID_HOME=/tmp/compositor-android-sdk ANDROID_SDK_ROOT=/tmp/compositor-android-sdk`下运行`:app:assembleDebug`成功；Gradle使用环境HTTP代理及`/etc/ssl/certs/java/cacerts`，TLS验证保持启用。完整构建有32项任务执行、耗时53秒；最终内存错误提示调整后又增量构建成功，34项任务中12项执行、耗时3秒，Kotlin编译通过。本次没有运行单元测试或连接设备。最终APK SHA-256为`8c7f821ca6f8e1800be7129d2ab85462b4abc854cc559930da50cd1045ceb41e`。
- FIX-02构建检查：`JAVA_HOME=/tmp/compositor-openjdk21 ANDROID_HOME=/tmp/compositor-android-sdk ANDROID_SDK_ROOT=/tmp/compositor-android-sdk ./gradlew --no-daemon :app:lintDebug :app:assembleDebug`成功，44项任务中21项执行。首次Lint因Gradle未使用环境代理而无法解析JUnit；使用环境代理及系统CA信任库重试后依赖解析和Lint成功。Lint为0错误、3条SDK/Gradle升级建议。本次没有运行单元测试或连接设备。最终APK SHA-256为`3b5a9bd2e1e79c4221aa3cb31b6763e151969f16cc18beb625219b65459c036d`；aapt核实最低API31、目标API36。

新环境安装对应工具，设置 `JAVA_HOME`、`ANDROID_HOME` 后，从 `android/` 运行：

```sh
./gradlew --no-daemon :editor-core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

当前有 `app` 与纯Kotlin源码的 `editor-core` Android library 模块。核心测试运行 `:editor-core:testDebugUnitTest`；完整命令见本次BRUSH-01记录。报告、缓存、APK和签名密钥不提交Git。

## 本次 FIX-01 记录

- 基线：远端 `work` 提交 `e2ad882`；实现文件为 `app/src/main/kotlin/com/wonderassembly/compositor/MainActivity.kt` 与 `app/src/main/res/values/strings.xml`。
- 实现：打开新项目时不再先清空当前模型、URI、选中层或预览；后台解析候选项目并逐个解码引用的图层和蒙版PNG。预检成功后才取得持久读取权限、更新项目URI并提交新项目；读取、解码或内存不足失败时保留原项目。文件选择器取消不进入加载流程。
- 检查：`git diff --check`通过。经代理下载并校验临时JDK、SDK后运行`:app:assembleDebug`成功，最后一次增量构建中Kotlin重新编译通过；系统CA信任库用于代理TLS，未关闭证书验证。未运行单元测试或连接设备。
- 状态：代码与Debug构建完成。用户在Fold设备上手动确认四类故障项目拒绝打开，且连续打开失败项目时保留旧画布；图层交互/选中状态、取消、成功切换及自动规则检查仍待补充。FIX-01构建APK SHA-256为`8c7f821ca6f8e1800be7129d2ab85462b4abc854cc559930da50cd1045ceb41e`。

## 本次 FIX-02 记录

- 实现：最低系统版本从API26改为PRD默认API31；首版应用资源、画布无障碍描述、项目校验和预览错误信息统一为英文。Compositor项目内的图层名称继续按项目原文显示。API31起始后移除已不适用的旧版WindowInsets分支。
- 检查：应用源码/资源中无中文UI文案；`:app:lintDebug`、`:app:assembleDebug`通过，Lint 0错误、3条SDK/Gradle升级建议；`aapt`核实APK最低API31、目标API36。未运行单元测试。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `3b5a9bd2e1e79c4221aa3cb31b6763e151969f16cc18beb625219b65459c036d`。当时英文长文案和API变更后的Fold屏幕布局尚待复测；后续用户截图显示横屏样例中的英文说明可读，当前未覆盖窄窗口/分屏、TalkBack与最低API安装边界。

## 本次 FIX-03 记录

- 基线：`work`分支提交`e2ad882`；FIX-01/FIX-02仍在工作区，本次未提交或覆盖这些改动。
- 编辑核心：新增纯Kotlin `editor-core` 模块，包含栅格文档模型和图层新增/替换/移除/排序事务，按画布4096边长、约1680万像素和10个栅格层限制，按画布合成面、各图层RGBA面、最大活动层暂存面与灰度蒙版估算工作集。窗口→画布、图层原始像素→画布的坐标换算使用左上原点、+x向右、+y向下、像素边缘坐标；变换集中处理缩放、顺时针旋转和翻转。
- 像素契约：固定8-bit RGBA、sRGB、预乘Alpha、首行在上，行步长显式记录。新增直接ByteBuffer JNI适配，避免整图在JNI边界复制；将Mac工程已有的可移植`BrushPixels.c`编入Android ARM64共享库，当前连接Alpha非透明边界算法。其他原生调用、固定像素结果和JNI运行尚未验证。
- 设备预算：Bitmap预览每次读取系统可用内存/阈值、应用memory class和进程PSS，使用两者较小余量的一半作为安全工作集，再分给合成位图、实时蒙版、源位图、蒙版缓存与瞬时蒙版解码；最大预览边长仍以2048为上限，低内存会继续缩小。删除读取器固定800,000,000总像素规则，未将PRD建议画布范围称为设备保证。策略尚未在Fold/平板运行或测量，GPU与撤销预算需后续按真实编辑器补入。
- 分工：现有`Activity`/`Views`/`Bitmap`/`Canvas`继续只读预览和SAF/生命周期验证；模型不持有可编辑像素，正式Compose/GPU编辑区、绘画、历史、保存/导出均未实现。
- 检查：`git diff --check`通过；`:app:lintDebug`、`:app:assembleDebug`通过，NDK/CMake实际构建`arm64-v8a`并打包`libcompositor_editor.so`。Lint 0错误、4条建议：targetSdk、Gradle、compileSdk升级，以及因产品限定ARM64导致缺少ChromeOS x86_64。没有新增或运行单元/像素测试；没有连接设备，未执行JNI运行、动态预算行为或Mac对照检查。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `71486033bff6e2fb4732be197260f549f5d04497c963e137e52ea7689ee5145a`；最低API31、目标API36。APK未安装到用户设备。
- 下一步：FIX-04补P0混合模式和固定像素参考检查；为模型、坐标、内存估算及JNI像素接口补齐自动规则检查，并在设备上测量预算后再作性能承诺。

## 本次 INPUT-01 / BRUSH-01 记录

- 基线：`work`与`origin/work`均为`230b39b5e450d52ccc353c9d430c7e0e8b6de4e8`。检查了根目录`AGENTS.md`、Android PRD与开发计划、`android/README.md`及既有验证/开发历史。源`.comp`样例没有变化。
- 实现：`CanvasPreviewView`把View局部触点经当前画布视口映射到项目像素，再通过图层平移/缩放/旋转/翻转逆变换映射到源PNG像素；画布外或图层源边界外不落笔。Brush模式限定可见、非组、非调整、Normal混合、无效果且有PNG的图层。
- 首个笔画事务：固定黑色硬笔刷，直径32源像素；按需用`BitmapRegionDecoder`读取源PNG的256×256块到预乘RGBA8/sRGB稀疏缓存；源像素按插值笔段执行source-over。抬指提交内存中的before/after块patch；`ACTION_CANCEL`、窗口失焦、视图销毁和图层切换回滚未完成笔画。出现第二触点时先结束当前有效笔画再锁定为导航，不把两个触点连为笔迹。完成patch目前没有接入撤销栈；源`.comp`项目不写回，进程死亡后临时编辑不保留。Activity配置变化会复用会话对象。
- 自动验证：`:editor-core:testDebugUnitTest`8项通过，`:app:testDebugUnitTest`10项通过，共18项，失败/错误/跳过均为0。核心用例覆盖映射（含旋转/翻转逆变换）、跨256px边界、线段插值、透明输入、预乘Alpha source-over、取消回滚、patch undo/redo及拒绝直通道Alpha数据。`:app:lintDebug`通过，0错误、4条既有建议（目标API、Gradle、compileSdk、ChromeOS x86_64 ABI）。`:app:assembleDebug`成功，96项任务中17项执行、79项最新；`git diff --check`通过。
- 工具链与命令：临时JDK21、SDK Platform/Build Tools36、NDK27.2.12479018、CMake3.22.1、Gradle9.6.0/AGP9.4.0；从`android/`执行：

  ```sh
  ./gradlew --no-daemon -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 \
    -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 \
    -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
    :editor-core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  ```

  TLS验证保持开启。早期运行中曾发现并修正Activity非配置实例API用法、坐标测试期望值，以及一次触摸监听器变量名编译错误；以上问题在最终命令中已消失。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `f67332034095de1e7042703839e44c499fe73ac2d86e9c82f4ab0660f00b0316`；最低API31、目标API36，包含ARM64原生库。Lint报告为`app/build/reports/lint-results-debug.html`，测试XML位于各模块`build/test-results/testDebugUnitTest/`。构建产物不提交Git。
- 当前APK签名：本环境的Android debug keystore路径为`/home/agent/.android/debug.keystore`，alias `androiddebugkey`；证书SHA-256为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。本轮最终APK通过显式外部签名变量指向该keystore重建，`apksigner`核对指纹一致。此私钥没有写入仓库。`app/build.gradle.kts`现支持从`COMPOSITOR_DEBUG_KEYSTORE`、`COMPOSITOR_DEBUG_KEYSTORE_PASSWORD`、`COMPOSITOR_DEBUG_KEY_ALIAS`、`COMPOSITOR_DEBUG_KEY_PASSWORD`读取固定debug密钥，四项必须同时设置。当前工作区未找到此前APK或对应旧keystore，因此尚不能确认/恢复此前证书；要继续沿用旧安装签名，需要旧keystore及其alias/密码。
- 真机验证：使用SDK Platform Tools查询设备列表，当前为空；本轮没有Fold画笔/旋转翻转/跨块/取消/双指转换的手测，也没有性能、笔压、掌触或Mac检查。以前用户在Fold确认的导航和项目操作仍各自按既有记录有效，但不外推到这次笔画实现。
- 文件与样例：用户提到的FIX03、FIX04、Preview-Navigation三个旧打包文件在当前`/workspace`未找到，本轮未删除、重建或纳入提交；没有更新或重新发送任何测试`.comp`文件。

## 本次 BRUSH-01：双指开始时清除首指笔迹（2026-10-10）

- 用户在Fold报告：双指缩放时，第一根手指落下的位置会出现笔迹。原因是笔刷在首个`ACTION_DOWN`立即绘制圆点，第二指的`ACTION_POINTER_DOWN`又将活动事务提交。
- 修正：第二指进入时静默取消活动分块事务并刷新受影响图块，之后仍把整个多指序列锁为导航操作。这样捏合起点不会留下首指笔刷印记或显示“笔画已取消”；若第一指先画过一段再加第二指，该段未完成事务也会一并回滚。
- 构建：使用外置固定debug keystore执行`:app:assembleDebug`成功（56项任务中6项执行、50项最新）；`apksigner`确认SHA-256证书指纹仍为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。当前APK SHA-256为`475112cb5cd33ab7909b3fbea023a4b409e698a0d63d54dfd89d71c02730d899`。本次未运行单元测试或Lint；`git diff --check`通过。
- 验收状态：用户在Fold安装后确认“没问题了”，记为双指缩放起点不再留下笔迹。此结果只验证该手势修复；单指笔画、变换映射及撤销/重做仍待验收。没有修改或重发`.comp`样例。

## 本次 HISTORY-01：分块笔画撤销/重做（2026-10-10）

- 实现：已提交笔画patch由当前图层会话历史持有，Undo/Redo按钮只作用于该图层的临时笔画。每个会话最多保留20笔，按before/after快照像素字节限额；新笔画在撤销后提交会清空重做分支，空间不足时丢弃最旧的撤销记录。超出单次历史快照预算的笔画会回滚并提示。历史不会写入`.comp`，也不跨层合并；变换、删除等操作尚无历史。
- 预算：原图块缓存、历史快照和活动笔画余量在每个图层会话的既有编辑预算内拆分。此字节数不含Bitmap、对象头和多个图层会话的汇总峰值；Fold/4K真实内存压力仍待测量。
- 检查：`:app:assembleDebug`成功（56项任务中13项执行、43项最新）；本轮未运行单元测试或Lint。此前18项核心/App单测通过的是坐标和分块事务底层，不覆盖新历史栈；20笔、分支和淘汰规则仍待自动验证。`git diff --check`通过。
- APK：固定debug签名证书SHA-256指纹`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`；APK SHA-256为`99ee376930e9cc46437c939a0ac0ccbb0974bd651a3672489a74ce88290c27cb`。
- 验收状态：Undo/Redo按钮、20笔序列、预算淘汰和跨层切换尚未在Fold验收。历史无需重发或更新任何`.comp`样例。

## 本次 IO-01：单图层PNG安全导出（2026-10-10）

- 实现：可编辑图层工具行增加“Export PNG”，通过Android `ACTION_CREATE_DOCUMENT`创建单独目标。导出当前所选图层的源像素，并包含当前已提交的分块笔画（包括撤销/重做后的最终状态）；PNG写出按行编码，原图按16行条带解码，并从分块缓存覆盖改动像素，不分配完整图像素数组。只输出图层源像素，不应用图层变换、蒙版、效果或其他图层合成。`.comp`仍只读；若目标URI与源PNG完全相同则拒绝，写出失败时尝试删除部分输出。
- 代码检查：`ChunkedRaster.copyCommittedRowOnto`逐行叠加已提交改动；`ReferencePngWriter.writeRgba8PremultipliedRows`增加流式行接口；`LayerEditingSession`合并源条带和分块编辑；Activity负责SAF选择、工具禁用及错误状态。
- 构建与检查：固定外部debug keystore下运行`:app:assembleDebug`成功（56项任务中6项执行、50项最新），包含ARM64本地库；`apksigner verify --print-certs`确认SHA-256证书指纹为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。最终APK SHA-256为`e6893581eaa940d1cba7648f98c0b4fa4084a221d7d0d48a40b3dd4995152ac4`。`git diff --check`通过。未运行单元测试、Lint或PNG像素/透明边缘检查；环境没有`adb`命令，未安装到Fold或模拟器。开发中先后修正一条Android字符串资源转义错误和横向滚动布局参数类型错误，最终构建通过。
- 未验证范围：SAF不同文件提供方的创建/权限/删除语义、取消选择器、导出PNG是否可打开、透明像素与原图差异、当前笔刷及undo/redo结果是否逐像素正确、目标文件异常残留、4K耗时/内存、系统旋转或进程结束。未保存或更新测试`.comp`样例。
- 用户设备记录：用户回复“没问题”，据本轮此前的设备检查清单记录为Fold上的普通笔迹、撤销/重做、撤销后新笔画清空重做分支、切换图层后的独立历史均无异常；没有截图、录像、Android版本、窗口dp或逐步结果表。该反馈只覆盖交互，不代表自动栈边界、预算压力或本次文件导出已验收。
- 下一步：在Fold创建一张单图层PNG，检查笔画和undo/redo最终像素及透明边缘，重开源`.comp`确认未变化；检查新增ZIP条目、修改图层像素和失败清理，并在Mac解包打开。之后推进安全ZIP导入/重开、整图合成导出与图片导入。文件提供方拒绝删除部分输出时的残留行为仍要单独处理。

## 本次 IO-01/FILE-01 后续：独立项目 ZIP 副本（2026-10-10）

- 实现：工具行增加“Save Copy (.comp.zip)”，通过`ACTION_CREATE_DOCUMENT`选择外部目标。读取项目时保留源目录URI、目录名、manifest原始字节和`images/`所有直接子项；写出标准ZIP根目录`项目名.comp/`，先写原始manifest，再逐个复制图像资源。对有已提交分块笔画的图层，用流式PNG编码替换其`images/<layer UUID>.png`；撤销回原像素的图层不重编码。复制按64KiB缓冲逐流处理，检查512MiB单资源上限；拒绝嵌套`images/`目录和不安全路径名。源`.comp`不变，也不更新为“已保存”。Android尚不能直接打开ZIP，也没有写回`.comp`目录。
- 失败处理：读取、ZIP编码或外部写入失败时尽力删除不完整目标；根据可识别的SAF document ID拒绝在源包内部创建ZIP。部分DocumentsProvider使用不透明ID，无法对所有提供方可靠判断目录包含关系。进程被系统直接结束时，清理不能保证。
- 自动检查：加入归档实现后的首次构建使用JDK21、SDK/Build Tools36和固定外部debug keystore，`:app:assembleDebug`成功，56项任务中18项执行、38项最新；无障碍文案更新后的最终重打包成功，56项任务中10项执行、46项最新。`git diff --check`通过。未运行单元测试或Lint；未检查ZIP条目/CRC、替换PNG像素、失败注入或SAF提供方行为。
- 签名和产物：`app/build/outputs/apk/debug/app-debug.apk`，最终APK SHA-256：`ac125d69ca4185351f0a8ad33813cd0c90f3d6633f5d847e60e69927e0f51816`。`apksigner verify --print-certs`确认SHA-256证书指纹为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`，与先前统一签名一致。
- 真机/Mac：环境无`adb`，未安装或运行本次APK；没有Mac运行环境。用户尚未验收本次文件行为。没有创建、修改、删除或重发测试`.comp`样例或用户提及的旧打包文件。
- 下一步：Fold检查单层PNG透明像素和源项目不变，再创建ZIP检查目录与笔画/undo结果；Mac解包打开后推进ZIP安全导入/重开。

## 本次 IO-01/FILE-01：安全 ZIP 导入与重开（2026-10-11）

- 实现：打开项目入口增加“选择 `.comp.zip`”，同时保留 `.comp` 文件夹选择。归档接受唯一的`名称.comp/manifest.json`＋`images/`根结构，以及PRD要求的直接`manifest.json`＋`images/`根结构；其他混合根、多个项目根、未知路径和`images/`内嵌套目录拒绝导入。两种输入共用现有manifest、图层关系、PNG头和图层引用校验。
- 安全处理：导入文件先流式复制到应用私有缓存，压缩文件上限1GiB；限制最多20,003个中央目录项、manifest 4MiB、单资源512MiB和总解压1GiB。拒绝ZIP64/分卷、加密、未知压缩方法、重复/穿越路径、反斜杠和控制字符；对Unix/macOS文件类型标记拒绝软链接和特殊文件。每项提取到私有目录后核对解压长度和CRC32，引用图像全部解码预检通过后才替换当前项目。失败删除候选缓存并保留旧项目；切换项目时清理旧缓存。输入归档仍由用户提供的源URI读取，应用不会写回它。
- 自动检查：固定外置debug签名下运行`:app:assembleDebug`成功（56项任务中6项执行、50项最新）；`git diff --check`通过。`apksigner verify --print-certs`核对证书SHA-256为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`，最终APK SHA-256为`db873ed19465cbf8ce8296dcb970b8785b5db246475654dc52f259e7b81ba8ad`。本次没有运行单元测试、Lint或归档运行时行为检查。
- 设备/Mac状态：`adb devices`执行成功但设备列表为空，未安装Fold；没有Mac环境。ZIP选择器的MIME过滤、SAF持久权限、正常归档打开/保存后重开、恶意ZIP拒绝和CRC失败行为均待设备或自动检查；目录结构与替换PNG仍未和Mac互通验证。Debug构建只证明代码编译、ARM64链接和APK打包。
- 用户文件：没有生成、修改、删除或发送`.comp`测试样例，也没有触碰用户列出的FIX03、FIX04、Preview-Navigation旧包。
- 下一步：在Fold验证正常`.comp.zip`直接打开、另存后的副本重开、失败导入保留当前画布和ZIP来源不变；然后做归档安全/CRC与PNG像素自动检查，并在Mac解包打开。

## APK与样例

- APK位置：`app/build/outputs/apk/debug/app-debug.apk`；应用ID `com.wonderassembly.compositor`，版本0.1.0（versionCode 1），debug签名，最低API31，目标API36。
- FIX-02构建包SHA-256：`3b5a9bd2e1e79c4221aa3cb31b6763e151969f16cc18beb625219b65459c036d`；FIX-01构建包SHA-256：`8c7f821ca6f8e1800be7129d2ab85462b4abc854cc559930da50cd1045ceb41e`。重新构建的包可能受签名和环境影响，不要求新环境产物具有同一哈希。
- 当前构建包SHA-256：`db873ed19465cbf8ce8296dcb970b8785b5db246475654dc52f259e7b81ba8ad`；仅记录构建识别，不代表已在设备安装或验收。
- 2026-10-11设备反馈：用户在小米Fold点开最新`app-debug(16).apk`后停留在安装包扫描，随后自动返回文件列表；此前版本可以打开安装界面。用户上传旧`app-debug(15).apk`后，确认新旧APK的证书指纹和v2签名方案相同，Manifest字节相同；旧版已卸载。两包静态ZIP/DEX/ARM64 ELF完整性检查通过。尚无手机端文件哈希和PackageInstaller错误码；根因待取得设备侧记录。
- 设备上可手动安装重建的APK。可选择含 `manifest.json` 和 `images/` 的 `.comp` 文件夹，也可直接选择`.comp.zip`归档。
- [六层样例](../samples/Compositor-Android-Demo.zip)与[蒙版样例](../samples/Compositor-Android-Mask-Demo.zip)由本仓库按官方格式编写，均不是原作者发布的官方作品。预期画面及操作见[样例说明](../samples/README.zh-CN.md)。
- FIX-01 文件失败保护用[手动测试包](../samples/FIX-01-Manual-Test.zip)：先打开有效基准项目，再分别尝试四种故障项目；预期每次失败均保留当前项目。步骤和案例列表见压缩包内说明。

## 用户真机结果与限制

设备名称按用户报告记录为“小米18 Fold”，内屏；未通过adb读取型号、Android版本、实际窗口dp或字体比例。

- 用户截图显示六层项目可打开，图层列表与各单层原始像素预览可显示；修复后的横屏为画布/列表两列，状态栏与导航栏不再覆盖内容。
- 用户明确反馈已手动验证转屏，没有问题：项目保留，不必重新选择文件夹。未记录50轮循环、分屏、折叠、进程结束恢复或长列表滚动完整验收。
- 蒙版样例截图显示红色渐隐、上半组蒙版和黄色剪贴圆，包括隐藏剪贴来源；用户认为没有问题。这仅验证该样例的只读合成，不代表蒙版编辑、历史、保存/重开或Mac逐像素对照通过。
- FIX-01：用户在Fold设备上分别重启应用后测试缺失PNG、无效JSON、版本999和无法解码PNG，均显示对应错误提示。另据用户报告，在有效项目已打开时再选故障项目，错误提示出现而旧画布仍显示，符合TC-015的“不替换当前工程”。截图中的四次冷启动失败本身不验证保留行为；失败后图层列表/选中状态是否仍可操作、取消选择器及后续成功切换尚未单独确认。
- FIX-02：用户截图显示Fold横屏样例中的英文说明和布局可读；窄窗口/分屏、TalkBack与最低API安装边界仍未单独验证。
- BRUSH-01：用户已在Fold确认双指缩放起点无笔迹；固定32px普通笔画、变换层映射、跨块笔画、取消与笔感仍待设备验收。
- HISTORY-01：用户报告Fold上的普通笔画、Undo/Redo、撤销后新笔清空重做分支、切层后各层历史保留均无异常；20笔上限、预算压力反馈和自动历史规则仍未验证。
- IO-01：当前Debug APK已接入单图层PNG导出代码；尚无设备上的SAF创建、PNG可读性、透明像素、源`.comp`不变或失败清理结果。
- 截图原件位于本会话附件，未复制进Git；新会话可依赖此文字记录继续，若需要重新测量截图需重新获取原件。
- 当前执行环境中的`adb devices`列表为空，未连接或安装设备，也未运行仪器测试；没有Mac环境，未运行Mac打开/保存往返、截图或像素对照。

尚缺新建→导入→编辑/绘画→保存→重开→导出基础链路。全部完整FR/TC/MAC验收保持待完成；不能把以上样例反馈升级为整个产品通过。

## 历史

[早期开发记录](development-history.zh-CN.md)保留旧任务编号和检查经过。旧记录中的“PRD缺失”只描述当时尚未获取远端work的检出状态，原PRD现已接入；当前权威状态与纠偏顺序以主计划为准。

## IO-02 连续开发与统一验证（2026-10-11）

- 已实现：预算内精确画布尺寸PNG导出、白底JPEG三档质量、导出进度/取消/失败清理反馈、PNG/JPEG作为单层新项目导入、方向与sRGB/RGBA8规范化、Composite包含已提交笔迹、Cancel Open和未保存笔迹切换提示。新图层追加、缩小导入、大图分块合成、DPI导出、系统分享及安全文件夹保存/恢复仍待实现。
- 检查通过：固定外置签名的最终`:app:assembleDebug --offline`成功（56项，14项执行/42项最新）；`apksigner verify --print-certs`证书SHA-256与旧包相同；`git diff --check`通过。本轮未新增或运行测试，未运行Lint、像素对照或归档行为检查。
- 产物：`app/build/outputs/apk/debug/Compositor-Android-IO02.apk`与`app-debug.apk`同字节，大小3,283,316，SHA-256 `80bfb48365dbd9fdb4537ae0088267e7df8a7ed1cac5f95effb493a31bbf46f6`。证书SHA-256 `342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。
- 待验证：`adb devices`为空，无Mac环境；新文件和像素行为全部待设备/Mac验证。Fold安装扫描退出仍未解决，用户要求暂缓，未将本次构建记为安装通过。
- 用户要求连续多步后一起给测试内容：[IO-02合并测试清单](io-roundtrip-checklist.zh-CN.md)。没有更新或重发测试`.comp`，没有处理用户提到的旧ZIP。

## INSTALL-01恢复排查（2026-10-11）

用户确认IO-02仍不能安装，取消暂缓状态，恢复排查。已修正原生ELF的16KB对齐，保持固定证书并加入v1/v2/v3，生成压缩native/安装提取的兼容候选。构建、ZIP/Manifest/DEX、签名和ELF布局检查通过，**候选设备安装待测，根因未确定**。产物`app/build/outputs/apk/debug/Compositor-Android-INSTALL01.apk`，SHA-256 `6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5`；细节见[安装诊断](installation-diagnostics.zh-CN.md)。用户暂时无法连接电脑，已请求上传手机端失败APK检查传输一致性。

## INSTALL-01用户确认安装通过（2026-10-11）

- 用户明确反馈INSTALL01“这个版本可以安装了”，把SHA-256为`6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5`的设备安装记为通过；不自动确认应用启动、JNI或IO-02功能。
- 后续默认改用已成功组合：压缩原生库/安装提取、16KB ELF对齐、固定证书v1/v2/v3。`compositor.installerCompatibility`默认true，无需附加参数；false仅保留诊断用途。多个措施同时变化，具体失败根因没有隔离，不归因于某一个措施。
- 当前成功包继续使用，不重发业务代码未变化的APK或测试comp；按IO-02合并清单验证导入→绘画/撤销→合成→保存重开→导出及取消/失败保护。

- 默认配置复核：未传installerCompatibility参数的`:app:assembleDebug --offline`成功（56任务：2执行/54最新）；app-debug.apk与用户成功安装的INSTALL01哈希完全相同，原生ZIP method8，签名证书一致；`git diff --check`通过。未新增/运行功能测试，不重复发同字节APK。

## 本地Codex / ADB交接准备（2026-10-11）

- 用户希望由本地Codex通过ADB执行并回传测试。新增local-codex-adb-handoff.zh-CN.md和adb-result-template.zh-CN.md，准备包含已验收INSTALL01 APK、精确基线、工作区patch和未跟踪源码的交接包；不含签名密钥/密码、SDK或未更新comp样例。
- 本地任务覆盖IO-02八组：UI操作、文件往返、像素/哈希、取消/失败与证据收集。双指/折叠、云盘、低存储和Mac环境不足时保持待测；不卸载/清数据或破坏个人文档。先保护内存笔迹，使用自建测试副本。
- 结果要求RESULT.md、results.csv、命令、设备信息、截图/UI XML、相关日志、自建输入和真实PNG/JPEG/ZIP输出；逐项PASS/FAIL/BLOCKED/NOT_RUN，返回当前云端对话附件。此处仅准备交接，未执行本地ADB或提升任何功能验收状态。

## GitHub work本地开发交接（2026-10-11）

用户授权提交并推送当前全部Android源码和文档到GitHub work，方便本地直接接续。包括分块笔刷/历史、PNG/JPEG/ZIP导入导出、固定签名配置、已成功安装的默认兼容打包和实际验证记录；不提交APK/build/SDK/keystore/密码或用户旧ZIP。交接说明改为clone/pull work，旧离线patch仅作历史快照，不在新work重复应用。功能设备验证仍待回传；签名密钥单独迁移。推送结果和提交哈希以Git远端核对与本次最终回复为准。

## 本轮修复实施与继续开发（2026-10-11）

ADB-FIX-01～03已实现，加入生产导入/读取器/归档与导出故障、Activity重建回归；固定预算/历史诊断与真实画笔错误提示已加入。NEW-01透明画布新建已接入，可输入像素宽高/PPI，候选失败保护与Save Copy重开检查见[本轮实际验证](adb-fixes-20261011.zh-CN.md)。面向用户变化见[更新日志](../CHANGELOG.zh-CN.md)。本记录前面“尚未实现新建”等文字描述旧快照；完整背景/预设、文档历史、追加图层、安全保存/恢复、分块合成与Mac对齐仍未完成。

用户选择本地原密钥构建；云端没有原密钥，未生成或交付新APK。旧INSTALL01的21报告PASS和历史独立复核不转为新代码设备通过。新提交/APK/证书和逐项新设备结果必须一起记录；原受控旋转验收仍按用户要求跳过。默认INSTALL01兼容打包保留，密钥/密码不入Git。原comp样例与三个旧ZIP未处理。
