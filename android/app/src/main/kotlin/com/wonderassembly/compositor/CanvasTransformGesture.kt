package com.wonderassembly.compositor

import com.wonderassembly.compositor.core.PointD
import kotlin.math.*

/** Document-space geometry matching the pinned macOS TransformDrag. */
internal class CanvasTransformGesture(val original: CompProjectReader.LayerTransform, private val start: PointD, val handle: Int) {
    fun updated(point: PointD, lockRatio: Boolean): CompProjectReader.LayerTransform {
        if (point == start) return original
        val radians = Math.toRadians(original.rotation % 360)
        val c = cos(radians); val s = sin(radians)
        val result = when (handle) {
            MOVE -> original.copy(x = original.x + point.x - start.x, y = original.y + point.y - start.y)
            ROTATE -> {
                val center = PointD(original.x + original.width / 2, original.y + original.height / 2)
                val delta = atan2(point.y - center.y, point.x - center.x) - atan2(start.y - center.y, start.x - center.x)
                original.copy(rotation = (original.rotation + Math.toDegrees(delta)) % 360)
            }
            else -> {
                val unit = handles[handle]
                val anchorUnit = PointD(1 - unit.x, 1 - unit.y)
                val anchor = pointAt(original, anchorUnit)
                val initial = pointAt(original, unit)
                val dx = initial.x + point.x - start.x - anchor.x
                val dy = initial.y + point.y - start.y - anchor.y
                val localX = dx * c + dy * s; val localY = -dx * s + dy * c
                val sx = unit.x * 2 - 1; val sy = unit.y * 2 - 1
                val rawWidth = if (sx == 0.0) original.width else localX * sx
                val rawHeight = if (sy == 0.0) original.height else localY * sy
                val mirroredX = rawWidth < 0; val mirroredY = rawHeight < 0
                var width = max(1.0, abs(rawWidth)); var height = max(1.0, abs(rawHeight))
                if (lockRatio) {
                    val factor = when {
                        sx == 0.0 -> height / original.height
                        sy == 0.0 -> width / original.width
                        else -> max(1 / min(original.width, original.height),
                            (localX * sx * original.width + localY * sy * original.height) /
                                (original.width * original.width + original.height * original.height))
                    }
                    width = original.width * factor; height = original.height * factor
                }
                val ox = (0.5 - anchorUnit.x) * width * if (mirroredX) -1 else 1
                val oy = (0.5 - anchorUnit.y) * height * if (mirroredY) -1 else 1
                original.copy(x = anchor.x + ox * c - oy * s - width / 2,
                    y = anchor.y + ox * s + oy * c - height / 2, width = width, height = height,
                    flipX = original.flipX xor mirroredX, flipY = original.flipY xor mirroredY)
            }
        }
        return if (valid(result)) result else original
    }

    companion object {
        const val MOVE = -1
        const val ROTATE = -2
        val handles = listOf(PointD(0.0, 0.0), PointD(0.5, 0.0), PointD(1.0, 0.0), PointD(1.0, 0.5),
            PointD(1.0, 1.0), PointD(0.5, 1.0), PointD(0.0, 1.0), PointD(0.0, 0.5))
        fun pointAt(t: CompProjectReader.LayerTransform, unit: PointD): PointD {
            val r = Math.toRadians(t.rotation % 360)
            val x = (unit.x - 0.5) * t.width; val y = (unit.y - 0.5) * t.height
            return PointD(t.x + t.width / 2 + x * cos(r) - y * sin(r), t.y + t.height / 2 + x * sin(r) + y * cos(r))
        }
        fun contains(t: CompProjectReader.LayerTransform, point: PointD): Boolean {
            val r = Math.toRadians(t.rotation % 360)
            val x = point.x - t.x - t.width / 2; val y = point.y - t.y - t.height / 2
            return abs(x * cos(r) + y * sin(r)) <= t.width / 2 && abs(-x * sin(r) + y * cos(r)) <= t.height / 2
        }
        fun committed(t: CompProjectReader.LayerTransform): CompProjectReader.LayerTransform {
            fun rounded(value: Double) = if (value >= 0) floor(value + 0.5) else ceil(value - 0.5)
            return t.copy(x = rounded(t.x), y = rounded(t.y), width = max(1.0, rounded(t.width)),
                height = max(1.0, rounded(t.height)), rotation = rounded(t.rotation) % 360)
        }
        private fun valid(t: CompProjectReader.LayerTransform) =
            listOf(t.x, t.y, t.width, t.height, t.rotation).all { it.isFinite() } &&
                abs(t.x) <= 1_000_000 && abs(t.y) <= 1_000_000 && t.width in 1.0..300_000.0 && t.height in 1.0..300_000.0
    }
}
