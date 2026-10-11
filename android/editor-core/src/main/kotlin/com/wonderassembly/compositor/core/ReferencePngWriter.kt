package com.wonderassembly.compositor.core

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Streams the reference compositor's top-first premultiplied RGBA pixels as a PNG. */
object ReferencePngWriter {
    fun writeRgba8Premultiplied(
        output: OutputStream,
        pixels: ByteArray,
        layout: PixelBufferLayout
    ) {
        require(layout.format == PixelFormat.RGBA8_SRGB_PREMULTIPLIED) {
            "The PNG reference writer requires premultiplied RGBA8 sRGB pixels."
        }
        require(pixels.size.toLong() >= layout.requiredBytes) {
            "Pixel buffer is shorter than its declared layout."
        }

        val rowBytes = Math.multiplyExact(layout.width, PixelBufferLayout.BYTES_PER_PIXEL)
        writeRgba8PremultipliedRows(output, layout.width, layout.height) { y, row ->
            val sourceOffset = Math.multiplyExact(y, layout.rowStrideBytes)
            val sourceEnd = Math.addExact(sourceOffset, rowBytes)
            pixels.copyInto(row, endIndex = sourceEnd, startIndex = sourceOffset, destinationOffset = 0)
        }
    }

    /** Writes one premultiplied RGBA8/sRGB row at a time to keep image-sized buffers out of memory. */
    fun writeRgba8PremultipliedRows(
        output: OutputStream,
        width: Int,
        height: Int,
        fillRow: (rowIndex: Int, destination: ByteArray) -> Unit
    ) {
        require(width > 0 && height > 0) { "PNG dimensions must be positive." }
        val pixelRowBytes = Math.multiplyExact(width, PixelBufferLayout.BYTES_PER_PIXEL)

        output.write(PNG_SIGNATURE)
        writeChunk(output, IHDR, header(width, height))
        writeChunk(output, SRGB, byteArrayOf(0))

        val pixels = ByteArray(pixelRowBytes)
        val row = ByteArray(Math.addExact(pixelRowBytes, 1))
        val compressed = ByteArray(COMPRESSED_BUFFER_SIZE)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            for (y in 0 until height) {
                fillRow(y, pixels)
                row[0] = 0 // PNG filter: None
                var outputIndex = 1
                var sourceIndex = 0
                repeat(width) {
                    val alpha = pixels[sourceIndex + 3].toInt() and 0xff
                    for (channel in 0..2) {
                        val premultiplied = pixels[sourceIndex + channel].toInt() and 0xff
                        row[outputIndex + channel] = if (alpha == 0) 0 else unpremultiply(premultiplied, alpha).toByte()
                    }
                    row[outputIndex + 3] = alpha.toByte()
                    sourceIndex += PixelBufferLayout.BYTES_PER_PIXEL
                    outputIndex += PixelBufferLayout.BYTES_PER_PIXEL
                }

                deflater.setInput(row)
                while (!deflater.needsInput()) {
                    val count = deflater.deflate(compressed)
                    if (count > 0) writeChunk(output, IDAT, compressed, count)
                }
            }

            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(compressed)
                if (count > 0) writeChunk(output, IDAT, compressed, count)
            }
        } finally {
            deflater.end()
        }

        writeChunk(output, IEND, EMPTY)
        output.flush()
    }

    private fun unpremultiply(channel: Int, alpha: Int): Int =
        ((channel * 255 + alpha / 2) / alpha).coerceIn(0, 255)

    private fun header(width: Int, height: Int) = byteArrayOf(
        (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
        (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
        8, 6, 0, 0, 0 // 8-bit RGBA, deflate, adaptive filtering, no interlace
    )

    private fun writeChunk(output: OutputStream, type: ByteArray, data: ByteArray, length: Int = data.size) {
        writeInt(output, length)
        output.write(type)
        output.write(data, 0, length)
        val crc = CRC32().apply {
            update(type)
            update(data, 0, length)
        }
        writeInt(output, crc.value.toInt())
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    private val SRGB = byteArrayOf(0x73, 0x52, 0x47, 0x42)
    private val IDAT = byteArrayOf(0x49, 0x44, 0x41, 0x54)
    private val IEND = byteArrayOf(0x49, 0x45, 0x4e, 0x44)
    private val EMPTY = byteArrayOf()
    private const val COMPRESSED_BUFFER_SIZE = 32 * 1024
}
