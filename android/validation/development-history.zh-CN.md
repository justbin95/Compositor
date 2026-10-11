> 此文保存整合前的本地记录，不作为新的产品计划。原v0.4文档已从远端work接入；历史中“PRD缺失”“无设备”等描述仅指记录时的工作副本/环境，当前状态与下一步统一以docs/android-development-plan.zh-CN.md第14.3～16节为准。早期DEV-02～07编号与原计划工作包的映射见主计划15.2节。用户样例截图通过不等于完整TC/MAC验收。

# 早期Android开发历史（归档）

> 恢复说明：本文件在 2026-10-10 新建，用来记录当前检出能验证的进度。用户指定的 v0.4 `android-prd.zh-CN.md` 和原开发计划不在当前工作区或 Git 历史中，因此这里没有伪造它们第13–16节的内容。

## 目标和边界

- Android 平板与折叠屏内屏持续对齐仓库固定原版 Compositor for macOS 1.4.8（build 43，commit `22c7b8d`）。
- Android 工程仅放在 `android/`；根目录 Xcode 工程和 Swift 源码保持分开。
- 保留原版布局、工具组织、面板和深色视觉风格；按窗口尺寸收起、缩放或重排，同时保证文字清楚、触控目标好点。
- 基础可用版只是阶段目标。任何按钮都必须连到真实行为；保存必须写入真实项目文件。
- 分开记录代码、构建和设备验收；缺设备时继续其他工作并留下待测项。

## 已完成

### DEV-01：建立 Android 工程并构建 Debug APK

- [x] 检查仓库状态：起始分支 `work` 与 `origin/main` 同指向 `22c7b8d`，起始工作区干净。
- [x] 检查环境：Linux x86-64、OpenJDK 21.0.12、约 30 GB 可用磁盘；无原装 Gradle、Android SDK、adb、Xcode、Mac 或连接设备。
- [x] Temurin JDK 21.0.12.1 与 Android 命令行工具均按官方 SHA-256 校验通过；工具链安装在仓库外 `/workspace/.android-jdk` 和 `/workspace/.android-sdk`。
- [x] 安装 Android SDK Platform 36、Build Tools 36.0.0，并接受这两个已安装组件所需的 SDK 许可；没有安装 Android Studio、模拟器或 NDK。
- [x] 新建独立 `android/` 工程；Android 应用 ID 沿用原版 `com.wonderassembly.compositor`。本节初版的空壳入口已继续替换为只读 `.comp` 打开流程。
- [x] 下载并校验 Gradle 9.6.0，生成 Wrapper 并在 `gradle-wrapper.properties` 固定官方分发地址及 SHA-256。
- [x] 标准 `./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug` 已通过；Gradle Wrapper 下载并校验 Gradle 9.6.0，50 个任务成功。构建使用仓库外准备的 JDK/SDK，以及环境提供的代理根证书和代理参数；这些网络设置没有写入项目。
- [x] APK 包名、SDK、启动 Activity、应用图标、debug 签名、ZIP 结构和 4 字节对齐静态检查通过；本次 APK SHA-256：`d558a37c38e2c881517767e35d58ee33564b4c29666b2b543c1c4768f40a352f`。
- [x] JVM 单元测试实际运行 3 项画布缩放布局测试，3 项通过、0 失败；最后一次干净构建无 Kotlin 编译警告。
- [x] Android Lint 通过：0 个错误；剩余 3 条是 Android SDK/Gradle 可升级版本建议。
- [x] 调整命令行编译器版本后，D8 元数据兼容诊断为 0；Kotlin 编译没有警告。
- [ ] 设备安装与启动验收待有 Android 平板/折叠屏或模拟器后进行；当前 `adb devices -l` 没有列出设备。

### PARITY-00：原版功能和界面对照

- [x] 根据 1.4.8 README、SwiftUI 主界面、菜单、`EditorSession` 与 UI 文件，建立 [`android-parity-checklist.zh-CN.md`](../../docs/android-parity-checklist.zh-CN.md)。
- [ ] 对照用户指定的 PRD 第21节逐项补齐验收标准；当前缺少该文档，尚不能声称 PRD 对齐完成。
- [ ] 在 Mac 上实测原版并采集平板/折叠屏布局的对照截图；当前没有 Mac 和真机。

### DEV-02：原生 `.comp` 项目只读打开

- [x] 审阅当前格式 v1–v11、Swift 项目读写、图层变换、尺寸限制和蒙版/图层树校验。
- [x] 新增 [`android-project-format-port.zh-CN.md`](../../docs/android-project-format-port.zh-CN.md)，记录格式边界和未知字段保留要求。
- [x] 增加 Android 系统文件夹选择器；用户选择项目文件夹后，读取 `manifest.json` 与 `images/`，检查基础元数据、层级/引用、文件大小和 PNG 头部，并显示画布尺寸、分辨率和真实图层列表。
- [x] 图层列表使用可回收的系统列表控件，避免项目接近原版 10,000 图层上限时一次创建大量界面元素。
- [x] 解析时保留原始 JSON 对象，不因当前未实现形状、文字、调整、效果等能力而重写或丢弃字段；当前不会写入项目。
- [x] Kotlin 2.2.21 命令行编译：通过，无编译警告；D8 元数据兼容诊断为 0。
- [ ] 打开项目时仍只检查各 PNG 头部；选中图层后会额外解码该图层用于预览，但尚未逐个完整解码所有 PNG，也没有独立验证 PNG CRC；需真实 Mac 项目样例和 Android 设备测试。
- [ ] 尚未实现完整可编辑画布、绘图、撤销或保存；当前有限合成也不等于原版完整渲染。安全保存需支持文档提供器临时目录写入、完整校验和原子替换。

### DEV-03：画布预览初版

- [x] 项目解析保留每个像素层的图像引用、像素尺寸、位置、大小、旋转、翻转、采样方式和不透明度。
- [x] 新增可缩放适配项目比例的画布视图；按图层变换显示选中 PNG，透明区域以棋盘格呈现，并对大 PNG 使用有上限的采样解码。
- [x] 预览清楚标明当前是单图层原始像素；未合成其它图层、蒙版、混合模式、组、调整和效果，不冒充最终画面。
- [x] 增加 3 项画布布局单元测试；执行 `clean lintDebug testDebugUnitTest assembleDebug`：构建与 Lint 成功，3 项测试通过。
- [x] 该阶段交付的是有明确标注的单图层预览；后续 DEV-04 已继续实现多层有限合成。
- [x] 用户在小米 18 Fold 上提供的单层预览截图显示，点选图层后画布会显示对应图层的原始像素；DEV-05/DEV-07 的设备截图继续覆盖了单层预览。

### DEV-04：多图层有限合成预览

