package com.wonderassembly.compositor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @Test
    fun windowToLayerUsesViewportAndLayerTransform() {
        val viewport = ViewportTransform(zoom = 2.0, windowOriginX = 10.0, windowOriginY = 20.0)
        val layer = LayerTransform(x = 100.0, y = 50.0, width = 200.0, height = 100.0)

        val source = CoordinateMapper.windowToLayer(
            window = PointD(410.0, 220.0),
            viewport = viewport,
            sourceSize = PixelSize(400, 200),
            transform = layer
        )

        assertEquals(200.0, source.x, 0.0001)
        assertEquals(100.0, source.y, 0.0001)
    }

    @Test
    fun layerMappingInvertsRotationAndFlips() {
        val size = PixelSize(400, 200)
        val transform = LayerTransform(
            x = -30.0,
            y = 50.0,
            width = 200.0,
            height = 100.0,
            clockwiseDegrees = 90.0,
            flipX = true,
            flipY = true
        )
        val source = PointD(80.0, 40.0)

        val canvas = CoordinateMapper.layerToCanvas(source, size, transform)
        val mappedBack = CoordinateMapper.canvasToLayer(canvas, size, transform)

        assertEquals(source.x, mappedBack.x, 0.0001)
        assertEquals(source.y, mappedBack.y, 0.0001)
    }
}
