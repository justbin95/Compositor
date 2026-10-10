package com.wonderassembly.compositor

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.text.NumberFormat

/** First real project workflow: open and inspect a native .comp package without changing it. */
class MainActivity : Activity() {
    private val background = Color.rgb(36, 36, 36)
    private val surface = Color.rgb(48, 48, 48)
    private val primaryText = Color.rgb(242, 242, 242)
    private val secondaryText = Color.rgb(190, 190, 190)
    private val worker = Executors.newSingleThreadExecutor()
    private val previewRequestId = AtomicInteger()
    private lateinit var root: LinearLayout
    private lateinit var openButton: Button
    private lateinit var introText: TextView
    private lateinit var statusText: TextView
    private lateinit var projectSummaryText: TextView
    private lateinit var canvasTitle: TextView
    private lateinit var canvasPreviewView: CanvasPreviewView
    private lateinit var layersTitle: TextView
    private lateinit var layerList: ListView
    private lateinit var layerAdapter: LayerListAdapter
    private var currentProject: CompProjectReader.Project? = null
    private var selectedProjectUri: Uri? = null
    private var selectedLayerId: String? = null
    private var savedLayerScrollPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildHome()
        val restoredUri = savedInstanceState?.getString(STATE_PROJECT_URI)?.let(Uri::parse)
        selectedProjectUri = restoredUri
        selectedLayerId = savedInstanceState?.getString(STATE_LAYER_ID)
        savedLayerScrollPosition = savedInstanceState?.getInt(STATE_LAYER_SCROLL_POSITION) ?: 0
        if (restoredUri != null) loadProjectFolder(restoredUri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROJECT_URI, selectedProjectUri?.toString())
        outState.putString(STATE_LAYER_ID, selectedLayerId)
        outState.putInt(STATE_LAYER_SCROLL_POSITION, if (::layerList.isInitialized) layerList.firstVisiblePosition else 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewRequestId.incrementAndGet()
        worker.shutdownNow()
        if (::canvasPreviewView.isInitialized) canvasPreviewView.clear(getString(R.string.choose_project_status))
        super.onDestroy()
    }