- [x] 按原版 `.comp` 数组的底到顶顺序，并按组树深度优先、组内连续的顺序绘制图层。
- [x] 合成可见像素层；处理图层位置、尺寸、旋转、翻转、采样和不透明度，并将组可见性与组不透明度传给子层。
- [x] 将合成画布和逐次解码的源图都限制在最长边 2048 像素，工作放在串行后台线程，并在切换项目/图层时丢弃过期结果。
- [x] 对当前未实现的内容显式提示：调整层和非普通混合模式图层跳过；蒙版、剪贴蒙版和图层效果不应用。图层列表单击仍能查看单层源图。
- [x] 新增图层顺序、组展开、父组可见性和组不透明度测试；本次 5 项 JVM 单元测试全部通过。
- [x] 仓库和官方发布资料中未找到可直接下载的官方 `.comp` 项目包；官方提供格式写作说明与 manifest 片段。已按该格式生成一个小型多层测试项目 `android/samples/Compositor-Android-Demo.comp`，并打包为 ZIP；明确标为本仓库测试样例，不冒称官方作品。
- [x] 样例静态检查通过：6 条图层记录（5 个像素层、1 个组）、ID 唯一、组引用有效、5 张 800×500 的 8 位 RGBA PNG 完整；ZIP CRC 检查通过。
- [x] 用户在小米 18 Fold 上打开测试样例并提供合成预览截图；Android 样例打开和有限合成通过人工画面验收。Mac 原版对照仍待有 Mac 时进行。
- [x] 本次干净构建 `clean lintDebug testDebugUnitTest assembleDebug` 成功；Lint 0 个错误、3 条 SDK/Gradle 升级建议。
- [x] APK 静态检查通过：包名/SDK/启动 Activity 正确，debug V2 签名有效，ZIP 完整，4 字节对齐；SHA-256：`654380b6c3e01e269c3f3d515c5d56246c46aacfecc9f7f89ad6f00723c1c186`。
- [x] 云端 `adb devices -l` 为空，不能远程安装或操控用户设备；用户已在小米 18 Fold 手动打开样例并提供画面截图。Mac 原版对照仍未进行。

### DEV-05：折叠屏横竖屏布局与状态恢复

- [x] 根据用户在小米 18 Fold 上提供的截图，把横屏工作区改为左右两栏：画布和项目状态在左，图层标题及可滚动列表在右；竖屏仍为画布在上、图层列表在下。
- [x] 给内容区应用系统状态栏、导航栏和屏幕缺口的安全留白，处理截图中标题被状态栏遮挡的问题。
- [x] 保存已选 `.comp` 文件夹 URI、选中图层和图层列表位置；屏幕方向改变、Activity 重建后，使用系统持久文件夹授权重新读取项目并恢复选择，不要求重新挑文件夹。
- [x] 执行 `clean lintDebug testDebugUnitTest assembleDebug`：构建成功，5 项 JVM 单元测试通过、0 失败；Lint 0 个错误，另有 3 条 SDK/Gradle 版本建议。
- [x] APK 包名、SDK、启动 Activity、debug V2 签名、ZIP 结构和 4 字节对齐静态检查通过；APK SHA-256：`2e79a148247773da1b65f257a3c91b5cc1cc052cfbbf2cd608d3462b829b48a9`。
- [x] 用户在小米 18 Fold 内屏截图和手动操作中确认：横屏分栏、系统栏留白和样例 6 个图层显示正常；点选图层会切换单层预览，来回转屏后项目仍保持打开，不必重新选择文件夹。
- [ ] 当前截图中的 6 个图层均在横屏可见；更长图层列表的溢出滚动尚未单独验收。云端 `adb devices -l` 为空。

### DEV-06：像素蒙版和组蒙版预览合成

- [x] 项目读取器保留蒙版 PNG 的 URI 和尺寸，并读取可选的独立 `maskPlacement`；链接蒙版默认跟随所属图层/组变换。
- [x] 合成普通像素层时应用其灰度蒙版；白色显示、黑色隐藏、灰色按覆盖程度显示，禁用的蒙版不生效。
- [x] 合成组内像素层时，逐个应用所属组的启用蒙版，嵌套组蒙版与图层自身蒙版共同控制透明度。
- [x] 蒙版解码最大边长限制为 2048 像素，并使用 16 MiB 上限的缓存，避免一次把项目中的所有蒙版留在内存里。
- [x] 单图层预览继续显示原始 PNG；界面文字明确说明它不应用该图层或所属组的蒙版，避免与已应用蒙版的项目合成预览混淆。
- [x] 新增 3 项蒙版规划测试，验证嵌套组/图层蒙版顺序、禁用蒙版、独立位置和默认链接变换；全部 3 项通过。本地 JVM 测试不包含 Android Canvas 的逐像素图像验证。
- [x] 新增 [`Compositor-Android-Mask-Demo.zip`](../samples/Compositor-Android-Mask-Demo.zip)，内含像素蒙版、组蒙版和后续加入的剪贴蒙版手动测试内容；蒙版本身分别见下方 DEV-07。
- [x] 最终 `clean lintDebug testDebugUnitTest assembleDebug` 成功；8 项 JVM 单元测试通过、0 失败；Lint 0 错误、3 条 SDK/Gradle 版本建议。样例 manifest、引用关系、PNG 尺寸、灰度蒙版和 ZIP CRC 检查通过。
- [x] APK 包名、debug V2 签名、ZIP 结构和 4 字节对齐静态检查通过；APK SHA-256：`81e7b69aefebec86f28e50c8bc790a8bfbeebb32b8da3893d6901e7d1fee92fc`。
- [x] 用户在小米 18 Fold 上打开蒙版样例并提供合成截图；红色渐隐、上部绿色组蒙版区域和黄色圆形均符合样例预期。此项为用户真机画面验收；云端没有连接设备，无法通过 adb 独立复测。

### DEV-07：剪贴蒙版预览合成

- [x] 按原版 `maskSourceID` 规则，将来源图层的透明度、不透明度、自身像素蒙版和上游剪贴关系合成为覆盖；来源图层即使隐藏，仍可提供覆盖，来源 RGB 颜色不参与。
- [x] 来源图层所在组的不透明度会参与覆盖，来源图层的组蒙版不参与剪贴覆盖；目标图层仍会正常应用自己的像素蒙版和所属组蒙版。
- [x] 覆盖按来源链逐层计算，深度限制为格式规定的 256；复用当前连续使用的来源覆盖，图像和蒙版仍按 2048 像素边长限制解码。
- [x] 扩展蒙版测试项目：隐藏的透明圆形作为来源，黄色图层通过 `maskSourceID` 引用它，预期仍显示半透明黄色圆；用户真机截图确认黄色圆形仍显示。
- [x] 新增 2 项来源链测试，覆盖隐藏来源、上游链接和循环防护；最终 10 项 JVM 单元测试全部通过，0 失败。
- [x] 执行 `clean lintDebug testDebugUnitTest assembleDebug`：构建成功；Lint 0 错误、3 条 SDK/Gradle 版本建议；测试项目 manifest、资产引用和 ZIP CRC 检查通过。
- [x] APK 包名、debug V2 签名、ZIP 结构和 4 字节对齐静态检查通过；最新 APK SHA-256：`73fca1154d8842903469d224ec7a7934758978ad114c512ba1c3570958ed4e1f`。
- [x] 用户在小米 18 Fold 上提供的截图确认隐藏来源仍产生黄色圆形剪贴结果；样例整体合成画面符合预期。本地 JVM 测试检查来源链，不包含逐像素画面验证；设备截图补充了实际视觉验收。

