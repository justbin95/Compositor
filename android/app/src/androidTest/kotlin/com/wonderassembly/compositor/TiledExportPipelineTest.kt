package com.wonderassembly.compositor

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.wonderassembly.compositor.core.ReferencePngWriter
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class TiledExportPipelineTest {
    private val budget = AndroidRasterBudget(16L shl 20, 16L shl 20, 32L shl 20, 2048)

    @Test fun tiledPngMatchesWholeCanvasAcrossModesAndTransforms() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
                val reference = PreviewCompositeRenderer(context, { budget }).render(project, { false }, { _, _ -> }, true)
                val output = ByteArrayOutputStream()
                try {
                    CompositeImageExporter(context, { budget }).write(output, project, emptyMap(), CompositeImageExporter.Format.PNG,
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
                    } finally { saved.recycle() }
                } finally { reference.bitmap.recycle() }
            }
        } finally { base.releasePrivateAssets(); imported.releasePrivateAssets() }
    }

}
