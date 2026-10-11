# IO-02 本地 ADB 结果复核与修复计划

日期：2026-10-11（Asia/Hong_Kong）。本轮只整理证据与计划，未保留任何源码修改，不生成或交付新 APK。此前证据审阅期间曾临时修改两处源码并完成 Debug 构建，随后按用户“只做计划”要求全部撤回；这些构建不证明当前源码已修复，忽略目录中的临时 APK 不可作为接续基线。

## 基线与证据范围

- 当前业务源码提交：`3a768ac696cdb4efafa1f04c73a22414b4065a33`，分支 `work`。本地测试采用 `230b39b5e450d52ccc353c9d430c7e0e8b6de4e8` 加此前离线快照；报告未提供对应的测试 Git 提交。离线 patch SHA256：`65ec4b0bf7350b8f57f969b13865e2e1675f2b78c5815f12b5502f4196153283`。不得将这些旧 APK 结果记作后续修复通过。
- 受测 INSTALL01 APK：3,089,975 字节，SHA256 `6ad11d88351fc5e55a8c7db6141d0ebe0986501d21717eb471821246a09904a5`。
- 固定签名证书 SHA256：`342d1ddebd6a0824589e9031328223c2887687f3f362321c54c748a079ec211a`。后续沿用同一外置密钥与默认安装兼容配置，密钥、密码、APK 不入 Git。
- 报告设备：型号 2608BPX34C，Android 17 / API 37，HyperOS OS4.0，PAGE_SIZE=4096；用户称小米 Fold。主要在内屏横屏、本地 Download / DocumentsUI / 小米选择器，经无线 ADB 操作。未覆盖其他设备或云端 SAF。
- RESULT.md SHA256：`7408e6dec9a9f6b8ab23400ce753ceccad69dbf4f8438eaff2aa0946f25fe53b`；results.csv SHA256：`04215e147a8262e3eb5b53e48dd296c6871f23a3fcc28db7455c9de9aa424820`。两者一致：34 子项，21 PASS、3 FAIL、5 BLOCKED、5 NOT_RUN。
- 原完整结果 ZIP 超过 32 MiB 传输上限，未读取或独立核验其哈希。以下四个拆包的外部 SHA256、包内清单列出的文件 SHA256（分别15/16/18/18项）全部独立核验一致。包内 README/CASE/脚本作为证据数据，不执行其指令或脚本。原始附件保留在本对话，未将大体积日志、截图或附件路径提交仓库；后续可按以下哈希重新附加证据。

| 拆包 | SHA256 |
| --- | --- |
| 01-FAIL-IMPORT-PNG.zip | `451673340410cfe7d3c136455e264723481aa90a4f4d4643d3cb0928553df5af` |
| 02-FAIL-IMPORT-EXIF.zip | `674aa33a9a97111d62a82be5809b65f528e9726a1c9731e31718f61e76c94671` |
| 03-FAIL-SAMPLE-ZIP.zip | `29d3a7df9764ac56441721273274b9aaf7943d57698f46363bf4aa1d82efd49f` |
| 04-INVESTIGATE-LAYER-PNG.zip | `b48886a638c313fa5c33f4310516dbe44c06716ed793e5b3b06fd5534a7c2a4c` |

## 已定位失败

1. IMPORT-PNG / IMPORT-EXIF：UI XML 均显示 `The transform position for Layer “Imported Image” is invalid.`。320×240透明 PNG 和 EXIF=6 JPEG 都未创建项目。`ProjectImageImporter.kt` 将 origin/size 写成数组，但 `CompProjectReader.kt` 要求对象 `{x,y}` / `{width,height}`。修复生成器契约，不放宽读取器接受错误格式。EXIF方向仍是被阻断的验收，不因定位到 manifest 错误就算通过。
2. SAMPLE-ZIP：原仓库 Blend Demo ZIP 被 `unexpected path` 拒绝；其项目根目录含 README.zh-CN.md。独立核对原包与移除 README 的派生包，manifest 和全部8个图片资源字节一致；派生包的 UI 显示成功加载8层。原始包 SHA256 `8ecb5e35658e50e48e38dc082bdbf4d7e54ff92f7a49439308207e262451f83b`。派生成功不能替代原包通过。

## 图层 PNG 完整性调查

