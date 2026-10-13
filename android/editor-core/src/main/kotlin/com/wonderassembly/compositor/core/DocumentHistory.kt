package com.wonderassembly.compositor.core

import java.util.ArrayDeque

/** One chronological branch for document edits; payloads remain owned by the caller. */
class DocumentHistory<T>(val maxBytes: Long, val maxEntries: Int = 20) {
    private data class Entry<T>(val edit: T, val bytes: Long)
    private val undo = ArrayDeque<Entry<T>>()
    private val redo = ArrayDeque<Entry<T>>()
    var retainedBytes = 0L
        private set
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun retainedEdits(): List<T> = (undo.toList() + redo.toList()).map { it.edit }

    init { require(maxBytes >= 0 && maxEntries > 0) }

    fun record(edit: T, bytes: Long): Boolean {
        require(bytes >= 0)
        if (bytes > maxBytes) return false
        while (redo.isNotEmpty()) retainedBytes -= redo.removeFirst().bytes
        while (undo.size >= maxEntries || retainedBytes > maxBytes - bytes) {
            retainedBytes -= undo.removeFirst().bytes
        }
        undo.addLast(Entry(edit, bytes))
        retainedBytes += bytes
        return true
    }

    fun undo(apply: (T) -> Unit): T? = move(undo, redo, apply)
    fun redo(apply: (T) -> Unit): T? = move(redo, undo, apply)

    private fun move(from: ArrayDeque<Entry<T>>, to: ArrayDeque<Entry<T>>, apply: (T) -> Unit): T? {
        val entry = from.peekLast() ?: return null
        apply(entry.edit)
        from.removeLast()
        to.addLast(entry)
        return entry.edit
    }

    fun clear() { undo.clear(); redo.clear(); retainedBytes = 0 }
}
