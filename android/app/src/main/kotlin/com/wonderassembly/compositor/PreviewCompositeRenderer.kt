package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
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
internal class PreviewCompositeRenderer(private val context: Context,
    private val captureBudget: () -> AndroidRasterBudget = { AndroidRasterBudget.capture(context) },
    private val sourceRegionDecoder: ((android.net.Uri, CompProjectReader.PixelSize, android.graphics.Rect, BitmapFactory.Options) -> Bitmap)? = null) {
    private data class Crop(val bounds: android.graphics.Rect, val width: Int, val height: Int)
    private val crops = java.util.IdentityHashMap<Bitmap, Crop>()
    private var exactRasterRegion: android.graphics.Rect? = null
    private var renderCancelled: () -> Boolean = { false }
    private var exportRegion: android.graphics.Rect? = null
    data class Result(
        val bitmap: Bitmap,
        val drawnLayerCount: Int,
        val limitations: Set<PreviewLimitation>
    )

    fun render(
        project: CompProjectReader.Project,
        isCancelled: () -> Boolean,
        onProgress: (completed: Int, total: Int) -> Unit,
        fullResolution: Boolean = false,
        editedAssets: Map<String, LayerEditingSession> = emptyMap(),
        region: android.graphics.Rect? = null
    ): Result {
        require(region == null || fullResolution)
        require(region == null || region.left >= 0 && region.top >= 0 && region.right <= project.width &&
            region.bottom <= project.height && region.width() > 0 && region.height() > 0)
        exportRegion = region
        exactRasterRegion = if (fullResolution) region ?: android.graphics.Rect(0, 0, project.width, project.height) else null
        renderCancelled = isCancelled
        crops.clear()
        val capturedBudget = captureBudget()
        // Reserve room for the output, clipping coverage, source, masks and nested saveLayer buffers.
        val rasterBudget = if (fullResolution) capturedBudget.copy(
            maxSingleBitmapBytes = capturedBudget.maxSingleBitmapBytes / 2,
            maxMaskCacheBytes = capturedBudget.maxMaskCacheBytes / 2
        ) else capturedBudget
        if (fullResolution) validateExport(project, rasterBudget)
        val outputScale = if (fullResolution) 1.0 else minOf(1.0, rasterBudget.maxPreviewSide.toDouble() / maxOf(project.width, project.height))
        val outputWidth = region?.width() ?: (project.width * outputScale).toInt().coerceAtLeast(1)
        val outputHeight = region?.height() ?: (project.height * outputScale).toInt().coerceAtLeast(1)
        val composite = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        if (region == null) canvas.scale(outputWidth.toFloat() / project.width, outputHeight.toFloat() / project.height)
        else canvas.translate(-region.left.toFloat(), -region.top.toFloat())

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
        val maskBitmaps = MaskBitmapCache(context, rasterBudget, fullResolution) { uri, size, transform ->
            decodePreviewBitmap(uri, size, rasterBudget, true, transform = transform)
        }
        var liveCoverageSourceId: String? = null
        var liveCoverageBitmap: Bitmap? = null
        var processed = 0
        var drawn = 0

        try {
            for (entry in entries) {
                if (isCancelled()) throw CancellationException("Preview request was cancelled")
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
                val blendMode = canvasBlendMode(layer.blendMode)
                if (blendMode == null) {
                    limitations += PreviewLimitation.BLEND_MODES
                    reportProgress(processed, candidates.size, onProgress)
                    continue
                }
                val uri = project.imageUris[imageFile]
                    ?: throw IllegalArgumentException("The PNG for layer “${layer.name}” does not exist.")
                val source = decodePreviewBitmap(uri, project.imageSizes[imageFile], rasterBudget, fullResolution, editedAssets[imageFile], isCancelled, layer.transform)
                try {
                    val liveCoverage = layer.maskSourceId?.let { sourceId ->
                        if (sourceId != liveCoverageSourceId) {
                            liveCoverageBitmap?.recycle()
                            liveCoverageBitmap = null
                            liveCoverageSourceId = null
                            liveCoverageBitmap = renderLiveMaskCoverage(
                                sourceId, project, layersById, effectiveOpacityById,
                                outputWidth, outputHeight, isCancelled, maskBitmaps, rasterBudget,
                                fullResolution, editedAssets
                            )
                            liveCoverageSourceId = sourceId
                        }
                        liveCoverageBitmap
                    }
                    drawLayer(
                        canvas, source, layer, entry.effectiveOpacity,
                        PreviewMaskPlanner.forLayer(layer, layersById), liveCoverage, project, maskBitmaps,
                        blendMode
                    )
                } finally {
                    source.recycle()
                }
                drawn++
                reportProgress(processed, candidates.size, onProgress)
            }
            if (isCancelled()) throw CancellationException("Preview request was cancelled")
            onProgress(candidates.size, candidates.size)
            return Result(composite, drawn, limitations)
        } catch (error: Throwable) {
            composite.recycle()
            throw error
        } finally {
            liveCoverageBitmap?.recycle()
            maskBitmaps.recycleAll()
            crops.clear()
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
        maskBitmaps: MaskBitmapCache,
        rasterBudget: AndroidRasterBudget,
        fullResolution: Boolean,
        editedAssets: Map<String, LayerEditingSession>
    ): Bitmap {
        val coverage = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(coverage)
        val scaleX = if (exportRegion != null) 1f else outputWidth.toFloat() / project.width
        val scaleY = if (exportRegion != null) 1f else outputHeight.toFloat() / project.height
        canvas.scale(scaleX, scaleY)
        exportRegion?.let { canvas.translate(-it.left.toFloat(), -it.top.toFloat()) }
        canvas.drawColor(Color.WHITE)
        val documentBounds = RectF(0f, 0f, project.width.toFloat(), project.height.toFloat())
        val chain = PreviewLiveMaskPlanner.chain(sourceId, layersById)

        try {
            for (sourceLayer in chain) {
                if (isCancelled()) throw CancellationException("Mask preview request was cancelled")
                val imageFile = sourceLayer.imageFile
                val imageUri = imageFile?.let { project.imageUris[it] }
                val imageSize = imageFile?.let { project.imageSizes[it] }
                val image = if (imageFile != null) {
                    decodePreviewBitmap(
                        imageUri ?: throw IllegalArgumentException("The PNG for clipping mask source “${sourceLayer.name}” does not exist."),
                        imageSize, rasterBudget, fullResolution, editedAssets[imageFile], isCancelled, sourceLayer.transform
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
                        ?: throw IllegalArgumentException("Layer mask “$file” does not exist.")
                    val maskTransform = sourceLayer.maskTransform ?: sourceLayer.transform
                    val mask = maskBitmaps.get(file, uri, project.maskSizes[file], maskTransform)
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
        maskBitmaps: MaskBitmapCache,
        blendMode: BlendMode
    ) {
        val documentBounds = RectF(0f, 0f, project.width.toFloat(), project.height.toFloat())
        val bounds = transformedBounds(layer.transform)
        for (mask in masks) bounds.union(transformedBounds(mask.transform))
        if (!bounds.intersect(documentBounds)) return

        val opacityPaint = Paint().apply {
            alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
            this.blendMode = blendMode
        }
        val contentSave = canvas.saveLayer(bounds, opacityPaint)
        try {
            drawBitmap(canvas, bitmap, layer.transform)
            for (mask in masks) {
                val uri = project.maskUris[mask.file]
                    ?: throw IllegalArgumentException("Layer mask “${mask.file}” does not exist.")
                val maskBitmap = maskBitmaps.get(mask.file, uri, project.maskSizes[mask.file], mask.transform)
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
                    val region = exportRegion
                    if (region == null) drawBitmap(canvas, liveCoverage, documentTransform(project))
                    else canvas.drawBitmap(liveCoverage, region.left.toFloat(), region.top.toFloat(), Paint())
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
        val rasterRegion = exactRasterRegion
        if (rasterRegion != null) {
            val crop = crops[bitmap]
            val bounds = crop?.bounds ?: android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val pixels = IntArray(Math.multiplyExact(bitmap.width, bitmap.height))
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val sampler = TransformedRasterSampler(pixels, bounds, crop?.width ?: bitmap.width,
                crop?.height ?: bitmap.height, transform)
            val outputPixels = IntArray(Math.multiplyExact(rasterRegion.width(), rasterRegion.height()))
            for (y in 0 until rasterRegion.height()) {
                if (y % 16 == 0 && renderCancelled()) throw CancellationException("Composition was cancelled.")
                for (x in 0 until rasterRegion.width()) outputPixels[y * rasterRegion.width() + x] =
                    sampler.pixelAt(rasterRegion.left + x + 0.5, rasterRegion.top + y + 0.5)
            }
            val aligned = Bitmap.createBitmap(outputPixels, rasterRegion.width(), rasterRegion.height(), Bitmap.Config.ARGB_8888)
            try {
                canvas.drawBitmap(aligned, rasterRegion.left.toFloat(), rasterRegion.top.toFloat(), Paint(paint).apply {
                    isFilterBitmap = false; isAntiAlias = false
                })
            } finally { aligned.recycle() }
            return
        }
        val centerX = transform.x + transform.width / 2.0
        val centerY = transform.y + transform.height / 2.0
        val save = canvas.save()
        canvas.translate(centerX.toFloat(), centerY.toFloat())
        canvas.rotate((transform.rotation % 360.0).toFloat())
        canvas.scale(if (transform.flipX) -1f else 1f, if (transform.flipY) -1f else 1f)
        val halfWidth = transform.width / 2.0
        val halfHeight = transform.height / 2.0
        canvas.drawBitmap(bitmap, null, RectF((-halfWidth).toFloat(), (-halfHeight).toFloat(),
            halfWidth.toFloat(), halfHeight.toFloat()), paint)
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

    private fun decodePreviewBitmap(
        uri: android.net.Uri,
        pixelSize: CompProjectReader.PixelSize?,
        rasterBudget: AndroidRasterBudget,
        fullResolution: Boolean = false,
        editingSession: LayerEditingSession? = null,
        isCancelled: () -> Boolean = { false },
        transform: CompProjectReader.LayerTransform? = null
    ): Bitmap {
        requireNotNull(pixelSize) { "The project has no dimensions for this PNG." }
        val region = exportRegion
        if (region != null && transform != null) {
            val bounds = SourceRegionPlanner.bounds(region, transform, pixelSize)
            if (bounds.width().toLong() * bounds.height() * 4 > rasterBudget.maxSingleBitmapBytes)
                throw TileSourceBudgetException()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
                inMutable = editingSession?.hasCommittedChanges == true
                inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            }
            val bitmap = sourceRegionDecoder?.invoke(uri, pixelSize, bounds, options) ?: context.contentResolver.openInputStream(uri)?.use { input ->
                @Suppress("DEPRECATION")
                val decoder = android.graphics.BitmapRegionDecoder.newInstance(input, false)
                    ?: error("The source PNG does not support region decoding.")
                try {
                    require(decoder.width == pixelSize.width && decoder.height == pixelSize.height) { "The source dimensions changed." }
                    decoder.decodeRegion(bounds, options)
                } finally { decoder.recycle() }
            } ?: error("Could not decode the source region.")
            try {
                require(bitmap.width == bounds.width() && bitmap.height == bounds.height() &&
                    bitmap.allocationByteCount.toLong() <= rasterBudget.maxSingleBitmapBytes)
                editingSession?.applyCommittedRegion(bitmap, bounds.left, bounds.top, isCancelled)
                crops[bitmap] = Crop(bounds, pixelSize.width, pixelSize.height)
                return bitmap
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
        val sampleSize = if (fullResolution) 1 else rasterBudget.sampleSizeFor(pixelSize.width, pixelSize.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = editingSession?.hasCommittedChanges == true
            inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalArgumentException("Could not decode the layer PNG.")
        try {
            if (fullResolution) {
                require(bitmap.width == pixelSize.width && bitmap.height == pixelSize.height) { "The source image dimensions changed." }
                require(bitmap.allocationByteCount.toLong() <= rasterBudget.maxSingleBitmapBytes) { "The source image exceeds the export memory budget." }
            }
            editingSession?.applyCommittedChanges(bitmap, isCancelled, fullResolution)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private class MaskBitmapCache(
        private val context: Context,
        private val rasterBudget: AndroidRasterBudget,
        private val fullResolution: Boolean,
        private val decodeRegion: (android.net.Uri, CompProjectReader.PixelSize, CompProjectReader.LayerTransform) -> Bitmap
    ) {
        private val cached = LinkedHashMap<String, Bitmap>(0, 0.75f, true)
        private val transient = ArrayList<Bitmap>()
        private var cachedBytes = 0L

        fun get(file: String, uri: android.net.Uri, pixelSize: CompProjectReader.PixelSize?, transform: CompProjectReader.LayerTransform): Bitmap {
            val key = "$file|$transform"
            cached[key]?.let { return it }
            requireNotNull(pixelSize) { "The project has no dimensions for this mask PNG." }
            val sampleSize = if (fullResolution) 1 else rasterBudget.sampleSizeFor(pixelSize.width, pixelSize.height)
            val expectedBytes =
                ((pixelSize.width.toLong() + sampleSize - 1) / sampleSize) *
                    ((pixelSize.height.toLong() + sampleSize - 1) / sampleSize) * 4L
            if (expectedBytes <= rasterBudget.maxMaskCacheBytes) {
                while (cachedBytes > rasterBudget.maxMaskCacheBytes - expectedBytes && cached.isNotEmpty()) {
                    evictOldest()
                }
            }
            val bitmap = if (fullResolution) decodeRegion(uri, pixelSize, transform) else decodeMaskBitmap(context, uri, pixelSize, sampleSize)
            if (fullResolution && bitmap.allocationByteCount.toLong() > rasterBudget.maxSingleBitmapBytes) {
                bitmap.recycle()
                throw IllegalArgumentException("The mask changed or exceeds the export memory budget.")
            }
            val bytes = bitmap.allocationByteCount.toLong()
            if (bytes > rasterBudget.maxMaskCacheBytes) {
                transient += bitmap
                return bitmap
            }
            while (cachedBytes > rasterBudget.maxMaskCacheBytes - bytes && cached.isNotEmpty()) evictOldest()
            cached[key] = bitmap
            cachedBytes += bytes
            return bitmap
        }

        private fun evictOldest() {
            val iterator = cached.entries.iterator()
            if (!iterator.hasNext()) return
            val oldest = iterator.next()
            cachedBytes -= oldest.value.allocationByteCount.toLong()
            oldest.value.recycle()
            iterator.remove()
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
            pixelSize: CompProjectReader.PixelSize?,
            sampleSize: Int
        ): Bitmap {
            requireNotNull(pixelSize) { "The project has no dimensions for this mask PNG." }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            }
            return context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IllegalArgumentException("Could not decode the project mask PNG.")
        }

    }

    private fun reportProgress(completed: Int, total: Int, onProgress: (Int, Int) -> Unit) {
        if (completed == total || completed % PROGRESS_INTERVAL == 0) onProgress(completed, total)
    }

    /** Export must fail explicitly rather than write the preview's skipped features or sampled pixels. */
    private fun validateExport(project: CompProjectReader.Project, budget: AndroidRasterBudget) {
        fun checkSize(width: Int, height: Int) {
            require(width.toLong() * height.toLong() <= budget.maxSingleBitmapBytes / 4L) {
                "This project exceeds the current full-resolution export memory budget. No reduced-resolution file was exported."
            }
        }
        exportRegion?.let { checkSize(it.width(), it.height()) } ?: checkSize(project.width, project.height)
        val layers = project.layers.associateBy { it.id }
        val entries = PreviewLayerOrderPlanner.plan(project.layers.map {
            PreviewLayerOrderPlanner.Node(it.id, it.parentId, it.visible, it.group, it.opacity)
        })
        for (entry in entries) {
            if (!entry.effectivelyVisible || entry.effectiveOpacity <= 0.0) continue
            val layer = layers.getValue(entry.node.id)
            require(!layer.adjustment && !layer.hasEffects && canvasBlendMode(layer.blendMode) != null) {
                "Full-canvas export is unavailable because “${layer.name}” uses an unsupported adjustment, effect or blend mode."
            }
            val sources = listOf(layer) + layer.maskSourceId?.let { PreviewLiveMaskPlanner.chain(it, layers) }.orEmpty()
            for (source in sources) {
                require(!source.hasEffects && !source.adjustment) { "A clipping source uses an unsupported effect or adjustment." }
                source.imageFile?.let { file ->
                    val size = requireNotNull(project.imageSizes[file]) { "Missing image dimensions." }
                    if (exportRegion == null) checkSize(size.width, size.height)
                }
                for (mask in PreviewMaskPlanner.forLayer(source, layers)) {
                    val size = requireNotNull(project.maskSizes[mask.file]) { "Missing mask dimensions." }
                    if (exportRegion == null) checkSize(size.width, size.height)
                }
            }
        }
    }

    companion object {
        private const val PROGRESS_INTERVAL = 24
        private val MASK_ALPHA_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f, 1f,
            0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f
        )))

        /** These are the seven first-release modes; other valid Mac modes remain explicit limits. */
        private fun canvasBlendMode(name: String): BlendMode? = when (name) {
            "Normal" -> BlendMode.SRC_OVER
            "Darken" -> BlendMode.DARKEN
            "Multiply" -> BlendMode.MULTIPLY
            "Lighten" -> BlendMode.LIGHTEN
            "Screen" -> BlendMode.SCREEN
            "Overlay" -> BlendMode.OVERLAY
            "Difference" -> BlendMode.DIFFERENCE
            else -> null
        }
    }
}
