package com.wonderassembly.compositor

import org.junit.Assert.*
import org.junit.Test

class NumericLayerTransformTest {
    private val value = CompProjectReader.LayerTransform(10.25, -5.5, 80.0, 30.0, 31.0, true, true, "Nearest")
    private val pixels = CompProjectReader.PixelSize(200, 100)

    @Test fun lockedDimensionsPreserveCurrentRatioAndOriginWithoutRounding() {
        val width = NumericLayerTransform.resize(value, 100.5, true, true)
        assertEquals(37.6875, width.height, 1e-10)
        val height = NumericLayerTransform.resize(width, 12.25, false, true)
        assertEquals(12.25 * 80 / 30, height.width, 1e-10)
        assertEquals(value.x, height.x, 0.0); assertEquals(value.y, height.y, 0.0)
        assertEquals(value.rotation, height.rotation, 0.0)
        assertTrue(height.flipX && height.flipY); assertEquals("Nearest", height.sampling)
        assertEquals(value.height, NumericLayerTransform.resize(value, 101.0, true, false).height, 0.0)
    }

    @Test fun absoluteSourceScalePreservesCenterAndDoesNotCompound() {
        assertEquals(40.0, NumericLayerTransform.scalePercent(value, pixels), 0.0)
        val first = NumericLayerTransform.scale(value, pixels, 50.25)
        assertEquals(100.5, first.width, 0.0); assertEquals(50.25, first.height, 0.0)
        assertEquals(value.x + value.width / 2, first.x + first.width / 2, 1e-10)
        assertEquals(value.y + value.height / 2, first.y + first.height / 2, 1e-10)
        val second = NumericLayerTransform.scale(first, pixels, 200.0)
        assertEquals(400.0, second.width, 0.0); assertEquals(200.0, second.height, 0.0)
        assertEquals(value.rotation, second.rotation, 0.0)
        assertTrue(second.flipX && second.flipY); assertEquals("Nearest", second.sampling)
    }

    @Test fun invalidInputAndResultingBoundsRejectWithoutClamping() {
        for (number in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 0.09, 30_001.0))
            assertThrows(IllegalArgumentException::class.java) { NumericLayerTransform.scale(value, pixels, number) }
        assertThrows(IllegalArgumentException::class.java) { NumericLayerTransform.scale(value, CompProjectReader.PixelSize(300_000, 300_000), 200.0) }
        assertThrows(IllegalArgumentException::class.java) { NumericLayerTransform.scale(value.copy(x = 1_000_000.0), pixels, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { NumericLayerTransform.resize(value.copy(height = 300_000.0), 160.0, true, true) }
        assertThrows(IllegalArgumentException::class.java) { NumericLayerTransform.resize(value, 0.5, false, false) }
        assertEquals(80.0, value.width, 0.0)
    }
}
