package com.wonderassembly.compositor.core

import org.junit.Assert.*
import org.junit.Test

class FrozenRasterEditsTest {
    @Test fun frozenRowsKeepEarlierPixelsWhileUndoAndNewStrokesContinue() {
        val raster = ChunkedRaster(520, 8, maxCachedBytes = 1L shl 20, loadChunk = { ByteArray(it.byteCount) })
        val first = raster.beginBrushStroke(4.0, 0xff000000.toInt())
        first.addPoint(PointD(254.5, 4.5)); first.addPoint(PointD(258.5, 4.5))
        val patch = first.commit()!!
        val frozen = raster.freezeCommitted(1L shl 20)
        val expected = ByteArray(520 * 4).also { raster.copyCommittedRowOnto(4, it) }
        patch.undo()
        val next = raster.beginBrushStroke(4.0, 0xffff0000.toInt())
        next.addPoint(PointD(4.5, 4.5)); next.commit()
        val actual = ByteArray(520 * 4).also { frozen.copyRowOnto(4, it) }
        assertArrayEquals(expected, actual)
        assertTrue(actual.any { it != 0.toByte() })
        assertFalse(actual.contentEquals(ByteArray(520 * 4).also { raster.copyCommittedRowOnto(4, it) }))
    }

    @Test fun activeStrokeAndSnapshotBudgetAreRejectedWithoutChangingPixels() {
        val raster = ChunkedRaster(8, 8, maxCachedBytes = 4096, loadChunk = { ByteArray(it.byteCount) })
        val stroke = raster.beginBrushStroke(2.0, 0xff000000.toInt())
        stroke.addPoint(PointD(4.0, 4.0))
        try { raster.freezeCommitted(4096); fail() } catch (_: IllegalStateException) {}
        stroke.commit()
        try { raster.freezeCommitted(255); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(raster.hasCommittedChanges)
        assertEquals(256L, raster.freezeCommitted(256).retainedBytes)
    }
}
