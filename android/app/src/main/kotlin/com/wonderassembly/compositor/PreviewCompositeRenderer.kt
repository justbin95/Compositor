package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import java.util.concurrent.CancellationException

internal enum class PreviewLimitation {
    ADJUSTMENTS,
    BLEND_MODES,
    EFFECTS
}

/** Builds a bounded-resolution, read-only composite from the project's raster layer assets. */
internal class PreviewCompositeRenderer(private val context: Context) {
    data class Result(
        val bitmap: Bitmap,
        val drawnLayerCount: Int,
        val limitations: Set<PreviewLimitation>
    )

    fun render(
        project: CompProjectReader.Project,
        isCancelled: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit
    ): Result {
        val outputScale = minOf(1.0, MAX_OUTPUT_SIDE.toDouble() / maxOf(project.width, project.height))
        val outputWidth = (project.width * outputScale).toInt().coerceAtLeast(1)
        val outputHeight = (project.height * outputScale).toInt().coerceAtLeast(1)
        val composite = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        canvas.scale(outputWidth.toFloat() / project.width, outputHeight.toFloat() / project.height)

        val entries = PreviewLayerOrderPlanner.plan(project.layers.map { layer ->
            PreviewLayerOrderPlanner.Node(layer.id, layer.parentId, layer.visible, layer.group, layer.opacity)
        })
        val layersById = project.layers.associateBy { it.id }
        val effectiveOpacityById = entries.associate { it.node.id to it.effectiveOpacity }
        val candidates = entries.filter { entry ->
            val layer = layersById[entry.node.id]
            entry.effectivelyVisible && entry.effectiveOpacity > 0.0 && !entry.node.group &&
                layer?.adjustment == false && layer.imageFile != null
        }
        val limitations = LinkedHashSet<PreviewLimitation>()
        val maskBitmaps = MaskBitmapCache(context)
        var liveCoverageSourceId: String? = null
        var liveCoverageBitmap: Bitmap? = null
        var processed = 0
        var drawn = 0

        try {
            for (entry in entries) {
                if (isCancelled()) throw CancellationException("预览请求已取消")
                if (!entry.effectivelyVisible || entry.effectiveOpacity <= 0.0) continue
                val layer = layersById[entry.node.id] ?: continue
                if (layer.hasEffects) limitations += PreviewLimitation.EFFECTS
                if (layer.group) continue
                if (layer.adjustment) {
                    limitations += PreviewLimitation.ADJUSTMENTS
                    continue
                }

                val imageFile = layer.imageFile
                if (imageFile == null) continue
                processed++
                if (layer.blendMode != NORMAL_BLEND_MODE) {
                    limitations += PreviewLimitation.BLEND_MODES
                    reportProgress(processed, candidates.size, onProgress)
                    continue
                }
                val uri = project.imageUris[imageFile]
                    ?: throw IllegalArgumentException("图层“${layer.name}”引用的 PNG 不存在。")
                val source = decodePreviewBitmap(uri, project.imageSizes[imageFile])
                try {
                    val liveCoverage = layer.maskSourceId?.let { sourceId ->
                        if (sourceId != liveCoverageSourceId) {
                            liveCoverageBitmap?.recycle()
                            liveCoverageBitmap = null
                            liveCoverageSourceId = null
                            liveCoverageBitmap = renderLiveMaskCoverage(
                                sourceId, project, layersById, effectiveOpacityById,
                                outputWidth, outputHeight, isCancelled, maskBitmaps
                            )
                            liveCoverageSourceId = sourceId
                        }
                        liveCoverageBitmap
                    }
                    drawLayer(
                        canvas, source, layer, entry.effectiveOpacity,
                        PreviewMaskPlanner.forLayer(layer, layersById), liveCoverage, project, maskBitmaps
                    )
                } finally {
                    source.recycle()
                }
                drawn++
                reportProgress(processed, candidates.size, onProgress)
            }
            if (isCancelled()) throw CancellationException("预览请求已取消")
            onProgress(candidates.size, candidates.size)
            return Result(composite, drawn, limitations)
        } catch (error: Throwable) {
            composite.recycle()
            throw error
        } finally {
            liveCoverageBitmap?.recycle()
            maskBitmaps.recycleAll()
        }
    }

