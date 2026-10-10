package com.wonderassembly.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CanvasPreviewGeometryTest {
    @Test
    fun fitsLandscapeCanvasWithoutCroppingAndCentersItVertically() {
        val rect = CanvasPreviewGeometry.fit(1600.0, 900.0, 800.0, 600.0, 20.0)

        assertNotNull(rect)
        assertEquals(20.0, rect!!.left, 0.000001)
        assertEquals(86.25, rect.top, 0.000001)
        assertEquals(760.0, rect.width, 0.000001)
        assertEquals(427.5, rect.height, 0.000001)
    }

    @Test
    fun fitsPortraitCanvasWithoutCroppingAndCentersItHorizontally() {
        val rect = CanvasPreviewGeometry.fit(900.0, 1600.0, 1000.0, 700.0, 30.0)

        assertNotNull(rect)
        assertEquals(320.0, rect!!.left, 0.000001)
        assertEquals(30.0, rect.top, 0.000001)
        assertEquals(360.0, rect.width, 0.000001)
        assertEquals(640.0, rect.height, 0.000001)
    }

    @Test
    fun returnsNoCanvasWhenViewportCannotFitTheInset() {
        assertNull(CanvasPreviewGeometry.fit(100.0, 100.0, 20.0, 100.0, 12.0))
    }
}