## DEV-07阶段的环境与检查结果（历史记录）

- 仓库根目录 `AGENTS.md` 已阅读；它只描述 Mac 工程，未包含 Android 特别要求。
- `/workspace/Compositor/docs/` 中只有原版项目、性能和参考图片资料；没有 Android PRD/开发计划的 v0.4 副本。
- Git 起始无用户改动。Android 文件和本文档是本次新建内容。
- 当前状态：独立工程、只读 `.comp` 检查、虚拟化图层列表、单层原始像素预览、多图层有限合成、像素/组蒙版与剪贴蒙版合成，以及折叠屏横竖屏排版和转屏状态恢复已实现。用户在小米 18 Fold 手动验收了样例画面、图层点选和转屏恢复；最新截图中的红色渐隐、绿色组蒙版和隐藏来源的黄色圆形均符合预期。最新标准干净构建成功，Lint 0 错误、3 条 SDK/Gradle 版本建议，10 项 JVM 单元测试通过；最新 Debug APK 静态检查通过（SHA-256：`73fca1154d8842903469d224ec7a7934758978ad114c512ba1c3570958ed4e1f`）。调整、非普通混合模式、图层效果、绘画、撤销和保存仍未实现或有限；Mac 对照尚未验收。
- 云端无法通过 adb 访问用户的折叠屏；更长图层列表的溢出滚动、触控笔和 Mac 原版对照仍待验收。

## 工具链版本

- JDK：环境预装 OpenJDK 21.0.12；Android Gradle Plugin 的要求以项目锁定的版本为准。
- Android Gradle Plugin：9.4.0。
- Gradle Wrapper：9.6.0。
- Android SDK Platform：36；Build Tools：36.0.0；Platform-Tools：37.0.1。
- Android NDK：27.2.12479018；CMake：3.22.1。FIX-03开始用于ARM64原生库构建。
- SDK 管理器在本机环境初始化时需接受 Android SDK 对应组件的许可；仅安装构建所需的 SDK，不安装 Android Studio 或模拟器。

## 阻塞与下一步

1. 继续原版功能对齐：扩展混合模式，再补齐调整与图层效果；当前有限预览不能视为完成。
2. 随后实现可编辑画布的平移/缩放和真实触控/触控笔绘画、撤销与安全保存，并按原版组织工具栏与属性面板。
3. 后续加入更长图层列表或新编辑功能时，再在小米 18 Fold 验证溢出滚动、触控和转屏恢复；本轮蒙版样例已由用户真机截图验收。本次只更新记录，尚未开始下一项开发。
4. 在有 Mac 或设备时，用 Mac 导出的 v1、v11 及含组/蒙版/调整/文字数据的项目验证打开、合成和错误提示，并完成原版界面对照。
5. 恢复 v0.4 PRD 与开发计划后，对照第21节和第13–16节补齐正式验收项；若文件在当前仓库仍缺失，以本恢复记录为准但继续标注未对齐。

### 本次已知阻塞

- PRD 第21节和原开发计划第13–16节未随当前仓库检出；本次只能按原版源码及用户明确要求建立第一版清单。
- 当前为云端 Linux 容器；SDK 中有 adb，但 `adb devices -l` 为空，无法远程安装或复测小米 18 Fold。用户已完成布局、转屏和当前蒙版样例的手动验收；更长列表滚动及 Mac 原版对照仍待后续验证。

## FIX-01：失败打开保留当前项目（2026-10-10）

- 基线：本地 `work` 从 `22c7b8d` 快进至最新远端 `origin/work` 提交 `e2ad882`；起始工作区干净。按仓库 `AGENTS.md` 阅读 Android PRD v0.4 与开发计划第13–16节后开始。
- 修改：`MainActivity` 现在把候选项目读取与图层/蒙版PNG解码放在后台预检；只有完整成功后才替换活动项目、项目URI和选中层。读取、解码或内存不足失败时只更新状态提示，保留旧项目、图层选择和画布预览。用户取消文件选择时不进入加载流程。预检请求与画布预览使用独立请求编号，Activity销毁时废弃未完成的打开请求。
- 差异检查：`git diff --check`通过。没有增加或运行测试。
- 构建检查：首次 `./gradlew :app:assembleDebug` 在Gradle Wrapper下载时因直连被拒；代理重试后发现系统只有JRE，且没有Android SDK。临时安装并校验Adoptium JDK 21.0.12.1（SHA-256 `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`）及Google命令行工具（SHA-1 `e025545c62a8e64c7559119566a569fb1dec5f60`），安装API 36、Build Tools 36.0.0和Platform Tools 37.0.1。Gradle使用环境代理和系统Java CA信任库后，完整`:app:assembleDebug`成功，34项任务中32项执行、耗时53秒；加上内存不足处理后再次增量构建成功，34项任务中12项执行、耗时3秒，Kotlin编译通过。没有运行Lint或测试。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；最终SHA-256 `8c7f821ca6f8e1800be7129d2ab85462b4abc854cc559930da50cd1045ceb41e`。文件未提交Git，也未安装到设备。
- 设备检查：后续用户在Fold设备上分别重启应用测试缺失PNG、坏清单、版本999和无法解码PNG，均显示错误提示；另报告在有效项目打开时再选故障项目，旧画布仍显示，符合TC-015。失败后图层交互/选中状态、取消和成功切换尚未确认。云端无连接设备，无法独立复测。
- 下一步：FIX-01的设备拒绝与保留旧画布行为已有用户手动验证；TC-015自动规则检查及图层交互、取消、成功切换仍待验证，然后按FIX-02继续。当前工作区改动未提交。

## FIX-02：最低系统版本与英文UI（2026-10-10）

- 修改：将`minSdk`从26改为PRD默认API31；应用资源、项目校验错误、画布提示和无障碍描述统一为英文，项目文档中的图层名称保留原文。移除仅为API30以下保留的旧WindowInsets分支；按英文语法增加图层/像素层单复数资源。
- 检查：`rg`确认Android主源码/资源无中文UI字串；`git diff --check`通过。`:app:lintDebug`和`:app:assembleDebug`成功；Lint 0错误、3条升级建议（目标API、Gradle、compileSdk）。未运行单元测试。`aapt dump badging`确认最低API31、目标API36。
- 网络：首次Lint依赖解析因Gradle未读取环境代理失败；使用当前环境代理和系统CA信任库重试后成功，未关闭TLS校验。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `3b5a9bd2e1e79c4221aa3cb31b6763e151969f16cc18beb625219b65459c036d`。用户Fold设备尚未复测英文文案长度与新最低API布局。
- 下一步：按计划进入FIX-03；同时补足FIX-01剩余设备交互验证，并请用户安装本次APK检查英文长文案与窗口布局。工作区改动尚未提交。

