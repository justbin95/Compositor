package com.wonderassembly.compositor

/** Pure layout math shared by the canvas preview and its unit tests. */
internal object CanvasPreviewGeometry {
    data class Rect(val left: Double, val top: Double, val width: Double, val height: Double) {
        val right: Double get() = left + width
        val bottom: Double get() = top + height
    }

    /** Fits a document inside a view while preserving its aspect ratio and centering it. */
    fun fit(documentWidth: Double, documentHeight: Double, viewWidth: Double, viewHeight: Double, inset: Double): Rect? {
        if (documentWidth <= 0.0 || documentHeight <= 0.0 || viewWidth <= 2 * inset || viewHeight <= 2 * inset) return null
        val scale = minOf((viewWidth - 2 * inset) / documentWidth, (viewHeight - 2 * inset) / documentHeight)
        if (!scale.isFinite() || scale <= 0.0) return null
        val width = documentWidth * scale
        val height = documentHeight * scale
        return Rect((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
    }
}
