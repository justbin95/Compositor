package com.wonderassembly.compositor

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.wonderassembly.compositor.core.DocumentHistory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupTransformDialogTest {
    @Test fun canvasGroupReleaseUsesOneHistoryAndRestoresGroupTool() = canvasDrag(false)
    @Test fun canvasGroupBudgetRejectionKeepsAllMembersAndRedo() = canvasDrag(true)
    @Test fun activeCanvasDraftIsExcludedFromSnapshotAndConfigurationRecovery() = fixture { controller, f, history ->
        var activity = controller.get()
        field("canvasTransformLayerId").set(activity, f.root)
        MainActivity::class.java.getDeclaredMethod("showLayerPreview", CompProjectReader.Layer::class.java)
            .apply { isAccessible = true }.invoke(activity, f.project.layers.single { it.id == f.root })
        drain(activity)
        val view = field("canvasPreviewView").get(activity) as CanvasPreviewView
        view.layout(0, 0, 800, 600)
        val start = view.transformViewPoint(com.wonderassembly.compositor.core.PointD(0.5, 0.5))!!
        for ((action, x) in listOf(android.view.MotionEvent.ACTION_DOWN to start.x, android.view.MotionEvent.ACTION_MOVE to start.x + 40)) {
            val event = android.view.MotionEvent.obtain(0, 10, action, x.toFloat(), start.y.toFloat(), 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        drain(activity)
        assertTrue(view.hasActiveEdit)
        val file = java.io.File.createTempFile("group-draft-snapshot-", ".zip", activity.cacheDir)
        try {
            val snapshot = field("currentProject").get(activity) as CompProjectReader.Project
            file.outputStream().use { CompProjectArchiveWriter(activity.contentResolver).write(it, snapshot, emptyMap()) }
            val reopened = CompProjectArchiveImporter(activity, CompProjectReader(activity)).read(android.net.Uri.fromFile(file)) { false }
            try { assertEquals(f.project.layers, reopened.layers) } finally { reopened.releasePrivateAssets() }
        } finally { file.delete() }
        controller.recreate(); activity = controller.get(); drain(activity)
        val restored = field("canvasPreviewView").get(activity) as CanvasPreviewView
        assertTrue(restored.isTransformMode); assertFalse(restored.hasActiveEdit)
        assertEquals(GroupTransformPlan.forGroup(f.project, f.root).box, restored.displayedLayerTransform)
        assertEquals(f.project.layers, (field("currentProject").get(activity) as CompProjectReader.Project).layers)
        assertFalse(history.canUndo)
    }

    private fun canvasDrag(reject: Boolean) = fixture(reject) { controller, f, history ->
        var activity = controller.get()
        field("canvasTransformLayerId").set(activity, f.root)
        MainActivity::class.java.getDeclaredMethod("showLayerPreview", CompProjectReader.Layer::class.java)
            .apply { isAccessible = true }.invoke(activity, f.project.layers.single { it.id == f.root })
        drain(activity)
        val view = field("canvasPreviewView").get(activity) as CanvasPreviewView
        view.layout(0, 0, 800, 600)
        assertTrue(view.isTransformMode)
        val start = view.transformViewPoint(com.wonderassembly.compositor.core.PointD(0.5, 0.5))!!
        fun touch(action: Int, x: Double) {
            val event = android.view.MotionEvent.obtain(0, 10, action, x.toFloat(), start.y.toFloat(), 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(android.view.MotionEvent.ACTION_DOWN, start.x)
        touch(android.view.MotionEvent.ACTION_MOVE, start.x + 40)
        drain(activity)
        assertSame(f.project, field("currentProject").get(activity))
        assertFalse(history.canUndo)
        touch(android.view.MotionEvent.ACTION_UP, start.x + 40)
        drain(activity)
        if (reject) {
            assertSame(f.project, field("currentProject").get(activity))
            assertTrue(history.canRedo); assertFalse(history.canUndo)
        } else {
            val changed = field("currentProject").get(activity) as CompProjectReader.Project
            assertEquals(1, history.retainedEdits().size)
            assertNotEquals(f.project.layers.single { it.id == f.first }.transform, changed.layers.single { it.id == f.first }.transform)
            assertNotEquals(f.project.layers.single { it.id == f.second }.transform, changed.layers.single { it.id == f.second }.transform)
            controller.recreate(); activity = controller.get(); drain(activity)
            val restored = field("canvasPreviewView").get(activity) as CanvasPreviewView
            assertTrue(restored.isTransformMode); assertFalse(restored.hasActiveEdit)
            val undo = MainActivity::class.java.getDeclaredMethod("applyDocumentHistory", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            undo.invoke(activity, false); drain(activity)
            assertEquals(f.project.layers, (field("currentProject").get(activity) as CompProjectReader.Project).layers)
            undo.invoke(activity, true); drain(activity)
            assertEquals(changed.layers, (field("currentProject").get(activity) as CompProjectReader.Project).layers)
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun drain(activity: MainActivity) {
        repeat(3) {
            (field("worker").get(activity) as java.util.concurrent.ExecutorService).submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }
    private fun open(activity: MainActivity): Pair<AlertDialog, List<EditText>> {
        MainActivity::class.java.getDeclaredMethod("showLayerTransform").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        return dialog to descendants(dialog.window!!.decorView).filterIsInstance<EditText>()
    }
    private fun fixture(reject: Boolean = false, body: (org.robolectric.android.controller.ActivityController<MainActivity>, GroupProjectFixture, DocumentHistory<DocumentEdit>) -> Unit) {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        Shadows.shadowOf(manager).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply {
            availMem = 2L shl 30; totalMem = 4L shl 30; threshold = 128L shl 20
        })
        Shadows.shadowOf(manager).setMemoryClass(256)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val f = GroupProjectFixture.create(controller.get())
        field("currentProject").set(controller.get(), f.project); field("selectedLayerId").set(controller.get(), f.root)
        val history = DocumentHistory<DocumentEdit>(if (reject) 1 else 1L shl 20)
        if (reject) { history.record(DocumentEdit.Metadata(f.project, f.project, f.root, false), 1); history.undo { } }
        field("documentHistory").set(controller.get(), history)
        try { body(controller, f, history) }
        finally { ShadowAlertDialog.getLatestAlertDialog()?.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun oneBatchHistorySurvivesRecreationUndoRedoAndRecoveryReopen() = fixture { controller, f, history ->
        var activity = controller.get()
        val (dialog, inputs) = open(activity)
        assertEquals(6, inputs.size)
        inputs[5].setText("150")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        val changed = field("currentProject").get(activity) as CompProjectReader.Project
        assertEquals(1, history.retainedEdits().size)
        assertEquals(-15.0, changed.layers.single { it.id == f.first }.transform.x, 1e-10)
        assertEquals(45.0, changed.layers.single { it.id == f.second }.transform.x, 1e-10)
        assertEquals(30.0, changed.layers.single { it.id == f.first }.transform.width, 1e-10)
        val root = java.io.File(activity.cacheDir, "group-recovery-${java.util.UUID.randomUUID()}")
        try {
            fun check(file: java.io.File) {
                val reopened = CompProjectArchiveImporter(activity, CompProjectReader(activity)).read(android.net.Uri.fromFile(file)) { false }
                try { assertEquals(changed.layers, reopened.layers) } finally { reopened.releasePrivateAssets() }
            }
            val saved = RecoveryStore(root, {}).save(changed.documentId, { output ->
                CompProjectArchiveWriter(activity.contentResolver).write(output, changed, emptyMap())
            }, ::check)
            check(saved.copy.file)
        } finally { root.deleteRecursively() }
        controller.recreate(); activity = controller.get(); drain(activity)
        assertEquals(f.root, field("selectedLayerId").get(activity))
        val button = field("transformLayerButton").get(activity) as android.widget.Button
        assertTrue(button.isEnabled); assertEquals(activity.getString(R.string.group_transform), button.text.toString())
        val method = MainActivity::class.java.getDeclaredMethod("applyDocumentHistory", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        method.invoke(activity, false); drain(activity)
        assertEquals(f.project.layers, (field("currentProject").get(activity) as CompProjectReader.Project).layers)
        assertTrue(history.canRedo)
        method.invoke(activity, true); drain(activity)
        assertEquals(changed.layers, (field("currentProject").get(activity) as CompProjectReader.Project).layers)
    }

    @Test fun budgetRejectsBatchWithoutLosingRedoOrMovingAnyMember() = fixture(true) { controller, f, history ->
        val activity = controller.get(); val (dialog, inputs) = open(activity)
        inputs[0].setText("50")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        assertSame(f.project, field("currentProject").get(activity)); assertFalse(history.canUndo); assertTrue(history.canRedo)
    }

    @Test fun cancelNoOpAndStaleDialogPreserveProject() = fixture { controller, f, history ->
        val activity = controller.get(); val (cancel, inputs) = open(activity)
        inputs[0].setText("20")
        cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); drain(activity)
        assertSame(f.project, field("currentProject").get(activity)); assertFalse(history.canUndo)
        open(activity).first.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        assertSame(f.project, field("currentProject").get(activity)); assertFalse(history.canUndo)
        val (stale, pending) = open(activity); pending[0].setText("80")
        val replacement = f.project.copy()
        field("currentProject").set(activity, replacement)
        stale.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        assertSame(replacement, field("currentProject").get(activity)); assertFalse(history.canUndo)
        val hidden = ProjectLayerMetadataEditor(activity).update(replacement, f.root, visible = false)
        field("currentProject").set(activity, hidden)
        MainActivity::class.java.getDeclaredMethod("updateCanvasTools", CompProjectReader.Layer::class.java).apply { isAccessible = true }.invoke(activity, null)
        assertFalse((field("transformLayerButton").get(activity) as android.widget.Button).isEnabled)
    }
}
