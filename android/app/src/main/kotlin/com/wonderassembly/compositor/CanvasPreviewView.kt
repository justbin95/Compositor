package com.wonderassembly.compositor

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.wonderassembly.compositor.core.ChunkedBrushStroke
import com.wonderassembly.compositor.core.CoordinateMapper
import com.wonderassembly.compositor.core.PixelSize
import com.wonderassembly.compositor.core.PointD
import com.wonderassembly.compositor.core.RasterChunkId
import com.wonderassembly.compositor.core.RasterChunkSnapshot
import com.wonderassembly.compositor.core.LayerTransform as CoreLayerTransform
import com.wonderassembly.compositor.core.ViewportTransform
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Displays a bounded project composite or a selected source layer in document coordinates. */
internal class CanvasPreviewView(context: Context) : View(context) {
    private val dark = Color.rgb(28, 28, 28)
    private val checkerA = Color.rgb(68, 68, 68)
    private val checkerB = Color.rgb(82, 82, 82)
    private val checkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 170, 170)
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val documentRect = RectF()

    private var project: CompProjectReader.Project? = null
    private var layer: CompProjectReader.Layer? = null
    private var editingSession: LayerEditingSession? = null
    private var bitmap: Bitmap? = null
    private val editedTileBitmaps = LinkedHashMap<RasterChunkId, EditedTileBitmap>()
    private var message: String? = null
    private var layerOpacity: Double = 1.0
    private var showingComposite = false
    private var viewportZoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private var clickDetected = false
    private var multiTouchGesture = false
    private var previousPinchFocusX: Double? = null
    private var previousPinchFocusY: Double? = null
    private var previousPinchSpan: Double? = null
    private var panResetAnimator: ValueAnimator? = null
    private var transformMode = false
    private var transformGesture: CanvasTransformGesture? = null
    private var transformDraft: CompProjectReader.LayerTransform? = null
    private var transformNavigating = false
    internal var locksTransformRatio = true
    internal var onTransformCommitted: ((String, CompProjectReader.LayerTransform) -> Unit)? = null
    internal var onTransformActivityChanged: (() -> Unit)? = null
    internal val isTransformMode get() = transformMode
    internal val hasActiveEdit get() = hasActiveStroke || transformGesture != null
    internal val displayedLayerTransform get() = transformDraft ?: layer?.transform
    private val transformPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(2).toFloat() }
    private var brushMode = false
    internal var brushDiameter = 32.0
    internal var brushOpacity = 1.0
    internal var brushHardness = 1.0
    internal var brushColor = Color.BLACK
    internal var eraser = false
    private var activeBrushStroke: ChunkedBrushStroke? = null

    internal var onBrushStrokeCommitted: ((String) -> Unit)? = null
    internal var onBrushStrokeCancelled: (() -> Unit)? = null
    internal var onBrushError: ((String) -> Unit)? = null
    internal var onBrushHistoryChanged: (() -> Unit)? = null
    internal var onBrushHistoryLimitReached: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (multiTouchGesture) return false
                panX -= distanceX
                panY -= distanceY
                constrainPan()
                invalidate()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                resetViewport()
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                clickDetected = true
                return true
            }
        }
    )

    init {
        setBackgroundColor(dark)
        isClickable = true
        setOnTouchListener { view, event ->
            if (project == null || bitmap == null) {
                false
            } else {
                if (brushMode) handleBrushTouch(event) else if (transformMode) handleTransformTouch(event) else handleNavigationTouch(event)
                if (clickDetected) {
                    clickDetected = false
                    view.performClick()
                }
                true
            }
        }
        contentDescription = context.getString(R.string.canvas_project_description)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    internal val isBrushMode: Boolean get() = brushMode
    internal val hasActiveStroke: Boolean get() = activeBrushStroke != null
    internal var recordStroke: ((String, com.wonderassembly.compositor.core.ChunkedStrokePatch) -> Boolean)? = null

    internal fun refreshHistoryLayer(layerId: String, patch: com.wonderassembly.compositor.core.ChunkedStrokePatch): Boolean {
        if (layer?.id != layerId || editingSession == null || bitmap == null) return false
        refreshEditedTiles(patch.changedChunkIds)
        invalidate()
        return true
    }

    internal fun canEnableBrush(): Boolean {
        val currentLayer = layer ?: return false
        val session = editingSession ?: return false
        return session.canBeginStroke && currentLayer.visible && !currentLayer.group && !currentLayer.adjustment &&
            currentLayer.imageFile != null && currentLayer.blendMode.equals("Normal", ignoreCase = true) &&
            currentLayer.opacity > 0.0 && !currentLayer.hasEffects
    }

    internal fun setBrushMode(enabled: Boolean): Boolean {
        if (enabled && !canEnableBrush()) return false
        if (enabled) setTransformMode(false)
        if (brushMode == enabled) return true
        if (!enabled) finishBrushStroke(commit = true)
        brushMode = enabled
        multiTouchGesture = false
        clearPinchBaseline()
        updateContentDescription()
        invalidate()
        return true
    }

    /** Maps local view pixels to project pixel-edge coordinates for editor input. */
    internal fun documentPointAtView(viewX: Double, viewY: Double): PointD? {
        val currentProject = project ?: return null
        if (viewX !in 0.0..width.toDouble() || viewY !in 0.0..height.toDouble()) return null
        val displayed = fittedDocumentRect(currentProject)?.let(::displayedRect) ?: return null
        if (viewX < displayed.left || viewX > displayed.right || viewY < displayed.top || viewY > displayed.bottom) {
            return null
        }
        val scale = displayed.width / currentProject.width
        if (!scale.isFinite() || scale <= 0.0) return null
        return ViewportTransform(scale, displayed.left, displayed.top).windowToCanvas(PointD(viewX, viewY))
    }

    /** Maps a canvas touch through the selected layer's transform into its original PNG pixels. */
    internal fun layerPointAtView(viewX: Double, viewY: Double): PointD? {
        val currentLayer = layer ?: return null
        val session = editingSession ?: return null
        val documentPoint = documentPointAtView(viewX, viewY) ?: return null
        val transform = currentLayer.transform.toCoreTransform()
        val sourceSize = PixelSize(session.raster.width, session.raster.height)
        return CoordinateMapper.canvasToLayer(documentPoint, sourceSize, transform).takeIf { point ->
            point.x in 0.0..sourceSize.width.toDouble() && point.y in 0.0..sourceSize.height.toDouble()
        }
    }

    private fun CompProjectReader.LayerTransform.toCoreTransform() = CoreLayerTransform(
        x = x,
        y = y,
        width = width,
        height = height,
        clockwiseDegrees = rotation,
        flipX = flipX,
        flipY = flipY
    )

    internal fun canEnableTransform(): Boolean {
        val selected = layer ?: return false
        return bitmap != null && !selected.group && !selected.adjustment && selected.imageFile != null
    }

    internal fun setTransformMode(enabled: Boolean): Boolean {
        if (enabled && !canEnableTransform()) return false
        cancelTransform()
        if (enabled) setBrushMode(false)
        transformMode = enabled
        updateContentDescription()
        invalidate()
        return true
    }

    internal fun cancelTransform() {
        val active = transformGesture != null
        transformGesture = null
        transformDraft = null
        transformNavigating = false
        invalidate()
        if (active) onTransformActivityChanged?.invoke()
    }

    private fun transformViewport(): CanvasPreviewGeometry.Rect? = project?.let { fittedDocumentRect(it)?.let(::displayedRect) }
    private fun transformDocumentPoint(x: Double, y: Double): PointD? {
        val current = project ?: return null
        val viewport = transformViewport() ?: return null
        val scale = viewport.width / current.width
        return PointD((x - viewport.left) / scale, (y - viewport.top) / scale)
    }
    internal fun transformViewPoint(unit: PointD): PointD? {
        val t = displayedLayerTransform ?: return null
        val viewport = transformViewport() ?: return null
        val scale = viewport.width / requireNotNull(project).width
        val point = CanvasTransformGesture.pointAt(t, unit)
        return PointD(viewport.left + point.x * scale, viewport.top + point.y * scale)
    }
    private fun rotationViewPoint(): PointD? {
        val top = transformViewPoint(PointD(0.5, 0.0)) ?: return null
        val t = displayedLayerTransform ?: return null
        val r = Math.toRadians(t.rotation % 360)
        return PointD(top.x + kotlin.math.sin(r) * dp(32), top.y - kotlin.math.cos(r) * dp(32))
    }
    private fun handleTransformTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPanResetAnimation()
                multiTouchGesture = false
                clearPinchBaseline()
                val t = layer?.transform ?: return
                val point = transformDocumentPoint(event.x.toDouble(), event.y.toDouble()) ?: return
                fun near(p: PointD?) = p != null && kotlin.math.hypot(p.x - event.x, p.y - event.y) <= dp(24)
                val handle = if (near(rotationViewPoint())) CanvasTransformGesture.ROTATE else {
                    CanvasTransformGesture.handles.indices.minByOrNull { index ->
                        val p = transformViewPoint(CanvasTransformGesture.handles[index])!!
                        kotlin.math.hypot(p.x - event.x, p.y - event.y)
                    }?.takeIf { near(transformViewPoint(CanvasTransformGesture.handles[it])) }
                }
                transformNavigating = handle == null && !CanvasTransformGesture.contains(t, point)
                if (transformNavigating) handleNavigationTouch(event)
                else {
                    transformGesture = CanvasTransformGesture(t, point, handle ?: CanvasTransformGesture.MOVE)
                    transformDraft = t
                    onTransformActivityChanged?.invoke()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelTransform()
                transformNavigating = true
                handleNavigationTouch(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (transformNavigating) handleNavigationTouch(event)
                else transformDocumentPoint(event.x.toDouble(), event.y.toDouble())?.let { point ->
                    transformDraft = transformGesture?.updated(point, locksTransformRatio)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (transformNavigating) handleNavigationTouch(event)
                else {
                    val gesture = transformGesture
                    val point = transformDocumentPoint(event.x.toDouble(), event.y.toDouble())
                    val updated = if (gesture != null && point != null) gesture.updated(point, locksTransformRatio) else transformDraft
                    val id = layer?.id
                    cancelTransform()
                    if (gesture != null && updated != null && updated != gesture.original && id != null) {
                        val committed = CanvasTransformGesture.committed(updated)
                        if (committed != gesture.original) onTransformCommitted?.invoke(id, committed)
                    }
                }
                transformNavigating = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTransform()
                multiTouchGesture = false
                clearPinchBaseline()
            }
            MotionEvent.ACTION_POINTER_UP -> if (transformNavigating) handleNavigationTouch(event)
        }
    }

    private fun drawTransformControls(canvas: Canvas) {
        val points = CanvasTransformGesture.handles.map { transformViewPoint(it) ?: return }
        transformPaint.style = Paint.Style.STROKE
        for (index in points.indices) {
            val p = points[index]; val next = points[(index + 1) % points.size]
            canvas.drawLine(p.x.toFloat(), p.y.toFloat(), next.x.toFloat(), next.y.toFloat(), transformPaint)
        }
        val rotation = rotationViewPoint() ?: return
        canvas.drawLine(points[1].x.toFloat(), points[1].y.toFloat(), rotation.x.toFloat(), rotation.y.toFloat(), transformPaint)
        transformPaint.style = Paint.Style.FILL
        for (point in points + rotation) {
            transformPaint.color = dark
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(7).toFloat(), transformPaint)
            transformPaint.color = Color.WHITE
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(4).toFloat(), transformPaint)
        }
    }

    private fun handleNavigationTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPanResetAnimation()
                multiTouchGesture = false
                clearPinchBaseline()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouchGesture = true
                updatePinchBaseline(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchGesture && event.pointerCount >= 2) handlePinchMove(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 >= 2) {
                    updatePinchBaseline(event, excludedPointerIndex = event.actionIndex)
                } else {
                    clearPinchBaseline()
                }
            }
        }
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            multiTouchGesture = false
            clearPinchBaseline()
            animatePanToOriginAtMinimumZoom()
        }
    }

    private fun handleBrushTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPanResetAnimation()
                multiTouchGesture = false
                clearPinchBaseline()
                beginBrushStroke(event.x.toDouble(), event.y.toDouble())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multiTouchGesture) {
                    multiTouchGesture = true
                    finishBrushStroke(commit = false, notifyCancellation = false)
                }
                updatePinchBaseline(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchGesture && event.pointerCount >= 2) {
                    handlePinchMove(event)
                } else if (event.pointerCount == 1) {
                    continueBrushStroke(event.x.toDouble(), event.y.toDouble())
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 >= 2) {
                    updatePinchBaseline(event, excludedPointerIndex = event.actionIndex)
                } else {
                    clearPinchBaseline()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!multiTouchGesture) {
                    continueBrushStroke(event.x.toDouble(), event.y.toDouble())
                    finishBrushStroke(commit = true)
                }
                multiTouchGesture = false
                clearPinchBaseline()
                animatePanToOriginAtMinimumZoom()
            }
            MotionEvent.ACTION_CANCEL -> {
                finishBrushStroke(commit = false)
                multiTouchGesture = false
                clearPinchBaseline()
            }
        }
    }

    private fun beginBrushStroke(viewX: Double, viewY: Double) {
        val session = editingSession ?: return
        val start = layerPointAtView(viewX, viewY) ?: return
        try {
            val stroke = session.raster.beginBrushStroke(
                diameterPixels = brushDiameter,
                colorArgb = brushColor,
                opacity = brushOpacity,
                hardness = brushHardness,
                erase = eraser
            )
            activeBrushStroke = stroke
            refreshEditedTiles(stroke.addPoint(start))
            onBrushHistoryChanged?.invoke()
        } catch (error: Exception) {
            failBrushStroke(error)
        } catch (error: OutOfMemoryError) {
            failBrushStroke(error)
        }
    }

    private fun continueBrushStroke(viewX: Double, viewY: Double) {
        val stroke = activeBrushStroke ?: return
        try {
            val point = layerPointAtView(viewX, viewY)
            if (point == null) {
                stroke.endSegment()
            } else {
                refreshEditedTiles(stroke.addPoint(point))
            }
        } catch (error: Exception) {
            failBrushStroke(error)
        } catch (error: OutOfMemoryError) {
            failBrushStroke(error)
        }
    }

    private fun finishBrushStroke(commit: Boolean, notifyCancellation: Boolean = true) {
        val stroke = activeBrushStroke ?: return
        activeBrushStroke = null
        val layerName = layer?.name.orEmpty()
        val touchedChunks = stroke.touchedChunkIds
        try {
            if (commit) {
                val patch = stroke.commit()
                if (patch != null) {
                    val session = editingSession
                    if (session != null && recordStroke?.invoke(requireNotNull(layer).id, patch) == true) {
                        onBrushStrokeCommitted?.invoke(layerName)
                    } else {
                        patch.undo()
                        refreshEditedTiles(patch.changedChunkIds)
                        onBrushHistoryLimitReached?.invoke()
                    }
                }
            } else {
                stroke.cancel()
                refreshEditedTiles(touchedChunks)
                if (notifyCancellation) onBrushStrokeCancelled?.invoke()
            }
            onBrushHistoryChanged?.invoke()
        } catch (error: Exception) {
            onBrushError?.invoke(error.message ?: "The brush transaction could not be completed.")
        } catch (error: OutOfMemoryError) {
            onBrushError?.invoke("There is not enough memory to finish this brush transaction.")
        }
    }

    private fun failBrushStroke(error: Throwable) {
        val stroke = activeBrushStroke
        activeBrushStroke = null
        if (stroke != null) {
            val touchedChunks = stroke.touchedChunkIds
            runCatching { stroke.cancel() }
            runCatching { refreshEditedTiles(touchedChunks) }
        }
        brushMode = false
        onBrushHistoryChanged?.invoke()
        updateContentDescription()
        onBrushError?.invoke(error.message ?: "The brush transaction could not be completed.")
    }

    private fun refreshEditedTiles(ids: Set<RasterChunkId>) {
        val session = editingSession ?: return
        for (id in ids) {
            val snapshot = session.raster.visibleChunkSnapshot(id)
            if (snapshot == null) {
                editedTileBitmaps.remove(id)?.bitmap?.recycle()
            } else {
                updateEditedTileBitmap(snapshot)
            }
        }
        invalidate()
    }

    private fun restoreEditedTiles(session: LayerEditingSession) {
        clearEditedTileBitmaps()
        for (snapshot in session.raster.committedChunkSnapshots()) updateEditedTileBitmap(snapshot)
    }

    private fun updateEditedTileBitmap(snapshot: RasterChunkSnapshot) {
        val bounds = snapshot.bounds
        val existing = editedTileBitmaps[bounds.id]
        val tileBitmap = if (existing?.bounds?.width == bounds.width && existing.bounds.height == bounds.height) {
            existing.bitmap
        } else {
            existing?.bitmap?.recycle()
            Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888).also {
                editedTileBitmaps[bounds.id] = EditedTileBitmap(bounds, it)
            }
        }
        val bytes = snapshot.copyPixels()
        val pixels = IntArray(bounds.width * bounds.height)
        for (index in pixels.indices) {
            val offset = index * 4
            val alpha = bytes[offset + 3].toInt() and 0xff
            val red = unpremultiply(bytes[offset].toInt() and 0xff, alpha)
            val green = unpremultiply(bytes[offset + 1].toInt() and 0xff, alpha)
            val blue = unpremultiply(bytes[offset + 2].toInt() and 0xff, alpha)
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        tileBitmap.setPixels(pixels, 0, bounds.width, 0, 0, bounds.width, bounds.height)
        editedTileBitmaps[bounds.id] = EditedTileBitmap(bounds, tileBitmap)
    }

    private fun unpremultiply(channel: Int, alpha: Int): Int =
        if (alpha == 0) 0 else ((channel * 255 + alpha / 2) / alpha).coerceAtMost(255)

    private fun clearEditedTileBitmaps() {
        editedTileBitmaps.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        editedTileBitmaps.clear()
    }

    private fun updateContentDescription() {
        val currentLayer = layer
        contentDescription = if (currentLayer == null) {
            context.getString(R.string.canvas_project_description)
        } else if (transformMode) {
            context.getString(R.string.canvas_transform_description, currentLayer.name)
        } else if (brushMode) {
            context.getString(R.string.canvas_layer_description, currentLayer.name) + " Brush mode is on."
        } else {
            context.getString(R.string.canvas_layer_description, currentLayer.name)
        }
    }

    private data class EditedTileBitmap(val bounds: com.wonderassembly.compositor.core.RasterChunkBounds, val bitmap: Bitmap)

    private data class PinchGeometry(val focusX: Double, val focusY: Double, val span: Double)

    private fun pinchGeometry(event: MotionEvent, excludedPointerIndex: Int = -1): PinchGeometry? {
        val pointerCount = event.pointerCount - if (excludedPointerIndex >= 0) 1 else 0
        if (pointerCount < 2) return null

        var focusX = 0.0
        var focusY = 0.0
        for (index in 0 until event.pointerCount) {
            if (index == excludedPointerIndex) continue
            focusX += event.getX(index)
            focusY += event.getY(index)
        }
        focusX /= pointerCount
        focusY /= pointerCount

        var squaredRadiusSum = 0.0
        for (index in 0 until event.pointerCount) {
            if (index == excludedPointerIndex) continue
            val dx = event.getX(index) - focusX
            val dy = event.getY(index) - focusY
            squaredRadiusSum += dx * dx + dy * dy
        }
        val span = sqrt(2.0 * squaredRadiusSum / pointerCount)
        return PinchGeometry(focusX, focusY, span)
    }

    private fun updatePinchBaseline(event: MotionEvent, excludedPointerIndex: Int = -1) {
        val geometry = pinchGeometry(event, excludedPointerIndex)
        previousPinchFocusX = geometry?.focusX
        previousPinchFocusY = geometry?.focusY
        previousPinchSpan = geometry?.span
    }

    private fun clearPinchBaseline() {
        previousPinchFocusX = null
        previousPinchFocusY = null
        previousPinchSpan = null
    }

    private fun animatePanToOriginAtMinimumZoom() {
        if (viewportZoom > MIN_ZOOM) return
        val startX = panX
        val startY = panY
        if (abs(startX) < 0.5 && abs(startY) < 0.5) {
            panX = 0.0
            panY = 0.0
            invalidate()
            return
        }

        cancelPanResetAnimation()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAN_RESET_DURATION_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                panX = startX * (1.0 - progress)
                panY = startY * (1.0 - progress)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (panResetAnimator === animation) panResetAnimator = null
                }
            })
        }
        panResetAnimator = animator
        animator.start()
    }

    private fun cancelPanResetAnimation() {
        panResetAnimator?.cancel()
        panResetAnimator = null
    }

    private fun handlePinchMove(event: MotionEvent) {
        val geometry = pinchGeometry(event) ?: run {
            clearPinchBaseline()
            return
        }
        val anchorX = previousPinchFocusX ?: geometry.focusX
        val anchorY = previousPinchFocusY ?: geometry.focusY
        val previousSpan = previousPinchSpan
        val scaleFactor = if (previousSpan != null && previousSpan > 0.0 && geometry.span > 0.0) {
            geometry.span / previousSpan
        } else {
            1.0
        }
        applyPinchTransform(anchorX, anchorY, geometry.focusX, geometry.focusY, scaleFactor)
        previousPinchFocusX = geometry.focusX
        previousPinchFocusY = geometry.focusY
        previousPinchSpan = geometry.span
    }

    private fun applyPinchTransform(
        anchorX: Double,
        anchorY: Double,
        focusX: Double,
        focusY: Double,
        scaleFactor: Double
    ) {
        val previousZoom = viewportZoom
        val nextZoom = (previousZoom * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val documentAnchor = documentPointAtView(anchorX, anchorY)
        viewportZoom = nextZoom
        val currentProject = project
        if (documentAnchor != null && currentProject != null) {
            val zoomed = fittedDocumentRect(currentProject)?.let(::displayedRect)
            if (zoomed != null) {
                val scale = zoomed.width / currentProject.width
                panX += focusX - (zoomed.left + documentAnchor.x * scale)
                panY += focusY - (zoomed.top + documentAnchor.y * scale)
            }
        } else {
            val ratio = nextZoom / previousZoom
            val centerX = width / 2.0
            val centerY = height / 2.0
            panX = focusX - centerX - (anchorX - centerX - panX) * ratio
            panY = focusY - centerY - (anchorY - centerY - panY) * ratio
        }
        constrainPan()
        invalidate()
    }

    fun showLayer(
        project: CompProjectReader.Project,
        layer: CompProjectReader.Layer,
        bitmap: Bitmap,
        editingSession: LayerEditingSession?
    ) {
        cancelTransform()
        finishBrushStroke(commit = false)
        resetViewportForProject(project)
        clearEditedTileBitmaps()
        replaceBitmap(bitmap)
        this.project = project
        this.layer = layer
        this.editingSession = editingSession
        brushMode = false
        showingComposite = false
        layerOpacity = layer.opacity
        message = context.getString(R.string.layer_preview_note)
        updateContentDescription()
        editingSession?.let(::restoreEditedTiles)
        invalidate()
    }

    fun showComposite(project: CompProjectReader.Project, bitmap: Bitmap, note: String) {
        setTransformMode(false)
        cancelTransform()
        finishBrushStroke(commit = false)
        resetViewportForProject(project)
        clearEditedTileBitmaps()
        replaceBitmap(bitmap)
        this.project = project
        layer = null
        editingSession = null
        brushMode = false
        showingComposite = true
        message = note
        contentDescription = context.getString(R.string.canvas_composite_description, note)
        invalidate()
    }

    fun showMessage(project: CompProjectReader.Project, message: String) {
        setTransformMode(false)
        finishBrushStroke(commit = false)
        clearEditedTileBitmaps()
        replaceBitmap(null)
        this.project = project
        layer = null
        editingSession = null
        brushMode = false
        showingComposite = false
        this.message = message
        contentDescription = message
        invalidate()
    }

    fun clear(message: String) {
        setTransformMode(false)
        finishBrushStroke(commit = false)
        clearEditedTileBitmaps()
        replaceBitmap(null)
        project = null
        layer = null
        editingSession = null
        brushMode = false
        showingComposite = false
        resetViewport()
        this.message = message
        contentDescription = message
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        cancelTransform()
        cancelPanResetAnimation()
        constrainPan()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            cancelTransform()
            finishBrushStroke(commit = false)
            multiTouchGesture = false
            clearPinchBaseline()
        }
    }

    override fun onDetachedFromWindow() {
        cancelTransform()
        finishBrushStroke(commit = false)
        cancelPanResetAnimation()
        clearEditedTileBitmaps()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(dark)
        val currentProject = project
        val currentLayer = layer?.let { selected -> transformDraft?.let { selected.copy(transform = it) } ?: selected }
        if (currentProject == null) {
            drawCenteredMessage(canvas, message ?: context.getString(R.string.canvas_waiting_message))
            return
        }

        val fittedBase = fittedDocumentRect(currentProject) ?: run {
            drawCenteredMessage(canvas, message ?: context.getString(R.string.canvas_small_area_message))
            return
        }
        val fitted = displayedRect(fittedBase)
        documentRect.set(fitted.left.toFloat(), fitted.top.toFloat(), fitted.right.toFloat(), fitted.bottom.toFloat())

        val save = canvas.save()
        canvas.clipRect(documentRect)
        drawCheckerboard(canvas, documentRect)
        val currentBitmap = bitmap
        if (currentBitmap != null && showingComposite) {
            imagePaint.alpha = 255
            imagePaint.isFilterBitmap = true
            canvas.drawBitmap(currentBitmap, null, documentRect, imagePaint)
        } else if (currentLayer != null && currentBitmap != null) {
            drawLayer(canvas, fitted, currentProject, currentLayer, currentBitmap)
            drawEditedTiles(canvas, fitted, currentProject, currentLayer)
        }
        canvas.restoreToCount(save)
        canvas.drawRect(documentRect, borderPaint)
        if (transformMode && currentBitmap != null && currentLayer != null) drawTransformControls(canvas)
        if (currentBitmap == null) drawCenteredMessage(canvas, message ?: context.getString(R.string.canvas_layer_empty_message))
    }

    private fun drawLayer(
        canvas: Canvas,
        documentRect: CanvasPreviewGeometry.Rect,
        project: CompProjectReader.Project,
        layer: CompProjectReader.Layer,
        image: Bitmap
    ) {
        val scale = documentRect.width / project.width
        val transform = layer.transform
        val centerX = documentRect.left + (transform.x + transform.width / 2) * scale
        val centerY = documentRect.top + (transform.y + transform.height / 2) * scale
        val targetWidth = transform.width * scale
        val targetHeight = transform.height * scale

        val save = canvas.save()
        canvas.translate(centerX.toFloat(), centerY.toFloat())
        canvas.rotate((transform.rotation % 360.0).toFloat())
        canvas.scale(if (transform.flipX) -1f else 1f, if (transform.flipY) -1f else 1f)
        imagePaint.alpha = (layerOpacity.coerceIn(0.0, 1.0) * 255).toInt()
        imagePaint.isFilterBitmap = transform.sampling != "Nearest"
        canvas.drawBitmap(
            image,
            null,
            RectF(
                (-targetWidth / 2).toFloat(), (-targetHeight / 2).toFloat(),
                (targetWidth / 2).toFloat(), (targetHeight / 2).toFloat()
            ),
            imagePaint
        )
        canvas.restoreToCount(save)
    }

    private fun drawEditedTiles(
        canvas: Canvas,
        documentRect: CanvasPreviewGeometry.Rect,
        project: CompProjectReader.Project,
        layer: CompProjectReader.Layer
    ) {
        if (editedTileBitmaps.isEmpty()) return
        val session = editingSession ?: return
        val transform = layer.transform
        val scale = documentRect.width / project.width
        val centerX = documentRect.left + (transform.x + transform.width / 2) * scale
        val centerY = documentRect.top + (transform.y + transform.height / 2) * scale
        val targetWidth = transform.width * scale
        val targetHeight = transform.height * scale
        val save = canvas.save()
        canvas.translate(centerX.toFloat(), centerY.toFloat())
        canvas.rotate((transform.rotation % 360.0).toFloat())
        canvas.scale(if (transform.flipX) -1f else 1f, if (transform.flipY) -1f else 1f)
        imagePaint.alpha = (layerOpacity.coerceIn(0.0, 1.0) * 255).toInt()
        imagePaint.isFilterBitmap = transform.sampling != "Nearest"
        for ((_, tile) in editedTileBitmaps) {
            if (tile.bitmap.isRecycled) continue
            val left = -targetWidth / 2 + tile.bounds.left.toDouble() / session.raster.width * targetWidth
            val top = -targetHeight / 2 + tile.bounds.top.toDouble() / session.raster.height * targetHeight
            val right = left + tile.bounds.width.toDouble() / session.raster.width * targetWidth
            val bottom = top + tile.bounds.height.toDouble() / session.raster.height * targetHeight
            canvas.drawBitmap(
                tile.bitmap,
                null,
                RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                imagePaint
            )
        }
        canvas.restoreToCount(save)
    }

    private fun drawCheckerboard(canvas: Canvas, bounds: RectF) {
        val tile = dp(14).toFloat().coerceAtLeast(4f)
        var row = 0
        var top = floor(bounds.top / tile) * tile
        while (top < bounds.bottom) {
            var left = floor(bounds.left / tile) * tile
            var column = 0
            while (left < bounds.right) {
                checkerPaint.color = if ((row + column) % 2 == 0) checkerA else checkerB
                canvas.drawRect(left, top, left + tile, top + tile, checkerPaint)
                left += tile
                column++
            }
            top += tile
            row++
        }
    }

    private fun displayedRect(base: CanvasPreviewGeometry.Rect): CanvasPreviewGeometry.Rect {
        val centerX = width / 2.0
        val centerY = height / 2.0
        val left = centerX + (base.left - centerX) * viewportZoom + panX
        val top = centerY + (base.top - centerY) * viewportZoom + panY
        return CanvasPreviewGeometry.Rect(left, top, base.width * viewportZoom, base.height * viewportZoom)
    }

    private fun fittedDocumentRect(project: CompProjectReader.Project) = CanvasPreviewGeometry.fit(
        project.width.toDouble(), project.height.toDouble(), width.toDouble(), height.toDouble(), dp(14).toDouble()
    )

    private fun constrainPan() {
        val currentProject = project
        if (currentProject == null || width <= 0 || height <= 0) {
            panX = 0.0
            panY = 0.0
            return
        }
        if (viewportZoom <= MIN_ZOOM && !multiTouchGesture) {
            panX = 0.0
            panY = 0.0
            return
        }
        val inset = dp(14).toDouble()
        val base = CanvasPreviewGeometry.fit(
            currentProject.width.toDouble(), currentProject.height.toDouble(),
            width.toDouble(), height.toDouble(), inset
        ) ?: return
        val centerX = width / 2.0
        val centerY = height / 2.0
        val left = centerX + (base.left - centerX) * viewportZoom
        val right = centerX + (base.right - centerX) * viewportZoom
        val top = centerY + (base.top - centerY) * viewportZoom
        val bottom = centerY + (base.bottom - centerY) * viewportZoom
        val minVisible = minOf(dp(48).toDouble(), minOf(width, height) / 2.0)
        panX = panX.coerceIn(minVisible - right, width - minVisible - left)
        panY = panY.coerceIn(minVisible - bottom, height - minVisible - top)
    }

    private fun resetViewportForProject(newProject: CompProjectReader.Project) {
        if (project?.documentId != newProject.documentId) resetViewport()
    }

    private fun resetViewport() {
        cancelPanResetAnimation()
        viewportZoom = MIN_ZOOM
        panX = 0.0
        panY = 0.0
        invalidate()
    }

    private fun drawCenteredMessage(canvas: Canvas, text: String) {
        val lines = text.split('\n')
        val lineHeight = messagePaint.textSize * 1.4f
        val startY = height / 2f - (lines.size - 1) * lineHeight / 2f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, width / 2f, startY + index * lineHeight, messagePaint)
        }
    }

    private fun replaceBitmap(replacement: Bitmap?) {
        val previous = bitmap
        bitmap = replacement
        if (previous !== replacement && previous != null && !previous.isRecycled) previous.recycle()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun sp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val MIN_ZOOM = 1.0
        private const val MAX_ZOOM = 12.0
        private const val PAN_RESET_DURATION_MS = 220L
    }
}
