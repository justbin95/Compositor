package com.wonderassembly.compositor.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class PointD(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite()) { "Point coordinates must be finite." }
    }
}

/** Maps canvas pixel-edge coordinates to local window pixel coordinates. */
data class ViewportTransform(
    val zoom: Double,
    val windowOriginX: Double,
    val windowOriginY: Double
) {
    init {
        require(zoom.isFinite() && zoom > 0.0) { "Viewport zoom must be finite and positive." }
        require(windowOriginX.isFinite() && windowOriginY.isFinite()) { "Viewport origin must be finite." }
    }

    fun canvasToWindow(point: PointD) = PointD(
        windowOriginX + point.x * zoom,
        windowOriginY + point.y * zoom
    )

    fun windowToCanvas(point: PointD) = PointD(
        (point.x - windowOriginX) / zoom,
        (point.y - windowOriginY) / zoom
    )
}

/** All coordinates use a top-left origin, +x right, +y down, and pixel-edge positions. */
object CoordinateMapper {
    /** Maps a local window position through the viewport and into the layer's source pixels. */
    fun windowToLayer(
        window: PointD,
        viewport: ViewportTransform,
        sourceSize: PixelSize,
        transform: LayerTransform
    ): PointD = canvasToLayer(viewport.windowToCanvas(window), sourceSize, transform)

    fun layerToCanvas(source: PointD, sourceSize: PixelSize, transform: LayerTransform): PointD {
        val unflippedX = source.x / sourceSize.width * transform.width
        val unflippedY = source.y / sourceSize.height * transform.height
        val localX = if (transform.flipX) transform.width - unflippedX else unflippedX
        val localY = if (transform.flipY) transform.height - unflippedY else unflippedY
        val radians = transform.clockwiseDegrees * PI / 180.0
        val cosine = cos(radians)
        val sine = sin(radians)
        val dx = localX - transform.width / 2.0
        val dy = localY - transform.height / 2.0
        return PointD(
            transform.x + transform.width / 2.0 + dx * cosine - dy * sine,
            transform.y + transform.height / 2.0 + dx * sine + dy * cosine
        )
    }

    fun canvasToLayer(canvas: PointD, sourceSize: PixelSize, transform: LayerTransform): PointD {
        val radians = transform.clockwiseDegrees * PI / 180.0
        val cosine = cos(radians)
        val sine = sin(radians)
        val dx = canvas.x - (transform.x + transform.width / 2.0)
        val dy = canvas.y - (transform.y + transform.height / 2.0)
        val rotatedX = dx * cosine + dy * sine
        val rotatedY = -dx * sine + dy * cosine
        val localX = rotatedX + transform.width / 2.0
        val localY = rotatedY + transform.height / 2.0
        val unflippedX = if (transform.flipX) transform.width - localX else localX
        val unflippedY = if (transform.flipY) transform.height - localY else localY
        return PointD(
            unflippedX / transform.width * sourceSize.width,
            unflippedY / transform.height * sourceSize.height
        )
    }
}