## FIX-03：编辑模型、像素契约与ARM64核心（2026-10-10）

- 基线：`work`提交`e2ad882`。本次保留先前FIX-01/FIX-02改动和样例文件。
- 模型：新增纯Kotlin `editor-core` Android library 源模块，当前源码不依赖Android API。`EditorDocument`以不可变层列表表示底到顶顺序，支持新增、替换、移除、排序；限制画布4096边长、约1680万像素与10个栅格层，并按画布合成面、源层RGBA面、最大层工作副本和灰度蒙版估算字节。新建/编辑操作先与运行时提供的ResourceBudget比较。
- 坐标/像素：统一窗口、画布和图层源坐标的换算；+x向右、+y向下，顺时针角度、以图层矩形中心旋转，映射支持翻转。核心像素描述为RGBA顺序、8-bit、sRGB、预乘Alpha、顶行优先，并显式携带行步长和有效字节范围。
- NDK：从原Mac源码直接编译`Compositor/Rendering/BrushPixels.c`，经无整图复制的直接ByteBuffer JNI接口暴露透明Alpha边界。添加AGP外部CMake配置，固定NDK 27.2.12479018与CMake 3.22.1，仅构建`arm64-v8a`。本次成功链接并打包；未运行JNI或比较固定输入输出，不能记为像素算法正确性通过。
- 资源：用Android系统空闲内存和阈值、应用memory class、进程PSS的较小余量确定预览工作集；一半继续保留，剩余拆成五份限制合成面、实时蒙版面、源位图、蒙版缓存及瞬时解码。预览最高边仍为2048，内存少时尺寸降低。移除读取器800,000,000像素的固定总量限制；PRD建议范围仍以实际设备资源为准。预算与实际Bitmap/平台峰值尚未在Fold或平板验证，正式GPU与历史预算尚未实现。
- 边界：当前`Activity`/`Views`和Bitmap/Canvas路径仍只读显示项目与蒙版预览。新模型尚未接到可编辑像素存储，正式Compose/GPU画布、绘画、撤销、保存和导出没有实现。
- 检查：`:app:lintDebug`和`:app:assembleDebug`通过；实际任务构建CMake `arm64-v8a`库。APK `libcompositor_editor.so`为ELF AArch64；`aapt`核实minSdk 31、targetSdk 36。Lint 0错误、4条建议（targetSdk、Gradle、compileSdk升级和ARM64-only对应的ChromeOS x86_64提示）。`git diff --check`通过。本次未新增或运行测试；未运行真机/设备、JNI、性能、Mac对照检查。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`；最终SHA-256 `71486033bff6e2fb4732be197260f549f5d04497c963e137e52ea7689ee5145a`。工作区未提交。
- 下一步：FIX-04补齐PNG参考写出路径、参考算法与Canvas的像素比较，并安排用户设备/Mac对照；模型、坐标、预算与JNI检查需单独排期。不能把预算策略或像素输出记为已验收。

## FIX-04：首版混合模式预览与参考像素算法（2026-10-10）

- 用户提供的小米18 Fold截图显示应用重新打开了六层样例，摘要为6层，合成预览绘制4个像素层；截图中的黄圆、组蒙版和红蓝底色符合该样例。这张图早于本次FIX-04 APK，不作为新增混合模式的设备验收。
- 修改：只读合成预览将Normal、Darken、Multiply、Lighten、Screen、Overlay、Difference映射到Android `BlendMode`，混合应用在图层蒙版和图层/组不透明度处理之后；其余格式合法但未支持的混合模式继续跳过并明确提示。单层原始图片预览不变。
- 参考算法：`editor-core`新增 `ReferenceCompositor`，接受带行步长的RGBA8/sRGB/预乘Alpha缓冲，按逐通道混合函数和source-over Alpha公式生成参考像素；`ReferencePngWriter`可将其流式编码为带sRGB标记的PNG。算法约定见 `android/validation/blend-reference-v1.zh-CN.md`。参考写出尚未接入应用导出，也未与Android Canvas或Mac结果逐像素比较；PRD的容差未冻结。
- 手动样例：新增 `android/samples/Compositor-Android-Blend-Demo.comp/`与对应ZIP；800×500画布有8层，含背景及七种模式的半透明圆形，可在设备上逐格比较蓝/黄底色上的输出。
- 后续设备反馈：用户的Fold截图先确认8层混合样例打开成功、七种模式均有预览输出，并指出旧水印遮住底部标题。移除画布水印后，用户新截图确认水印已消失且七个模式标题完整可见；这是视觉手动验收，不代表逐像素一致。
- 后续实现与验证：只读画布新增单指拖动平移、双指缩放和双击复位。用户随后在小米18 Fold确认三种手势均正常；此功能不等于画笔/触控笔输入。
- 检查/产物：移除水印并加入PNG参考写出器后，首次`:app:lintDebug`和`:app:assembleDebug`成功，86个Gradle任务中30个执行、56个最新；新增手势后修正点击可访问性回调。最终`:app:lintDebug`通过（62项任务中10项执行、52项最新；0错误、4条SDK/Gradle/ABI建议），`:app:assembleDebug`通过（56项任务中5项执行、51项最新）；`git diff --check`通过。未新增或运行单元测试；PNG编码内容尚未逐像素解码验证。
- 最新APK：`android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `f3f561b7229e54caec7341ac085a581f5ec885dbdab66156c86d3a9a5fcec860`。
- 下一步：手势基础操作已由用户确认；继续把画布触点映射到文档/活动图层坐标并完成首个分块笔刷事务。参考PNG写出结果、自动像素比较和Android Canvas/Mac基准仍待验证；当前仍为只读预览，保存/导出编辑流程尚未实现。

## INPUT-01：画布缩放焦点坐标映射（2026-10-10）

