package com.wonderassembly.compositor.core

import java.util.ArrayDeque

/**
 * Bounded undo/redo history for committed sparse brush patches.
 *
 * The byte limit counts retained before/after pixel payloads. It does not include collection or
 * object headers. When a new patch is accepted, the oldest undo entries are discarded first.
 */
class ChunkedStrokeHistory(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxBytes: Long
) {
    private data class Entry(val patch: ChunkedStrokePatch, val retainedBytes: Long)

    private val undoEntries = ArrayDeque<Entry>()
    private val redoEntries = ArrayDeque<Entry>()
    var retainedBytes: Long = 0L
        private set

    val undoCount: Int get() = undoEntries.size
    val redoCount: Int get() = redoEntries.size
    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()

    init {
        require(maxEntries > 0) { "History must retain at least one entry." }
        require(maxBytes >= 0L) { "History byte budget cannot be negative." }
    }

    /**
     * Retains an already-applied patch. Returns false if this one patch cannot fit the byte budget;
     * in that case the caller must undo it or otherwise report that the edit was not accepted.
     */
    fun record(patch: ChunkedStrokePatch): Boolean {
        val bytes = patch.retainedSnapshotBytes
        if (bytes > maxBytes) return false

        clearRedoBranch()
        while (undoEntries.size >= maxEntries || retainedBytes > maxBytes - bytes) {
            val oldest = undoEntries.pollFirst() ?: return false
            retainedBytes -= oldest.retainedBytes
        }
        undoEntries.addLast(Entry(patch, bytes))
        retainedBytes = Math.addExact(retainedBytes, bytes)
        return true
    }

    /** Applies the latest undo in reverse order and moves it to the redo branch. */
    fun undo(): ChunkedStrokePatch? {
        val entry = undoEntries.pollLast() ?: return null
        try {
            entry.patch.undo()
        } catch (error: Throwable) {
            undoEntries.addLast(entry)
            throw error
        }
        redoEntries.addLast(entry)
        return entry.patch
    }

    /** Reapplies the earliest undone edit before moving to later redo entries. */
    fun redo(): ChunkedStrokePatch? {
        val entry = redoEntries.pollLast() ?: return null
        try {
            entry.patch.redo()
        } catch (error: Throwable) {
            redoEntries.addLast(entry)
            throw error
        }
        undoEntries.addLast(entry)
        return entry.patch
    }

    private fun clearRedoBranch() {
        while (redoEntries.isNotEmpty()) retainedBytes -= redoEntries.removeFirst().retainedBytes
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 20
    }
}
