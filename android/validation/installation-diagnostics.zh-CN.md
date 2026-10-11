# INSTALL-01 安装诊断与兼容候选包

更新：2026-10-11。用户确认IO-02仍无法安装，恢复安装排查；用户暂时无法连接电脑。MIUI扫描后返回文件列表的原因**尚未确定**，本地检查不等于设备安装通过。用户随后明确反馈INSTALL01“可以安装了”，该包安装结果现记为用户设备通过。

## 已知证据

| 包 | SHA-256 | 设备结果 |
| --- | --- | --- |
| 用户旧版app-debug(15).apk | `ac125d69ca4185351f0a8ad33813cd0c90f3d6633f5d847e60e69927e0f51816` | 用户报告此前可以安装 |
| ZIP导入版app-debug(16).apk | `db873ed19465cbf8ce8296dcb970b8785b5db246475654dc52f259e7b81ba8ad` | 用户录屏显示MIUI扫描后退出 |
| Compositor-Android-IO02.apk | `80bfb48365dbd9fdb4537ae0088267e7df8a7ed1cac5f95effb493a31bbf46f6` | 用户确认仍无法安装 |
| Compositor-Android-INSTALL01.apk | `6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5` | 用户确认可以安装 |

- 旧15与IO-02：证书指纹相同、Manifest字节相同、minSdk31/targetSdk36/ARM64；ZIP CRC检查通过。旧版已卸载，不再假定覆盖安装签名冲突。
- 两者APK中原生库的ZIP数据偏移符合16KiB对齐，但ELF的三个LOAD段均只有`p_align=0x1000`。此前“APK对齐通过”只检查ZIP布局，不代表ELF已支持16KB页。旧版同样为4KB，因此这个缺陷不能单独解释本次回归；设备页大小尚未取得。
- IO-02的`aapt2 dump badging`、`apkanalyzer manifest print`、v2签名与`zipalign -c -P 16 4`通过。当前无设备/模拟器，不能运行PackageManager实际安装。手机端失败包仍待上传，尚未排除传输或提供方路径问题。

## 本轮修改

- NDK27增加`ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`，重新链接原生库；候选包实际三个LOAD段`p_align=0x4000`，偏移与虚拟地址模16KB一致。
- Debug使用同一固定外部密钥，同时生成v1、v2、v3签名，v4关闭。证书SHA-256仍为`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。这是签名方案兼容调整，没有更换证书，也不是已确认旧v2签名失效。
- 添加可选Gradle参数`-Pcompositor.installerCompatibility=true`。此参数把原生库压缩进APK并由安装器提取（`extractNativeLibs=true`）；默认仍直接映射未压缩库。参数只用于诊断安装布局/扫描器兼容性，不修改包名、系统版本、权限或编辑功能。
- 执行`:app:clean :app:assembleDebug --offline -Pcompositor.installerCompatibility=true`，清理并重新生成应用打包产物；59项任务中24执行、17缓存、18最新。缓存复用了相同业务编译结果；不是重置仓库。此前IO-02保存到忽略的`android/build/install-diagnostics/`，并恢复其原下载路径；原复现包继续保留在仓库外。用户旧ZIP未处理。

## 实际检查

- 候选包3,089,975字节，SHA-256见上表。5个DEX和`resources.arsc`与IO-02逐字节相同；没有回退新增编辑功能。
- ZIP所有条目CRC通过；Manifest正常解析，包名/版本/API范围保持一致，只有原生提取方式变化；ARM64库为ZIP DEFLATE（method8）。ZIP资源对齐通过。
- `apksigner verify --verbose --print-certs`针对实际API范围通过v3。额外分别指定SDK23与24–27验证v1和v2通过；这些检查只是单独检验签名方案，不宣称应用支持API31以下。签名块包含v2/v3 ID，JAR签名条目齐全。旧SDK范围校验提示Gradle META-INF元数据不受JAR签名保护、旧校验器不认识v3 ID，这是附带提示；实际minSdk31范围无该错误。
- `dexdump -d`对5个DEX的结构/反汇编验证均退出0；没有禁用校验。ELF LOAD对齐检查通过。`git diff --check`通过。
- 未运行编辑器单测/Lint、设备安装、原生运行或功能回归。静态DEX校验不等于ART实际加载通过。

## 设备下一步

先只验证候选APK能否进入安装界面并完成安装，成功后再执行IO-02合并功能清单。当前无法使用电脑，已请求用户上传手机端实际失败的IO-02 APK，核对大小/SHA-256、ZIP、签名以检查传输；不要求重复卸载。若候选仍失败，需要安装器错误码/安全扫描记录或进一步定位手机文件提供方行为，不能宣称上述兼容措施已修复根因。

## 用户安装确认与后续默认配置

2026-10-11，用户确认INSTALL01“这个版本可以安装了”。只将这个包的设备安装记为通过；应用启动、原生运行、导入/保存/导出与其他设备安装仍待对应验证。由于此次同时调整打包、签名方案和ELF布局，没有逐项实验或安装器日志，不能把成功归因于其中某一项。

后续默认采用成功组合：压缩native/安装提取、16KB ELF LOAD对齐、固定证书v1/v2/v3。`compositor.installerCompatibility`默认改为true，普通assembleDebug不需额外参数；false仅保留为未验收的直接加载诊断开关，不作为后续默认发包方式。原失败包留存，不再要求用户为此次安装成功重复上传或取日志。继续按IO-02合并清单验收功能。

- 默认配置复核：未传installerCompatibility参数的`:app:assembleDebug --offline`成功（56任务：2执行/54最新）；app-debug.apk与用户成功安装的INSTALL01哈希完全相同，原生ZIP method8，签名证书一致；`git diff --check`通过。未新增/运行功能测试，不重复发同字节APK。