- 用户在小米18 Fold确认上个Debug APK的拖动、双指缩放、双击复位均正常。本次接续缩放输入映射：`CanvasPreviewView.documentPointAtView()`使用显示矩形和`ViewportTransform`把View局部像素映射到文档像素边界坐标；双指缩放先求焦点下的文档坐标，再调整平移量，使该文档点继续位于双指焦点。焦点落在画布外时沿用原始缩放中心计算。此映射尚未接入绘画指针或活动图层像素。
- 检查：`:app:lintDebug`与`:app:assembleDebug`成功，86项任务中15项执行、71项最新；Lint 0错误、4条SDK/Gradle/ABI建议。`git diff --check`通过。未新增或运行单元测试；新缩放焦点逻辑尚未由Fold复测。
- 视频反馈：用户提供屏幕录像，感觉双指缩放容易被识别为平移。源码检查发现`ScaleGestureDetector.isInProgress`结束后，剩余单指仍会进入`GestureDetector.onScroll`；同时旧缩放代码没有处理缩放比例为1但双指中点移动的情况。
- 修正：检测到第二个触点后将整段触摸序列锁定为多指模式，直到所有指针抬起；剩余单指移动不再意外切回单指平移。缩放每次用上一帧双指中点对应的文档坐标作为锚点，新焦点相对旧焦点的移动继续形成明确的双指平移；仅中点变化、距离不变时也可平移。
- 检查：修正后`:app:lintDebug`与`:app:assembleDebug`通过，86项任务中15项执行、71项最新；Lint 0错误、4条SDK/Gradle/ABI建议。未新增或运行单元测试；本修正版尚未由Fold设备复测。
- 最新APK：`android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `48bae41618a02db7e7a9eac3b73971ba4b2b9b6506ad9252002fe7bd5de31da7`。
- 下一步：请用户复测双指缩放，并在一根手指抬起后移动剩余手指，确认画布保持不动；两根手指同向移动时应作为双指平移。单指拖动仍用于画布平移。然后继续映射活动图层像素输入，并实现可提交/取消的一笔分块修改事务。

### INPUT-01：双指平移与缩放停顿（2026-10-10）

- 用户提供Fold录屏，反馈双指同时缩放和平移时偶尔像“断触”。录像没有显示明确的系统触点取消；代码中的`ScaleGestureDetector`在缩放进行期间会随移动事件持续回调，因此不是回调门限漏掉平移。源码检查确认：当监听器返回`false`时，Android不会推进缩放跨度基准；旧实现只在画面缩放或中点移动后才返回`true`，在无可见变化的事件后可能留下过期跨度。画布同时有48dp最小可见范围约束，碰到边界时平移会停止，这是预期钳制。
- 修正：改为直接从每个`ACTION_MOVE`计算多指中心与跨度并同步更新缩放、平移；增加/抬起指针时重置基准。保留整段手势的多指锁定，因此第二根手指抬起后，剩余单指不会误切到单指平移。该改动移除了依赖缩放检测器跨度基准的状态；边界处的可见范围限制仍保留。
- 检查：`JAVA_HOME=/tmp/compositor-openjdk21`、`ANDROID_HOME=/tmp/compositor-android-sdk`下执行`:app:lintDebug`和`:app:assembleDebug`成功，86项任务中15项执行、71项最新。未新增或运行单元测试；Fold设备实测待用户验证。
- 最新APK：`android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `b0cd25bde19384cf936a6fd8bdaf96a2cc037004072fa461266176210005bcc8`。
- 设备复测结果：用户反馈“双指缩放同时平移断触”问题已解决，作为该问题的Fold手动验收通过；此反馈不代表触控笔或完整设备矩阵均已验证。

### INPUT-01：接近适配尺寸时平滑回中（2026-10-10）

- 用户新录像指出：缩小到适配尺寸时，若画布此前被平移，会突然跳回居中。源码确认`constrainPan()`在缩放达到`MIN_ZOOM`时直接将`panX/panY`清零，正是该跳变来源。
- 修正：平移许可范围随缩放比例靠近`MIN_ZOOM`逐步缩小；限制幅度按适配画布宽/高乘以超出适配比例计算。缩放回到适配尺寸前偏移已收敛到零，适配尺寸仍保持居中；双击复位行为不变。
- 检查：`:app:lintDebug`与`:app:assembleDebug`成功，86项Gradle任务中15项执行、71项最新；`git diff --check`通过。未新增或运行单元测试；Fold真机手感待复测。
- 最新APK：`android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `d5bd60917d81939ce7538dabfa98c8af9f79a60de065952de8e6e2cc01117dc5`。
- 本版“缩放过程中逐步回中”后续按用户偏好改为松手后回中动画，最终交互和验收结果见下一小节。

### INPUT-01：松开后再回中（2026-10-10）

- 用户明确交互要求：双指仍接触时保持当前位置；只有手指全部松开、且缩放已回到适配尺寸时，才做回中动画。此要求取代上一小节“缩放过程中逐步回中”的实现。
- 修正：在多指手势期间，即使缩放触及最小适配比例，也保留当前平移位置；最终`ACTION_UP`/`ACTION_CANCEL`后若缩放为适配尺寸，以220ms减速动画将平移量归零。单指操作在适配尺寸仍不能平移；双击复位保持即时。
- 检查：`:app:lintDebug`和`:app:assembleDebug`成功，86项Gradle任务中15项执行、71项最新；`git diff --check`通过。未新增或运行单元测试。
- 最新APK：`android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `8321cc5edf252ec67e240728bb086d25c782ebe66d7dde3acf40e71cff358e24`。
- 设备复测结果：用户反馈“没问题了”，确认松手后回中符合预期；记为该交互的Fold手动验收通过，不延伸为对双击、触控笔或全部手势组合的完整验收。
- 后续：用户要求先更新开发文档，不开始下一项开发；当前停在本次手势体验验收记录。

## INPUT-01 / BRUSH-01：活动图层坐标映射与首个分块笔画事务（2026-10-10）