    private fun buildHome() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val left: Int
                val top: Int
                val right: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    left = bars.left
                    top = bars.top
                    right = bars.right
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        left = insets.systemWindowInsetLeft
                        top = insets.systemWindowInsetTop
                        right = insets.systemWindowInsetRight
                        bottom = insets.systemWindowInsetBottom
                    }
                }
                view.setPadding(dp(12) + left, dp(8) + top, dp(12) + right, bottom)
                insets
            }
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(header, matchWidth())
        header.addView(text("Compositor", 26, primaryText, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        openButton = Button(this).apply {
            text = getString(R.string.open_project_button)
            textSize = 16f
            isAllCaps = false
            minHeight = dp(52)
            minWidth = dp(172)
            setOnClickListener { chooseProjectFolder() }
        }
        header.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })

        introText = text(getString(R.string.app_intro), 16, secondaryText)
        statusText = text(getString(R.string.choose_project_status), 15, secondaryText)

        projectSummaryText = text("", 15, primaryText)
        projectSummaryText.setPadding(dp(12), dp(10), dp(12), dp(10))
        projectSummaryText.setBackgroundColor(surface)
        projectSummaryText.visibility = View.GONE

        canvasTitle = text("画布预览", 18, primaryText, bold = true)
        canvasTitle.visibility = View.GONE

        canvasPreviewView = CanvasPreviewView(this).apply { visibility = View.GONE }

        layersTitle = text("图层（由上到下）", 18, primaryText, bold = true)
        layersTitle.visibility = View.GONE

        layerAdapter = LayerListAdapter()
        layerList = ListView(this).apply {
            adapter = layerAdapter
            divider = android.graphics.drawable.ColorDrawable(Color.rgb(65, 65, 65))
            dividerHeight = dp(1)
            setBackgroundColor(this@MainActivity.background)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setOnItemClickListener { _, _, position, _ -> showLayerPreview(layerAdapter.getItem(position).layer) }
        }

        if (isLandscape) {
            buildLandscapeWorkspace()
        } else {
            buildPortraitWorkspace()
        }
        root.requestApplyInsets()
    }

    private fun buildPortraitWorkspace() {
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(details, matchWidth())
        details.addView(introText.withTopMargin(4))
        details.addView(statusText.withTopMargin(10))
        details.addView(projectSummaryText.withTopMargin(10))
        details.addView(canvasTitle.withTopMargin(14))
        val previewHeight = if (resources.configuration.smallestScreenWidthDp < 600) dp(176) else dp(250)
        details.addView(canvasPreviewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight).apply {
            topMargin = dp(6)
        })
        root.addView(layersTitle.withTopMargin(12))
        root.addView(layerList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildLandscapeWorkspace() {
        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(12), dp(4))
        }
        workspace.addView(previewPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f))
        previewPanel.addView(statusText.withTopMargin(2))
        previewPanel.addView(projectSummaryText.withTopMargin(6))
        previewPanel.addView(canvasTitle.withTopMargin(8))
        previewPanel.addView(canvasPreviewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        val layersPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        workspace.addView(layersPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        layersPanel.addView(layersTitle)
        layersPanel.addView(layerList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun chooseProjectFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, OPEN_PROJECT_REQUEST)
    }

    @Deprecated("The platform picker returns a persistable document-tree permission through this callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != OPEN_PROJECT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        selectedProjectUri = uri
        selectedLayerId = null
        savedLayerScrollPosition = 0
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The picker still grants a temporary read permission for this session.
        }
        loadProjectFolder(uri)
    }

    private fun loadProjectFolder(uri: Uri) {
        currentProject = null
        val request = previewRequestId.incrementAndGet()
        canvasTitle.visibility = View.GONE
        canvasPreviewView.visibility = View.GONE
        openButton.isEnabled = false
        statusText.text = getString(R.string.project_loading_status)
        layerAdapter.submit(emptyList())
        projectSummaryText.visibility = View.GONE
        layersTitle.visibility = View.GONE
        worker.execute {
            try {
                val project = CompProjectReader(this).read(uri)
                runOnUiThread {
                    if (request == previewRequestId.get() && !isFinishing && !isDestroyed) showProject(project)
                }
            } catch (error: Exception) {
                val message = error.message ?: getString(R.string.project_open_failed_status)
                runOnUiThread {
                    if (request != previewRequestId.get() || isFinishing || isDestroyed) return@runOnUiThread
                    statusText.text = message
                    openButton.isEnabled = true
                }
            }
        }
    }

    private fun showProject(project: CompProjectReader.Project) {
        currentProject = project
        openButton.isEnabled = true
        statusText.text = getString(R.string.project_opened_status)
        projectSummaryText.text = getString(
            R.string.project_summary, project.width, project.height, project.formatVersion,
            project.layers.size, formatResolution(project.resolution)
        )
        projectSummaryText.visibility = View.VISIBLE
        canvasTitle.visibility = View.VISIBLE
        canvasPreviewView.visibility = View.VISIBLE
        layersTitle.visibility = View.VISIBLE
        val rows = flattenLayersTopFirst(project.layers)
        layerAdapter.submit(rows)
        val restoredLayer = selectedLayerId?.let { id -> project.layers.firstOrNull { it.id == id } }
        if (restoredLayer != null) {
            val rowPosition = rows.indexOfFirst { it.layer.id == restoredLayer.id }
            if (rowPosition >= 0) layerList.post { layerList.setSelection(rowPosition) }
            showLayerPreview(restoredLayer)
        } else {
            selectedLayerId = null
            layerList.post { layerList.setSelection(savedLayerScrollPosition.coerceAtMost((rows.size - 1).coerceAtLeast(0))) }
            showCompositePreview(project)
        }
    }

    private fun showCompositePreview(project: CompProjectReader.Project) {
        selectedLayerId = null
        val request = previewRequestId.incrementAndGet()
        canvasTitle.text = getString(R.string.canvas_composite_preview)
        canvasPreviewView.showMessage(project, getString(R.string.composite_loading_message))
        statusText.text = getString(R.string.composite_loading_status)
        worker.execute {
            var result: PreviewCompositeRenderer.Result? = null
            var failure: String? = null
            try {
                result = PreviewCompositeRenderer(this).render(
                    project,
                    isCancelled = { request != previewRequestId.get() || isFinishing || isDestroyed },
                    onProgress = { completed, total ->
                        if (completed == total || completed % 24 == 0) {
                            runOnUiThread {
                                if (request == previewRequestId.get() && !isFinishing && !isDestroyed) {
                                    statusText.text = getString(R.string.composite_progress_status, completed, total)
                                }
                            }
                        }
                    }
                )
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.composite_failed_message)
            }
            runOnUiThread {
                val completed = result
                if (request != previewRequestId.get() || isFinishing || isDestroyed) {
                    completed?.bitmap?.recycle()
                    return@runOnUiThread
                }
                if (completed != null) {
                    val limitations = completed.limitations.map { limitationLabel(it) }
                        .distinct().joinToString("、")
                        .ifBlank { getString(R.string.composite_no_known_limits) }
                    val note = getString(R.string.composite_preview_note, completed.drawnLayerCount)
                    canvasPreviewView.showComposite(project, completed.bitmap, note)
                    statusText.text = getString(
                        R.string.composite_loaded_status,
                        completed.drawnLayerCount,
                        if (completed.limitations.isEmpty()) getString(R.string.composite_no_known_limits)
                        else getString(R.string.composite_has_limits, limitations)
                    )
                } else {
                    val message = failure ?: getString(R.string.composite_failed_message)
                    canvasPreviewView.showMessage(project, message)
                    statusText.text = message
                }
            }
        }
    }

    private fun limitationLabel(limitation: PreviewLimitation): String = getString(when (limitation) {
        PreviewLimitation.ADJUSTMENTS -> R.string.composite_limit_adjustments
        PreviewLimitation.BLEND_MODES -> R.string.composite_limit_blend_modes
        PreviewLimitation.EFFECTS -> R.string.composite_limit_effects
    })

    private fun showLayerPreview(layer: CompProjectReader.Layer) {
        val project = currentProject ?: return
        selectedLayerId = layer.id
        canvasTitle.text = getString(R.string.canvas_preview_layer, layer.name)
        if (layer.imageFile == null) {
            previewRequestId.incrementAndGet()
            canvasPreviewView.showMessage(project, getString(R.string.layer_has_no_pixels_message))
            statusText.text = getString(R.string.layer_without_image_status, layer.name)
            return
        }
        val imageUri = project.imageUris[layer.imageFile]
        if (imageUri == null) {
            previewRequestId.incrementAndGet()
            canvasPreviewView.showMessage(project, getString(R.string.layer_image_missing_message))
            statusText.text = getString(R.string.layer_image_missing_status, layer.name)
            return
        }

        val request = previewRequestId.incrementAndGet()
        statusText.text = getString(R.string.layer_preview_loading_status, layer.name)
        worker.execute {
            var decoded: Bitmap? = null
            var failure: String? = null
            try {
                decoded = decodePreviewBitmap(imageUri, project.imageSizes[layer.imageFile])
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.layer_decode_failed_message)
            }
            runOnUiThread {
                if (request != previewRequestId.get() || isFinishing || isDestroyed) {
                    decoded?.recycle()
                    return@runOnUiThread
                }
                if (decoded != null) {
                    canvasPreviewView.showLayer(project, layer, decoded!!)
                    statusText.text = getString(R.string.layer_preview_loaded_status, layer.name)
                } else {
                    val message = failure ?: getString(R.string.layer_decode_failed_message)
                    canvasPreviewView.showMessage(project, message)
                    statusText.text = message
                }
            }
        }
    }

    private fun decodePreviewBitmap(uri: Uri, pixelSize: CompProjectReader.PixelSize?): Bitmap {
        requireNotNull(pixelSize) { "项目没有记录这张 PNG 的尺寸。" }
        var sampleSize = 1
        while (maxOf(pixelSize.width, pixelSize.height) / sampleSize > MAX_PREVIEW_BITMAP_SIDE) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalArgumentException("无法解码图层 PNG 文件。")
        return decoded
    }

    private fun flattenLayersTopFirst(layers: List<CompProjectReader.Layer>): List<LayerRow> {
        val children = layers.groupBy { it.parentId }
        val result = ArrayList<LayerRow>(layers.size)
        fun append(parentId: String?, depth: Int) {
            for (layer in children[parentId].orEmpty().asReversed()) {
                result += LayerRow(layer, depth)
                if (layer.group) append(layer.id, depth + 1)
            }
        }
        append(null, 0)
        return result
    }

    private fun formatResolution(value: Double): String =
        getString(R.string.resolution_ppi, NumberFormat.getNumberInstance().format(value))

    private fun text(value: String, sizeSp: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sizeSp.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun LinearLayout.LayoutParams.withTopMargin(value: Int) = apply { topMargin = dp(value) }

    private fun View.withTopMargin(value: Int): View {
        layoutParams = matchWidth().withTopMargin(value)
        return this
    }

    private data class LayerRow(val layer: CompProjectReader.Layer, val depth: Int)

    private inner class LayerListAdapter : BaseAdapter() {
        private var rows: List<LayerRow> = emptyList()

        fun submit(value: List<LayerRow>) {
            rows = value
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): LayerRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val holder = (recycled?.tag as? RowHolder) ?: createRowHolder()
            val row = getItem(position)
            holder.container.setPadding(dp(12 + row.depth * 16), dp(10), dp(12), dp(10))
            holder.title.text = row.layer.name
            holder.subtitle.text = getString(
                R.string.layer_row_subtitle,
                layerKind(row.layer),
                getString(if (row.layer.visible) R.string.layer_visible else R.string.layer_hidden)
            )
            return holder.container
        }

        private fun createRowHolder(): RowHolder {
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(surface)
            }
            val title = text("", 16, primaryText)
            val subtitle = text("", 13, secondaryText)
            container.addView(title)
            container.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            })
            return RowHolder(container, title, subtitle).also { container.tag = it }
        }

        private fun layerKind(layer: CompProjectReader.Layer): String = when {
            layer.group && layer.maskFile != null -> getString(R.string.layer_kind_group_with_mask)
            layer.group -> getString(R.string.layer_kind_group)
            layer.adjustment -> getString(R.string.layer_kind_adjustment)
            layer.imageFile != null && layer.maskFile != null -> getString(R.string.layer_kind_pixel_with_mask)
            layer.imageFile != null -> getString(R.string.layer_kind_pixel)
            layer.maskFile != null -> getString(R.string.layer_kind_empty_with_mask)
            else -> getString(R.string.layer_kind_generic)
        }
    }

    private data class RowHolder(val container: LinearLayout, val title: TextView, val subtitle: TextView)

    companion object {
        private const val OPEN_PROJECT_REQUEST = 41
        private const val MAX_PREVIEW_BITMAP_SIDE = 2048
        private const val STATE_PROJECT_URI = "projectTreeUri"
        private const val STATE_LAYER_ID = "selectedLayerId"
        private const val STATE_LAYER_SCROLL_POSITION = "layerScrollPosition"
    }
}
