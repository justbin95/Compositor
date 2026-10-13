# GROUP14 开发接续（2026-10-13）

## 当前基线与授权

GROUP13业务源码为`892eebfef4e0c787956f877cc09155f5eb944b1d`，阶段文档提交为`ac8913f876dd06eec2bc1602bdba70e095150b5b`。接续整理前工作区干净，以上提交已核对GitHub的`work`分支。远端为`https://github.com/justbin95/Compositor.git`。接续整理的提交以`git log -1`为准。

用户要求提交代码与文档后，在同一Compositor项目新建普通本地对话继续开发，使用`gpt-6.1-sol`、`medium`推理。沿用`C:\Users\ASUS\Documents\Compositor`和`work`分支；不fork、不新建分支、不创建工作树。下一对话直接实施，不再询问是否开始。

真机测试继续暂缓，设备旋转保持NOT_RUN。模拟器仅讨论过，未安装/运行；接续开发不进行模拟器安装、ADB或设备操作。没有Mac运行环境时继续独立实现，保留Mac对照待验收。最终目标仍是固定Mac版本的功能/UI对齐以及平板、折叠内屏适配，P0不是终点。

## 开始前读取

- 根目录`AGENTS.md`、[Android PRD](../../docs/android-prd.zh-CN.md)第21节、[开发计划](../../docs/android-development-plan.zh-CN.md)第13–16节及当前15.0快照。
- [GROUP13实际记录](group13-20261013.zh-CN.md)、[待测清单](group13-device-checklist.zh-CN.md)、[HANDLES11记录](handles11-20261013.zh-CN.md)。
- 当前Git状态与目标目录指令；保留用户改动，只修改Android实现及相关开发文档，Mac源码作为只读参照。

## 下一小步：组画布变换框与手柄

1. 复核`GroupTransformPlan`、`ProjectLayerTransformEditor`、`CanvasTransformGesture`及MainActivity的预览/事务入口，对照固定Mac的组框与成员规则。沿用有效可见像素成员、嵌套/隐藏保护、仿射投影与蒙版放置，避免重复实现数值变换。
2. 为选中组接入画布框、移动/缩放/旋转手柄和比例锁。先解决组预览与文档坐标映射，明确预览包含的成员、已提交笔迹和蒙版；不将仅绘制外框宣称为完整组实时预览，不改变源素材或临时写入工程清单。
3. 拖动使用草稿事务，抬指一次整组提交、一条历史；取消、第二触点、失焦、尺寸/模式变化和视图拆除回滚草稿。提交复用GROUP13整组预校验及历史/恢复资源保护；无变化不增加历史，预算拒绝保留项目及Redo，草稿不进入保存/恢复。
4. 增加有意义的几何、组可见性、草稿取消、整组历史和生命周期检查。验证实际预览及提交的一致性，记录预算/复杂层边界；未完成项如实保持未完成。
5. 更新计划15–16节、阶段实际验证和设备待测清单，提交并推送现有`work`分支。之后按队列推进多层选择/变换、独立蒙版编辑及其余Mac对齐项目。

GROUP13“无效成员整组拒绝”与Mac“跳过无效成员”的差异仍待验收；单层数值角度限制、文字/形状专用重绘和复杂效果语义未关闭。不可把这些差异默认为已获接受的平台例外。

## 验证边界与命令

GROUP13已有104项JVM（app83/core21），失败/错误/跳过均0；Lint0错误/6条既有建议，ARM64应用及仪器APK构建通过。包身份、签名、静态检查和测试替身边界见GROUP13记录。以上只覆盖该代码版本，不能继承为GROUP14通过。

本轮仅更新接续文档，未重新运行功能测试、Lint或构建；检查文档链接、Git空白及提交同步。下一轮代码修改后按实际范围执行：

```powershell
.\android\scripts\build-local.ps1 -Tasks ':app:testDebugUnitTest',':editor-core:testDebugUnitTest',':app:lintDebug',':app:assembleDebug',':app:assembleDebugAndroidTest'
```

仪器APK编译不代表仪器测试运行；Windows/Robolectric不能替代Android输入、SAF、目录同步/进程死亡、原生内存/性能或Mac对照。SDK、构建缓存、APK和外置签名材料不提交Git；不暴露密钥或改变设备作品数据。
