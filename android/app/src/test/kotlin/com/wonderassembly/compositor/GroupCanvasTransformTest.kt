package com.wonderassembly.compositor

import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import com.wonderassembly.compositor.core.PointD
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
class GroupCanvasTransformTest {
    private val context = RuntimeEnvironment.getApplication()
    private val budget = AndroidRasterBudget(64L shl 20, 64L shl 20, 64L shl 20, 2048)
    private fun touch(view: CanvasPreviewView, action: Int, point: PointD) {
        val event = MotionEvent.obtain(0, 10, action, point.x.toFloat(), point.y.toFloat(), 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    private fun fixture(body: (GroupProjectFixture, CanvasPreviewView) -> Unit) {
        val f = GroupProjectFixture.create(context)
        val view = CanvasPreviewView(context)
        view.layout(0, 0, 800, 600)
        view.showGroup(f.project, f.project.layers.single { it.id == f.root }, Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888), "Group")
        assertTrue(view.setTransformMode(true))
        try { body(f, view) } finally { view.clear("Closed"); f.project.releasePrivateAssets() }
    }

    @Test fun draftGeometryAndMasksMatchProductionCandidateWithoutChangingManifest() {
        val f = GroupProjectFixture.create(context)
        try {
            val plan = GroupTransformPlan.forGroup(f.project, f.root)
            for (target in listOf(plan.box.copy(x = 2.0), plan.box.copy(width = 90.0, height = 25.0, rotation = 31.0), plan.box.copy(flipX = true))) {
                val draft = plan.preview(f.project, target)
                val candidate = ProjectLayerTransformEditor(context).updateGroup(f.project, f.root, target)
                assertSame(f.project.originalManifestBytes, draft.originalManifestBytes)
                assertEquals(candidate.layers.map { it.transform }, draft.layers.map { it.transform })
                val expected = candidate.layers.associateBy { it.id }
                for (layer in draft.layers) assertEquals(PreviewMaskPlanner.forLayer(expected.getValue(layer.id), expected),
                    PreviewMaskPlanner.forLayer(layer, draft.layers.associateBy { it.id }))
                for (id in listOf(f.root, f.hidden, f.hiddenChild, f.outside))
                    assertEquals(f.project.layers.single { it.id == id }, draft.layers.single { it.id == id })
            }
            assertThrows(IllegalArgumentException::class.java) { plan.preview(f.project, plan.box.copy(x = 1_000_000.0)) }
        } finally { f.project.releasePrivateAssets() }
    }

    @Test fun compositeDraftIncludesCommittedStrokeAndMatchesCommittedMaskedPixels() {
        val f = GroupProjectFixture.create(context)
        val file = f.project.layers.single { it.id == f.first }.imageFile!!
        val session = LayerEditingSession(context, f.project.imageUris.getValue(file), 20, 10, 16L shl 20)
        try {
            val stroke = session.raster.beginBrushStroke(3.0, Color.RED)
            stroke.addPoint(PointD(5.0, 5.0)); stroke.commit()
            val plan = GroupTransformPlan.forGroup(f.project, f.root)
            val target = plan.box.copy(x = 2.0)
            val draft = plan.preview(f.project, target)
            val candidate = ProjectLayerTransformEditor(context).updateGroup(f.project, f.root, target)
            fun render(project: CompProjectReader.Project, edits: Map<String, LayerEditingSession>) =
                PreviewCompositeRenderer(context, { budget }).render(project, { false }, { _, _ -> }, editedAssets = edits).bitmap
            val preview = render(draft, mapOf(file to session))
            val committed = render(candidate, mapOf(file to session))
            val unedited = render(draft, emptyMap())
            try {
                assertTrue(preview.getPixel(7, 5) ushr 24 > 0)
                assertEquals(0, unedited.getPixel(7, 5))
                for (y in 0 until 10) for (x in 0 until 20) assertEquals("$x,$y", committed.getPixel(x, y), preview.getPixel(x, y))
            } finally { preview.recycle(); committed.recycle(); unedited.recycle() }
        } finally { session.close(); f.project.releasePrivateAssets() }
    }

    @Test fun groupMoveCommitsOnceAndRejectsLatePreviewAfterRelease() = fixture { f, view ->
        val commits = mutableListOf<CompProjectReader.LayerTransform>()
        var accept: ((Bitmap?) -> Unit)? = null
        var cancelled: (() -> Boolean)? = null
        view.onTransformCommitted = { id, t -> assertEquals(f.root, id); commits += t }
        view.onGroupPreviewRequested = { _, isCancelled, callback -> cancelled = isCancelled; accept = callback }
        val original = view.displayedLayerTransform!!
        val start = view.transformViewPoint(PointD(0.5, 0.5))!!
        touch(view, MotionEvent.ACTION_DOWN, start)
        touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 40, start.y))
        assertTrue(view.hasActiveEdit); assertTrue(commits.isEmpty())
        assertNotEquals(original, view.displayedLayerTransform)
        touch(view, MotionEvent.ACTION_UP, PointD(start.x + 40, start.y))
        assertEquals(1, commits.size); assertEquals(original, view.displayedLayerTransform)
        assertTrue(cancelled!!.invoke())
        val stale = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        accept!!.invoke(stale); assertTrue(stale.isRecycled)
        assertEquals(original, GroupTransformPlan.forGroup(f.project, f.root).box)
    }

    @Test fun groupResizeAndRotateUseDocumentMappingAndOneRelease() = fixture { _, view ->
        val commits = mutableListOf<CompProjectReader.LayerTransform>()
        view.onTransformCommitted = { _, t -> commits += t }
        val original = view.displayedLayerTransform!!
        val corner = view.transformViewPoint(PointD(1.0, 1.0))!!
        touch(view, MotionEvent.ACTION_DOWN, corner)
        touch(view, MotionEvent.ACTION_MOVE, PointD(corner.x + 60, corner.y + 10))
        val draft = view.displayedLayerTransform!!
        assertEquals(original.width / original.height, draft.width / draft.height, 1e-10)
        touch(view, MotionEvent.ACTION_UP, PointD(corner.x + 60, corner.y + 10))
        assertEquals(1, commits.size)
        assertTrue(kotlin.math.abs(draft.width - commits[0].width) <= 0.5)
        assertTrue(kotlin.math.abs(draft.height - commits[0].height) <= 0.5)
        val top = view.transformViewPoint(PointD(0.5, 0.0))!!
        val center = view.transformViewPoint(PointD(0.5, 0.5))!!
        val rotate = PointD(top.x, top.y - 32 * context.resources.displayMetrics.density)
        touch(view, MotionEvent.ACTION_DOWN, rotate)
        touch(view, MotionEvent.ACTION_UP, PointD(center.x + center.y - rotate.y, center.y))
        assertEquals(2, commits.size); assertEquals(90.0, commits[1].rotation, 0.0)
    }

    @Test fun allInterruptionsDiscardGroupDraftAndPendingFrames() = fixture { _, view ->
        var commits = 0
        view.onTransformCommitted = { _, _ -> commits++ }
        val callbacks = mutableListOf<Pair<() -> Boolean, (Bitmap?) -> Unit>>()
        view.onGroupPreviewRequested = { _, cancelled, accept -> callbacks += cancelled to accept }
        fun begin() {
            view.setTransformMode(true)
            val start = view.transformViewPoint(PointD(0.5, 0.5))!!
            touch(view, MotionEvent.ACTION_DOWN, start)
            touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 40, start.y))
        }
        begin(); touch(view, MotionEvent.ACTION_CANCEL, PointD(400.0, 300.0))
        begin(); view.onWindowFocusChanged(false)
        begin(); view.layout(0, 0, 810, 610)
        begin(); view.setTransformMode(false)
        begin(); CanvasPreviewView::class.java.getDeclaredMethod("onDetachedFromWindow").apply { isAccessible = true }.invoke(view)
        touch(view, MotionEvent.ACTION_UP, PointD(440.0, 300.0))
        assertEquals(0, commits); assertFalse(view.hasActiveEdit)
        for ((cancelled, accept) in callbacks) {
            assertTrue(cancelled())
            val stale = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
            accept(stale); assertTrue(stale.isRecycled)
        }
    }

    @Test fun secondPointerCancelsGroupAndKeepsSinglePointerTailNavigating() = fixture { _, view ->
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
    }

    @Test fun previewFailureAndNoOpDoNotCommit() = fixture { _, view ->
        var commits = 0
        view.onTransformCommitted = { _, _ -> commits++ }
        view.onGroupPreviewRequested = { _, _, accept -> accept(null) }
        val start = view.transformViewPoint(PointD(0.5, 0.5))!!
        touch(view, MotionEvent.ACTION_DOWN, start); touch(view, MotionEvent.ACTION_UP, start)
        touch(view, MotionEvent.ACTION_DOWN, start)
        touch(view, MotionEvent.ACTION_MOVE, PointD(start.x + 40, start.y))
        touch(view, MotionEvent.ACTION_UP, PointD(start.x + 40, start.y))
        assertEquals(0, commits); assertFalse(view.hasActiveEdit)
    }
}
