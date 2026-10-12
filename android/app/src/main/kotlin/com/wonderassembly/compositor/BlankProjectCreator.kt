package com.wonderassembly.compositor

import android.content.Context
import android.net.Uri
import com.wonderassembly.compositor.core.ReferencePngWriter
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

/** Creates a private raster project; the current project is replaced only by the caller. */
internal class BlankProjectCreator(
    private val context: Context,
    private val captureBudget: () -> AndroidRasterBudget = { AndroidRasterBudget.capture(context) }
) {
    enum class Background(val rgba: ByteArray) {
        TRANSPARENT(byteArrayOf(0, 0, 0, 0)),
        WHITE(byteArrayOf(-1, -1, -1, -1)),
        BLACK(byteArrayOf(0, 0, 0, -1))
    }

    fun create(width: Int, height: Int, resolution: Double, isCancelled: () -> Boolean): CompProjectReader.Project {
        return create(width, height, resolution, Background.TRANSPARENT, isCancelled)
    }

    fun create(width: Int, height: Int, resolution: Double, background: Background,
        isCancelled: () -> Boolean): CompProjectReader.Project {
        require(width in 1..30000 && height in 1..30000 && width.toLong() * height <= 100_000_000L) {
            "Choose positive dimensions within the project format limits."
        }
        require(resolution.isFinite() && resolution in 1.0..9600.0) { "Resolution must be between 1 and 9600 ppi." }
        // The encoder holds two rows, not two full-size raster surfaces. Preview admission
        // is checked separately by the caller before replacing the current project.
        require(ReferencePngWriter.rowEncodingWorkingBytes(width) <= captureBudget().maxSingleBitmapBytes) {
            "The canvas exceeds the current memory budget. Choose smaller dimensions."
        }
        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Project creation was cancelled.")
        }
        checkCancelled()
        val cache = File(context.cacheDir, "compositor-import-${UUID.randomUUID()}")
        val root = File(cache, "Untitled.comp")
        val images = File(root, "images")
        try {
            require(images.mkdirs()) { "Could not prepare private storage for the new project." }
            val id = UUID.randomUUID().toString().uppercase(Locale.ROOT)
            File(images, "$id.png").outputStream().use { output ->
                ReferencePngWriter.writeRgba8PremultipliedRows(output, width, height) { _, row ->
                    checkCancelled()
                    for (x in 0 until width) background.rgba.copyInto(row, x * 4)
                }
            }
            val manifest = ProjectImageImporter.importedImageManifest(id, width, height).put("resolution", resolution)
            manifest.getJSONArray("layers").getJSONObject(0).put("name", "Background")
            File(root, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
            checkCancelled()
            return CompProjectReader(context).readExtractedPackage(Uri.fromFile(root), root, "Untitled.comp", cache)
        } catch (error: Throwable) {
            cache.deleteRecursively()
            throw error
        }
    }
}
