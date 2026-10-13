package com.wonderassembly.compositor

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.wonderassembly.compositor.core.ReferencePngWriter
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TiledCompositeTest {
    private val budget = AndroidRasterBudget(16L shl 20, 16L shl 20, 32L shl 20, 2048)

    @Test fun premultipliedSamplingHasNoTransparentColorFringeAndKeepsPixelCenters() {
        val transform = CompProjectReader.LayerTransform(0.0, 0.0, 2.0, 1.0, 0.0, false, false, "Smooth")
        val pixels = intArrayOf(0xffff0000.toInt(), 0x000000ff)
        val sampler = TransformedRasterSampler(pixels, Rect(0, 0, 2, 1), 2, 1, transform)
        assertEquals(0xffff0000.toInt(), sampler.pixelAt(0.5, 0.5))
        assertEquals(0x80ff0000.toInt(), sampler.pixelAt(1.0, 0.5))
        assertEquals(0, sampler.pixelAt(1.5, 0.5))
        val flipped = TransformedRasterSampler(pixels, Rect(0, 0, 2, 1), 2, 1, transform.copy(flipX = true, sampling = "Nearest"))
        assertEquals(0, flipped.pixelAt(0.5, 0.5))
        assertEquals(0xffff0000.toInt(), flipped.pixelAt(1.5, 0.5))
    }

    @Test fun masksClippingAndCommittedChunksMatchWholeReference() {
        val context = RuntimeEnvironment.getApplication()
        val creator = BlankProjectCreator(context) { budget }
        val base = creator.create(513, 517, 72.0) { false }
        val upper = creator.create(513, 517, 72.0, BlankProjectCreator.Background.WHITE) { false }
        val session = LayerEditingSession(context, base.packageAssets.single().uri, 513, 517, 16L shl 20)
        try {
            val stroke = session.raster.beginBrushStroke(64.0, android.graphics.Color.BLACK)
            stroke.addPoint(com.wonderassembly.compositor.core.PointD(255.0, 255.0)); stroke.commit()
            val appended = ProjectLayerAppender(context).append(base, upper)
            val maskFile = File(upper.archiveCacheDirectory, "gradient.mask.png")
            val gray = java.awt.image.BufferedImage(513, 517, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            for (y in 0 until 517) for (x in 0 until 513) gray.raster.setSample(x, y, 0, 80 + x % 176)
            javax.imageio.ImageIO.write(gray, "png", maskFile)
            val name = maskFile.name
            val source = appended.layers[0].copy(maskFile = name, maskEnabled = true)
            val clipped = appended.layers[1].copy(maskSourceId = source.id)
            val project = appended.copy(layers = listOf(source, clipped), maskUris = mapOf(name to android.net.Uri.fromFile(maskFile)),
                maskSizes = mapOf(name to CompProjectReader.PixelSize(513, 517)))
            val edits = mapOf(base.layers.single().imageFile!! to session)
            val reference = PreviewCompositeRenderer(context, { budget }, ::decodeRegionForNativeCanvasTest)
                .render(project, { false }, { _, _ -> }, true, edits)
            try {
                val output = ByteArrayOutputStream()
                CompositeImageExporter(context, { budget }, ::decodeRegionForNativeCanvasTest).write(output, project, edits,
                    CompositeImageExporter.Format.PNG, 100, { false }, {})
                val saved = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())!!
                try {
                    for (y in 0 until 517) for (x in 0 until 513) {
                        val a = saved.getPixel(x, y); val b = reference.bitmap.getPixel(x, y)
                        assertEquals("alpha $x,$y", b ushr 24, a ushr 24)
                        for (shift in listOf(0, 8, 16)) {
                            val alpha = b ushr 24
                            assertTrue(kotlin.math.abs((a ushr shift and 255) * alpha / 255 - (b ushr shift and 255) * alpha / 255) <= 1)
                        }
                    }
                    assertTrue(saved.getPixel(255, 255) ushr 24 > 0)
                    assertEquals(0, saved.getPixel(0, 0) ushr 24)
                } finally { saved.recycle() }
            } finally { reference.bitmap.recycle() }
        } finally { session.close(); base.releasePrivateAssets(); upper.releasePrivateAssets() }
    }

    @Test fun stripBudgetAndRecursiveSplitsAreBoundedAndCoverEveryPixel() {
        assertEquals(256, TileExportPlan.create(4096, 16L shl 20).stripRows)
        val width = 30_000
        val bytes = 2L shl 20
        val rows = TileExportPlan.create(width, bytes).stripRows
        assertTrue(rows in 1..255)
        assertTrue(width.toLong() * rows * 4 + ReferencePngWriter.rowEncodingWorkingBytes(width) + width * 4 + (1L shl 20) <= bytes)
        try { TileExportPlan.create(width, 1L shl 20); fail() } catch (_: IllegalArgumentException) {}
        val parts = TileExportPlan.split(Rect(5, 9, 18, 16))!!
        assertEquals(91, parts.sumOf { it.width() * it.height() })
        assertFalse(parts[0].intersect(parts[1]))
        assertNull(TileExportPlan.split(Rect(5, 9, 6, 10)))
    }

    @Test fun largeCanvasExportsWithBudgetBelowWholeImageAndCancellationKeepsSource() {
        val context = RuntimeEnvironment.getApplication()
        val project = BlankProjectCreator(context) { budget }.create(2048, 2048, 72.0,
            BlankProjectCreator.Background.WHITE) { false }
        val limited = budget.copy(maxSingleBitmapBytes = 4L shl 20, maxMaskCacheBytes = 4L shl 20)
        val file = File(project.packageAssets.single().uri.path!!)
        val original = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        try {
            val output = ByteArrayOutputStream()
            CompositeImageExporter(context, { limited }, ::decodeRegionForNativeCanvasTest).write(output, project, emptyMap(),
                CompositeImageExporter.Format.PNG, 100, { false }, {})
            val saved = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())!!
            try {
                assertEquals(2048, saved.width); assertEquals(2048, saved.height)
                for (point in listOf(0, 255, 256, 1023, 2047)) assertEquals(android.graphics.Color.WHITE, saved.getPixel(point, point))
            } finally { saved.recycle() }
            val shrunk = project.copy(layers = project.layers.map { it.copy(transform = it.transform.copy(width = 512.0, height = 512.0)) })
            val adaptive = ByteArrayOutputStream()
            CompositeImageExporter(context, { limited }, ::decodeRegionForNativeCanvasTest).write(adaptive, shrunk, emptyMap(),
                CompositeImageExporter.Format.PNG, 100, { false }, {})
            val adaptiveSaved = BitmapFactory.decodeByteArray(adaptive.toByteArray(), 0, adaptive.size())!!
            try { assertEquals(android.graphics.Color.WHITE, adaptiveSaved.getPixel(100, 100)); assertEquals(0, adaptiveSaved.getPixel(600, 600)) }
            finally { adaptiveSaved.recycle() }
            var checks = 0
            try {
                CompositeImageExporter(context, { limited }, ::decodeRegionForNativeCanvasTest).write(ByteArrayOutputStream(), project,
                    emptyMap(), CompositeImageExporter.Format.PNG, 100, { ++checks > 15 }, {})
                fail("Cancellation must not report success")
            } catch (_: java.util.concurrent.CancellationException) {}
            assertArrayEquals(original, java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { project.releasePrivateAssets() }
    }

    @Test fun tiledPngMatchesWholeCanvasAcrossModesAndTransforms() {
        val context = RuntimeEnvironment.getApplication()
        val creator = BlankProjectCreator(context) { budget }
        val base = creator.create(513, 517, 72.0) { false }
        val imported = creator.create(320, 310, 72.0) { false }
        try {
            for (source in listOf(base, imported)) {
                File(source.packageAssets.single().uri.path!!).outputStream().use { output ->
                    ReferencePngWriter.writeRgba8PremultipliedRows(output, source.width, source.height) { y, row ->
                        for (x in 0 until source.width) {
                            val alpha = 100 + (x + y) % 156
                            row[x * 4] = ((x % 256) * alpha / 255).toByte()
                            row[x * 4 + 1] = ((y % 256) * alpha / 255).toByte()
                            row[x * 4 + 2] = (alpha / 2).toByte(); row[x * 4 + 3] = alpha.toByte()
                        }
                    }
                }
            }
            val appended = ProjectLayerAppender(context).append(base, imported)
            for (mode in listOf("Normal", "Darken", "Multiply", "Lighten", "Screen", "Overlay", "Difference")) {
                val manifest = org.json.JSONObject(String(appended.originalManifestBytes, Charsets.UTF_8))
                val layer = manifest.getJSONArray("layers").getJSONObject(1)
                layer.put("blendMode", mode)
                layer.getJSONObject("transform").put("rotation", 31.0).put("flipX", true)
                val project = CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
                    manifest.toString().toByteArray(), appended.packageAssets, base.archiveCacheDirectory)
                val reference = PreviewCompositeRenderer(context, { budget }, ::decodeRegionForNativeCanvasTest).render(project, { false }, { _, _ -> }, true)
                val output = ByteArrayOutputStream()
                try {
                    CompositeImageExporter(context, { budget }, ::decodeRegionForNativeCanvasTest).write(output, project, emptyMap(), CompositeImageExporter.Format.PNG,
                        100, { false }, {})
                    val saved = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size())!!
                    try {
                        assertEquals(project.width, saved.width); assertEquals(project.height, saved.height)
                        var worst = 0
                        var detail = ""
                        for (y in 0 until saved.height) for (x in 0 until saved.width) {
                            val actual = saved.getPixel(x, y); val expected = reference.bitmap.getPixel(x, y)
                            for (shift in listOf(0, 8, 16, 24)) {
                                val error = kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255))
                                if (error > worst) { worst = error; detail = "$x,$y actual=${actual.toUInt().toString(16)} expected=${expected.toUInt().toString(16)}" }
                            }
                        }
                        assertTrue("$mode max channel error $worst $detail", worst <= 2)
                        println("TILE08 $mode: ${project.width * project.height} pixels, max channel error $worst")
                    } finally { saved.recycle() }
                } finally { reference.bitmap.recycle() }
            }
        } finally { base.releasePrivateAssets(); imported.releasePrivateAssets() }
    }

    @Test fun inverseSourceBoundsRespectRotationAndFlip() {
        val transform = CompProjectReader.LayerTransform(0.0, 0.0, 1000.0, 1000.0, 0.0, false, false, "Nearest")
        val size = CompProjectReader.PixelSize(1000, 1000)
        assertEquals(Rect(96, 196, 204, 304), SourceRegionPlanner.bounds(Rect(100, 200, 200, 300), transform, size))
        assertEquals(Rect(796, 196, 904, 304), SourceRegionPlanner.bounds(Rect(100, 200, 200, 300), transform.copy(flipX = true), size))
        assertEquals(Rect(196, 796, 304, 904), SourceRegionPlanner.bounds(Rect(100, 200, 200, 300), transform.copy(rotation = 90.0), size))
    }
    // Robolectric 4.16.1's region decoder returns blank pixels even in NATIVE mode.
    // Exercise native Canvas and real PNG encoding with an explicit source-crop adapter;
    // production BitmapRegionDecoder pixel behavior is a separate device instrumented check.
    private fun decodeRegionForNativeCanvasTest(uri: android.net.Uri, size: CompProjectReader.PixelSize,
        bounds: Rect, options: BitmapFactory.Options): android.graphics.Bitmap {
        val full = BitmapFactory.decodeFile(uri.path!!)!!
        require(full.width == size.width && full.height == size.height)
        val crop = android.graphics.Bitmap.createBitmap(full, bounds.left, bounds.top, bounds.width(), bounds.height())
        val result = crop.copy(android.graphics.Bitmap.Config.ARGB_8888, options.inMutable)
        if (crop !== full) crop.recycle()
        full.recycle()
        return result
    }

}
