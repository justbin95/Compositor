package com.wonderassembly.compositor

import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.roundToInt

/** Samples at global document pixel centers, so coverage cannot depend on tile origins. */
internal class TransformedRasterSampler(
    private val pixels: IntArray,
    private val bounds: Rect,
    private val width: Int,
    private val height: Int,
    private val transform: CompProjectReader.LayerTransform
) {
    private val angle = Math.toRadians(transform.rotation % 360)
    private val c = cos(angle)
    private val s = sin(angle)
    private val centerX = transform.x + transform.width / 2
    private val centerY = transform.y + transform.height / 2
    private val factorX = width / transform.width * if (transform.flipX) -1 else 1
    private val factorY = height / transform.height * if (transform.flipY) -1 else 1

    init { require(pixels.size == bounds.width() * bounds.height()) }

    fun pixelAt(x: Double, y: Double): Int {
        val dx = x - centerX; val dy = y - centerY
        val sx = (dx * c + dy * s) * factorX + width / 2.0 - 0.5
        val sy = (-dx * s + dy * c) * factorY + height / 2.0 - 0.5
        if (transform.sampling == "Nearest") return pixel(floor(sx + 0.5).toInt(), floor(sy + 0.5).toInt())
        val left = floor(sx).toInt(); val top = floor(sy).toInt()
        val fx = sx - left; val fy = sy - top
        var alpha = 0.0; var red = 0.0; var green = 0.0; var blue = 0.0
        for (oy in 0..1) for (ox in 0..1) {
            val weight = (if (ox == 0) 1 - fx else fx) * (if (oy == 0) 1 - fy else fy)
            if (weight <= 0) continue
            val value = pixel(left + ox, top + oy)
            val a = (value ushr 24 and 255) * weight
            alpha += a
            red += (value ushr 16 and 255) * a
            green += (value ushr 8 and 255) * a
            blue += (value and 255) * a
        }
        val a = alpha.roundToInt().coerceIn(0, 255)
        if (a == 0) return 0
        fun channel(value: Double) = (value / alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
    }

    private fun pixel(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        require(x in bounds.left until bounds.right && y in bounds.top until bounds.bottom) {
            "The planned source region does not cover a required sample."
        }
        val value = pixels[(y - bounds.top) * bounds.width() + x - bounds.left]
        return if (value ushr 24 == 0) 0 else value
    }
}