- 基线与范围：从`work`/`origin/work`最新提交`230b39b5e450d52ccc353c9d430c7e0e8b6de4e8`继续。先阅读根目录`AGENTS.md`、Android PRD、开发计划、README及本目录验证/历史文档。实现本轮计划中的触点到活动层像素映射及首笔事务，不修改PRD或`.comp`样例。
- 坐标映射：`CanvasPreviewView`把本地View位置映射到当前显示的项目画布像素，再通过`CoordinateMapper.canvasToLayer`反解选中图层的缩放、旋转和翻转，输出源PNG像素坐标。画布视口已经包含当前缩放和平移；视口外和源层边界外的触点不落笔，离开后重新进入会分段，不跨空白连线。
- 分块源读取：新增`LayerEditingSession`和惰性PNG区域读取器，为一个项目图层保留临时编辑状态。需要时用`BitmapRegionDecoder`读取源PNG的原分辨率矩形，默认256×256（图像边缘块可小于该尺寸），转换为RGBA8/sRGB/预乘Alpha。缓存按会话内存预算检查，不提前展开整张源图；该预算与像素格式仍未设备测量。
- 事务与显示：`ChunkedRaster`保存每个访问块的原始/当前像素；`ChunkedBrushStroke`实现固定32px黑色硬笔刷，按源坐标插值补齐线段并对预乘Alpha像素做source-over。完成后返回块级before/after `ChunkedStrokePatch`，支持undo/redo调用，但本轮App未保留patch或提供历史按钮。抬指提交内存块；系统`ACTION_CANCEL`、失焦、视图拆除和图层切换回滚活动笔画；第二触点出现时先提交当前有效笔迹，再将该序列锁为导航，避免连接两指位置。图块叠加在当前层原始预览上；不回写`.comp`，进程死亡后临时改动会丢失，旋转/Activity配置变更保留会话。
- 可用范围：只对可见的像素PNG层开放，且要求非组、非调整层、Normal混合、正不透明度和无图层效果。brush尺寸、颜色、橡皮、锁定层、压感、掌触和真实笔输入都未做；这是第一笔连通性原型，不是完整绘画器。
- 测试：新增`ChunkedBrushStrokeTest`6项与`CoordinateMapperTest`2项；另运行原有app单测10项。最终`:editor-core:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`成功，18项测试失败/错误/跳过均为0；Lint 0错误、4条既有建议（目标API、Gradle、compileSdk、ChromeOS x86_64 ABI）；96项任务中17项执行、79项最新。`git diff --check`通过。最终APK为`android/app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `f67332034095de1e7042703839e44c499fe73ac2d86e9c82f4ab0660f00b0316`。本轮又以四个`COMPOSITOR_DEBUG_*`变量显式指定当前debug keystore重建APK，`apksigner`确认指纹保持为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。此前APK/keystore不在当前工作区，无法本地确认或沿用旧证书。
- 检查过程中的修正：第一次构建发现Android普通`Activity`没有`lastCustomNonConfigurationInstance`/`onRetainCustomNonConfigurationInstance` API，改用平台的`lastNonConfigurationInstance`/`onRetainNonConfigurationInstance`；坐标单测窗口样本期望值修正为对应图层中点；第一次无障碍Lint提出触摸回调可访问性建议，已将`performClick()`点击分发放在触摸监听器内。最终构建另修正了一次监听器lambda参数名错误，最终Lint不再报该项。以上皆非待解决失败。
- 环境：临时Temurin JDK21.0.12.1（SHA-256见本会话环境记录）、Android SDK Platform36/Build Tools36、NDK27.2.12479018、CMake3.22.1、Gradle9.6.0、AGP9.4.0。代理TLS通过`/etc/ssl/certs/java/cacerts`验证。云端adb设备列表为空，无Mac环境；本轮新画笔无Fold/平板实机验证，不记录为TC-004/005/009设备通过。
- 用户文件：当前`/workspace`不存在`Compositor-Android-FIX03.zip`、`Compositor-Android-FIX04.zip`和`Compositor-Android-Preview-Navigation.zip`；未删除、重建或加入源码。没有更新或重发测试`.comp`文件。
- 下一步：Fold上安装当前APK，按PRD的变换层、跨分块连续笔画、系统取消、单指绘画转双指导航检查。完成设备记录后接入HISTORY-01历史栈和预算，再开发保存/重开/导出闭环。

## BRUSH-01跟进：双指缩放起点的笔刷印记（2026-10-10）

- 用户报告双指缩放时第一指碰触位置会出现笔迹。原因是单指`ACTION_DOWN`立即显示brush dab，后续`ACTION_POINTER_DOWN`将这笔提交。
- 修正：第二指进入时静默取消未完成的`ChunkedBrushStroke`事务并刷新受影响图块，然后继续锁定整段多指触摸为导航手势。这样既回滚第一指产生的临时笔迹，也不会显示“笔画已取消”；若第一指在第二指进入前移动过，该未完成笔迹也随事务整体回滚。
- 构建：外置固定debug keystore下`:app:assembleDebug`成功，56项任务中6项执行、50项最新；`apksigner`确认SHA-256证书指纹保持`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。最终APK SHA-256 `475112cb5cd33ab7909b3fbea023a4b409e698a0d63d54dfd89d71c02730d899`。未运行测试或Lint；`git diff --check`通过。
- 验收：当前环境无连接设备，修正尚未在Fold复测；需确认双指捏合起点无残留，且正常单指抬起仍能提交笔画。测试`.comp`没有变化。

- 设备反馈：用户随后在Fold复测并回复“没问题了”，确认双指缩放起点不再留下笔迹。只把该仲裁问题记为设备通过；普通绘画坐标、跨块、取消与笔感仍待测。

## HISTORY-01：分块笔画撤销与重做（2026-10-10）

- 实现：新增`ChunkedStrokeHistory`，保留选中图层会话中已提交的笔刷patch；每次笔画是一条undo记录。最多20条并按before/after RGBA快照像素字节共同限额，预算吃紧时按需淘汰最旧的undo条目。新笔画在undo后成功进入历史时清空redo分支；单个patch不能放入空历史预算时撤销该笔并提示。Undo/Redo更新当前图层块预览，不把导航事件记录入历史。
- UI/生命周期：画布工具行增加Undo/Redo按钮，按当前图层历史状态启用/禁用；切换图层保留各自的历史，Activity配置变化沿用现有会话保留方式。编辑仍仅在内存，不保存到项目；变换/删除和跨层全局历史尚未接入。
- 内存：图层会话给出的编辑额度划分给原/当前分块缓存、历史快照与活动笔画余量；历史最多20条。当前预算按快照载荷字节计算，不含对象元数据或多图层会话的设备级峰值，Fold/4K压力尚未测量。
- 构建：`:app:assembleDebug`成功，56项Gradle任务中13项执行、43项最新；未新增或运行自动行为测试，也未运行Lint。此前18项单测只覆盖映射与底层笔刷事务，不覆盖新增历史栈。20笔往返、历史分支、预算淘汰顺序和峰值需要后续验证。`git diff --check`通过。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `99ee376930e9cc46437c939a0ac0ccbb0974bd651a3672489a74ce88290c27cb`；签名保持使用固定debug证书，SHA-256指纹`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。
- 下一步：先复核历史栈的预算和边界规则，补自动行为检查；Fold验收20笔undo/redo、undo后新画笔清空redo、切层后各层历史隔离及双指导航不产生历史，再进入基础保存链路。

## IO-01 / FILE-01 后续：独立 `.comp.zip` 项目副本（2026-10-10）

- 目的：在直接`.comp`文件夹安全写入与重开设计完成前，提供源目录之外的项目副本，并把当前已提交的分块笔画纳入副本；保留Mac manifest格式和源项目不变。
- 实现：工具行增加“Save Copy (.comp.zip)”，系统`ACTION_CREATE_DOCUMENT`创建外部ZIP。`CompProjectReader.Project`保留源tree URI、包目录名、`images/`所有直接子项元数据和manifest原始字节。`CompProjectArchiveWriter`写成`目录名.comp.zip`，内部根目录为`目录名.comp/`，保留原始manifest字节和所有平铺资源；只替换有已提交笔迹的图层PNG。Undo到原始像素的会话不触发重编码。资源逐流复制，输入缓冲64KiB，检查512MiB单资源上限；拒绝嵌套`images/`目录和含路径分隔符/控制字符的文件名。UI尝试拒绝能由SAF document ID识别的源包内部目标；外部写入失败尽力删除半成品。源文件夹保持只读，不清除历史，也不更改已保存状态。
- 限制：Android reader仍只读`.comp`目录，不支持ZIP导入/解包重开；直接`.comp`目录安全保存、保存状态管理、恢复副本、图片导入和整图导出仍未实现。某些DocumentsProvider使用不透明ID，内部目标判断能力有限；后台打包没有用户取消按钮；嵌套`images/`目录会拒绝写副本。Mac尚未验证该ZIP结构是否可打开。
- 构建检查：固定外置debug keystore下加入归档实现后的`:app:assembleDebug`成功，56项任务中18项执行、38项最新；无障碍文案调整后的最终构建成功，56项任务中10项执行、46项最新。`git diff --check`通过。`apksigner verify --print-certs`确认SHA-256签名指纹保持`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。最终APK SHA-256为`ac125d69ca4185351f0a8ad33813cd0c90f3d6633f5d847e60e69927e0f51816`。
- 未验证：未运行单测或Lint；未打开归档检查central directory/CRC、条目路径、替换层像素或错误恢复；没有`adb`，未在Fold安装；没有Mac环境，未解包/打开；未测试SAF写权限、取消和删除。用户尚未验收本次文件行为。未创建、删除或改动用户提及的旧ZIP及测试`.comp`样例。
- 下一步：Fold检查单层PNG透明边缘、笔画及undo/redo后的像素和源`.comp`不变；用小项目检查ZIP结构/像素/失败清理，在Mac解包打开，再实施ZIP安全导入与重开。