    private fun renderLiveMaskCoverage(
        sourceId: String,
        project: CompProjectReader.Project,
        layersById: Map<String, CompProjectReader.Layer>,
        effectiveOpacityById: Map<String, Double>,
        outputWidth: Int,
        outputHeight: Int,
        isCancelled: () -> Boolean,
        maskBitmaps: MaskBitmapCache
    ): Bitmap {
        val coverage = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(coverage)
        val scaleX = outputWidth.toFloat() / project.width
        val scaleY = outputHeight.toFloat() / project.height
        canvas.scale(scaleX, scaleY)
        canvas.drawColor(Color.WHITE)
        val documentBounds = RectF(0f, 0f, project.width.toFloat(), project.height.toFloat())
        val chain = PreviewLiveMaskPlanner.chain(sourceId, layersById)

        try {
            for (sourceLayer in chain) {
                if (isCancelled()) throw CancellationException("蒙版预览请求已取消")
                val imageFile = sourceLayer.imageFile
                val imageUri = imageFile?.let { project.imageUris[it] }
                val imageSize = imageFile?.let { project.imageSizes[it] }
                val image = if (imageFile != null) {
                    decodePreviewBitmap(
                        imageUri ?: throw IllegalArgumentException("剪贴蒙版来源“${sourceLayer.name}”的 PNG 不存在。"),
                        imageSize
                    )
                } else {
                    null
                }
                try {
                    val sourceSave = canvas.saveLayer(documentBounds, alphaCompositePaint())
                    try {
                        if (image != null) {
                            val opacity = effectiveOpacityById[sourceLayer.id] ?: sourceLayer.opacity
                            val paint = bitmapPaint(sourceLayer.transform).apply {
                                alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
                            }
                            drawBitmap(canvas, image, sourceLayer.transform, paint)
                        }
                    } finally {
                        canvas.restoreToCount(sourceSave)
                    }
                } finally {
                    image?.recycle()
                }

                // A clipping source contributes its own raster mask, but folder masks are
                // intentionally excluded from clipping coverage in the native file format.
                if (sourceLayer.maskFile != null && sourceLayer.maskEnabled) {
                    val file = sourceLayer.maskFile
                    val uri = project.maskUris[file]
                        ?: throw IllegalArgumentException("图层蒙版“$file”不存在。")
                    val mask = maskBitmaps.get(file, uri, project.maskSizes[file])
                    val maskTransform = sourceLayer.maskTransform ?: sourceLayer.transform
                    val maskSave = canvas.saveLayer(documentBounds, maskCompositePaint())
                    try {
                        drawBitmap(canvas, mask, maskTransform)
                    } finally {
                        canvas.restoreToCount(maskSave)
                        maskBitmaps.finishLayer()
                    }
                }
            }
            return coverage
        } catch (error: Throwable) {
            coverage.recycle()
            throw error
        }
    }

