package com.wonderassembly.compositor

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
class NumericTransformDialogTest {
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun drain(activity: MainActivity) {
        repeat(3) {
            (field("worker").get(activity) as java.util.concurrent.ExecutorService).submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }
    private fun fixture(reject: Boolean = false, body: (MainActivity, CompProjectReader.Project, DocumentHistory<DocumentEdit>, AlertDialog, List<EditText>, CheckBox) -> Unit) {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        Shadows.shadowOf(manager).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply {
            availMem = 2L shl 30; totalMem = 4L shl 30; threshold = 128L shl 20
        })
        Shadows.shadowOf(manager).setMemoryClass(256)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val base = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }.create(100, 50, 72.0) { false }
        field("currentProject").set(activity, base); field("selectedLayerId").set(activity, base.layers.single().id)
        val history = DocumentHistory<DocumentEdit>(if (reject) 1 else 1L shl 20)
        if (reject) { history.record(DocumentEdit.Metadata(base, base, base.layers.single().id, false), 1); history.undo { } }
        field("documentHistory").set(activity, history)
        MainActivity::class.java.getDeclaredMethod("showLayerTransform").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val views = descendants(dialog.window!!.decorView)
        try { body(activity, base, history, dialog, views.filterIsInstance<EditText>(),
            views.filterIsInstance<CheckBox>().single { it.text.toString() == activity.getString(R.string.layer_lock_ratio) }) }
        finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun couplingScaleAndCancelLeaveProjectAndHistoryUntouched() = fixture { activity, base, history, dialog, inputs, ratio ->
        assertEquals(6, inputs.size)
        ratio.isChecked = true
        inputs[2].setText("125.5")
        assertEquals(62.75, inputs[3].text.toString().toDouble(), 1e-10)
        inputs[3].setText("25.25")
        assertEquals(50.5, inputs[2].text.toString().toDouble(), 1e-10)
        ratio.isChecked = false
        inputs[2].setText("80")
        assertEquals(25.25, inputs[3].text.toString().toDouble(), 0.0)
        inputs[5].setText("200")
        assertEquals(200.0, inputs[2].text.toString().toDouble(), 0.0)
        assertEquals(100.0, inputs[3].text.toString().toDouble(), 0.0)
        assertEquals(-60.0, inputs[0].text.toString().toDouble(), 0.0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertSame(base, field("currentProject").get(activity)); assertFalse(history.canUndo)
    }

    @Test fun invalidScaleBlocksApplyAndCorrectionCommitsOneSavedHistoryStep() = fixture { activity, base, history, dialog, inputs, _ ->
        inputs[5].setText("0")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertNotNull(inputs[5].error)
        assertSame(base, field("currentProject").get(activity)); assertFalse(history.canUndo)
        inputs[5].setText("50.5")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        val changed = field("currentProject").get(activity) as CompProjectReader.Project
        assertEquals("status=${(field("statusText").get(activity) as android.widget.TextView).text} showing=${dialog.isShowing} errors=${inputs.map { it.error }} scaleError=${inputs[5].error}", 50.5, changed.layers.single().transform.width, 0.0)
        assertEquals(25.25, changed.layers.single().transform.height, 0.0)
        assertEquals(1, history.retainedEdits().size)
        val archive = java.io.File(activity.cacheDir, "numeric-transform-test.zip")
        try {
            archive.outputStream().use { CompProjectArchiveWriter(activity.contentResolver).write(it, changed, emptyMap()) }
            val reopened = CompProjectArchiveImporter(activity, CompProjectReader(activity)).read(android.net.Uri.fromFile(archive)) { false }
            try { assertEquals(changed.layers.single().transform, reopened.layers.single().transform) }
            finally { reopened.releasePrivateAssets() }
        } finally { archive.delete() }
        val recoveryRoot = java.io.File(activity.cacheDir, "numeric-recovery-${java.util.UUID.randomUUID()}")
        try {
            val store = RecoveryStore(recoveryRoot, {})
            fun check(file: java.io.File) {
                val reopened = CompProjectArchiveImporter(activity, CompProjectReader(activity)).read(android.net.Uri.fromFile(file)) { false }
                try { assertEquals(changed.layers.single().transform, reopened.layers.single().transform) }
                finally { reopened.releasePrivateAssets() }
            }
            val saved = store.save(changed.documentId, { output ->
                CompProjectArchiveWriter(activity.contentResolver).write(output, changed, emptyMap())
            }, ::check)
            check(saved.copy.file)
        } finally { recoveryRoot.deleteRecursively() }
        val method = MainActivity::class.java.getDeclaredMethod("applyDocumentHistory", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        method.invoke(activity, false); drain(activity)
        assertEquals(base.layers.single().transform, (field("currentProject").get(activity) as CompProjectReader.Project).layers.single().transform)
        method.invoke(activity, true); drain(activity)
        assertEquals(changed.layers.single().transform, (field("currentProject").get(activity) as CompProjectReader.Project).layers.single().transform)
    }

    @Test fun rejectedHistoryBudgetPreservesRedoAndProject() = fixture(true) { activity, base, history, dialog, inputs, _ ->
        inputs[5].setText("200")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        assertSame(base, field("currentProject").get(activity)); assertTrue(history.canRedo); assertFalse(history.canUndo)
    }

    @Test fun invalidPositionIsNotReplacedByScalingAndUnchangedApplyHasNoHistory() = fixture { activity, base, history, dialog, inputs, _ ->
        inputs[0].setText("-")
        inputs[5].setText("200")
        assertEquals("-", inputs[0].text.toString()); assertNotNull(inputs[5].error)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertSame(base, field("currentProject").get(activity))
        inputs[0].setText("0")
        inputs[5].setText("100")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain(activity)
        assertFalse(history.canUndo); assertSame(base, field("currentProject").get(activity))
    }
}
