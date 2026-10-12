package com.wonderassembly.compositor.core

import org.junit.Assert.*
import org.junit.Test

class DocumentHistoryTest {
    @Test fun mixedLayerAndStructureEditsFollowOneBranch() {
        val history = DocumentHistory<String>(100)
        val seen = mutableListOf<String>()
        history.record("stroke A", 10)
        history.record("append B", 5)
        history.record("stroke B", 10)
        repeat(3) { history.undo { seen.add(it) } }
        assertEquals(listOf("stroke B", "append B", "stroke A"), seen)
        seen.clear()
        repeat(3) { history.redo { seen.add(it) } }
        assertEquals(listOf("stroke A", "append B", "stroke B"), seen)
        history.undo {}
        assertFalse(history.record("too large", 101))
        assertTrue(history.canRedo)
        history.record("new stroke A", 10)
        assertFalse(history.canRedo)
    }

    @Test fun failedApplyKeepsCursorAndByteBudget() {
        val history = DocumentHistory<String>(10, 2)
        history.record("A", 6)
        history.record("B", 4)
        try { history.undo { error("failure") }; fail() } catch (_: IllegalStateException) {}
        assertEquals("B", history.undo {})
        assertEquals(10L, history.retainedBytes)
        history.record("C", 5)
        assertEquals(5L, history.retainedBytes)
        assertEquals("C", history.undo {})
        assertNull(history.undo {})
        history.clear()
        assertEquals(0L, history.retainedBytes)
        assertFalse(history.canRedo)
    }

    @Test fun twentyEditsEvictOldestAcrossLayers() {
        val history = DocumentHistory<Int>(100)
        repeat(21) { assertTrue(history.record(it, 1)) }
        assertEquals((20 downTo 1).toList(), List(20) { history.undo {} })
        assertNull(history.undo {})
    }

    @Test fun sharedStrokePatchesRestoreIndependentRasters() {
        val history = DocumentHistory<ChunkedStrokePatch>(1L shl 20)
        fun raster() = ChunkedRaster(8, 8, maxCachedBytes = 4096, loadChunk = { ByteArray(it.width * it.height * 4) })
        val a = raster(); val b = raster()
        for (raster in listOf(a, b)) {
            val stroke = raster.beginBrushStroke(2.0, 0xff000000.toInt())
            stroke.addPoint(PointD(4.0, 4.0))
            val patch = stroke.commit()!!
            assertTrue(history.record(patch, patch.retainedSnapshotBytes))
        }
        history.undo { it.undo() }
        assertTrue(a.hasCommittedChanges); assertFalse(b.hasCommittedChanges)
        history.undo { it.undo() }
        assertFalse(a.hasCommittedChanges)
        history.redo { it.redo() }; history.redo { it.redo() }
        assertTrue(a.hasCommittedChanges); assertTrue(b.hasCommittedChanges)
    }
}
