package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BlendMode
import com.wonderassembly.compositor.core.ReferencePngWriter
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Exact-size sRGB composite output, admitted only when the measured memory budget permits it. */
internal class CompositeImageExporter(private val context: Context,
    private val captureBudget: () -> AndroidRasterBudget = { AndroidRasterBudget.capture(context) },
    private val sourceRegionDecoder: ((android.net.Uri, CompProjectReader.PixelSize, android.graphics.Rect, BitmapFactory.Options) -> Bitmap)? = null) {
    enum class Format(val mimeType: String, val extension: String) {
        PNG("image/png", "png"), JPEG("image/jpeg", "jpg")
    }

    fun write(
        output: OutputStream,
        project: CompProjectReader.Project,
        editedAssets: Map<String, LayerEditingSession>,
        format: Format,
        quality: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        require(quality in 1..100)
        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Image export was cancelled.")
        }
        val checkedOutput = object : FilterOutputStream(output) {
            override fun write(value: Int) { checkCancelled(); out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkCancelled()
                out.write(bytes, offset, length)
            }
            override fun flush() { checkCancelled(); out.flush() }
        }
        checkCancelled()
        if (format == Format.PNG) {
            writeTiledPng(checkedOutput, project, editedAssets, isCancelled, onProgress)
            return
        }
        val rendered = PreviewCompositeRenderer(context, captureBudget, sourceRegionDecoder).render(
            project, isCancelled,
            { completed, total -> onProgress(if (total == 0) 65 else completed * 65 / total) },
            fullResolution = true, editedAssets = editedAssets
        )
        val bitmap = rendered.bitmap
        try {
            check(rendered.limitations.isEmpty()) { "The project uses features that cannot be exported yet." }
            checkCancelled()
            // JPEG retains its explicit whole-image budget until a row encoder is available.
            Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply {
                color = Color.WHITE
                blendMode = BlendMode.DST_OVER
            })
            bitmap.setHasAlpha(false)
            onProgress(75)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, checkedOutput)) { "Could not encode the JPEG." }
            checkedOutput.flush()
            checkCancelled()
            onProgress(100)
        } finally {
            bitmap.recycle()
        }
    }
    private fun writeTiledPng(output: OutputStream, project: CompProjectReader.Project,
        edits: Map<String, LayerEditingSession>, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val budget = captureBudget()
        val plan = TileExportPlan.create(project.width, budget.maxSingleBitmapBytes)
        val rows = plan.stripRows
        val strip = IntArray(Math.multiplyExact(project.width, rows))
        val renderer = PreviewCompositeRenderer(context, captureBudget, sourceRegionDecoder)
        var stripTop = -1
        ReferencePngWriter.writeRgba8PremultipliedRows(output, project.width, project.height) { y, row ->
            if (cancelled()) throw CancellationException("Image export was cancelled.")
            if (stripTop < 0 || y >= stripTop + rows) {
                stripTop = y
                val height = minOf(rows, project.height - y)
                var x = 0
                while (x < project.width) {
                    if (cancelled()) throw CancellationException("Image export was cancelled.")
                    val width = minOf(256, project.width - x)
                    fun renderTile(tile: android.graphics.Rect) {
                        if (cancelled()) throw CancellationException("Image export was cancelled.")
                        try {
                            val expanded = android.graphics.Rect(maxOf(0, tile.left - 8), maxOf(0, tile.top - 8),
                                minOf(project.width, tile.right + 8), minOf(project.height, tile.bottom + 8))
                            val result = renderer.render(project, cancelled, { _, _ -> }, fullResolution = true,
                                editedAssets = edits, region = expanded)
                            try {
                                check(result.limitations.isEmpty()) { "The project uses features that cannot be exported yet." }
                                result.bitmap.getPixels(strip, (tile.top - stripTop) * project.width + tile.left,
                                    project.width, tile.left - expanded.left, tile.top - expanded.top, tile.width(), tile.height())
                            } finally { result.bitmap.recycle() }
                        } catch (error: TileSourceBudgetException) {
                            val parts = TileExportPlan.split(tile) ?: throw error
                            parts.forEach(::renderTile)
                        }
                    }
                    renderTile(android.graphics.Rect(x, y, x + width, y + height))
                    x += width
                }
            }
            var index = (y - stripTop) * project.width
            for (x in 0 until project.width) {
                val pixel = strip[index++]
                val alpha = Color.alpha(pixel)
                val offset = x * 4
                row[offset] = ((Color.red(pixel) * alpha + 127) / 255).toByte()
                row[offset + 1] = ((Color.green(pixel) * alpha + 127) / 255).toByte()
                row[offset + 2] = ((Color.blue(pixel) * alpha + 127) / 255).toByte()
                row[offset + 3] = alpha.toByte()
            }
            if (y % 32 == 0) progress((y + 1) * 99 / project.height)
        }
        if (cancelled()) throw CancellationException("Image export was cancelled.")
        progress(100)
    }

}
