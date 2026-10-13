package com.wonderassembly.compositor

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectLayerMetadataEditorTest {
    private val context = RuntimeEnvironment.getApplication()
    private fun base() = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
        .create(16, 16, 72.0) { false }

    @Test fun metadataPreservesAssetsUnknownFieldsAndOriginalBytes() {
        val original = base()
        try {
            val manifest = JSONObject(String(original.originalManifestBytes, Charsets.UTF_8)).put("futureField", "retained")
            manifest.getJSONArray("layers").getJSONObject(0).put("futureLayerField", 42)
            val project = original.copy(originalManifestBytes = manifest.toString().toByteArray(Charsets.UTF_8))
            val bytes = project.originalManifestBytes.copyOf()
            val asset = java.io.File(requireNotNull(original.packageAssets.single().uri.path))
            val pixels = asset.readBytes()
            val changed = ProjectLayerMetadataEditor(context).update(project, project.layers.single().id, "Renamed", false, 0.25)
            assertEquals("Renamed", changed.layers.single().name)
            assertFalse(changed.layers.single().visible)
            assertEquals(0.25, changed.layers.single().opacity, 0.0)
            assertEquals("retained", changed.originalManifest.getString("futureField"))
            assertEquals(42, changed.originalManifest.getJSONArray("layers").getJSONObject(0).getInt("futureLayerField"))
            assertArrayEquals(bytes, project.originalManifestBytes)
            assertArrayEquals(pixels, asset.readBytes())
            assertEquals(project.packageAssets, changed.packageAssets)
            assertEquals(project.archiveCacheDirectory, changed.archiveCacheDirectory)
        } finally { original.releasePrivateAssets() }
    }

    @Test fun noOpAndInvalidChangesPreserveProject() {
        val project = base()
        try {
            val editor = ProjectLayerMetadataEditor(context)
            val layer = project.layers.single()
            assertSame(project, editor.update(project, layer.id, layer.name, layer.visible, layer.opacity))
            for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
                assertThrows(IllegalArgumentException::class.java) { editor.update(project, layer.id, opacity = invalid) }
            }
            for (invalid in listOf(" ", "中".repeat(6000))) {
                assertThrows(IllegalArgumentException::class.java) { editor.update(project, layer.id, name = invalid) }
            }
            assertThrows(NoSuchElementException::class.java) { editor.update(project, "missing", name = "Name") }
        } finally { project.releasePrivateAssets() }
    }

    @Test fun siblingMovesAreReversibleAndBoundariesAreNoOps() {
        val base = base()
        val image = base()
        try {
            val project = ProjectLayerAppender(context).append(base, image)
            val editor = ProjectLayerMetadataEditor(context)
            val id = base.layers.single().id
            val moved = editor.move(project, id, 1)
            assertEquals(project.layers.map { it.id }.reversed(), moved.layers.map { it.id })
            assertEquals(project.layers.map { it.id }, editor.move(moved, id, -1).layers.map { it.id })
            assertSame(moved, editor.move(moved, id, 1))
            assertEquals(project.packageAssets, moved.packageAssets)
            assertThrows(IllegalArgumentException::class.java) { editor.move(project, id, 2) }
        } finally { base.releasePrivateAssets(); image.releasePrivateAssets() }
    }

    @Test fun changedMetadataSurvivesProductionArchiveReopen() {
        val project = base()
        val archive = java.io.File.createTempFile("metadata-", ".zip", context.cacheDir)
        try {
            val changed = ProjectLayerMetadataEditor(context).update(project, project.layers.single().id, "Saved name", false, 0.125)
            archive.outputStream().use { CompProjectArchiveWriter(context.contentResolver).write(it, changed, emptyMap()) }
            val reopened = CompProjectArchiveImporter(context, CompProjectReader(context)).read(android.net.Uri.fromFile(archive)) { false }
            try {
                assertEquals(changed.layers, reopened.layers)
                assertArrayEquals(changed.originalManifestBytes, reopened.originalManifestBytes)
                assertArrayEquals(java.io.File(project.imageUris.values.single().path!!).readBytes(),
                    java.io.File(reopened.imageUris.values.single().path!!).readBytes())
            } finally { reopened.releasePrivateAssets() }
        } finally { project.releasePrivateAssets(); archive.delete() }
    }

    @Test fun groupOpacityUpgradeRetainsChildrenAndSiblingBoundaries() {
        val project = base()
        try {
            val manifest = JSONObject(String(project.originalManifestBytes, Charsets.UTF_8)).put("version", 7)
            val records = manifest.getJSONArray("layers")
            val groupId = java.util.UUID.randomUUID().toString().uppercase(java.util.Locale.ROOT)
            records.getJSONObject(0).put("parentID", groupId)
            val group = JSONObject(records.getJSONObject(0).toString()).put("id", groupId).put("isGroup", true)
            group.remove("imageFile"); group.remove("parentID")
            records.put(group)
            val grouped = CompProjectReader(context).readProject(project.sourceUri, project.packageFolderName,
                manifest.toString().toByteArray(Charsets.UTF_8), project.packageAssets, project.archiveCacheDirectory)
            val changed = ProjectLayerMetadataEditor(context).update(grouped, groupId, opacity = 0.4)
            assertEquals(8, changed.formatVersion)
            assertEquals(groupId, changed.layers.first().parentId)
            assertEquals(0.4, changed.layers.last().opacity, 0.0)
            assertSame(changed, ProjectLayerMetadataEditor(context).move(changed, changed.layers.first().id, 1))
        } finally { project.releasePrivateAssets() }
    }

    @Test fun appearanceUpgradeUsesExistingFormatVersion() {
        val original = base()
        try {
            val manifest = JSONObject(String(original.originalManifestBytes, Charsets.UTF_8)).put("version", 1)
            val old = CompProjectReader(context).readProject(original.sourceUri, original.packageFolderName,
                manifest.toString().toByteArray(Charsets.UTF_8), original.packageAssets, original.archiveCacheDirectory)
            val editor = ProjectLayerMetadataEditor(context)
            assertEquals(1, editor.update(old, old.layers.single().id, name = "Name").formatVersion)
            assertEquals(3, editor.update(old, old.layers.single().id, opacity = 0.5).formatVersion)
        } finally { original.releasePrivateAssets() }
    }
}
