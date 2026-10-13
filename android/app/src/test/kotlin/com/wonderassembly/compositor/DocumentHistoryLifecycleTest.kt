package com.wonderassembly.compositor

import com.wonderassembly.compositor.core.DocumentHistory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DocumentHistoryLifecycleTest {
    @Test fun discardedAppendReclaimsPrivateFilesButRedoRetainsThem() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val creator = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
        val base = creator.create(16, 16, 72.0) { false }
        val image = creator.create(8, 8, 72.0) { false }
        val appended = ProjectLayerAppender(activity).append(base, image)
        field("currentProject").set(activity, appended)
        @Suppress("UNCHECKED_CAST")
        val sessions = field("layerEditingSessions").get(activity) as MutableMap<String, LayerEditingSession>
        val hiddenKey = "|${base.documentId}|${image.layers.single().id}"
        sessions[hiddenKey] = LayerEditingSession(activity, image.packageAssets.single().uri, 8, 8, 1L shl 20)
        val history = DocumentHistory<DocumentEdit>(1L shl 20)
        field("documentHistory").set(activity, history)
        history.record(DocumentEdit.Append(base, appended, base.activeLayerId, false), 4096)
        apply(activity, false)
        assertTrue(image.archiveCacheDirectory!!.exists())
        assertTrue(sessions.containsKey(hiddenKey))
        history.record(DocumentEdit.Append(base, base, base.activeLayerId, false), 1)
        MainActivity::class.java.getDeclaredMethod("reclaimUnusedDocumentResources").apply { isAccessible = true }.invoke(activity)
        assertFalse(image.archiveCacheDirectory.exists())
        assertFalse(sessions.containsKey(hiddenKey))
        assertTrue(base.archiveCacheDirectory!!.exists())
        assertEquals(1, (field("currentProject").get(activity) as CompProjectReader.Project).layers.size)
        controller.pause().stop().destroy()
    }
    @Test fun appendUndoRedoRetainsAssetsAcrossRecreationAndCleansOnClose() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        val creator = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
        val base = creator.create(16, 16, 72.0) { false }
        val imported = creator.create(8, 8, 72.0) { false }
        val appended = ProjectLayerAppender(activity).append(base, imported)
        field("currentProject").set(activity, appended)
        field("documentStructureChanged").setBoolean(activity, true)
        val history = DocumentHistory<DocumentEdit>(1L shl 20)
        field("documentHistory").set(activity, history)
        assertTrue(history.record(DocumentEdit.Append(base, appended, base.activeLayerId, false), 4096))
        apply(activity, false)
        val undone = field("currentProject").get(activity) as CompProjectReader.Project
        assertEquals(1, undone.layers.size)
        assertArrayEquals(base.originalManifestBytes, undone.originalManifestBytes)
        assertFalse(field("documentStructureChanged").getBoolean(activity))
        assertTrue(imported.archiveCacheDirectory!!.exists())
        controller.recreate()
        activity = controller.get()
        assertSame(history, field("documentHistory").get(activity))
        apply(activity, true)
        val redone = field("currentProject").get(activity) as CompProjectReader.Project
        assertEquals(2, redone.layers.size)
        assertArrayEquals(appended.originalManifestBytes, redone.originalManifestBytes)
        assertTrue(field("documentStructureChanged").getBoolean(activity))
        apply(activity, false)
        history.record(DocumentEdit.Append(base, base, base.activeLayerId, false), 1)
        assertFalse(history.canRedo)
        controller.pause().stop().destroy()
        assertFalse(base.archiveCacheDirectory!!.exists())
        assertFalse(imported.archiveCacheDirectory!!.exists())
    }

    @Test fun metadataUndoRedoAcrossRecreationRestoresManifestAndDirtyState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        val base = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }
            .create(16, 16, 72.0) { false }
        val changed = ProjectLayerMetadataEditor(activity).update(base, base.layers.single().id, "Renamed", false, 0.3)
        field("currentProject").set(activity, changed)
        field("documentStructureChanged").setBoolean(activity, true)
        val history = DocumentHistory<DocumentEdit>(1L shl 20)
        field("documentHistory").set(activity, history)
        assertTrue(history.record(DocumentEdit.Metadata(base, changed, base.layers.single().id, false), 4096))
        apply(activity, false)
        assertArrayEquals(base.originalManifestBytes, (field("currentProject").get(activity) as CompProjectReader.Project).originalManifestBytes)
        assertFalse(field("documentStructureChanged").getBoolean(activity))
        controller.recreate()
        activity = controller.get()
        apply(activity, true)
        assertArrayEquals(changed.originalManifestBytes, (field("currentProject").get(activity) as CompProjectReader.Project).originalManifestBytes)
        assertTrue(field("documentStructureChanged").getBoolean(activity))
        assertTrue(base.archiveCacheDirectory!!.exists())
        controller.pause().stop().destroy()
        assertFalse(base.archiveCacheDirectory.exists())
    }

    private fun apply(activity: MainActivity, redo: Boolean) =
        MainActivity::class.java.getDeclaredMethod("applyDocumentHistory", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, redo)
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
