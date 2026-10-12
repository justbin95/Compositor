package com.wonderassembly.compositor.core

import org.junit.Assert.*
import org.junit.Test

class BrushToolsTest {
    @Test fun eraserReducesPremultipliedChannelsAndUndoRestoresAcrossChunks() {
        val raster = ChunkedRaster(520, 16, maxCachedBytes = 1L shl 20, loadChunk = { b ->
            ByteArray(b.width * b.height * 4) { i -> if (i % 4 == 0 || i % 4 == 3) 128.toByte() else 0 }
        })
        val stroke = raster.beginBrushStroke(4.0, 0, 0.5, erase = true)
        stroke.addPoint(PointD(254.5, 8.5)); stroke.addPoint(PointD(258.5, 8.5))
        val patch = stroke.commit()!!
        assertEquals(2, patch.changes.size)
        for (change in patch.changes) {
            val pixels = change.after.copyPixels()
            for (i in pixels.indices step 4) {
                assertEquals(pixels[i], pixels[i + 3])
                assertEquals(0, pixels[i + 1].toInt())
            }
        }
        assertTrue(patch.changes.any { it.after.copyPixels().any { b -> (b.toInt() and 255) in 1..127 } })
        patch.undo(); assertFalse(raster.hasCommittedChanges)
        patch.redo(); assertTrue(raster.hasCommittedChanges)
    }

    @Test fun softBrushHasOpaqueCenterAndPartialEdgeAndCancelRestores() {
        val raster = ChunkedRaster(32, 32, maxCachedBytes = 1L shl 20, loadChunk = { b -> ByteArray(b.width * b.height * 4) })
        val stroke = raster.beginBrushStroke(16.0, 0xff00ff00.toInt(), hardness = 0.0)
        stroke.addPoint(PointD(16.5, 16.5))
        val bytes = raster.visibleChunkSnapshot(RasterChunkId(0, 0))!!.copyPixels()
        assertEquals(255, bytes[(16 * 32 + 16) * 4 + 3].toInt() and 255)
        assertTrue((bytes[(16 * 32 + 22) * 4 + 3].toInt() and 255) in 1..254)
        assertEquals(0, bytes[(16 * 32 + 26) * 4 + 3].toInt())
        stroke.cancel(); assertFalse(raster.hasCommittedChanges)
    }
}
