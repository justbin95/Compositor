package com.wonderassembly.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import kotlin.math.floor

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

    init {
        setBackgroundColor(dark)
        contentDescription = "项目画布预览"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun showLayer(
        project: CompProjectReader.Project,
        layer: CompProjectReader.Layer,
        bitmap: Bitmap
    ) {
        replaceBitmap(bitmap)
        this.project = project
        this.layer = layer
        showingComposite = false
        layerOpacity = layer.opacity
        message = "单层原始像素预览\n蒙版、混合、组和调整效果尚未合成"
        contentDescription = "${layer.name}，单层原始像素预览。项目其他图层及蒙版和效果尚未合成。"
        invalidate()
    }

    fun showComposite(project: CompProjectReader.Project, bitmap: Bitmap, note: String) {
        replaceBitmap(bitmap)
        this.project = project
        layer = null
        showingComposite = true
        message = note
        contentDescription = "項目多圖層合成預覽。$note"
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
        this.message = message
        contentDescription = message
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(dark)
        val currentProject = project
        val currentLayer = layer
        if (currentProject == null) {
            drawCenteredMessage(canvas, message ?: "打开项目后显示画布")
            return
        }

        val inset = dp(14).toDouble()
        val fitted = CanvasPreviewGeometry.fit(
            currentProject.width.toDouble(), currentProject.height.toDouble(),
            width.toDouble(), height.toDouble(), inset
        ) ?: run {
            drawCenteredMessage(canvas, message ?: "画布预览区域太小")
            return
        }
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
        if (currentBitmap == null) drawCenteredMessage(canvas, message ?: "此图层没有可预览的像素图片")
        else drawPreviewNote(canvas, documentRect)
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

    private fun drawPreviewNote(canvas: Canvas, bounds: RectF) {
        val lines = (message ?: return).split('\n')
        messagePaint.textAlign = Paint.Align.RIGHT
        val lineHeight = messagePaint.textSize * 1.25f
        val startY = bounds.bottom - dp(8) - (lines.size - 1) * lineHeight
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, bounds.right - dp(8), startY + index * lineHeight, messagePaint)
        }
        messagePaint.textAlign = Paint.Align.CENTER
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
}
