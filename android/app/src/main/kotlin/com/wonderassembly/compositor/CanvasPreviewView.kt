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
import com.wonderassembly.compositor.core.PointD
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
    private var bitmap: Bitmap? = null
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
        setOnTouchListener { _, event ->
            if (project == null || bitmap == null) {
                false
            } else {
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
                if (clickDetected) {
                    clickDetected = false
                    performClick()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    multiTouchGesture = false
                    clearPinchBaseline()
                    animatePanToOriginAtMinimumZoom()
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
        bitmap: Bitmap
    ) {
        resetViewportForProject(project)
        replaceBitmap(bitmap)
        this.project = project
        this.layer = layer
        showingComposite = false
        layerOpacity = layer.opacity
        message = context.getString(R.string.layer_preview_note)
        contentDescription = context.getString(R.string.canvas_layer_description, layer.name)
        invalidate()
    }

    fun showComposite(project: CompProjectReader.Project, bitmap: Bitmap, note: String) {
        resetViewportForProject(project)
        replaceBitmap(bitmap)
        this.project = project
        layer = null
        showingComposite = true
        message = note
        contentDescription = context.getString(R.string.canvas_composite_description, note)
        invalidate()
    }

    fun showMessage(project: CompProjectReader.Project, message: String) {
        replaceBitmap(null)
        this.project = project
        layer = null
        showingComposite = false
        this.message = message
        contentDescription = message
        invalidate()
    }

    fun clear(message: String) {
        replaceBitmap(null)
        project = null
        layer = null
        showingComposite = false
        resetViewport()
        this.message = message
        contentDescription = message
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        cancelPanResetAnimation()
        constrainPan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(dark)
        val currentProject = project
        val currentLayer = layer
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
        }
        canvas.restoreToCount(save)
        canvas.drawRect(documentRect, borderPaint)
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
