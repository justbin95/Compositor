package com.wonderassembly.compositor.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Identifies one fixed-size raster chunk. The final row or column may be smaller. */
data class RasterChunkId(val column: Int, val row: Int) {
    init {
        require(column >= 0 && row >= 0) { "Chunk coordinates cannot be negative." }
    }
}

data class RasterChunkBounds(
    val id: RasterChunkId,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
) {
    init {
        require(left >= 0 && top >= 0 && width > 0 && height > 0) { "Chunk bounds must be non-empty." }
    }

    val rowStrideBytes: Int get() = Math.multiplyExact(width, PixelBufferLayout.BYTES_PER_PIXEL)
    val byteCount: Int get() = Math.multiplyExact(rowStrideBytes, height)
}

/** An immutable copy of one chunk, encoded as premultiplied RGBA8/sRGB. */
class RasterChunkSnapshot internal constructor(
    val bounds: RasterChunkBounds,
    pixels: ByteArray
) {
    private val data = pixels.copyOf()

    init {
        require(data.size == bounds.byteCount) { "Chunk bytes do not match its dimensions." }
    }

    fun copyPixels(): ByteArray = data.copyOf()

    internal fun contentEquals(other: RasterChunkSnapshot): Boolean =
        bounds == other.bounds && data.contentEquals(other.data)

    internal fun contentEquals(otherBounds: RasterChunkBounds, pixels: ByteArray): Boolean =
        bounds == otherBounds && data.contentEquals(pixels)
}

data class RasterChunkChange(
    val id: RasterChunkId,
    val before: RasterChunkSnapshot,
    val after: RasterChunkSnapshot
)

/**
 * Sparse 256x256 editing surface. Original pixels are loaded only for chunks touched by a stroke;
 * untouched source pixels stay in the caller's backing image or project file.
 */
