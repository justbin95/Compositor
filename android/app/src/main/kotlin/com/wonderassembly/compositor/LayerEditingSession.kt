package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import com.wonderassembly.compositor.core.ChunkedRaster
import com.wonderassembly.compositor.core.PixelBufferLayout
import com.wonderassembly.compositor.core.RasterChunkBounds
import com.wonderassembly.compositor.core.ReferencePngWriter
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Keeps sparse in-memory edits for one selected layer. It does not write to the .comp project. */
internal class LayerEditingSession(
    context: Context,
    private val imageUri: Uri,
    width: Int,
    height: Int,
    maxCachedBytes: Long
) : Closeable {
    private val source = ProjectPngTileSource(context.applicationContext, imageUri, width, height)
    private val applicationContext = context.applicationContext
    private val rasterCacheBudgetBytes = maxCachedBytes / RASTER_BUDGET_DIVISOR
    private val historySnapshotBudgetBytes = maxCachedBytes / HISTORY_BUDGET_DIVISOR

    val raster = ChunkedRaster(
        width,
        height,
        maxCachedBytes = rasterCacheBudgetBytes,
        loadChunk = source::loadChunk
    )
    val hasCommittedChanges: Boolean get() = raster.hasCommittedChanges
    val canBeginStroke: Boolean = run {
        val minimumChunkSnapshotBytes = Math.multiplyExact(
            Math.multiplyExact(
                minOf(width, ChunkedRaster.DEFAULT_CHUNK_SIZE).toLong(),
                minOf(height, ChunkedRaster.DEFAULT_CHUNK_SIZE).toLong()
            ),
            8L
        )
        minimumChunkSnapshotBytes <= rasterCacheBudgetBytes &&
            minimumChunkSnapshotBytes <= historySnapshotBudgetBytes
    }

    fun closeSource() = source.closeSource()

    fun freezeForRecovery(maxBytes: Long): FrozenLayerExport {
        val edits = raster.freezeCommitted(maxBytes)
        return FrozenLayerExport(ProjectPngTileSource(applicationContext, imageUri, raster.width, raster.height), edits)
    }

    fun writePng(
        output: OutputStream,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: (Int) -> Unit = {}
    ) = source.writePng(output, raster, isCancelled, onProgress)

    /** Replaces committed source chunks, leaving untouched source pixels unchanged. */
    fun applyCommittedChanges(bitmap: Bitmap, isCancelled: () -> Boolean, fullResolution: Boolean = true) {
        if (!hasCommittedChanges) return
        require(bitmap.isMutable)
        if (fullResolution) require(bitmap.width == raster.width && bitmap.height == raster.height)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            blendMode = android.graphics.BlendMode.SRC
            isFilterBitmap = true
        }
        if (isCancelled()) throw java.util.concurrent.CancellationException("Composition was cancelled.")
        raster.forEachCommittedChunkSnapshot { snapshot ->
            if (isCancelled()) throw java.util.concurrent.CancellationException("Composition was cancelled.")
            val bounds = snapshot.bounds
            val bytes = snapshot.copyPixels()
            val pixels = IntArray(bounds.width * bounds.height)
            for (index in pixels.indices) {
                val offset = index * 4
                val alpha = bytes[offset + 3].toInt() and 255
                fun channel(channelIndex: Int): Int = if (alpha == 0) 0 else
                    (((bytes[offset + channelIndex].toInt() and 255) * 255 + alpha / 2) / alpha).coerceAtMost(255)
                pixels[index] = Color.argb(alpha, channel(0), channel(1), channel(2))
            }
            if (fullResolution) {
                bitmap.setPixels(pixels, 0, bounds.width, bounds.left, bounds.top, bounds.width, bounds.height)
            } else {
                val chunk = Bitmap.createBitmap(pixels, bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
                try {
                    val scaleX = bitmap.width.toFloat() / raster.width
                    val scaleY = bitmap.height.toFloat() / raster.height
                    canvas.drawBitmap(chunk, null, android.graphics.RectF(
                        bounds.left * scaleX, bounds.top * scaleY,
                        (bounds.left + bounds.width) * scaleX, (bounds.top + bounds.height) * scaleY
                    ), paint)
                } finally {
                    chunk.recycle()
                }
            }
        }
    }

    override fun close() = source.close()

    private companion object {
        const val RASTER_BUDGET_DIVISOR = 3L
        const val HISTORY_BUDGET_DIVISOR = 2L
    }
}

internal class FrozenLayerExport(
    private val source: ProjectPngTileSource,
    private val edits: com.wonderassembly.compositor.core.FrozenRasterEdits
) : Closeable {
    val retainedBytes: Long get() = edits.retainedBytes
    fun writePng(output: OutputStream) {
        try { source.writeRows(output, { false }, {}, edits::copyRowOnto) }
        finally { source.closeSource() }
    }
    override fun close() = source.close()

}