## IO-01 / FILE-01：安全 `.comp.zip` 导入与重开（2026-10-11）

- 实现：项目打开入口可选 `.comp` 文件夹或 `.comp.zip`。导入兼容一个`名称.comp/`目录根，以及PRD规定的直接`manifest.json`和`images/`根。两种入口交给同一`CompProjectReader`做manifest、图层关系、引用文件和PNG头校验；Activity再解码预检全部被引用资源，候选项目通过后才替换当前画布。失败时清理候选缓存并保留原项目；切换时关闭旧编辑会话并清理旧的ZIP提取缓存。
- ZIP检查：归档先复制到应用私有缓存，通过`ZipFile`中心目录读取并用本地中心目录字段检查条目名和Unix/macOS类型位。拒绝多根/混合根、未知路径、绝对或穿越路径、反斜杠、控制字符、重复路径、嵌套`images/`目录、分卷/ZIP64、加密条目、未知压缩方法、Unix符号链接及特殊文件。输入归档≤1GiB，最多20,003项，manifest≤4MiB，资源≤512MiB，总解压≤1GiB；逐项实际解压时核对长度和CRC32。导入只写入私有临时目录，不修改源归档或外部文件夹。
- 生命周期：归档提取缓存随当前Project持有；成功切换项目或Activity结束时清理。旋转时通过非配置实例保留当前Project与临时笔刷会话，避免重新提取导致会话指向失效文件。冷启动会清理此前遗留的导入缓存，恢复工程时再从用户选择的持久URI导入。
- 构建与代码检查：JDK21、Android SDK Platform/Build Tools36、NDK27.2.12479018和CMake3.22.1安装在仓库外后，固定外部debug签名执行`:app:assembleDebug`成功（56项任务中6项执行、50项最新），ARM64 native链接通过。`git diff --check`通过；本轮没有运行单元测试、Lint或ZIP行为检查。`apksigner`核对证书SHA-256为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`，最终APK SHA-256为`db873ed19465cbf8ce8296dcb970b8785b5db246475654dc52f259e7b81ba8ad`。
- 待设备/Mac验证：`adb devices`为空；未安装Fold或模拟器；无Mac环境。未从系统文件选择器打开ZIP，也未实际验证正常项目打开、保存副本后重开、损坏CRC/恶意路径拒绝、失败时保留旧画布、缓存清理和源ZIP未变化。Mac归档兼容、图层像素和SAF持久权限待验证。
- 用户文件：没有修改或发送测试`.comp`样例；用户提到的FIX03、FIX04和Preview-Navigation旧ZIP未添加、删除或重建。
- 下一步：Fold验收归档打开、另存后重开和导入失败保护；自动检查恶意ZIP/CRC与资源上限；Mac解包打开；之后继续整图合成导出和PNG/JPEG导入。

## INSTALL-01：最新 APK 在 Fold 安装扫描后退出（2026-10-11）

- 用户反馈：此前版本可以进入安装界面；最新 `app-debug(16).apk` 点击后显示“安装包扫描中，请稍候”，约数秒后直接返回文件列表。两份录屏附件内容相同。视频中设备显示文件约3.24 MB，与当前本地产物大小相符；未取得设备端文件哈希，不能据此确认字节完全一致。用户随后确认旧版应用已卸载。
- 当前本地产物：`android/app/build/outputs/apk/debug/app-debug.apk`，3,235,288字节，SHA-256 `db873ed19465cbf8ce8296dcb970b8785b5db246475654dc52f259e7b81ba8ad`。此前静态检查确认ZIP完整、APK v2签名有效，包名`com.wonderassembly.compositor`、版本0.1.0、最低API31、目标API36、ARM64。清单和版本配置没有本轮修改；最新APK尚未在设备上安装。
- 新旧APK对比：用户上传的旧`app-debug(15).apk` SHA-256为`ac125d69ca4185351f0a8ad33813cd0c90f3d6633f5d847e60e69927e0f51816`。两包证书指纹相同（`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`），签名方案相同（仅v2），`AndroidManifest.xml`字节完全相同；两包均有18个条目、5个DEX、同一ARM64库路径。旧版已卸载，因此旧应用覆盖更新冲突也不成立。
- 静态完整性：两个APK的ZIP条目CRC均通过；所有DEX均为039格式，头部大小、声明文件长度、SHA-1和Adler-32均自洽；原生库均为AArch64 ELF，APK内数据偏移均满足16KiB对齐。新版的resources、classes3/classes4和原生库字节有变化，属于包内容差异；仅凭离线静态检查未发现容器损坏或签名/清单差异。
- 故障范围：录屏停在MIUI安装包扫描界面，Compositor尚未启动；签名不连续、包名/Manifest差异和旧应用更新冲突已排除。当前没有连接设备或PackageInstaller日志，尚不能判断是MIUI扫描器拒绝、设备端文件与本地产物字节不同，还是新版内容触发其他安装器错误。
- 下一步：获取当前Fold上对最新APK的`adb install`/PackageInstaller错误码或MIUI安全扫描记录，并核对设备文件SHA-256与本地产物是否一致；在取得设备侧原因前不改动已通过对比的签名、Manifest或ZIP布局。


## IO-02：整图PNG/JPEG、图片新项目导入与笔迹合成（2026-10-11）

- 授权与范围：用户要求先暂缓安装扫描退出问题，继续开发，并连续多步后统一提供测试内容。保留INSTALL-01为未解决；故障包另存仓库外`/tmp/compositor-install-repro-db873ed1.apk`，哈希仍为`db873ed19465cbf8ce8296dcb970b8785b5db246475654dc52f259e7b81ba8ad`。未重建/重置工作区，保留已有未提交源码；无测试`.comp`更新，无旧ZIP操作。
- 步骤1：PreviewCompositeRenderer新增fullResolution模式；原画布尺寸、源资产不采样，包含七种混合、图层变换/透明度、像素/组/实时剪贴蒙版和已提交稀疏块。导出预检可见内容，含未支持调整/效果/混合时拒绝，剪贴来源的效果也拒绝。按动态余量为完整位图、来源、蒙版、实时覆盖和saveLayer缓冲预留预算；超预算拒绝，没有输出缩小预览。
- 步骤2：CompositeImageExporter逐行输出透明RGBA8/sRGB PNG；JPEG在同一合成位图后方填白，选择75/90/100质量。Export Canvas通过SAF创建独立目标，拒绝已知源资产或源包内部目标。操作期间禁止编辑/开项目；独立任务保存完成/取消状态，配置变化取消并等待清理再恢复入口。进度、Cancel Export、失败/取消删除半成品以及删除失败提示已接入；导出不清除历史或标为项目已保存。
- 步骤3：ProjectImageImporter复制≤512MiB输入到私有缓存，以实际MIME识别PNG/JPEG并拒绝动画和部分解码。解码前检查项目格式尺寸、像素数和设备预算；ImageDecoder软件解码应用照片方向，目标sRGB，规范化RGBA8 PNG写入私有单层`.comp`（v11/72dpi）。manifest/PNG仍经过项目读取器与资产预检；失败/取消清理候选缓存，只有成功后替换旧项目。生成ZIP副本嵌入规范化图像，不依赖原照片。Cancel Open、未保存笔迹切换警告及打开/导出互斥已加入。
- 步骤4：Composite工具返回带已提交笔迹的有界合成。已改块逐块投影覆盖，无需复制所有编辑快照；精确模式只替换修改块，未修改源像素保持原解码结果。剪贴覆盖也读取当前提交块。成功重开同源文件夹会重建编辑会话，避免沿用不匹配的旧像素。
- 限制：全分辨率Bitmap合成是过渡实现，大图分块管线仍未完成，4K/10层性能未承诺。新图片目前创建新项目，不追加到现有画布；超预算未提供缩小选项。DPI输出元数据、JPEG导出结果预览/连续质量、分享、文件夹安全写回/自动恢复、全部Mac效果仍未实现。单次平台解码/编码不能保证即时中断。SAF不透明ID/URI别名、删除失败和后台/进程死亡需要设备验证。
- 检查：本轮多个阶段`:app:assembleDebug --offline`成功；最终56项任务中14项执行、42项最新，Kotlin/ARM64链接和打包通过。`git diff --check`通过；`apksigner verify --print-certs`通过，证书SHA-256保持`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。本轮未新增或运行单元测试、Lint、PNG/JPEG像素、ZIP行为或仪器测试。构建/签名不等于安装或文件行为通过。
- 产物：`app/build/outputs/apk/debug/Compositor-Android-IO02.apk`（与app-debug.apk同字节），3,283,316字节，SHA-256 `80bfb48365dbd9fdb4537ae0088267e7df8a7ed1cac5f95effb493a31bbf46f6`；APK/build目录不提交。
- 待测：`adb devices`为空，无连接设备，无Mac环境。导入方向/透明度、合成像素/块边界、PNG/JPEG查看器、导出转屏/取消/清理、ZIP保存重开、源文件不变及Mac互通全待验证；使用[合并清单](io-roundtrip-checklist.zh-CN.md)，不重复发送未变更样例。
- 下一步：安装诊断按用户要求暂缓；设备可用后统一记录TC-013/019及文件往返结果。继续现有项目追加图层、新建画布、安全保存/恢复与分块合成，补历史/PNG/ZIP固定数据和Mac证据，不把P0基础链路代码贯通等同完整验收。


