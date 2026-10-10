package com.wonderassembly.compositor.core

import java.util.Collections
import kotlin.math.max

data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Pixel dimensions must be positive." }
    }

    val pixels: Long get() = Math.multiplyExact(width.toLong(), height.toLong())
}

enum class SamplingMode { NEAREST, SMOOTH, HIGH_QUALITY }

data class LayerTransform(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val clockwiseDegrees: Double = 0.0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val sampling: SamplingMode = SamplingMode.SMOOTH
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Layer origin must be finite." }
        require(width.isFinite() && height.isFinite() && width > 0.0 && height > 0.0) {
            "Layer size must be finite and positive."
        }
        require(clockwiseDegrees.isFinite()) { "Layer rotation must be finite." }
    }
}

data class EditableLayer(
    val id: String,
    val name: String,
    val sourceSize: PixelSize,
    val transform: LayerTransform,
    val visible: Boolean = true,
    val opacity: Double = 1.0,
    val blendMode: String = "Normal",
    val maskPixels: Long = 0
) {
    init {
        require(id.isNotBlank()) { "Layer ID cannot be empty." }
        require(name.isNotBlank()) { "Layer name cannot be empty." }
        require(opacity.isFinite() && opacity in 0.0..1.0) { "Layer opacity must be between 0 and 1." }
        require(blendMode.isNotBlank()) { "Blend mode cannot be empty." }
        require(maskPixels >= 0) { "Mask pixel count cannot be negative." }
    }
}

/** Runtime headroom is supplied by the platform; the core only evaluates byte estimates. */
data class ResourceBudget(val maxEditableBytes: Long) {
    init {
        require(maxEditableBytes >= 0) { "Editable memory budget cannot be negative." }
    }

    fun requireFits(footprint: DocumentFootprint) {
        require(footprint.estimatedWorkingBytes <= maxEditableBytes) {
            "Estimated document memory ${footprint.estimatedWorkingBytes} bytes exceeds the current " +
                "editable budget of $maxEditableBytes bytes."
        }
    }
}

data class DocumentFootprint(
    val canvasPixels: Long,
    val rasterPixels: Long,
    val largestLayerPixels: Long,
    val maskPixels: Long
) {
    /** Composite + all editable RGBA sources + one full-size edit scratch + grayscale masks. */
    val estimatedWorkingBytes: Long get() = Math.addExact(
        Math.addExact(
            Math.multiplyExact(Math.addExact(canvasPixels, Math.addExact(rasterPixels, largestLayerPixels)), 4L),
            maskPixels
        ),
        0L
    )
}

/** Immutable metadata for an editable raster document. Layer order is explicitly bottom-to-top. */
class EditorDocument private constructor(
    val canvasSize: PixelSize,
    val resolutionPpi: Double,
    layersBottomToTop: List<EditableLayer>,
    val revision: Long
) {
    val layersBottomToTop: List<EditableLayer> = Collections.unmodifiableList(ArrayList(layersBottomToTop))

    private fun copy(
        layersBottomToTop: List<EditableLayer> = this.layersBottomToTop,
        revision: Long = this.revision
    ) = EditorDocument(canvasSize, resolutionPpi, layersBottomToTop, revision)

    val footprint: DocumentFootprint
        get() {
            var rasterPixels = 0L
            var largestLayerPixels = 0L
            var maskPixels = 0L
            for (layer in layersBottomToTop) {
                val pixels = layer.sourceSize.pixels
                rasterPixels = Math.addExact(rasterPixels, pixels)
                largestLayerPixels = max(largestLayerPixels, pixels)
                maskPixels = Math.addExact(maskPixels, layer.maskPixels)
            }
            return DocumentFootprint(canvasSize.pixels, rasterPixels, largestLayerPixels, maskPixels)
        }

    fun addLayer(layer: EditableLayer, budget: ResourceBudget): EditorDocument {
        require(layersBottomToTop.none { it.id == layer.id }) { "Layer ID already exists." }
        val updated = copy(layersBottomToTop = layersBottomToTop + layer, revision = Math.addExact(revision, 1))
        updated.validate(budget)
        return updated
    }

    fun replaceLayer(layer: EditableLayer, budget: ResourceBudget): EditorDocument {
        val index = layersBottomToTop.indexOfFirst { it.id == layer.id }
        require(index >= 0) { "Layer does not exist." }
        val updatedLayers = layersBottomToTop.toMutableList().also { it[index] = layer }
        val updated = copy(layersBottomToTop = updatedLayers, revision = Math.addExact(revision, 1))
        updated.validate(budget)
        return updated
    }

    fun removeLayer(id: String, budget: ResourceBudget): EditorDocument {
        require(layersBottomToTop.any { it.id == id }) { "Layer does not exist." }
        val updated = copy(
            layersBottomToTop = layersBottomToTop.filterNot { it.id == id },
            revision = Math.addExact(revision, 1)
        )
        updated.validate(budget)
        return updated
    }

    fun moveLayer(id: String, destinationIndex: Int, budget: ResourceBudget): EditorDocument {
        val oldIndex = layersBottomToTop.indexOfFirst { it.id == id }
        require(oldIndex >= 0) { "Layer does not exist." }
        require(destinationIndex in layersBottomToTop.indices) { "Layer destination is out of range." }
        if (oldIndex == destinationIndex) return this
        val updatedLayers = layersBottomToTop.toMutableList().also {
            val layer = it.removeAt(oldIndex)
            it.add(destinationIndex, layer)
        }
        val updated = copy(layersBottomToTop = updatedLayers, revision = Math.addExact(revision, 1))
        updated.validate(budget)
        return updated
    }

    fun validate(budget: ResourceBudget) {
        require(canvasSize.width <= MAX_CANVAS_SIDE && canvasSize.height <= MAX_CANVAS_SIDE) {
            "Canvas side exceeds the current editable limit."
        }
        require(canvasSize.pixels <= MAX_CANVAS_PIXELS) { "Canvas area exceeds the current editable limit." }
        require(resolutionPpi.isFinite() && resolutionPpi in 1.0..9600.0) { "Resolution must be valid." }
        require(layersBottomToTop.size <= MAX_RASTER_LAYERS) { "The current editable layer limit was reached." }
        require(layersBottomToTop.map { it.id }.toSet().size == layersBottomToTop.size) { "Layer IDs must be unique." }
        budget.requireFits(footprint)
    }

    companion object {
        const val MAX_CANVAS_SIDE = 4096
        const val MAX_CANVAS_PIXELS = 16_800_000L
        const val MAX_RASTER_LAYERS = 10

        fun create(
            width: Int,
            height: Int,
            resolutionPpi: Double,
            budget: ResourceBudget
        ): EditorDocument {
            val document = EditorDocument(PixelSize(width, height), resolutionPpi, emptyList(), 0)
            document.validate(budget)
            return document
        }
    }
}
