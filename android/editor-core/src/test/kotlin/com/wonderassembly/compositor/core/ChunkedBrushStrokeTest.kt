package com.wonderassembly.compositor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedBrushStrokeTest {
    @Test
    fun commitKeepsOnlyChangedChunksAndPatchCanUndoAndRedo() {
        val raster = transparentRaster(width = 600, height = 300)
        val stroke = raster.beginBrushStroke(diameterPixels = 4.0, colorArgb = RED)

        stroke.addPoint(PointD(255.5, 100.5))
        stroke.addPoint(PointD(257.5, 100.5))
        val patch = stroke.commit()

        assertNotNull(patch)
        assertEquals(setOf(0, 1), patch!!.changes.map { it.id.column }.toSet())
        assertEquals(setOf(0), patch.changes.map { it.id.row }.toSet())
        assertEquals(2, raster.committedChunkSnapshots().size)

        patch.undo()
        assertTrue(raster.committedChunkSnapshots().isEmpty())
        patch.redo()
        assertEquals(2, raster.committedChunkSnapshots().size)
    }

    @Test
    fun cancelRestoresTouchedPixelsAndCreatesNoCommittedChunk() {
        val raster = transparentRaster(width = 300, height = 300)
        val stroke = raster.beginBrushStroke(diameterPixels = 18.0, colorArgb = RED)
        stroke.addPoint(PointD(250.5, 100.5))
        stroke.addPoint(PointD(270.5, 100.5))
        assertNotNull(raster.visibleChunkSnapshot(RasterChunkId(0, 0)))
        stroke.cancel()

        assertTrue(raster.committedChunkSnapshots().isEmpty())
        assertEquals(null, raster.visibleChunkSnapshot(RasterChunkId(0, 0)))
        val nextStroke = raster.beginBrushStroke(diameterPixels = 1.0, colorArgb = RED)
        nextStroke.addPoint(PointD(250.5, 100.5))
        val patch = nextStroke.commit()

        assertNotNull(patch)
        assertTrue(patch!!.changes.single().before.copyPixels().all { it == 0.toByte() })
    }

    @Test
    fun lineInterpolationCrossesChunkBoundaryWithoutLeavingAColumnGap() {
        val raster = transparentRaster(width = 520, height = 20)
        val stroke = raster.beginBrushStroke(diameterPixels = 3.0, colorArgb = RED)
        stroke.addPoint(PointD(240.5, 10.5))
        stroke.addPoint(PointD(272.5, 10.5))
        val patch = stroke.commit()!!

        val paintedX = patch.changes.flatMap { change ->
            val pixels = change.after.copyPixels()
            buildList {
                val bounds = change.after.bounds
                for (x in 0 until bounds.width) {
                    val offset = (10 - bounds.top) * bounds.width * 4 + x * 4 + 3
                    if ((pixels[offset].toInt() and 0xff) > 0) add(bounds.left + x)
                }
            }
        }.toSet()
        assertTrue((240..272).all { it in paintedX })
        assertEquals(setOf(0, 1), patch.changes.map { it.id.column }.toSet())
    }

    @Test
    fun transparentBrushDoesNotCreateAnEdit() {
        val raster = transparentRaster(width = 16, height = 16)
        val stroke = raster.beginBrushStroke(diameterPixels = 8.0, colorArgb = 0x00ff0000)
        stroke.addPoint(PointD(8.0, 8.0))

        assertEquals(null, stroke.commit())
        assertFalse(raster.committedChunkSnapshots().isNotEmpty())
    }

    @Test
    fun semitransparentBrushCompositesIntoPremultipliedDestination() {
        val raster = ChunkedRaster(16, 16) { bounds ->
            ByteArray(bounds.byteCount).also { pixels ->
                for (offset in pixels.indices step 4) {
                    pixels[offset + 2] = 0x80.toByte()
                    pixels[offset + 3] = 0x80.toByte()
                }
            }
        }
        val stroke = raster.beginBrushStroke(diameterPixels = 1.0, colorArgb = 0x80ff0000.toInt())
        stroke.addPoint(PointD(8.5, 8.5))

        val after = stroke.commit()!!.changes.single().after.copyPixels()
        val offset = (8 * 16 + 8) * 4
        assertEquals(128, after[offset].toInt() and 0xff)
        assertEquals(0, after[offset + 1].toInt() and 0xff)
        assertEquals(64, after[offset + 2].toInt() and 0xff)
        assertEquals(192, after[offset + 3].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStraightAlphaChunkData() {
        ChunkedRaster(8, 8) { bounds ->
            ByteArray(bounds.byteCount).also { pixels ->
                pixels[0] = 0xff.toByte()
                pixels[3] = 0x80.toByte()
            }
        }.beginBrushStroke(diameterPixels = 4.0, colorArgb = RED).addPoint(PointD(4.0, 4.0))
    }

    private fun transparentRaster(width: Int, height: Int) = ChunkedRaster(width, height) { bounds ->
        ByteArray(bounds.byteCount)
    }

    private companion object {
        const val RED = 0xffff0000.toInt()
    }
}
