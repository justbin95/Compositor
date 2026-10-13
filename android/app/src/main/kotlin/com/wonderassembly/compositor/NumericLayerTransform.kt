package com.wonderassembly.compositor

import kotlin.math.abs

internal object NumericLayerTransform {
    fun resize(value: CompProjectReader.LayerTransform, number: Double, width: Boolean,
        locked: Boolean): CompProjectReader.LayerTransform {
        require(number.isFinite() && number in 1.0..300_000.0)
        val result = if (width) value.copy(width = number,
            height = if (locked) value.height * (number / value.width) else value.height)
        else value.copy(height = number,
            width = if (locked) value.width * (number / value.height) else value.width)
        return validated(result)
    }

    fun scalePercent(value: CompProjectReader.LayerTransform, pixels: CompProjectReader.PixelSize): Double =
        value.width / pixels.width.coerceAtLeast(1) * 100

    fun scale(value: CompProjectReader.LayerTransform, pixels: CompProjectReader.PixelSize,
        percent: Double): CompProjectReader.LayerTransform {
        require(percent.isFinite() && percent in 0.1..30_000.0)
        val width = pixels.width * percent / 100
        val height = pixels.height * percent / 100
        return validated(value.copy(x = value.x + value.width / 2 - width / 2,
            y = value.y + value.height / 2 - height / 2, width = width, height = height))
    }

    fun validated(value: CompProjectReader.LayerTransform): CompProjectReader.LayerTransform {
        require(listOf(value.x, value.y, value.width, value.height, value.rotation).all { it.isFinite() } &&
            abs(value.x) <= 1_000_000 && abs(value.y) <= 1_000_000 &&
            value.width in 1.0..300_000.0 && value.height in 1.0..300_000.0 && abs(value.rotation) <= 360)
        return value
    }
}
