package com.wonderassembly.compositor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.wonderassembly.compositor.core.ResourceBudget
import kotlin.math.sqrt

/** Conservative per-operation headroom for the current bounded Bitmap preview path. */
internal data class AndroidRasterBudget(
    val maxSingleBitmapBytes: Long,
    val maxMaskCacheBytes: Long,
    val maxEditableDocumentBytes: Long,
    val maxPreviewSide: Int
) {
    fun sampleSizeFor(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "The image has invalid dimensions." }
        var sampleSize = 1
        val longestSide = maxOf(width, height).toLong()
        while ((longestSide + sampleSize - 1L) / sampleSize > maxPreviewSide) {
            require(sampleSize <= (1 shl 29)) { "The image dimensions exceed the preview range." }
            sampleSize *= 2
        }
        return sampleSize
    }

    fun editableResourceBudget() = ResourceBudget(maxEditableDocumentBytes)

    companion object {
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
        private const val PREVIEW_PARTITIONS = 5L
        private const val MAX_PREVIEW_SIDE = 2048

        fun capture(context: Context): AndroidRasterBudget {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val systemHeadroom = (memoryInfo.availMem - memoryInfo.threshold).coerceAtLeast(0L)
            val appAllowance = manager.memoryClass.toLong() * BYTES_PER_MEGABYTE
            val processUsage = Debug.getPss().toLong() * 1024L
            val processHeadroom = (appAllowance - processUsage).coerceAtLeast(0L)

            // Half of the smaller measured headroom remains reserved for the system and other app work.
            val safeWorkingBytes = minOf(systemHeadroom, processHeadroom) / 2L
            val singleBitmapBytes = safeWorkingBytes / PREVIEW_PARTITIONS
            require(singleBitmapBytes >= 4L) {
                "There is not enough available memory to create a safe image preview."
            }
            val sideFromBytes = sqrt((singleBitmapBytes / 4L).toDouble()).toInt().coerceAtLeast(1)
            val previewSide = minOf(MAX_PREVIEW_SIDE, sideFromBytes)
            return AndroidRasterBudget(
                maxSingleBitmapBytes = singleBitmapBytes,
                maxMaskCacheBytes = singleBitmapBytes,
                maxEditableDocumentBytes = safeWorkingBytes / 2L,
                maxPreviewSide = previewSide
            )
        }
    }
}
