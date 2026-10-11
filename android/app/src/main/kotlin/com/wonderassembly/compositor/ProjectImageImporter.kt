package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import com.wonderassembly.compositor.core.ReferencePngWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException

/** Imports an oriented PNG/JPEG into a private one-layer project without changing the source. */
internal class ProjectImageImporter(
    private val context: Context,
    private val captureBudget: () -> AndroidRasterBudget = { AndroidRasterBudget.capture(context) }
) {
    fun read(uri: Uri, isCancelled: () -> Boolean): CompProjectReader.Project {
        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Image import was cancelled.")
        }
        val cache = File(context.cacheDir, "compositor-import-${UUID.randomUUID()}")
        require(cache.mkdirs()) { "Could not prepare private storage for the image." }
        var decoded: Bitmap? = null
        var normalized: Bitmap? = null
        try {
            val source = File(cache, "source-image")
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "The image exceeds the 512 MiB input limit." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("Could not read the selected image.")
            val budget = captureBudget()
            decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                require(info.mimeType == "image/png" || info.mimeType == "image/jpeg") { "Choose a PNG or JPEG image." }
                require(!info.isAnimated) { "Animated images are not supported." }
                val width = info.size.width
                val height = info.size.height
                require(width in 1..30000 && height in 1..30000 && width.toLong() * height <= 100_000_000L) {
                    "The image dimensions exceed the project format limits."
                }
                require(width.toLong() * height <= budget.maxSingleBitmapBytes / 8L) {
                    "The image exceeds the current import memory budget. No resized copy was imported."
                }
                checkCancelled()
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }
            checkCancelled()
            val original = requireNotNull(decoded)
            normalized = if (original.config == Bitmap.Config.ARGB_8888) original else
                original.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalArgumentException("Could not normalize the image to RGBA8.")
            val image = requireNotNull(normalized)
            val packageRoot = File(cache, "package")
            val images = File(packageRoot, "images")
            require(images.mkdirs()) { "Could not prepare image storage." }
            val layerId = UUID.randomUUID().toString().uppercase(Locale.ROOT)
            val pixels = IntArray(image.width)
            File(images, "$layerId.png").outputStream().use { output ->
                ReferencePngWriter.writeRgba8PremultipliedRows(output, image.width, image.height) { y, row ->
                    checkCancelled()
                    image.getPixels(pixels, 0, image.width, 0, y, image.width, 1)
                    for (x in pixels.indices) {
                        val pixel = pixels[x]
                        val alpha = Color.alpha(pixel)
                        val offset = x * 4
                        row[offset] = ((Color.red(pixel) * alpha + 127) / 255).toByte()
                        row[offset + 1] = ((Color.green(pixel) * alpha + 127) / 255).toByte()
                        row[offset + 2] = ((Color.blue(pixel) * alpha + 127) / 255).toByte()
                        row[offset + 3] = alpha.toByte()
                    }
                }
            }
            val manifest = importedImageManifest(layerId, image.width, image.height)
            File(packageRoot, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
            checkCancelled()
            check(source.delete()) { "Could not clean up the imported source image." }
            return CompProjectReader(context).readExtractedPackage(uri, packageRoot, "Imported Image.comp", cache)
        } catch (error: Throwable) {
            cache.deleteRecursively()
            throw error
        } finally {
            normalized?.let { if (it !== decoded) it.recycle() }
            decoded?.recycle()
        }
    }

    companion object {
        internal fun importedImageManifest(layerId: String, width: Int, height: Int): JSONObject {
            val transform = JSONObject().put("origin", JSONObject().put("x", 0).put("y", 0))
                .put("size", JSONObject().put("width", width).put("height", height))
                .put("rotation", 0).put("flipX", false).put("flipY", false).put("sampling", "High quality")
            val layer = JSONObject().put("id", layerId).put("name", "Imported Image")
                .put("isVisible", true).put("isGroup", false).put("opacity", 1)
                .put("blendMode", "Normal").put("imageFile", "$layerId.png").put("transform", transform)
            return JSONObject().put("format", "com.compositor.project").put("version", 11)
                .put("colorSpace", "sRGB").put("documentID", UUID.randomUUID().toString().uppercase(Locale.ROOT))
                .put("width", width).put("height", height).put("resolution", 72)
                .put("activeLayerID", layerId).put("layers", JSONArray().put(layer))
        }
    }
}
