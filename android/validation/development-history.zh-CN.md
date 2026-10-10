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