/** Reads exact source PNG rectangles as premultiplied RGBA8 chunks on demand. */
internal class ProjectPngTileSource(
    context: Context,
    private val uri: Uri,
    private val width: Int,
    private val height: Int
) : Closeable {
    private val resolver = context.contentResolver
    private var input: InputStream? = null
    private var decoder: BitmapRegionDecoder? = null
    private var closed = false

    @Synchronized
    fun loadChunk(bounds: RasterChunkBounds): ByteArray {
        check(!closed) { "The source image is no longer available." }
        require(bounds.left + bounds.width <= width && bounds.top + bounds.height <= height) {
            "The requested brush chunk is outside the source image."
        }
        val regionDecoder = decoder ?: openDecoder()
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        }
        val bitmap = regionDecoder.decodeRegion(
            Rect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height),
            options
        ) ?: throw IllegalArgumentException("Could not decode the source image chunk.")
        try {
            require(bitmap.width == bounds.width && bitmap.height == bounds.height) {
                "The source image returned an unexpected chunk size."
            }
            val argb = IntArray(bounds.width * bounds.height)
            bitmap.getPixels(argb, 0, bounds.width, 0, 0, bounds.width, bounds.height)
            return argb.toPremultipliedRgba()
        } finally {
            bitmap.recycle()
        }
    }

    fun writePng(output: OutputStream, raster: ChunkedRaster, isCancelled: () -> Boolean, onProgress: (Int) -> Unit) {
        require(raster.width == width && raster.height == height) {
            "The edited raster dimensions do not match the source image."
        }
        writeRows(output, isCancelled, onProgress, raster::copyCommittedRowOnto)
    }

    fun writeRows(output: OutputStream, isCancelled: () -> Boolean, onProgress: (Int) -> Unit,
                  overlayRow: (Int, ByteArray) -> Unit) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        }
        var stripBitmap: Bitmap? = null
        var stripPixels = IntArray(0)
        var stripTop = -1
        var stripHeight = 0
        try {
            ReferencePngWriter.writeRgba8PremultipliedRows(output, width, height) { y, row ->
                if (isCancelled()) throw java.util.concurrent.CancellationException("PNG export was cancelled.")
                if (stripBitmap == null || y < stripTop || y >= stripTop + stripHeight) {
                    stripBitmap?.recycle()
                    stripTop = y
                    stripHeight = minOf(EXPORT_STRIP_HEIGHT, height - y)
                    stripBitmap = synchronized(this) {
                        check(!closed) { "The source image is no longer available." }
                        val regionDecoder = decoder ?: openDecoder()
                        regionDecoder.decodeRegion(
                            Rect(0, stripTop, width, stripTop + stripHeight),
                            options
                        )
                    } ?: throw IllegalArgumentException("Could not decode the source image for PNG export.")
                    require(stripBitmap!!.width == width && stripBitmap!!.height == stripHeight) {
                        "The source image returned an unexpected export region size."
                    }
                    stripPixels = IntArray(Math.multiplyExact(width, stripHeight))
                    stripBitmap!!.getPixels(stripPixels, 0, width, 0, 0, width, stripHeight)
                }

                var sourceIndex = Math.multiplyExact(y - stripTop, width)
                var destinationIndex = 0
                repeat(width) {
                    val pixel = stripPixels[sourceIndex++]
                    val alpha = Color.alpha(pixel)
                    row[destinationIndex] = ((Color.red(pixel) * alpha + 127) / 255).toByte()
                    row[destinationIndex + 1] = ((Color.green(pixel) * alpha + 127) / 255).toByte()
                    row[destinationIndex + 2] = ((Color.blue(pixel) * alpha + 127) / 255).toByte()
                    row[destinationIndex + 3] = alpha.toByte()
                    destinationIndex += PixelBufferLayout.BYTES_PER_PIXEL
                }
                overlayRow(y, row)
                if (y % 32 == 0) onProgress((y + 1) * 90 / height)
            }
        } finally {
            stripBitmap?.recycle()
        }
    }

    @Synchronized
    fun closeSource() {
        decoder?.recycle()
        decoder = null
        input?.close()
        input = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closeSource()
        closed = true
    }

    private fun openDecoder(): BitmapRegionDecoder {
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not read the source image from the selected project.")
        return try {
            @Suppress("DEPRECATION")
            val opened = BitmapRegionDecoder.newInstance(stream, false)
                ?: throw IllegalArgumentException("The source PNG does not support chunk decoding.")
            require(opened.width == width && opened.height == height) {
                "The source image dimensions changed after project validation."
            }
            input = stream
            decoder = opened
            opened
        } catch (error: Exception) {
            stream.close()
            throw error
        }
    }

    private fun IntArray.toPremultipliedRgba(): ByteArray {
        val bytes = ByteArray(size * 4)
        for (index in indices) {
            val pixel = this[index]
            val alpha = Color.alpha(pixel)
            val offset = index * 4
            bytes[offset] = ((Color.red(pixel) * alpha + 127) / 255).toByte()
            bytes[offset + 1] = ((Color.green(pixel) * alpha + 127) / 255).toByte()
            bytes[offset + 2] = ((Color.blue(pixel) * alpha + 127) / 255).toByte()
            bytes[offset + 3] = alpha.toByte()
        }
        return bytes
    }

    private companion object {
        const val EXPORT_STRIP_HEIGHT = 16
    }
}
