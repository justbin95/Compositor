package com.wonderassembly.compositor.core

/** The byte contract shared by CPU algorithms, GPU uploads, preview conversion, and encoders. */
enum class PixelChannelOrder { RGBA }
enum class PixelAlphaMode { PREMULTIPLIED }
enum class PixelRowOrder { TOP_TO_BOTTOM }

data class PixelFormat(
    val channelOrder: PixelChannelOrder = PixelChannelOrder.RGBA,
    val alphaMode: PixelAlphaMode = PixelAlphaMode.PREMULTIPLIED,
    val rowOrder: PixelRowOrder = PixelRowOrder.TOP_TO_BOTTOM,
    val bitsPerChannel: Int = 8,
    val colorSpace: String = "sRGB"
) {
    init {
        require(bitsPerChannel == 8) { "Only 8-bit channels are currently supported." }
        require(colorSpace == "sRGB") { "Only sRGB pixels are currently supported." }
    }

    companion object {
        val RGBA8_SRGB_PREMULTIPLIED = PixelFormat()
    }
}

/** Describes storage without assuming Android Bitmap, Core Graphics, or a tightly packed row. */
data class PixelBufferLayout(
    val width: Int,
    val height: Int,
    val rowStrideBytes: Int,
    val format: PixelFormat = PixelFormat.RGBA8_SRGB_PREMULTIPLIED
) {
    val minimumRowBytes: Long
    val requiredBytes: Long

    init {
        require(width > 0 && height > 0) { "Pixel buffer dimensions must be positive." }
        val minimum = Math.multiplyExact(width.toLong(), BYTES_PER_PIXEL.toLong())
        require(rowStrideBytes.toLong() >= minimum) { "Row stride is shorter than one pixel row." }
        minimumRowBytes = minimum
        requiredBytes = Math.addExact(
            Math.multiplyExact((height - 1).toLong(), rowStrideBytes.toLong()),
            minimum
        )
    }

    companion object {
        const val BYTES_PER_PIXEL = 4
    }
}

data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
}
