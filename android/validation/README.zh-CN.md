# Android 验证与接续记录

更新日期：2026-10-10。需求以仓库 v0.4 PRD 为准；当前状态以[开发计划](../../docs/android-development-plan.zh-CN.md)第15.1节和本文末尾的用户真机记录为准。用户已确认Fold上的双指缩放/平移连续，且达到适配尺寸后松手回中正常；用户要求暂停下一项开发。本文件中按FIX-01～FIX-03及后续问题累积的“下一步”均是对应记录当时的计划，不代表当前指令。接续基线与代码版本通过 `git log -1` 查看，工作区状态通过 `git status` 查看。

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
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

当前有 `app` 与纯Kotlin源码的 `editor-core` Android library 模块。模型用例尚未新增；后续可运行 `:editor-core:testDebugUnitTest`。报告、缓存、APK和签名密钥不提交Git。

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

## APK与样例

- APK位置：`app/build/outputs/apk/debug/app-debug.apk`；应用ID `com.wonderassembly.compositor`，版本0.1.0（versionCode 1），debug签名，最低API31，目标API36。
- FIX-02构建包SHA-256：`3b5a9bd2e1e79c4221aa3cb31b6763e151969f16cc18beb625219b65459c036d`；FIX-01构建包SHA-256：`8c7f821ca6f8e1800be7129d2ab85462b4abc854cc559930da50cd1045ceb41e`。重新构建的包可能受签名和环境影响，不要求新环境产物具有同一哈希。
- 设备上可手动安装重建的APK。ZIP样例需先解压，再在应用选择含 `manifest.json` 和 `images/` 的 `.comp` 文件夹；应用目前不直接打开ZIP。
- [六层样例](../samples/Compositor-Android-Demo.zip)与[蒙版样例](../samples/Compositor-Android-Mask-Demo.zip)由本仓库按官方格式编写，均不是原作者发布的官方作品。预期画面及操作见[样例说明](../samples/README.zh-CN.md)。
- FIX-01 文件失败保护用[手动测试包](../samples/FIX-01-Manual-Test.zip)：先打开有效基准项目，再分别尝试四种故障项目；预期每次失败均保留当前项目。步骤和案例列表见压缩包内说明。

## 用户真机结果与限制

设备名称按用户报告记录为“小米18 Fold”，内屏；未通过adb读取型号、Android版本、实际窗口dp或字体比例。

- 用户截图显示六层项目可打开，图层列表与各单层原始像素预览可显示；修复后的横屏为画布/列表两列，状态栏与导航栏不再覆盖内容。
- 用户明确反馈已手动验证转屏，没有问题：项目保留，不必重新选择文件夹。未记录50轮循环、分屏、折叠、进程结束恢复或长列表滚动完整验收。
- 蒙版样例截图显示红色渐隐、上半组蒙版和黄色剪贴圆，包括隐藏剪贴来源；用户认为没有问题。这仅验证该样例的只读合成，不代表蒙版编辑、历史、保存/重开或Mac逐像素对照通过。
- FIX-01：用户在Fold设备上分别重启应用后测试缺失PNG、无效JSON、版本999和无法解码PNG，均显示对应错误提示。另据用户报告，在有效项目已打开时再选故障项目，错误提示出现而旧画布仍显示，符合TC-015的“不替换当前工程”。截图中的四次冷启动失败本身不验证保留行为；失败后图层列表/选中状态是否仍可操作、取消选择器及后续成功切换尚未单独确认。
- FIX-02：用户截图显示Fold横屏样例中的英文说明和布局可读；窄窗口/分屏、TalkBack与最低API安装边界仍未单独验证。
- 截图原件位于本会话附件，未复制进Git；新会话可依赖此文字记录继续，若需要重新测量截图需重新获取原件。
- 当前云端adb无连接设备，未运行仪器测试；没有Mac环境，未运行Mac打开/保存往返、截图或像素对照。

尚缺新建→导入→编辑/绘画→保存→重开→导出基础链路。全部完整FR/TC/MAC验收保持待完成；不能把以上样例反馈升级为整个产品通过。

## 历史

[早期开发记录](development-history.zh-CN.md)保留旧任务编号和检查经过。旧记录中的“PRD缺失”只描述当时尚未获取远端work的检出状态，原PRD现已接入；当前权威状态与纠偏顺序以主计划为准。
