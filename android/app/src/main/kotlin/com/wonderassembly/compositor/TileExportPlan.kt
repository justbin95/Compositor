package com.wonderassembly.compositor

import android.graphics.Rect
import com.wonderassembly.compositor.core.ReferencePngWriter

internal class TileSourceBudgetException : IllegalArgumentException(
    "A transformed source region exceeds the tiled export budget. No sampled file was exported.")

internal data class TileExportPlan(val stripRows: Int) {
    companion object {
        fun create(width: Int, maximumWorkingBytes: Long): TileExportPlan {
            require(width > 0)
            val rowBytes = width.toLong() * 4
            val reserve = ReferencePngWriter.rowEncodingWorkingBytes(width) + rowBytes + 1024L * 1024L
            require(maximumWorkingBytes - reserve >= rowBytes) {
                "The PNG row and tile working buffers exceed the current export budget."
            }
            return TileExportPlan(minOf(256L, (maximumWorkingBytes - reserve) / rowBytes).toInt())
        }

        fun split(tile: Rect): List<Rect>? {
            if (tile.width() == 1 && tile.height() == 1) return null
            return if (tile.width() >= tile.height() && tile.width() > 1) {
                val middle = tile.left + tile.width() / 2
                listOf(Rect(tile.left, tile.top, middle, tile.bottom), Rect(middle, tile.top, tile.right, tile.bottom))
            } else {
                val middle = tile.top + tile.height() / 2
                listOf(Rect(tile.left, tile.top, tile.right, middle), Rect(tile.left, middle, tile.right, tile.bottom))
            }
        }
    }
}
