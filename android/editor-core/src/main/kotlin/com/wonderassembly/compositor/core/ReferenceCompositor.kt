package com.wonderassembly.compositor.core

/** The first-release blend modes used by the platform-independent pixel reference path. */
enum class ReferenceBlendMode(val projectName: String) {
    NORMAL("Normal"),
    DARKEN("Darken"),
    MULTIPLY("Multiply"),
    LIGHTEN("Lighten"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DIFFERENCE("Difference");

    companion object {
        fun fromProjectName(name: String): ReferenceBlendMode? = entries.firstOrNull { it.projectName == name }
    }
}

/**
 * Composites an RGBA8 premultiplied sRGB layer into an equally sized destination.
 * Rows may have padding. Blend functions operate on unpremultiplied sRGB colors; the
 * Porter-Duff source-over result is stored back as premultiplied RGBA8.
 */
object ReferenceCompositor {
    fun compositeLayer(
        destination: ByteArray,
        destinationLayout: PixelBufferLayout,
        source: ByteArray,
        sourceLayout: PixelBufferLayout,
        mode: ReferenceBlendMode,
        opacity: Float = 1f
    ) {
        require(destinationLayout.width == sourceLayout.width && destinationLayout.height == sourceLayout.height) {
            "Source and destination dimensions must match."
        }
        require(destinationLayout.format == PixelFormat.RGBA8_SRGB_PREMULTIPLIED &&
            sourceLayout.format == PixelFormat.RGBA8_SRGB_PREMULTIPLIED) {
            "The reference compositor requires premultiplied RGBA8 sRGB pixels."
        }
        require(destination.size.toLong() >= destinationLayout.requiredBytes) {
            "Destination buffer is shorter than its declared layout."
        }
        require(source.size.toLong() >= sourceLayout.requiredBytes) {
            "Source buffer is shorter than its declared layout."
        }
        require(opacity.isFinite() && opacity in 0f..1f) { "Layer opacity must be between 0 and 1." }

        val width = destinationLayout.width
        val height = destinationLayout.height
        for (y in 0 until height) {
            var destinationIndex = y * destinationLayout.rowStrideBytes
            var sourceIndex = y * sourceLayout.rowStrideBytes
            repeat(width) {
                val sourceAlpha = (source[sourceIndex + 3].toInt() and 0xff) / 255f
                val backdropAlpha = (destination[destinationIndex + 3].toInt() and 0xff) / 255f
                val sourceAlphaWithOpacity = sourceAlpha * opacity
                val outputAlpha = sourceAlphaWithOpacity + backdropAlpha * (1f - sourceAlphaWithOpacity)

                for (channel in 0..2) {
                    val sourcePremultiplied = (source[sourceIndex + channel].toInt() and 0xff) / 255f
                    val backdropPremultiplied = (destination[destinationIndex + channel].toInt() and 0xff) / 255f
                    val sourceColor = if (sourceAlpha > 0f) (sourcePremultiplied / sourceAlpha).coerceIn(0f, 1f) else 0f
                    val backdropColor = if (backdropAlpha > 0f) (backdropPremultiplied / backdropAlpha).coerceIn(0f, 1f) else 0f
                    val blendedColor = blendChannel(sourceColor, backdropColor, mode)
                    val outputPremultiplied =
                        sourceAlphaWithOpacity * (1f - backdropAlpha) * sourceColor +
                            sourceAlphaWithOpacity * backdropAlpha * blendedColor +
                            (1f - sourceAlphaWithOpacity) * backdropAlpha * backdropColor
                    destination[destinationIndex + channel] = toByte(outputPremultiplied.coerceIn(0f, outputAlpha))
                }
                destination[destinationIndex + 3] = toByte(outputAlpha)
                destinationIndex += PixelBufferLayout.BYTES_PER_PIXEL
                sourceIndex += PixelBufferLayout.BYTES_PER_PIXEL
            }
        }
    }

    private fun blendChannel(source: Float, backdrop: Float, mode: ReferenceBlendMode): Float = when (mode) {
        ReferenceBlendMode.NORMAL -> source
        ReferenceBlendMode.DARKEN -> minOf(source, backdrop)
        ReferenceBlendMode.MULTIPLY -> source * backdrop
        ReferenceBlendMode.LIGHTEN -> maxOf(source, backdrop)
        ReferenceBlendMode.SCREEN -> source + backdrop - source * backdrop
        ReferenceBlendMode.OVERLAY -> if (backdrop <= 0.5f) {
            2f * source * backdrop
        } else {
            1f - 2f * (1f - source) * (1f - backdrop)
        }
        ReferenceBlendMode.DIFFERENCE -> kotlin.math.abs(backdrop - source)
    }

    private fun toByte(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
}