    private fun drawLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        layer: CompProjectReader.Layer,
        opacity: Double,
        masks: List<PreviewMaskPlanner.Mask>,
        liveCoverage: Bitmap?,
        project: CompProjectReader.Project,
        maskBitmaps: MaskBitmapCache
    ) {
        val documentBounds = RectF(0f, 0f, project.width.toFloat(), project.height.toFloat())
        val bounds = transformedBounds(layer.transform)
        for (mask in masks) bounds.union(transformedBounds(mask.transform))
        if (!bounds.intersect(documentBounds)) return

        val opacityPaint = Paint().apply { alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt() }
        val contentSave = canvas.saveLayer(bounds, opacityPaint)
        try {
            drawBitmap(canvas, bitmap, layer.transform)
            for (mask in masks) {
                val uri = project.maskUris[mask.file]
                    ?: throw IllegalArgumentException("图层蒙版“${mask.file}”不存在。")
                val maskBitmap = maskBitmaps.get(mask.file, uri, project.maskSizes[mask.file])
                val paint = maskDrawPaint(mask.transform)
                val maskSave = canvas.saveLayer(bounds, maskCompositePaint())
                try {
                    drawBitmap(canvas, maskBitmap, mask.transform, paint)
                } finally {
                    canvas.restoreToCount(maskSave)
                }
            }
            if (liveCoverage != null) {
                val liveMaskSave = canvas.saveLayer(bounds, alphaCompositePaint())
                try {
                    drawBitmap(canvas, liveCoverage, documentTransform(project))
                } finally {
                    canvas.restoreToCount(liveMaskSave)
                }
            }
        } finally {
            canvas.restoreToCount(contentSave)
            maskBitmaps.finishLayer()
        }
    }

    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, transform: CompProjectReader.LayerTransform, paint: Paint = bitmapPaint(transform)) {
        val centerX = transform.x + transform.width / 2.0
        val centerY = transform.y + transform.height / 2.0
        val save = canvas.save()
        canvas.translate(centerX.toFloat(), centerY.toFloat())
        canvas.rotate((transform.rotation % 360.0).toFloat())
        canvas.scale(if (transform.flipX) -1f else 1f, if (transform.flipY) -1f else 1f)
        val halfWidth = transform.width / 2.0
        val halfHeight = transform.height / 2.0
        canvas.drawBitmap(
            bitmap,
            null,
            RectF((-halfWidth).toFloat(), (-halfHeight).toFloat(), halfWidth.toFloat(), halfHeight.toFloat()),
            paint
        )
        canvas.restoreToCount(save)
    }

    private fun bitmapPaint(transform: CompProjectReader.LayerTransform) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = transform.sampling != "Nearest"
    }

    private fun maskDrawPaint(transform: CompProjectReader.LayerTransform) = bitmapPaint(transform)

    @Suppress("DEPRECATION")
    private fun maskCompositePaint() = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        colorFilter = MASK_ALPHA_FILTER
    }

    @Suppress("DEPRECATION")
    private fun alphaCompositePaint() = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private fun documentTransform(project: CompProjectReader.Project) = CompProjectReader.LayerTransform(
        x = 0.0,
        y = 0.0,
        width = project.width.toDouble(),
        height = project.height.toDouble(),
        rotation = 0.0,
        flipX = false,
        flipY = false,
        sampling = "High quality"
    )

    private fun transformedBounds(transform: CompProjectReader.LayerTransform): RectF {
        val radians = Math.toRadians(transform.rotation % 360.0)
        val cosine = kotlin.math.abs(kotlin.math.cos(radians))
        val sine = kotlin.math.abs(kotlin.math.sin(radians))
        val halfWidth = (cosine * transform.width + sine * transform.height) / 2.0
        val halfHeight = (sine * transform.width + cosine * transform.height) / 2.0
        val centerX = transform.x + transform.width / 2.0
        val centerY = transform.y + transform.height / 2.0
        return RectF(
            (centerX - halfWidth).toFloat(), (centerY - halfHeight).toFloat(),
            (centerX + halfWidth).toFloat(), (centerY + halfHeight).toFloat()
        )
    }

    private fun decodePreviewBitmap(uri: android.net.Uri, pixelSize: CompProjectReader.PixelSize?): Bitmap {
        requireNotNull(pixelSize) { "项目没有记录这张 PNG 的尺寸。" }
        var sampleSize = 1
        while (maxOf(pixelSize.width, pixelSize.height) / sampleSize > MAX_OUTPUT_SIDE) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalArgumentException("无法解码图层 PNG 文件。")
    }

    private class MaskBitmapCache(
        private val context: Context
    ) {
        private val cached = LinkedHashMap<String, Bitmap>(0, 0.75f, true)
        private val transient = ArrayList<Bitmap>()
        private var cachedBytes = 0L

        fun get(file: String, uri: android.net.Uri, pixelSize: CompProjectReader.PixelSize?): Bitmap {
            cached[file]?.let { return it }
            val bitmap = decodeMaskBitmap(context, uri, pixelSize)
            val bytes = bitmap.allocationByteCount.toLong()
            if (bytes > MAX_MASK_CACHE_BYTES) {
                transient += bitmap
                return bitmap
            }
            while (cachedBytes + bytes > MAX_MASK_CACHE_BYTES && cached.isNotEmpty()) {
                val iterator = cached.entries.iterator()
                val oldest = iterator.next()
                cachedBytes -= oldest.value.allocationByteCount.toLong()
                oldest.value.recycle()
                iterator.remove()
            }
            cached[file] = bitmap
            cachedBytes += bytes
            return bitmap
        }

        fun finishLayer() {
            transient.forEach { if (!it.isRecycled) it.recycle() }
            transient.clear()
        }

        fun recycleAll() {
            finishLayer()
            cached.values.forEach { if (!it.isRecycled) it.recycle() }
            cached.clear()
            cachedBytes = 0L
        }

        private fun decodeMaskBitmap(
            context: Context,
            uri: android.net.Uri,
            pixelSize: CompProjectReader.PixelSize?
        ): Bitmap {
            requireNotNull(pixelSize) { "项目没有记录蒙版 PNG 的尺寸。" }
            var sampleSize = 1
            while (maxOf(pixelSize.width, pixelSize.height) / sampleSize > MAX_OUTPUT_SIDE) sampleSize *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            return context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IllegalArgumentException("无法解码项目蒙版 PNG 文件。")
        }

        companion object {
            private const val MAX_MASK_CACHE_BYTES = 16L * 1024 * 1024
        }
    }

    private fun reportProgress(completed: Int, total: Int, onProgress: (Int, Int) -> Unit) {
        if (completed == total || completed % PROGRESS_INTERVAL == 0) onProgress(completed, total)
    }

    companion object {
        private const val MAX_OUTPUT_SIDE = 2048
        private const val PROGRESS_INTERVAL = 24
        private const val NORMAL_BLEND_MODE = "Normal"
        private val MASK_ALPHA_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f
        )))
    }
}