class ChunkedRaster(
    val width: Int,
    val height: Int,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxCachedBytes: Long = Long.MAX_VALUE,
    private val loadChunk: (RasterChunkBounds) -> ByteArray
) {
    internal data class ChunkRecord(
        val bounds: RasterChunkBounds,
        val originalPixels: ByteArray,
        val pixels: ByteArray
    )

    private val chunks = LinkedHashMap<RasterChunkId, ChunkRecord>()
    private val committedChunks = LinkedHashSet<RasterChunkId>()
    private var activeToken: Long? = null
    private var nextToken = 1L
    private var cachedBytes = 0L

    val hasCommittedChanges: Boolean get() = committedChunks.isNotEmpty()

    init {
        require(width > 0 && height > 0) { "Raster dimensions must be positive." }
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "Chunk size is outside the safe range." }
        require(maxCachedBytes >= 0L) { "Chunk cache budget cannot be negative." }
        Math.multiplyExact(width.toLong(), height.toLong())
    }

    /** Starts the only active brush transaction for this raster. */
    fun beginBrushStroke(
        diameterPixels: Double,
        colorArgb: Int,
        opacity: Double = 1.0
    ): ChunkedBrushStroke {
        require(diameterPixels.isFinite() && diameterPixels > 0.0) { "Brush diameter must be positive." }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "Brush opacity must be between 0 and 1." }
        check(activeToken == null) { "A brush transaction is already active." }
        val token = nextToken
        nextToken = Math.addExact(nextToken, 1L)
        activeToken = token
        return ChunkedBrushStroke(this, token, diameterPixels, colorArgb, opacity)
    }

    /** Returns committed edit chunks for display without copying the source image. */
    fun committedChunkSnapshots(): List<RasterChunkSnapshot> = committedChunks.mapNotNull { id ->
        chunks[id]?.let { RasterChunkSnapshot(it.bounds, it.pixels) }
    }

    /** Visits one immutable chunk at a time so composition does not copy every edit at once. */
    fun forEachCommittedChunkSnapshot(action: (RasterChunkSnapshot) -> Unit) {
        for (id in committedChunks) {
            chunks[id]?.let { action(RasterChunkSnapshot(it.bounds, it.pixels)) }
        }
    }

    /** Overlays committed edited pixels for one source row onto a tightly packed RGBA row. */
    fun copyCommittedRowOnto(y: Int, destination: ByteArray) {
        require(y in 0 until height) { "The requested raster row is outside the image." }
        val rowBytes = Math.multiplyExact(width, PixelBufferLayout.BYTES_PER_PIXEL)
        require(destination.size >= rowBytes) { "The destination is shorter than one raster row." }
        for (id in committedChunks) {
            val record = chunks[id] ?: continue
            val bounds = record.bounds
            if (y < bounds.top || y >= bounds.top + bounds.height) continue
            val sourceOffset = (y - bounds.top) * bounds.rowStrideBytes
            val destinationOffset = bounds.left * PixelBufferLayout.BYTES_PER_PIXEL
            record.pixels.copyInto(
                destination,
                destinationOffset = destinationOffset,
                startIndex = sourceOffset,
                endIndex = sourceOffset + bounds.rowStrideBytes
            )
        }
    }

    /** Returns a changed chunk while a stroke is active or after it has been committed. */
    fun visibleChunkSnapshot(id: RasterChunkId): RasterChunkSnapshot? = chunks[id]?.let { record ->
        if (record.pixels.contentEquals(record.originalPixels)) null
        else RasterChunkSnapshot(record.bounds, record.pixels)
    }

    internal fun chunkIdsIntersecting(left: Int, top: Int, right: Int, bottom: Int): List<RasterChunkId> {
        if (right <= left || bottom <= top) return emptyList()
        val firstColumn = left / chunkSize
        val lastColumn = (right - 1) / chunkSize
        val firstRow = top / chunkSize
        val lastRow = (bottom - 1) / chunkSize
        val result = ArrayList<RasterChunkId>()
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) result += RasterChunkId(column, row)
        }
        return result
    }

    internal fun chunkBounds(id: RasterChunkId): RasterChunkBounds {
        require(id.column < ((width.toLong() + chunkSize - 1L) / chunkSize).toInt() &&
            id.row < ((height.toLong() + chunkSize - 1L) / chunkSize).toInt()) {
            "Chunk is outside the raster."
        }
        val left = Math.multiplyExact(id.column, chunkSize)
        val top = Math.multiplyExact(id.row, chunkSize)
        return RasterChunkBounds(
            id = id,
            left = left,
            top = top,
            width = min(chunkSize, width - left),
            height = min(chunkSize, height - top)
        )
    }

    internal fun mutablePixels(token: Long, id: RasterChunkId): ChunkRecord {
        check(activeToken == token) { "The brush transaction is no longer active." }
        return chunks.getOrPut(id) {
            val bounds = chunkBounds(id)
            val chunkStorageBytes = Math.multiplyExact(bounds.byteCount.toLong(), 2L)
            require(Math.addExact(cachedBytes, chunkStorageBytes) <= maxCachedBytes) {
                "The brush chunk would exceed the current edit memory budget."
            }
            val original = loadChunk(bounds)
            require(original.size == bounds.byteCount) { "Loaded chunk bytes do not match its dimensions." }
            requirePremultipliedRgba(original)
            cachedBytes = Math.addExact(cachedBytes, chunkStorageBytes)
            ChunkRecord(bounds, original.copyOf(), original.copyOf())
        }
    }

    internal fun commit(token: Long, before: Map<RasterChunkId, RasterChunkSnapshot>): ChunkedStrokePatch? {
        check(activeToken == token) { "The brush transaction is no longer active." }
        val changes = before.mapNotNull { (id, initial) ->
            val current = chunks.getValue(id)
            val after = RasterChunkSnapshot(current.bounds, current.pixels)
            if (initial.contentEquals(after)) null else RasterChunkChange(id, initial, after)
        }
        if (changes.isEmpty()) {
            activeToken = null
            return null
        }
        val patch = ChunkedStrokePatch(this, changes)
        changes.forEach { committedChunks += it.id }
        activeToken = null
        return patch
    }

    internal fun cancel(token: Long, before: Map<RasterChunkId, RasterChunkSnapshot>) {
        check(activeToken == token) { "The brush transaction is no longer active." }
        before.forEach { (id, snapshot) ->
            val record = chunks.getValue(id)
            snapshot.copyPixels().copyInto(record.pixels)
            updateCommittedState(record)
        }
        activeToken = null
    }

    internal fun applyPatch(changes: List<RasterChunkChange>, useAfter: Boolean) {
        check(activeToken == null) { "Cannot change history during a brush transaction." }
        changes.forEach { change ->
            val record = chunks.getValue(change.id)
            val expected = if (useAfter) change.before else change.after
            check(expected.contentEquals(record.bounds, record.pixels)) {
                "Brush changes must be undone and redone in history order."
            }
        }
        changes.forEach { change ->
            val record = chunks.getValue(change.id)
            (if (useAfter) change.after else change.before).copyPixels().copyInto(record.pixels)
            updateCommittedState(record)
        }
    }

    internal fun isCurrent(token: Long): Boolean = activeToken == token

    private fun updateCommittedState(record: ChunkRecord) {
        if (record.pixels.contentEquals(record.originalPixels)) committedChunks -= record.bounds.id
        else committedChunks += record.bounds.id
    }

    private fun requirePremultipliedRgba(pixels: ByteArray) {
        for (offset in pixels.indices step PixelBufferLayout.BYTES_PER_PIXEL) {
            val alpha = pixels[offset + 3].toInt() and 0xff
            require((pixels[offset].toInt() and 0xff) <= alpha &&
                (pixels[offset + 1].toInt() and 0xff) <= alpha &&
                (pixels[offset + 2].toInt() and 0xff) <= alpha) {
                "Loaded chunks must use premultiplied RGBA8 pixels."
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 256
        private const val MAX_CHUNK_SIZE = 1024
    }
}

/** One logical hard round brush stroke. It returns a chunk patch that a history stack can retain. */
class ChunkedBrushStroke internal constructor(
    private val raster: ChunkedRaster,
    private val token: Long,
    private val diameterPixels: Double,
    private val colorArgb: Int,
    private val opacity: Double
) {
    private val before = LinkedHashMap<RasterChunkId, RasterChunkSnapshot>()
    private var previousPoint: PointD? = null
    private var finished = false

    val touchedChunkIds: Set<RasterChunkId> get() = before.keys.toSet()

    /** Ends one in-bounds run so the next point does not bridge an out-of-layer gap. */
    fun endSegment() {
        requireActive()
        previousPoint = null
    }

    /** Adds a source-layer pixel-edge position, filling the segment from the previous point. */
    fun addPoint(point: PointD): Set<RasterChunkId> {
        requireActive()
        val previous = previousPoint
        val dirtyChunks = LinkedHashSet<RasterChunkId>()
        if (previous == null) {
            dirtyChunks += stamp(point.x, point.y)
        } else {
            val distance = hypot(point.x - previous.x, point.y - previous.y)
            if (distance == 0.0) {
                dirtyChunks += stamp(point.x, point.y)
            } else {
                val radius = diameterPixels / 2.0
                val spacing = max(1.0, radius * STAMP_SPACING_RADIUS_FACTOR)
                val requestedSteps = ceil(distance / spacing)
                require(requestedSteps.isFinite() && requestedSteps <= MAX_STAMPS_PER_SEGMENT) {
                    "A brush segment exceeds the current processing limit."
                }
                val steps = requestedSteps.toInt().coerceAtLeast(1)
                for (step in 1..steps) {
                    val fraction = step.toDouble() / steps
                    dirtyChunks += stamp(
                        previous.x + (point.x - previous.x) * fraction,
                        previous.y + (point.y - previous.y) * fraction
                    )
                }
            }
        }
        previousPoint = point
        return dirtyChunks
    }

    /** Commits this stroke as a before/after patch; an empty stroke produces no patch. */
    fun commit(): ChunkedStrokePatch? {
        requireActive()
        val patch = raster.commit(token, before)
        finished = true
        return patch
    }

    /** Restores only chunks touched by this stroke and discards the transaction. */
    fun cancel() {
        requireActive()
        raster.cancel(token, before)
        finished = true
    }

    private fun stamp(centerX: Double, centerY: Double): Set<RasterChunkId> {
        if (opacity == 0.0 || ((colorArgb ushr 24) and 0xff) == 0) return emptySet()
        val radius = diameterPixels / 2.0
        val left = max(0, floor(centerX - radius - 0.5).toInt())
        val top = max(0, floor(centerY - radius - 0.5).toInt())
        val right = min(raster.width, ceil(centerX + radius + 0.5).toInt())
        val bottom = min(raster.height, ceil(centerY + radius + 0.5).toInt())
        if (right <= left || bottom <= top) return emptySet()

        val ids = raster.chunkIdsIntersecting(left, top, right, bottom)
        for (id in ids) {
            val record = raster.mutablePixels(token, id)
            if (id !in before) before[id] = RasterChunkSnapshot(record.bounds, record.pixels)
        }

        for (y in top until bottom) {
            val dy = y + 0.5 - centerY
            for (x in left until right) {
                val dx = x + 0.5 - centerX
                val coverage = (radius + ANTIALIAS_RADIUS - sqrt(dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                if (coverage <= 0.0) continue
                val id = RasterChunkId(x / raster.chunkSize, y / raster.chunkSize)
                val record = raster.mutablePixels(token, id)
                val offset = ((y - record.bounds.top) * record.bounds.width + (x - record.bounds.left)) * 4
                compositePixel(record.pixels, offset, colorArgb, opacity, coverage)
            }
        }
        return ids.toSet()
    }

    private fun compositePixel(destination: ByteArray, offset: Int, argb: Int, opacity: Double, coverage: Double) {
        val sourceAlpha = (((argb ushr 24) and 0xff) * opacity * coverage).toInt().coerceIn(0, 255)
        if (sourceAlpha == 0) return
        val inverseAlpha = 255 - sourceAlpha
        val sourceRed = (((argb ushr 16) and 0xff) * sourceAlpha + 127) / 255
        val sourceGreen = (((argb ushr 8) and 0xff) * sourceAlpha + 127) / 255
        val sourceBlue = ((argb and 0xff) * sourceAlpha + 127) / 255
        val destinationAlpha = destination[offset + 3].toInt() and 0xff
        val outputAlpha = sourceAlpha + (destinationAlpha * inverseAlpha + 127) / 255
        destination[offset] = (sourceRed + ((destination[offset].toInt() and 0xff) * inverseAlpha + 127) / 255)
            .coerceAtMost(outputAlpha).toByte()
        destination[offset + 1] = (sourceGreen + ((destination[offset + 1].toInt() and 0xff) * inverseAlpha + 127) / 255)
            .coerceAtMost(outputAlpha).toByte()
        destination[offset + 2] = (sourceBlue + ((destination[offset + 2].toInt() and 0xff) * inverseAlpha + 127) / 255)
            .coerceAtMost(outputAlpha).toByte()
        destination[offset + 3] = outputAlpha.toByte()
    }

    private fun requireActive() {
        check(!finished && raster.isCurrent(token)) { "The brush transaction is no longer active." }
    }

    private companion object {
        const val ANTIALIAS_RADIUS = 0.5
        const val STAMP_SPACING_RADIUS_FACTOR = 0.35
        const val MAX_STAMPS_PER_SEGMENT = 32_768.0
    }
}

/** Reversible snapshots for one stroke; a history stack should apply them in LIFO order. */
class ChunkedStrokePatch internal constructor(
    private val raster: ChunkedRaster,
    changes: List<RasterChunkChange>
) {
    val changes: List<RasterChunkChange> = changes.toList()
    val changedChunkCount: Int get() = changes.size
    val changedChunkIds: Set<RasterChunkId> = changes.mapTo(LinkedHashSet()) { it.id }
    val retainedSnapshotBytes: Long = this.changes.fold(0L) { total, change ->
        Math.addExact(total, Math.multiplyExact(change.before.bounds.byteCount.toLong(), 2L))
    }
    private var applied = true

    fun undo() {
        check(applied) { "The brush stroke has already been undone." }
        raster.applyPatch(changes, useAfter = false)
        applied = false
    }

    fun redo() {
        check(!applied) { "The brush stroke is already applied." }
        raster.applyPatch(changes, useAfter = true)
        applied = true
    }
}
