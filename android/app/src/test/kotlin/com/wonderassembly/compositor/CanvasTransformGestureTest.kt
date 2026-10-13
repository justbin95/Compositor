package com.wonderassembly.compositor

import com.wonderassembly.compositor.core.PointD
import org.junit.Assert.*
import org.junit.Test

class CanvasTransformGestureTest {
    private val original = CompProjectReader.LayerTransform(10.0, 20.0, 100.0, 50.0, 0.0, false, false, "Nearest")
    @Test fun movementRotationAndRoundingMatchMacRules() {
        val start = PointD(60.0, 45.0)
        val move = CanvasTransformGesture(original, start, CanvasTransformGesture.MOVE)
        assertSame(original, move.updated(start, true))
        val moved = move.updated(PointD(69.5, 40.5), true)
        assertEquals(19.5, moved.x, 0.0); assertEquals(15.5, moved.y, 0.0)
        val rounded = CanvasTransformGesture.committed(moved.copy(x = -2.5))
        assertEquals(-3.0, rounded.x, 0.0); assertEquals(16.0, rounded.y, 0.0)
        val rotate = CanvasTransformGesture(original, PointD(60.0, 20.0), CanvasTransformGesture.ROTATE)
        assertEquals(90.0, rotate.updated(PointD(85.0, 45.0), false).rotation, 1e-10)
        assertThrows(IllegalArgumentException::class.java) { PointD(Double.NaN, 0.0) }
        assertSame(original, move.updated(PointD(2_000_000.0, 0.0), false))
    }
    @Test fun ratioLockedCornerAndEdgeKeepAnchorAndAspect() {
        val start = CanvasTransformGesture.pointAt(original, PointD(1.0, 1.0))
        val gesture = CanvasTransformGesture(original, start, 4)
        val scaled = gesture.updated(PointD(start.x + 100, start.y + 50), true)
        assertEquals(200.0, scaled.width, 1e-10); assertEquals(100.0, scaled.height, 1e-10)
        assertEquals(original.x, scaled.x, 1e-10); assertEquals(original.y, scaled.y, 1e-10)
        val edgeStart = CanvasTransformGesture.pointAt(original, PointD(1.0, 0.5))
        val edge = CanvasTransformGesture(original, edgeStart, 3).updated(PointD(edgeStart.x + 50, edgeStart.y), true)
        assertEquals(150.0, edge.width, 1e-10); assertEquals(75.0, edge.height, 1e-10)
        assertEquals(original.x, edge.x, 1e-10); assertEquals(7.5, edge.y, 1e-10)
    }
    @Test fun rotatedResizeKeepsOppositeCornerAndCrossingFlips() {
        val rotated = original.copy(rotation = 31.0, flipX = true)
        val start = CanvasTransformGesture.pointAt(rotated, PointD(1.0, 1.0))
        val anchor = CanvasTransformGesture.pointAt(rotated, PointD(0.0, 0.0))
        val expanded = CanvasTransformGesture(rotated, start, 4).updated(PointD(start.x + 30, start.y + 25), false)
        val remaining = CanvasTransformGesture.pointAt(expanded, PointD(0.0, 0.0))
        assertEquals(anchor.x, remaining.x, 1e-10); assertEquals(anchor.y, remaining.y, 1e-10)
        val unrotatedStart = CanvasTransformGesture.pointAt(original, PointD(1.0, 1.0))
        val crossed = CanvasTransformGesture(original, unrotatedStart, 4).updated(PointD(0.0, 10.0), false)
        assertTrue(crossed.flipX); assertTrue(crossed.flipY)
        assertEquals(10.0, crossed.width, 0.0); assertEquals(10.0, crossed.height, 0.0)
        assertEquals(0.0, crossed.x, 0.0); assertEquals(10.0, crossed.y, 0.0)
        assertTrue(CanvasTransformGesture.contains(rotated, CanvasTransformGesture.pointAt(rotated, PointD(0.5, 0.5))))
    }
}
