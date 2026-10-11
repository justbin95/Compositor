package com.wonderassembly.compositor.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BrushBudgetDiagnosticTest {
    @Test fun thirdFullChunkExceedsTwoChunkBudgetAndRollbackKeepsOldEditAndHistory() {
        val chunkBytes = 256L * 256L * 4L * 2L
        val raster = ChunkedRaster(1024, 256, maxCachedBytes = 2 * chunkBytes) { ByteArray(it.byteCount) }
        val history = ChunkedStrokeHistory(maxBytes = 2 * chunkBytes)
        fun shortStroke(x: Double): ChunkedStrokePatch = raster.beginBrushStroke(1.0, 0xffff0000.toInt()).run {
            addPoint(PointD(x, 20.5)); commit()!!
        }
        assertTrue(history.record(shortStroke(20.5)))
        val before = raster.committedChunkSnapshots().single().copyPixels()
        val long = raster.beginBrushStroke(1.0, 0xff000000.toInt())
        long.addPoint(PointD(30.5, 40.5))
        try { long.addPoint(PointD(800.5, 40.5)); fail("Expected two-chunk budget rejection") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("edit memory budget")) }
        long.cancel()
        assertArrayEquals(before, raster.committedChunkSnapshots().single().copyPixels())
        assertEquals(1, history.undoCount)
        assertEquals(chunkBytes, history.retainedBytes)
        assertTrue(history.record(shortStroke(40.5)))
        assertEquals(2, history.undoCount)
        history.undo(); history.undo()
        assertFalse(raster.hasCommittedChanges)
        history.redo(); history.redo()
        assertTrue(raster.hasCommittedChanges)
    }

    @Test fun resourceAccessFailureRemainsDifferentFromBudgetRejection() {
        val raster = ChunkedRaster(256, 256, maxCachedBytes = 524288) { throw IOException("Source access denied") }
        val stroke = raster.beginBrushStroke(1.0, 0xff000000.toInt())
        try { stroke.addPoint(PointD(20.5, 20.5)); fail() }
        catch (error: IOException) { assertEquals("Source access denied", error.message) }
        stroke.cancel()
        assertFalse(raster.hasCommittedChanges)
    }

    @Test fun twentyEntryLimitAndByteEvictionAndNewBranchPreserveOrder() {
        val raster = ChunkedRaster(64, 64) { ByteArray(it.byteCount) }
        val history = ChunkedStrokeHistory(maxBytes = Long.MAX_VALUE)
        for (x in 1..21) {
            val stroke = raster.beginBrushStroke(1.0, 0xff000000.toInt())
            stroke.addPoint(PointD(x + 0.5, 20.5))
            assertTrue(history.record(stroke.commit()!!))
        }
        assertEquals(20, history.undoCount)
        repeat(20) { assertNotNull(history.undo()) }
        assertNull(history.undo())
        assertTrue(raster.hasCommittedChanges) // First stroke was evicted, not undone.
        val stroke = raster.beginBrushStroke(1.0, 0xffff0000.toInt())
        stroke.addPoint(PointD(30.5, 20.5))
        assertTrue(history.record(stroke.commit()!!))
        assertEquals(0, history.redoCount)
        val small = ChunkedStrokeHistory(maxBytes = 64L * 64 * 8)
        for (x in 31..33) {
            val next = raster.beginBrushStroke(1.0, 0xffff0000.toInt())
            next.addPoint(PointD(x + 0.5, 20.5))
            assertTrue(small.record(next.commit()!!))
        }
        assertEquals(1, small.undoCount)
        assertEquals(32768, small.retainedBytes)
    }
}
