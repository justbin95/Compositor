package com.wonderassembly.compositor

import android.graphics.BitmapFactory
import android.view.MotionEvent
import com.wonderassembly.compositor.core.PointD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasTransformLifecycleTest {
    private fun fixture(body: (CanvasPreviewView, CompProjectReader.Project) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val project = BlankProjectCreator(context) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }.create(100, 100, 72.0) { false }
        val view = CanvasPreviewView(context)
        view.layout(0, 0, 800, 600)
        view.showLayer(project, project.layers.single(), BitmapFactory.decodeFile(project.imageUris.values.single().path), null)
        assertTrue(view.setTransformMode(true))
        try { body(view, project) } finally { view.clear("Closed"); project.releasePrivateAssets() }
    }
    private fun touch(view: CanvasPreviewView, action: Int, p: PointD) {
        val event = MotionEvent.obtain(0, 10, action, p.x.toFloat(), p.y.toFloat(), 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun dragPreviewsThenCommitsOnceWithoutMutatingBase() = fixture { view, project ->
        val commits = mutableListOf<CompProjectReader.LayerTransform>()
        view.onTransformCommitted = { _, t -> commits += t }
        val start = view.transformViewPoint(PointD(0.5, 0.5))!!
        touch(view, MotionEvent.ACTION_DOWN, start)
        assertTrue(view.hasActiveEdit)
        val moved = PointD(start.x + 40, start.y + 25)
        touch(view, MotionEvent.ACTION_MOVE, moved)
        assertTrue(commits.isEmpty())
        assertNotEquals(project.layers.single().transform, view.displayedLayerTransform)
        touch(view, MotionEvent.ACTION_UP, moved)
        assertFalse(view.hasActiveEdit)
        assertEquals(1, commits.size)
        assertEquals(project.layers.single().transform, view.displayedLayerTransform)
        val candidate = ProjectLayerTransformEditor(RuntimeEnvironment.getApplication()).update(project, project.layers.single().id, commits.single())
        assertEquals(commits.single(), candidate.layers.single().transform)
        assertEquals(0.0, project.layers.single().transform.x, 0.0)
    }
    @Test fun cancelFocusLossResizeAndModeChangeDiscardDraft() = fixture { view, project ->
        var commits = 0
        view.onTransformCommitted = { _, _ -> commits++ }
        fun begin() {
            view.setTransformMode(true)
            val start = view.transformViewPoint(PointD(0.5, 0.5))!!
            touch(view, MotionEvent.ACTION_DOWN, start)
            touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 40, start.y))
            assertTrue(view.hasActiveEdit)
        }
        begin(); touch(view, MotionEvent.ACTION_CANCEL, PointD(400.0, 300.0))
        begin(); view.onWindowFocusChanged(false)
        begin(); view.layout(0, 0, 810, 610)
        begin(); view.setTransformMode(false)
        touch(view, MotionEvent.ACTION_UP, PointD(440.0, 300.0))
        assertEquals(0, commits)
        assertFalse(view.hasActiveEdit)
        assertEquals(project.layers.single().transform, view.displayedLayerTransform)
    }
    @Test fun activityRecordsOneEditAndRestoresToolsThroughRecreation() {
        activityDrag(reject = false)
    }
    @Test fun historyBudgetRejectionPreservesProjectAndRedo() {
        activityDrag(reject = true)
    }
    private fun activityDrag(reject: Boolean) {
        val manager = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        org.robolectric.Shadows.shadowOf(manager).setMemoryInfo(android.app.ActivityManager.MemoryInfo().apply {
            availMem = 2L shl 30; totalMem = 4L shl 30; threshold = 128L shl 20
        })
        org.robolectric.Shadows.shadowOf(manager).setMemoryClass(256)
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity = controller.get()
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val project = BlankProjectCreator(activity) { AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048) }.create(100, 100, 72.0) { false }
        field("currentProject").set(activity, project)
        field("selectedLayerId").set(activity, project.layers.single().id)
        field("canvasTransformLayerId").set(activity, project.layers.single().id)
        val history = com.wonderassembly.compositor.core.DocumentHistory<DocumentEdit>(if (reject) 1 else 1L shl 20)
        if (reject) {
            history.record(DocumentEdit.Metadata(project, project, project.layers.single().id, false), 1)
            history.undo { }
        }
        field("documentHistory").set(activity, history)
        val view = field("canvasPreviewView").get(activity) as CanvasPreviewView
        view.layout(0, 0, 800, 600)
        view.showLayer(project, project.layers.single(), BitmapFactory.decodeFile(project.imageUris.values.single().path), null)
        view.locksTransformRatio = false
        view.setTransformMode(true)
        val start = view.transformViewPoint(PointD(0.5, 0.5))!!
        touch(view, MotionEvent.ACTION_DOWN, start)
        touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 40, start.y))
        assertFalse(history.canUndo)
        touch(view, MotionEvent.ACTION_UP, PointD(start.x + 40, start.y))
        fun drain() {
            repeat(3) {
                (field("worker").get(activity) as java.util.concurrent.ExecutorService).submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
        }
        drain()
        if (reject) {
            assertSame(project, field("currentProject").get(activity))
            assertTrue(history.canRedo)
            assertFalse(history.canUndo)
            assertEquals(project.layers.single().transform, view.displayedLayerTransform)
        } else {
            assertEquals(1, history.retainedEdits().size)
            val moved = (field("currentProject").get(activity) as CompProjectReader.Project).layers.single().transform
            assertNotEquals(project.layers.single().transform, moved)
            controller.recreate()
            activity = controller.get()
            drain()
            val restoredView = field("canvasPreviewView").get(activity) as CanvasPreviewView
            assertFalse(restoredView.locksTransformRatio)
            assertTrue("wanted=${field("canvasTransformLayerId").get(activity)} selected=${field("selectedLayerId").get(activity)} can=${restoredView.canEnableTransform()} status=${(field("statusText").get(activity) as android.widget.TextView).text}", restoredView.isTransformMode)
            MainActivity::class.java.getDeclaredMethod("applyDocumentHistory", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)
            drain()
            assertEquals(project.layers.single().transform, (field("currentProject").get(activity) as CompProjectReader.Project).layers.single().transform)
            assertTrue(history.canRedo)
        }
        controller.pause().stop().destroy()
        assertFalse(project.archiveCacheDirectory!!.exists())
    }

    @Test fun secondPointerRollsBackAndLocksTailToNavigation() = fixture { view, project ->
        var commits = 0
        view.onTransformCommitted = { _, _ -> commits++ }
        val start = view.transformViewPoint(PointD(0.5, 0.5))!!
        touch(view, MotionEvent.ACTION_DOWN, start)
        touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 40, start.y))
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { x = (start.x + i * 100).toFloat(); y = start.y.toFloat(); pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(0, 20, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        assertFalse(view.hasActiveEdit)
        touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 80, start.y))
        touch(view, MotionEvent.ACTION_UP, PointD(start.x + 80, start.y))
        assertEquals(0, commits)
        assertEquals(project.layers.single().transform, view.displayedLayerTransform)
    }
}
