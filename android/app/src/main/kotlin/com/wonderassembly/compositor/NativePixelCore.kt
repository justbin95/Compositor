package com.wonderassembly.compositor

import com.wonderassembly.compositor.core.PixelBounds
import com.wonderassembly.compositor.core.PixelBufferLayout
import java.nio.ByteBuffer

/** Native pixel entry points accept direct buffers to avoid copying whole images across JNI. */
internal object NativePixelCore {
    init {
        System.loadLibrary("compositor_editor")
    }

    fun alphaBounds(buffer: ByteBuffer, layout: PixelBufferLayout): PixelBounds {
        require(buffer.isDirect) { "Pixel data must use a direct buffer." }
        require(layout.requiredBytes <= buffer.remaining().toLong()) {
            "Pixel buffer does not contain the complete image."
        }
        val result = nativeAlphaBounds(
            buffer.slice(), layout.width, layout.height, layout.rowStrideBytes
        )
        check(result.size == 4) { "Native alpha bounds returned an invalid result." }
        return PixelBounds(result[0], result[1], result[2], result[3])
    }

    private external fun nativeAlphaBounds(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        strideBytes: Int
    ): IntArray
}
