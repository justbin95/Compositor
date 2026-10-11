# Compositor ADB测试结果

## 测试身份

- 执行日期/时区：
- 执行者：本地Codex；人工配合步骤另注明。
- 源码基线/patch SHA-256/本地是否修改源码：
- APK名称、字节数、SHA-256、证书SHA-256：
- 手机型号、Android/API、系统版本、页大小：
- 屏幕尺寸/density、测试窗口方向/是否折叠内屏：
- SAF提供方、测试专用目录：
- 安装/启动结果与原始错误（如有）：

## 逐项结果

只填写实际执行结果。PASS=满足本子项且有证据；FAIL=行为不符；BLOCKED=环境阻碍；NOT_RUN=未执行。必须拆开每组多个条件，不能一项截图代表整组通过。

| 子项ID | IO-02组 / FR或TC | 操作与输入 | 预期 | 实际 | 状态 | 证据相对路径 |
| --- | --- | --- | --- | --- | --- | --- |
| IMPORT-PNG | 1 / TC-013 | | | | NOT_RUN | |
| IMPORT-EXIF | 1 / TC-013 | | | | NOT_RUN | |
| HISTORY-COMPOSITE | 2 | | | | NOT_RUN | |
| EXPORT-PNG | 3 / TC-013 | | | | NOT_RUN | |
| EXPORT-JPEG-75 | 4 / TC-013 | | | | NOT_RUN | |
| EXPORT-JPEG-90 | 4 / TC-013 | | | | NOT_RUN | |
| EXPORT-JPEG-100 | 4 / TC-013 | | | | NOT_RUN | |
| ZIP-ROUNDTRIP | 5 / TC-012/013 | | | | NOT_RUN | |
| SOURCE-INDEPENDENCE | 5 | | | | NOT_RUN | |
| PICKER-CANCEL | 6 / TC-019 | | | | NOT_RUN | |
| LOAD-CANCEL | 6 / TC-019 | | | | NOT_RUN | |
| EXPORT-CANCEL | 6 / TC-019 | | | | NOT_RUN | |
| LOAD-FAIL-PRESERVE | 7 / TC-015/019 | | | | NOT_RUN | |
| UNSAVED-WARNING | 7 | | | | NOT_RUN | |
| UNSUPPORTED-EXPORT | 8 / TC-013 | | | | NOT_RUN | |
| BUDGET-REJECT | 8 / TC-019 | | | | NOT_RUN | |
| ROTATE-EXPORT | 8 / TC-017/019 | | | | NOT_RUN | |

## 文件/像素检查

- 输入/输出路径与SHA-256：
- 图片尺寸、格式、Alpha统计、方向参考：
- ZIP CRC/条目/manifest/图层像素结果：
- Undo/Redo及保存重开前后像素差异：
- 源文件测试前后哈希：
- 混合/透明边缘对照方法、差异值、尚未冻结的容差：

## FAIL的最小复现（逐个填写）

- 子项ID：
- 前置状态、输入文件及哈希：
- 操作序列（每步有时间或证据）：
- 预期/实际：
- 错误原文、logcat/截图/输出文件路径：
- 是否每次复现、是否有崩溃或ANR：

## 尚待验证

列出人工配合、不同SAF、低存储、Mac、双指/折叠等未覆盖项目及原因。不要只写“全部通过”。

## 回传文件

RESULT.md、results.csv、commands.log、device/、screenshots/、ui/、logs/、fixtures/、exports/；自建输入和实际输出均应附带。上传前检查个人信息；包外另记录结果ZIP SHA-256。
