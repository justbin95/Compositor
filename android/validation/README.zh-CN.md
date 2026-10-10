# Android 验证与接续记录

更新日期：2026-10-10。需求以仓库 v0.4 PRD 为准，下一步见[开发计划](../../docs/android-development-plan.zh-CN.md)第14.3～16节。此记录对应接入原文档提交 `77f7e43` 后的只读预览代码；最终提交号通过 `git log -1` 查看。

## 构建与自动检查

- 环境：云端 Linux x86-64、JDK 21、Gradle 9.6.0、AGP 9.4.0、SDK Platform 36、Build Tools 36.0.0。当前没有 NDK 实现或 ARM64 原生构建证据。
- 此前完整命令 `./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug` 已成功。测试 XML 时间为2026-10-10 08:52:31 UTC。
- JVM测试共10项：CanvasPreviewGeometryTest 3项、PreviewLayerOrderPlannerTest 2项、PreviewMaskPlannerTest 3项、PreviewLiveMaskPlannerTest 2项；失败和错误均为0。覆盖几何与图层/蒙版规则规划，尚不覆盖真实Canvas逐像素输出、文件失败保护、绘画或保存。
- Lint报告：0错误、3条SDK/Gradle建议。报告位置为 `app/build/reports/lint-results-debug.xml`；测试报告为 `app/build/test-results/testDebugUnitTest/`。
- 本次文档/Git检查：原PRD保持远端v0.4原文；暂存文件的本地Markdown链接与空白检查通过；没有提交构建目录、APK、工具链、签名密钥或检测到的凭据。Windows Wrapper通过Git属性保留CRLF工作区换行。
- 本次提交前复核未通过工具链依赖准备：Wrapper分发包下载连接被拒绝，改用已有Gradle离线运行后，缓存无法解析AGP9.4.0插件，因此没有重新执行Lint/测试/打包。已有成功报告和APK保留，源码本次未修改。首次Wrapper启动遇到分发包下载连接拒绝，因此改用环境中已有且此前校验过的同版本Gradle执行离线检查；这不是应用编译失败。新环境仍需准备工具链、网络代理和信任配置，不能假定本地缓存可用。

新环境安装对应工具，设置 `JAVA_HOME`、`ANDROID_HOME` 后，从 `android/` 运行：

```sh
./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

当前只有 `app` 模块，不能运行不存在的 `:editor-core:test`。报告、缓存、APK和签名密钥不提交Git。

## APK与样例

- APK位置：`app/build/outputs/apk/debug/app-debug.apk`；应用ID `com.wonderassembly.compositor`，版本0.1.0（versionCode 1），debug签名，当前最低API26，纠偏目标API31。
- 最近交付包SHA-256：`73fca1154d8842903469d224ec7a7934758978ad114c512ba1c3570958ed4e1f`。重新构建的包可能受签名和环境影响，不要求新环境产物具有同一哈希。
- 设备上可手动安装重建的APK。ZIP样例需先解压，再在应用选择含 `manifest.json` 和 `images/` 的 `.comp` 文件夹；应用目前不直接打开ZIP。
- [六层样例](../samples/Compositor-Android-Demo.zip)与[蒙版样例](../samples/Compositor-Android-Mask-Demo.zip)由本仓库按官方格式编写，均不是原作者发布的官方作品。预期画面及操作见[样例说明](../samples/README.zh-CN.md)。

## 用户真机结果与限制

设备名称按用户报告记录为“小米18 Fold”，内屏；未通过adb读取型号、Android版本、实际窗口dp或字体比例。

- 用户截图显示六层项目可打开，图层列表与各单层原始像素预览可显示；修复后的横屏为画布/列表两列，状态栏与导航栏不再覆盖内容。
- 用户明确反馈已手动验证转屏，没有问题：项目保留，不必重新选择文件夹。未记录50轮循环、分屏、折叠、进程结束恢复或长列表滚动完整验收。
- 蒙版样例截图显示红色渐隐、上半组蒙版和黄色剪贴圆，包括隐藏剪贴来源；用户认为没有问题。这仅验证该样例的只读合成，不代表蒙版编辑、历史、保存/重开或Mac逐像素对照通过。
- 截图原件位于本会话附件，未复制进Git；新会话可依赖此文字记录继续，若需要重新测量截图需重新获取原件。
- 当前云端adb无连接设备，未运行仪器测试；没有Mac环境，未运行Mac打开/保存往返、截图或像素对照。

尚缺新建→导入→编辑/绘画→保存→重开→导出基础链路。全部完整FR/TC/MAC验收保持待完成；不能把以上样例反馈升级为整个产品通过。

## 历史

[早期开发记录](development-history.zh-CN.md)保留旧任务编号和检查经过。旧记录中的“PRD缺失”只描述当时尚未获取远端work的检出状态，原PRD现已接入；当前权威状态与纠偏顺序以主计划为准。
