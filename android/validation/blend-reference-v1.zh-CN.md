# Android 混合模式像素参考 v1

日期：2026-10-10
实现：`editor-core/.../ReferenceCompositor.kt`
状态：参考混合和PNG写出算法已实现；设备、Metal/Core Image 与像素往返对照待完成。

## 像素约定

- 输入和输出为顶行优先、带行步长的 RGBA8、sRGB、预乘 Alpha。
- 混合函数在 sRGB 通道值上计算；不会在混合前后转换到线性光空间。
- 图层不透明度乘到源 Alpha 上。透明源像素的非预乘颜色按 0 处理。
- 输出颜色写为预乘值，使用最近整数舍入量化到 8 bit，并限制在输出 Alpha 以内。
- 参考入口支持 Normal、Darken、Multiply、Lighten、Screen、Overlay、Difference；其他原版模式暂不进入首版参考算法。

设源图层非预乘颜色为 `Cs`、底图颜色为 `Cb`，源 Alpha（含图层不透明度）为 `As`，底图 Alpha 为 `Ab`，模式函数为 `B(Cs, Cb)`：

```text
Ao = As + Ab × (1 − As)
Co = As × (1 − Ab) × Cs + As × Ab × B(Cs, Cb) + (1 − As) × Ab × Cb
```

其中 `Co` 是量化前的预乘输出颜色。Normal 的 `B(Cs, Cb) = Cs`；其余逐通道函数为：

| 模式 | `B(Cs, Cb)` |
| --- | --- |
| Darken | `min(Cs, Cb)` |
| Multiply | `Cs × Cb` |
| Lighten | `max(Cs, Cb)` |
| Screen | `Cs + Cb − Cs × Cb` |
| Overlay | `Cb ≤ 0.5 ? 2 × Cs × Cb : 1 − 2 × (1 − Cs) × (1 − Cb)` |
| Difference | `abs(Cb − Cs)` |

## 当前边界

Android 只读预览将七种模式映射到系统 `BlendMode`；其他格式合法的模式会保留在项目中并在预览状态提示跳过。`ReferencePngWriter`将行步长像素流式编码成带 sRGB 标记的 RGBA PNG，不关闭调用方的输出流。预乘颜色在写入PNG时反预乘，Alpha为0时RGB写0。该参考写出器尚未连接到 `.comp` 文件保存或应用导出，也未与Mac、Canvas做像素往返比较。PRD要求的混合容差尚未冻结；待与Mac和设备Canvas结果比较后确定。

手动素材：`android/samples/Compositor-Android-Blend-Demo.comp`。同一粉色半透明圆形分别覆盖蓝、黄两种底色，每格对应一种首版模式。
