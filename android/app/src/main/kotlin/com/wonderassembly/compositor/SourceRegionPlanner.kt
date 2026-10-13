package com.wonderassembly.compositor

import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.ceil

/** Inverse maps a document tile with a filtering halo into exact source pixels. */
internal object SourceRegionPlanner {
    fun bounds(tile: Rect, transform: CompProjectReader.LayerTransform, size: CompProjectReader.PixelSize): Rect {
        require(transform.width > 0 && transform.height > 0 && size.width > 0 && size.height > 0)
        val angle = Math.toRadians(transform.rotation % 360)
        val c = cos(angle); val s = sin(angle)
        val centerX = transform.x + transform.width / 2
        val centerY = transform.y + transform.height / 2
        val coordinates = listOf(tile.left to tile.top, tile.right to tile.top, tile.left to tile.bottom, tile.right to tile.bottom).map { (x, y) ->
            val dx = x - centerX; val dy = y - centerY
            val localX = (dx * c + dy * s) * if (transform.flipX) -1 else 1
            val localY = (-dx * s + dy * c) * if (transform.flipY) -1 else 1
            (localX / transform.width + 0.5) * size.width to (localY / transform.height + 0.5) * size.height
        }
        val left = (floor(coordinates.minOf { it.first } + 1e-7) - 4).toInt().coerceIn(0, size.width - 1)
        val top = (floor(coordinates.minOf { it.second } + 1e-7) - 4).toInt().coerceIn(0, size.height - 1)
        val right = (ceil(coordinates.maxOf { it.first } - 1e-7) + 4).toInt().coerceIn(left + 1, size.width)
        val bottom = (ceil(coordinates.maxOf { it.second } - 1e-7) + 4).toInt().coerceIn(top + 1, size.height)
        return Rect(left, top, right, bottom)
    }
}