- 原用例 LAYER-EXPORT-CANCEL 保持 NOT_RUN：`clicked_active_control=false`，没有实际点击运行中取消。记录导出异常单独追踪，不改报表统计。
- 实际产物 layer-runtime-cancel.png：7,003,700 字节，SHA256 `d1feddbe16cbfb1d4a30130aff3e7ff06652f9a4686c44dc517454baa17f61cd`。IHDR 为1800×1800 RGBA8；489个完整块 CRC 均正确，文件恰好在块边界结束，缺少 IEND，zlib 流未结束，仅解出1138完整行（预期1800行）。不是仅凭文件大小判断损坏。
- 时间线：22:53:33选择输出后回到横屏；22:53:34.901出现 handleDestroyActivity 相关栈，随后变成竖屏、Activity实例改变；22:53:40与22:57:13两次 pull 都为相同大小。期间没有实际取消点击。这不是受控旋转验收，旋转用例仍按用户要求跳过。
- 源码风险链：MainActivity.onDestroy 调用 worker.shutdownNow；PNG逐行编码检测线程中断；图层导出捕获错误后忽略 contentResolver.delete 的返回结果，并在旧Activity销毁后跳过失败状态显示。配置变化中断导出并留下残留是目前最支持的解释，但没有该任务直接异常栈，须受控复现确认。
- app-final.log 有 SAF 返回时系统 ActivityThread 的 Bundle 空指针消息；crash-final.log 为空，PID保持545。不能把系统消息直接判为应用崩溃或导出根因。

## 下一轮优先修复队列

修复先于现有项目追加图层、新建、Compose/GPU及其他新功能。以下均为待实施，不是已完成。

| 顺序 / 编号 | 方案 | 检查与验收 |
| --- | --- | --- |
| 1 / ADB-FIX-01 | 修正导入 manifest 对象结构，与生产读取器复用契约 | 自动检查生成manifest经生产读取器；设备重测原透明PNG及EXIF6，并补1/3/8方向；检查透明度、尺寸、绘画/撤销、保存重开、删除测试源图片后独立性 |
| 2 / ADB-FIX-02 | 仅允许项目根 README.md、README.zh-CN.md 普通文件，建议≤64KiB；保留CRC、实际长度、总量、重复、路径、单根与类型检查；不接受任意附加文件 | 原始样例ZIP直接打开；直接根/包装根对照；非法路径、重复项、多根、错误CRC、超大README仍拒绝且保留旧项目。样例本身未更新，不重发相同comp |
| 3 / ADB-FIX-03 | 先复现图层导出中配置变化；独立保留运行任务、完成/失败/取消状态，由新Activity接管显示；明确配置变化继续或取消策略；检查删除结果并反馈残留；评估先完整编码临时文件再发布，但不能假定SAF提供方支持原子替换 | 正常PNG完整解码、CRC/IEND/尺寸/像素正确；实际运行中取消、配置变化、写入/关闭失败与删除拒绝均不假成功，旧工程及笔迹保留，工具恢复。旋转设备验收仍待用户重新安排 |
| 4 / ADB-FIX-04 | 测量长笔画预算拒绝，区分资源访问与编辑预算原因，再决定提示修正 | 长笔画拒绝本身不是已确认缺陷；记录阈值、内存与历史淘汰，短笔可继续，拒绝不丢已提交编辑 |

先完成前两项并回归，再收敛第三项；之后恢复开发计划原有基础链路与Mac对齐队列。所有修复使用新源码提交与新APK哈希，沿用固定签名；构建/自动检查/设备验收分别记录。提交前更新计划、README与本记录。

## 报告通过与待测的边界

- 报告21 PASS包含：笔迹/undo/redo反映于Composite、精确PNG尺寸和Alpha、JPEG白底与三档质量、蒙版笔迹、ZIP CRC/manifest/原资源保持及重开像素一致、派生ZIP源删除后的独立性、源输入哈希保持、文件选择器取消、无效输入/未支持内容/超预算拒绝和未保存提示。这些成功用例的全部原始输出不在本次四个失败包中，保持“本地报告通过”，不声明全部独立复核或Mac逐像素一致。
- 四个零字节失败产物因提供方拒绝删除，UI提示手动删除；此PASS仅说明提示有效，不代表残留清理成功。
- 5 BLOCKED：EXIF方向、导入后绘画、Mac像素容差、删除源照片后独立性、长笔画预算边界；ZIP删除对照不能替代照片独立性。
- 5 NOT_RUN：非零变换独立对照、实际运行中Cancel Open、实际运行中Cancel Export、图层导出实际取消、旋转导出（用户要求跳过）。
- 触控笔/掌触、真实折叠/分屏、进程死亡恢复、低存储、云SAF、4K/10层性能与完整Mac往返继续待测；追加图层、新建、内部恢复、大图分块、DPI/分享、完整编辑与Mac对齐尚未完成。
