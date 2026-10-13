package com.wonderassembly.compositor

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectLayerTransformEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun base() = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
        .create(16, 16, 72.0) { false }

    @Test fun transformPreservesUnknownFieldsAndPixelsAndReopens() {
        val base = base()
        val archive = java.io.File.createTempFile("transform-", ".zip", context.cacheDir)
        try {
            val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
            manifest.getJSONArray("layers").getJSONObject(0).getJSONObject("transform").put("futureTransformField", 42)
            val project = base.copy(originalManifestBytes = manifest.toString().toByteArray(Charsets.UTF_8))
            val layer = project.layers.single()
            val target = layer.transform.copy(x = -5.5, y = 12.25, width = 32.0, height = 8.0, rotation = 31.0, flipX = true, sampling = "Nearest")
            val editor = ProjectLayerTransformEditor(context)
            val changed = editor.update(project, layer.id, target)
            assertEquals(target, changed.layers.single().transform)
            assertEquals(42, changed.originalManifest.getJSONArray("layers").getJSONObject(0).getJSONObject("transform").getInt("futureTransformField"))
            assertSame(changed, editor.update(changed, layer.id, target))
            assertEquals(layer.transform, project.layers.single().transform)
            archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, changed, emptyMap()) }
            val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(android.net.Uri.fromFile(archive)) { false }
            try {
                assertEquals(target, reopened.layers.single().transform)
                assertArrayEquals(java.io.File(base.imageUris.values.single().path!!).readBytes(), java.io.File(reopened.imageUris.values.single().path!!).readBytes())
            } finally { reopened.releasePrivateAssets() }
            for (invalid in listOf(target.copy(width = 0.0), target.copy(y = 1_000_001.0), target.copy(rotation = Double.NaN), target.copy(rotation = 361.0), target.copy(sampling = "invalid"))) {
                assertThrows(IllegalArgumentException::class.java) { editor.update(project, layer.id, invalid) }
            }
        } finally { base.releasePrivateAssets(); archive.delete() }
    }

    @Test fun linkedAndUnlinkedMasksFollowMacPlacementRules() {
        val base = base()
        try {
            val layer = base.layers.single()
            val maskName = "${layer.id}.mask.png"
            val maskFile = java.io.File(java.io.File(base.imageUris.values.single().path!!).parentFile, maskName)
            val gray = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            for (y in 0 until 16) for (x in 0 until 16) gray.raster.setSample(x, y, 0, 255)
            javax.imageio.ImageIO.write(gray, "png", maskFile)
            val asset = CompProjectReader.PackageAsset(maskName, android.net.Uri.fromFile(maskFile), maskFile.length(), false)
            fun masked(linked: Boolean, placed: Boolean): CompProjectReader.Project {
                val manifest = JSONObject(String(base.originalManifestBytes, Charsets.UTF_8))
                val record = manifest.getJSONArray("layers").getJSONObject(0).put("maskFile", maskName).put("maskLinked", linked)
                if (placed) record.put("maskPlacement", JSONObject(record.getJSONObject("transform").toString()).apply {
                    getJSONObject("origin").put("x", 4.0).put("y", 2.0)
                })
                return CompProjectReader(context).readProject(base.sourceUri, base.packageFolderName,
                    manifest.toString().toByteArray(Charsets.UTF_8), base.packageAssets + asset, base.archiveCacheDirectory)
            }
            val target = layer.transform.copy(x = 10.0, y = 20.0)
            val editor = ProjectLayerTransformEditor(context)
            assertNull(editor.update(masked(true, false), layer.id, target).layers.single().maskTransform)
            val linked = editor.update(masked(true, true), layer.id, target).layers.single().maskTransform!!
            assertEquals(14.0, linked.x, 0.0); assertEquals(22.0, linked.y, 0.0)
            assertEquals(layer.transform, editor.update(masked(false, false), layer.id, target).layers.single().maskTransform)
            assertEquals(4.0, editor.update(masked(false, true), layer.id, target).layers.single().maskTransform!!.x, 0.0)
        } finally { base.releasePrivateAssets() }
    }

    @Test fun linkedPlacementCarriesRotationScaleAndFlip() {
        val old = CompProjectReader.LayerTransform(0.0, 0.0, 20.0, 10.0, 0.0, false, false, "Smooth")
        val placed = old.copy(x = 5.0, y = 2.0, width = 4.0, height = 2.0)
        val new = old.copy(x = 10.0, y = 20.0, width = 40.0, height = 20.0, rotation = 90.0, flipX = true)
        val result = TransformPlacement.following(placed, old, new)
        assertEquals(8.0, result.width, 1e-10)
        assertEquals(4.0, result.height, 1e-10)
        fun point(t: CompProjectReader.LayerTransform, u: Double, v: Double): Pair<Double, Double> {
            val x = (u - 0.5) * t.width * if (t.flipX) -1 else 1
            val y = (v - 0.5) * t.height * if (t.flipY) -1 else 1
            val r = Math.toRadians(t.rotation)
            return Pair(t.x + t.width / 2 + x * cos(r) - y * sin(r), t.y + t.height / 2 + x * sin(r) + y * cos(r))
        }
        for ((u,v) in listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0)) {
            val source = point(placed, u, v)
            val expected = point(new, source.first / old.width, source.second / old.height)
            val actual = point(result, u, v)
            assertEquals(expected.first, actual.first, 1e-10)
            assertEquals(expected.second, actual.second, 1e-10)
        }
    }
}
