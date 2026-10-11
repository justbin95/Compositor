package com.wonderassembly.compositor

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class ImageImportPipelineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun importer() = ProjectImageImporter(context) { AndroidRasterBudget(64L * 1024 * 1024, 0, 64L * 1024 * 1024, 2048) }

    @Test fun transparentPngImportsThroughDecoderManifestReaderAndSurvivesSourceDeletion() {
        val source = File.createTempFile("image-input-", ".png", context.cacheDir)
        val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        image.setPixel(10, 10, 0x80ff0000.toInt())
        image.setPixel(20, 20, 0xff00ff00.toInt())
        source.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }; image.recycle()
        val project = importer().read(Uri.fromFile(source)) { false }
        assertEquals(320, project.width)
        assertEquals(240, project.height)
        assertEquals("sRGB", project.originalManifest.getString("colorSpace"))
        assertTrue(source.delete())
        val imported = requireNotNull(BitmapFactory.decodeFile(project.packageAssets.single().uri.path!!))
        assertEquals(0, imported.getPixel(0, 0))
        assertEquals(0x80ff0000.toInt(), imported.getPixel(10, 10))
        assertEquals(0xff00ff00.toInt(), imported.getPixel(20, 20))
        imported.recycle()
        project.archiveCacheDirectory!!.deleteRecursively()
    }

    @Test fun exifOrientationsOneThreeSixEightBecomeOrientedIndependentPngs() {
        val original = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        for (y in 0 until 240) for (x in 0 until 320) original.setPixel(x, y, 0xff000000.toInt() or (
            when { x < 160 && y < 120 -> 0xff0000; x >= 160 && y < 120 -> 0x00ff00
                x < 160 -> 0x0000ff; else -> 0xffff00 }))
        val jpeg = ByteArrayOutputStream().also { check(original.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        original.recycle()
        for (orientation in listOf(1, 3, 6, 8)) {
            val source = File.createTempFile("exif-input-", ".jpg", context.cacheDir)
            source.writeBytes(withExif(jpeg, orientation))
            val project = importer().read(Uri.fromFile(source)) { false }
            assertEquals(if (orientation in listOf(6, 8)) 240 else 320, project.width)
            assertEquals(if (orientation in listOf(6, 8)) 320 else 240, project.height)
            val imported = requireNotNull(BitmapFactory.decodeFile(project.packageAssets.single().uri.path!!))
            val colors = when (orientation) {
                1 -> listOf(0xff0000, 0x00ff00, 0x0000ff, 0xffff00)
                3 -> listOf(0xffff00, 0x0000ff, 0x00ff00, 0xff0000)
                6 -> listOf(0x0000ff, 0xff0000, 0xffff00, 0x00ff00)
                else -> listOf(0x00ff00, 0xffff00, 0xff0000, 0x0000ff)
            }
            val points = listOf(20 to 20, imported.width - 21 to 20, 20 to imported.height - 21,
                imported.width - 21 to imported.height - 21)
            for ((index, point) in points.withIndex()) {
                val actual = imported.getPixel(point.first, point.second)
                for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs(((actual shr shift) and 255) - ((colors[index] shr shift) and 255)) <= 3)
            }
            assertTrue(source.delete())
            assertTrue(File(project.packageAssets.single().uri.path!!).isFile)
            imported.recycle()
            project.archiveCacheDirectory!!.deleteRecursively()
        }
    }

    private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        // One little-endian TIFF IFD containing the orientation SHORT, carried by APP1.
        val payload = byteArrayOf(69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0,
            1, 0, 18, 1, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0)
        return jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (payload.size + 2).toByte()) +
            payload + jpeg.copyOfRange(2, jpeg.size)
    }
}
