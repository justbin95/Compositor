package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BlendMode
import com.wonderassembly.compositor.core.ReferencePngWriter
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException

/** Exact-size sRGB composite output, admitted only when the measured memory budget permits it. */
internal class CompositeImageExporter(private val context: Context) {
    enum class Format(val mimeType: String, val extension: String) {
        PNG("image/png", "png"), JPEG("image/jpeg", "jpg")
    }

    fun write(
        output: OutputStream,
        project: CompProjectReader.Project,
        editedAssets: Map<String, LayerEditingSession>,
        format: Format,
        quality: Int,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        require(quality in 1..100)
        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Image export was cancelled.")
        }
        val checkedOutput = object : FilterOutputStream(output) {
            override fun write(value: Int) { checkCancelled(); out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                checkCancelled()
                out.write(bytes, offset, length)
            }
            override fun flush() { checkCancelled(); out.flush() }
        }
        checkCancelled()
        val rendered = PreviewCompositeRenderer(context).render(
            project, isCancelled,
            { completed, total -> onProgress(if (total == 0) 65 else completed * 65 / total) },
            fullResolution = true, editedAssets = editedAssets
        )
        val bitmap = rendered.bitmap
        try {
            check(rendered.limitations.isEmpty()) { "The project uses features that cannot be exported yet." }
            checkCancelled()
            if (format == Format.PNG) {
                val pixels = IntArray(bitmap.width)
                ReferencePngWriter.writeRgba8PremultipliedRows(checkedOutput, bitmap.width, bitmap.height) { y, row ->
                    checkCancelled()
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in pixels.indices) {
                        val pixel = pixels[x]
                        val alpha = Color.alpha(pixel)
                        val offset = x * 4
                        row[offset] = ((Color.red(pixel) * alpha + 127) / 255).toByte()
                        row[offset + 1] = ((Color.green(pixel) * alpha + 127) / 255).toByte()
                        row[offset + 2] = ((Color.blue(pixel) * alpha + 127) / 255).toByte()
                        row[offset + 3] = alpha.toByte()
                    }
                    if (y % 32 == 0) onProgress(65 + (y + 1) * 34 / bitmap.height)
                }
            } else {
                // Paint white behind the existing premultiplied pixels without a second full bitmap.
                Canvas(bitmap).drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Paint().apply {
                    color = Color.WHITE
                    blendMode = BlendMode.DST_OVER
                })
                bitmap.setHasAlpha(false)
                onProgress(75)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, checkedOutput)) { "Could not encode the JPEG." }
                checkedOutput.flush()
            }
            checkCancelled()
            onProgress(100)
        } finally {
            bitmap.recycle()
        }
    }
}