## INSTALL-01恢复：IO-02仍无法安装与兼容候选（2026-10-11）

- 反馈：用户确认IO-02仍无法安装，恢复安装诊断；用户暂时不能连接电脑。未得到新PackageInstaller错误码、设备页大小或手机端APK哈希，根因仍未确定。
- 发现：旧15和IO-02的原生库ZIP偏移均16KiB对齐，但ELF LOAD均4KB对齐；这是一项需要补齐的16KB兼容缺陷，不能据此宣称它是Fold回归根因。两包Manifest字节相同、证书相同，重新核查未发现ZIP损坏。
- 修改：NDK开启flexible page sizes；debug同一密钥生成v1/v2/v3；可选`compositor.installerCompatibility=true`压缩并提取native库，默认保持未压缩映射。干净重新生成app打包产物成功（59任务：24执行/17缓存/18最新），工作区源码未重置，编辑功能保持。候选5个DEX和resources.arsc与IO-02完全相同。
- 检查通过：ZIP CRC、APK资源对齐、Manifest解析、实际API范围v3签名及分别核验v1/v2、固定证书指纹、5个DEX的dexdump结构/反汇编、ELF LOAD的16KB对齐和`git diff --check`。未运行编辑单测/Lint，未进行设备安装或JNI运行。
- 产物：`app/build/outputs/apk/debug/Compositor-Android-INSTALL01.apk`，3,089,975字节，SHA-256 `6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5`。证书SHA-256仍为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。原IO-02在忽略的build/install-diagnostics和原下载路径保留；未处理用户三个旧ZIP。
- 状态：兼容候选已生成，安装问题仍未解决。下一步先测能否安装；已请求上传手机端实际失败包核对传输，不重复要求卸载。详细证据与可复现命令见[安装诊断](installation-diagnostics.zh-CN.md)。成功安装后再执行IO-02合并功能清单。

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

## ADB结果复核与文档交接（2026-10-11）

- 从业务源码提交3a768ac接续，用户要求本轮只计划/文档，不修改代码；先前临时两处源码修改已撤回。未交付临时构建产物。
- 本地报告34子项：21 PASS / 3 FAIL / 5 BLOCKED / 5 NOT_RUN；失败拆包外部与内部哈希全部核验。定位导入manifest对象契约、样例根README兼容；独立确认1800×1800图层PNG截断、配置变化时间线，运行中取消未执行。
- 新增[ADB复核与优先修复计划](adb-results-20261011.zh-CN.md)，开发计划14.4优先修复；同步README、对照清单、安装记录与交接入口。成功项保留报告通过，未独立核验的全部成功输出、Mac对照、真实折叠与用户跳过旋转保持待测。
- 本轮无源码改动、新APK或新增功能测试；文档差异/提交范围及远端提交核对后推送work。原始证据、密钥与旧ZIP不提交。
