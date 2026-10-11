package com.wonderassembly.compositor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.text.NumberFormat

/** Opens native .comp packages and previews temporary in-memory pixel edits. */
class MainActivity : Activity() {
    private val background = Color.rgb(36, 36, 36)
    private val surface = Color.rgb(48, 48, 48)
    private val primaryText = Color.rgb(242, 242, 242)
    private val secondaryText = Color.rgb(190, 190, 190)
    private val worker = Executors.newSingleThreadExecutor()
    private val previewRequestId = AtomicInteger()
    private val projectOpenRequestId = AtomicInteger()
    private lateinit var root: LinearLayout
    private lateinit var openButton: Button
    private lateinit var cancelOpenButton: Button
    private lateinit var introText: TextView
    private lateinit var statusText: TextView
    private lateinit var projectSummaryText: TextView
    private lateinit var canvasTitle: TextView
    private lateinit var canvasPreviewView: CanvasPreviewView
    private lateinit var canvasToolScroller: HorizontalScrollView
    private lateinit var canvasToolRow: LinearLayout
    private lateinit var navigateButton: Button
    private lateinit var brushButton: Button
    private lateinit var layersTitle: TextView
    private lateinit var layerList: ListView
    private lateinit var layerAdapter: LayerListAdapter
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var exportLayerButton: Button
    private lateinit var saveProjectCopyButton: Button
    private lateinit var exportCanvasButton: Button
    private lateinit var cancelExportButton: Button
    private lateinit var compositeButton: Button
    private var currentProject: CompProjectReader.Project? = null
    private var selectedProjectUri: Uri? = null
    private var selectedProjectIsArchive = false
    private var selectedProjectIsImage = false
    private var selectedLayerId: String? = null
    private var savedLayerScrollPosition = 0
    private var layerEditingSessions: MutableMap<String, LayerEditingSession> = linkedMapOf()
    private var pendingLayerExport: PendingLayerExport? = null
    private var pendingProjectArchive: PendingProjectArchive? = null
    private var pendingCanvasExport: PendingCanvasExport? = null
    private var canvasExportJob: CanvasExportJob? = null
    private var exportFlowActive = false
    private var projectOpenInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val retained = lastNonConfigurationInstance as? RetainedLayerSessions
        layerEditingSessions = retained?.sessions ?: linkedMapOf()
        pendingLayerExport = retained?.pendingExport
        pendingProjectArchive = retained?.pendingProjectArchive
        pendingCanvasExport = retained?.pendingCanvasExport
        canvasExportJob = retained?.canvasExportJob
        exportFlowActive = pendingLayerExport != null || pendingProjectArchive != null || pendingCanvasExport != null || canvasExportJob != null
        if (retained == null) cleanupOrphanedArchiveImports()
        buildHome()
        if (exportFlowActive) updateExportFlowControls()
        val restoredUri = savedInstanceState?.getString(STATE_PROJECT_URI)?.let(Uri::parse)
        selectedProjectUri = restoredUri
        selectedLayerId = savedInstanceState?.getString(STATE_LAYER_ID)
        savedLayerScrollPosition = savedInstanceState?.getInt(STATE_LAYER_SCROLL_POSITION) ?: 0
        selectedProjectIsArchive = savedInstanceState?.getBoolean(STATE_PROJECT_IS_ARCHIVE) ?: false
        selectedProjectIsImage = savedInstanceState?.getBoolean(STATE_PROJECT_IS_IMAGE) ?: false
        if (retained?.project != null) {
            currentProject = retained.project
            showProject(retained.project)
        } else if (restoredUri != null) {
            if (selectedProjectIsImage) loadProjectArchive(restoredUri, restoringState = true, importImage = true)
            else if (selectedProjectIsArchive) loadProjectArchive(restoredUri, restoringState = true)
            else loadProjectFolder(restoredUri, restoringState = true)
        }
        canvasExportJob?.let(::monitorCanvasExport)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PROJECT_URI, selectedProjectUri?.toString())
        outState.putBoolean(STATE_PROJECT_IS_ARCHIVE, selectedProjectIsArchive)
        outState.putBoolean(STATE_PROJECT_IS_IMAGE, selectedProjectIsImage)
        outState.putString(STATE_LAYER_ID, selectedLayerId)
        outState.putInt(STATE_LAYER_SCROLL_POSITION, if (::layerList.isInitialized) layerList.firstVisiblePosition else 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewRequestId.incrementAndGet()
        projectOpenRequestId.incrementAndGet()
        canvasExportJob?.cancelled?.set(true)
        worker.shutdownNow()
        if (::canvasPreviewView.isInitialized) canvasPreviewView.clear(getString(R.string.choose_project_status))
        if (!isChangingConfigurations) {
            clearLayerEditingSessions()
            currentProject?.archiveCacheDirectory?.deleteRecursively()
        }
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any =
        RetainedLayerSessions(layerEditingSessions, pendingLayerExport, pendingProjectArchive, currentProject,
            pendingCanvasExport, canvasExportJob)

    private fun buildHome() {
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, bars.bottom)
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
            setOnClickListener { chooseProjectSource() }
        }
        header.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })
        cancelOpenButton = Button(this).apply {
            text = getString(R.string.project_open_cancel_button)
            isAllCaps = false
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener {
                projectOpenRequestId.incrementAndGet()
                setProjectOpenActive(false)
                statusText.text = getString(R.string.project_open_cancelled_status)
            }
        }
        header.addView(cancelOpenButton)

        introText = text(getString(R.string.app_intro), 16, secondaryText)
        statusText = text(getString(R.string.choose_project_status), 15, secondaryText)

        projectSummaryText = text("", 15, primaryText)
        projectSummaryText.setPadding(dp(12), dp(10), dp(12), dp(10))
        projectSummaryText.setBackgroundColor(surface)
        projectSummaryText.visibility = View.GONE

        canvasTitle = text(getString(R.string.canvas_composite_preview), 18, primaryText, bold = true)
        canvasTitle.visibility = View.GONE

        canvasPreviewView = CanvasPreviewView(this).apply { visibility = View.GONE }
        canvasPreviewView.onBrushStrokeCommitted = { layerName ->
            statusText.text = getString(R.string.brush_stroke_committed_status, layerName)
        }
        canvasPreviewView.onBrushStrokeCancelled = {
            statusText.text = getString(R.string.brush_stroke_cancelled_status)
        }
        canvasPreviewView.onBrushError = {
            statusText.text = getString(
                R.string.brush_edit_failed_status,
                getString(R.string.brush_generic_failure_reason)
            )
            brushButton.isSelected = false
            navigateButton.isSelected = true
            canvasPreviewView.setBrushMode(false)
            updateCanvasTools()
        }
        canvasPreviewView.onBrushHistoryChanged = { updateBrushHistoryButtons() }
        canvasPreviewView.onBrushHistoryLimitReached = {
            statusText.text = getString(R.string.brush_history_budget_exceeded_status)
        }
        canvasPreviewView.onBrushStrokeUndone = {
            statusText.text = getString(R.string.brush_stroke_undone_status)
        }
        canvasPreviewView.onBrushStrokeRedone = {
            statusText.text = getString(R.string.brush_stroke_redone_status)
        }

        canvasToolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = getString(R.string.canvas_tools_description)
        }
        navigateButton = toolButton(getString(R.string.canvas_tool_navigate)) {
            canvasPreviewView.setBrushMode(false)
            updateCanvasTools()
            statusText.text = getString(R.string.brush_mode_disabled_status)
        }
        brushButton = toolButton(getString(R.string.canvas_tool_brush)) {
            if (canvasPreviewView.setBrushMode(true)) {
                updateCanvasTools()
                val name = currentProject?.layers?.firstOrNull { it.id == selectedLayerId }?.name.orEmpty()
                statusText.text = getString(R.string.brush_mode_enabled_status, name)
            } else {
                statusText.text = getString(R.string.brush_layer_unavailable_status)
            }
        }
        canvasToolRow.addView(navigateButton)
        canvasToolRow.addView(brushButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        undoButton = toolButton(getString(R.string.canvas_tool_undo)) {
            if (canvasPreviewView.undoBrushStroke()) updateCanvasTools()
        }
        undoButton.contentDescription = getString(R.string.canvas_tool_undo_accessibility)
        canvasToolRow.addView(undoButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })
        redoButton = toolButton(getString(R.string.canvas_tool_redo)) {
            if (canvasPreviewView.redoBrushStroke()) updateCanvasTools()
        }
        redoButton.contentDescription = getString(R.string.canvas_tool_redo_accessibility)
        canvasToolRow.addView(redoButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })
        exportLayerButton = toolButton(getString(R.string.canvas_tool_export_layer)) {
            requestLayerPngExport()
        }
        exportLayerButton.contentDescription = getString(R.string.canvas_tool_export_layer_accessibility)
        canvasToolRow.addView(exportLayerButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })
        saveProjectCopyButton = toolButton(getString(R.string.canvas_tool_save_project_copy)) {
            requestProjectArchiveExport()
        }
        saveProjectCopyButton.contentDescription = getString(R.string.canvas_tool_save_project_copy_accessibility)
        canvasToolRow.addView(saveProjectCopyButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })
        exportCanvasButton = toolButton(getString(R.string.canvas_tool_export_canvas)) { chooseCanvasExportFormat() }
        exportCanvasButton.contentDescription = getString(R.string.canvas_tool_export_canvas_accessibility)
        canvasToolRow.addView(exportCanvasButton)
        cancelExportButton = toolButton(getString(R.string.canvas_export_cancel_button)) {
            canvasExportJob?.cancelled?.set(true)
            cancelExportButton.isEnabled = false
        }
        canvasToolRow.addView(cancelExportButton)
        compositeButton = toolButton(getString(R.string.canvas_tool_composite)) {
            currentProject?.let { project ->
                canvasPreviewView.setBrushMode(false)
                showCompositePreview(project)
            }
        }
        canvasToolRow.addView(compositeButton)
        canvasToolScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(canvasToolRow, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        layersTitle = text(getString(R.string.layers_title), 18, primaryText, bold = true)
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
        details.addView(canvasToolScroller.withTopMargin(4))
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
        previewPanel.addView(canvasToolScroller.withTopMargin(4))
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

    private fun chooseProjectSource() {
        if (layerEditingSessions.values.any { it.hasCommittedChanges }) {
            AlertDialog.Builder(this).setTitle(R.string.open_unsaved_title)
                .setMessage(R.string.open_unsaved_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_unsaved_continue) { _, _ -> showProjectSourcePicker() }.show()
        } else showProjectSourcePicker()
    }

    private fun showProjectSourcePicker() {
        AlertDialog.Builder(this)
            .setTitle(R.string.open_project_source_title)
            .setItems(arrayOf(
                getString(R.string.open_project_folder_option),
                getString(R.string.open_project_archive_option),
                getString(R.string.open_image_option)
            )) { _, choice ->
                when (choice) {
                    0 -> chooseProjectFolder()
                    1 -> chooseProjectArchive()
                    else -> chooseImage()
                }
            }
            .show()
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, OPEN_IMAGE_REQUEST)
    }

    private fun chooseProjectFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, OPEN_PROJECT_REQUEST)
    }

    private fun chooseProjectArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, OPEN_PROJECT_ARCHIVE_REQUEST)
    }

    @Deprecated("The platform picker returns a persistable document-tree permission through this callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_CANVAS_REQUEST) {
            val pending = pendingCanvasExport
            pendingCanvasExport = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null || pending == null) {
                setExportFlowActive(false)
                statusText.text = getString(R.string.canvas_export_cancelled_status)
            } else startCanvasExport(pending, uri)
            return
        }
        if (requestCode == OPEN_IMAGE_REQUEST) {
            if (resultCode == RESULT_OK) data?.data?.let { loadProjectArchive(it, importImage = true) }
            return
        }
        if (requestCode == EXPORT_LAYER_PNG_REQUEST) {
            val pending = pendingLayerExport
            pendingLayerExport = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null || pending == null) {
                setExportFlowActive(false)
                statusText.text = getString(R.string.layer_png_export_cancelled_status)
                return
            }
            exportLayerPng(pending, uri)
            return
        }
        if (requestCode == EXPORT_PROJECT_ARCHIVE_REQUEST) {
            val pending = pendingProjectArchive
            pendingProjectArchive = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null || pending == null) {
                setExportFlowActive(false)
                statusText.text = getString(R.string.project_copy_cancelled_status)
                return
            }
            writeProjectArchive(pending, uri)
            return
        }
        if (requestCode == OPEN_PROJECT_ARCHIVE_REQUEST && resultCode == RESULT_OK) {
            data?.data?.let(::loadProjectArchive)
            return
        }
        if (requestCode != OPEN_PROJECT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadProjectFolder(uri)
    }

    private fun loadProjectFolder(uri: Uri, restoringState: Boolean = false) {
        val request = projectOpenRequestId.incrementAndGet()
        setProjectOpenActive(true)
        statusText.text = getString(R.string.project_loading_status)
        worker.execute {
            var candidate: CompProjectReader.Project? = null
            var failure: String? = null
            try {
                val project = CompProjectReader(this).read(uri)
                validateProjectAssets(project) { request != projectOpenRequestId.get() || isFinishing || isDestroyed }
                candidate = project
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.project_open_failed_status)
            } catch (_: OutOfMemoryError) {
                failure = getString(R.string.project_open_memory_failure_status)
            }
            runOnUiThread {
                if (request != projectOpenRequestId.get() || isFinishing || isDestroyed) return@runOnUiThread
                val project = candidate
                if (project == null) {
                    setProjectOpenActive(false)
                    val message = failure ?: getString(R.string.project_open_failed_status)
                    statusText.text = if (currentProject != null) {
                        getString(R.string.project_open_preserved_status, message)
                    } else {
                        message
                    }
                    openButton.isEnabled = true
                    return@runOnUiThread
                }

                if (!restoringState) {
                    val previousCache = currentProject?.archiveCacheDirectory
                    previewRequestId.incrementAndGet()
                    canvasPreviewView.clear(getString(R.string.choose_project_status))
                    clearLayerEditingSessions()
                    if (previousCache != null && previousCache != project.archiveCacheDirectory) previousCache.deleteRecursively()
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        // The picker still grants a temporary read permission for this session.
                    }
                    selectedProjectUri = uri
                    selectedProjectIsArchive = false
                    selectedProjectIsImage = false
                    selectedLayerId = null
                    savedLayerScrollPosition = 0
                }
                showProject(project)
            }
        }
    }

    private fun loadProjectArchive(uri: Uri, restoringState: Boolean = false, importImage: Boolean = false) {
        val request = projectOpenRequestId.incrementAndGet()
        setProjectOpenActive(true)
        statusText.text = getString(if (importImage) R.string.image_import_loading_status else R.string.project_archive_loading_status)
        worker.execute {
            var candidate: CompProjectReader.Project? = null
            var failure: String? = null
            try {
                val cancelled = {
                    request != projectOpenRequestId.get() || isFinishing || isDestroyed || Thread.currentThread().isInterrupted
                }
                val project = if (importImage) ProjectImageImporter(this).read(uri, cancelled)
                    else CompProjectArchiveImporter(this, CompProjectReader(this)).read(uri, cancelled)
                try {
                    validateProjectAssets(project) {
                        request != projectOpenRequestId.get() || isFinishing || isDestroyed || Thread.currentThread().isInterrupted
                    }
                    candidate = project
                } catch (error: Exception) {
                    project.archiveCacheDirectory?.deleteRecursively()
                    throw error
                } catch (error: OutOfMemoryError) {
                    project.archiveCacheDirectory?.deleteRecursively()
                    throw error
                }
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.project_open_failed_status)
            } catch (_: OutOfMemoryError) {
                failure = getString(R.string.project_open_memory_failure_status)
            }
            val completedCandidate = candidate
            runOnUiThread {
                if (request != projectOpenRequestId.get() || isFinishing || isDestroyed) {
                    completedCandidate?.archiveCacheDirectory?.deleteRecursively()
                    return@runOnUiThread
                }
                val project = completedCandidate
                if (project == null) {
                    setProjectOpenActive(false)
                    val message = failure ?: getString(R.string.project_open_failed_status)
                    statusText.text = if (currentProject != null) {
                        getString(R.string.project_open_preserved_status, message)
                    } else {
                        message
                    }
                    openButton.isEnabled = true
                    return@runOnUiThread
                }

                val previousCache = currentProject?.archiveCacheDirectory
                previewRequestId.incrementAndGet()
                canvasPreviewView.clear(getString(R.string.choose_project_status))
                clearLayerEditingSessions()
                if (previousCache != null && previousCache != project.archiveCacheDirectory) previousCache.deleteRecursively()
                if (!restoringState) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        // The picker still grants a temporary read permission for this session.
                    }
                    selectedLayerId = null
                    savedLayerScrollPosition = 0
                }
                selectedProjectUri = uri
                selectedProjectIsArchive = !importImage
                selectedProjectIsImage = importImage
                showProject(project)
                if (importImage) statusText.text = getString(R.string.image_import_complete_status)
            }
        }
    }

    private fun validateProjectAssets(
        project: CompProjectReader.Project,
        isCancelled: () -> Boolean
    ) {
        fun validateAsset(name: String, uri: Uri, size: CompProjectReader.PixelSize?) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("Project preflight was cancelled")
            val bitmap = try {
                decodePreviewBitmap(uri, size)
            } catch (error: Exception) {
                throw IllegalArgumentException("Unable to decode project asset \"$name\".", error)
            }
            bitmap.recycle()
        }

        for ((filename, uri) in project.imageUris) {
            validateAsset("images/$filename", uri, project.imageSizes[filename])
        }
        for ((filename, uri) in project.maskUris) {
            validateAsset("images/$filename", uri, project.maskSizes[filename])
        }
    }

    private fun showProject(project: CompProjectReader.Project) {
        currentProject = project
        setProjectOpenActive(false)
        statusText.text = getString(R.string.project_opened_status)
        projectSummaryText.text = getString(
            R.string.project_summary, project.width, project.height, project.formatVersion,
            resources.getQuantityString(R.plurals.layer_count, project.layers.size, project.layers.size),
            formatResolution(project.resolution)
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
        releaseInactiveLayerSources()
        val request = previewRequestId.incrementAndGet()
        canvasTitle.text = getString(R.string.canvas_composite_preview)
        canvasPreviewView.showMessage(project, getString(R.string.composite_loading_message))
        updateCanvasTools()
        statusText.text = getString(R.string.composite_loading_status)
        val edits = project.layers.mapNotNull { layer ->
            val file = layer.imageFile ?: return@mapNotNull null
            val session = layerEditingSessions[layerEditingSessionKey(selectedProjectUri, project, layer)]
            if (session?.hasCommittedChanges == true) file to session else null
        }.toMap()
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
                                    val totalLayers = resources.getQuantityString(
                                        R.plurals.raster_layer_count, total, total
                                    )
                                    statusText.text = getString(R.string.composite_progress_status, completed, totalLayers)
                                }
                            }
                        }
                    },
                    editedAssets = edits
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
                        .distinct().joinToString(", ")
                        .ifBlank { getString(R.string.composite_no_known_limits) }
                    val rasterLayerCount = resources.getQuantityString(
                        R.plurals.raster_layer_count, completed.drawnLayerCount, completed.drawnLayerCount
                    )
                    val note = getString(R.string.composite_preview_note, rasterLayerCount)
                    canvasPreviewView.showComposite(project, completed.bitmap, note)
                    updateCanvasTools()
                    statusText.text = getString(
                        R.string.composite_loaded_status,
                        rasterLayerCount,
                        if (completed.limitations.isEmpty()) getString(R.string.composite_no_known_limits)
                        else getString(R.string.composite_has_limits, limitations)
                    )
                } else {
                    val message = failure ?: getString(R.string.composite_failed_message)
                    canvasPreviewView.showMessage(project, message)
                    updateCanvasTools()
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
        canvasPreviewView.showMessage(project, getString(R.string.layer_loading_canvas_message))
        updateCanvasTools(layer)
        releaseInactiveLayerSources()
        if (layer.imageFile == null) {
            previewRequestId.incrementAndGet()
            canvasPreviewView.showMessage(project, getString(R.string.layer_has_no_pixels_message))
            updateCanvasTools(layer)
            statusText.text = getString(R.string.layer_without_image_status, layer.name)
            return
        }
        val imageUri = project.imageUris[layer.imageFile]
        if (imageUri == null) {
            previewRequestId.incrementAndGet()
            canvasPreviewView.showMessage(project, getString(R.string.layer_image_missing_message))
            updateCanvasTools(layer)
            statusText.text = getString(R.string.layer_image_missing_status, layer.name)
            return
        }

        val request = previewRequestId.incrementAndGet()
        val editingKey = layerEditingSessionKey(selectedProjectUri, project, layer)
        val existingSession = layerEditingSessions[editingKey]
        statusText.text = getString(R.string.layer_preview_loading_status, layer.name)
        worker.execute {
            var decoded: Bitmap? = null
            var createdSession: LayerEditingSession? = null
            var failure: String? = null
            try {
                decoded = decodePreviewBitmap(imageUri, project.imageSizes[layer.imageFile])
                val sourceSize = project.imageSizes[layer.imageFile]
                if (existingSession != null) {
                    createdSession = existingSession
                } else if (sourceSize != null && supportsTemporaryBrush(layer)) {
                    val maxCachedBytes = AndroidRasterBudget.capture(this).maxEditableDocumentBytes / EDIT_CHUNK_CACHE_DIVISOR
                    createdSession = LayerEditingSession(
                        applicationContext,
                        imageUri,
                        sourceSize.width,
                        sourceSize.height,
                        maxCachedBytes
                    )
                }
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.layer_decode_failed_message)
            }
            runOnUiThread {
                if (request != previewRequestId.get() || isFinishing || isDestroyed) {
                    decoded?.recycle()
                    if (createdSession !== existingSession) createdSession?.close()
                    return@runOnUiThread
                }
                if (decoded != null) {
                    if (createdSession != null) layerEditingSessions[editingKey] = createdSession!!
                    canvasPreviewView.showLayer(project, layer, decoded!!, createdSession)
                    updateCanvasTools(layer)
                    statusText.text = getString(
                        if (createdSession != null && canvasPreviewView.canEnableBrush()) {
                            R.string.layer_preview_loaded_status
                        } else {
                            R.string.layer_preview_loaded_no_brush_status
                        },
                        layer.name
                    )
                } else {
                    val message = failure ?: getString(R.string.layer_decode_failed_message)
                    canvasPreviewView.showMessage(project, message)
                    updateCanvasTools(layer)
                    statusText.text = message
                }
            }
        }
    }

    private fun supportsTemporaryBrush(layer: CompProjectReader.Layer): Boolean =
        layer.visible && !layer.group && !layer.adjustment && layer.imageFile != null &&
            layer.blendMode.equals("Normal", ignoreCase = true) && layer.opacity > 0.0 && !layer.hasEffects

    private fun updateCanvasTools(selectedLayer: CompProjectReader.Layer? = null) {
        if (!::canvasToolRow.isInitialized) return
        val busy = exportFlowActive || projectOpenInProgress
        val layer = selectedLayer ?: currentProject?.layers?.firstOrNull { it.id == selectedLayerId }
        canvasToolScroller.visibility = if (currentProject == null) View.GONE else View.VISIBLE
        val available = layer != null && canvasPreviewView.canEnableBrush()
        navigateButton.isEnabled = !busy
        brushButton.isEnabled = available && !busy
        brushButton.text = getString(when {
            !available -> R.string.canvas_tool_brush_unavailable
            canvasPreviewView.isBrushMode -> R.string.canvas_tool_brush_active
            else -> R.string.canvas_tool_brush
        })
        brushButton.contentDescription = getString(
            if (available) R.string.canvas_tool_brush_accessibility
            else R.string.canvas_tool_brush_unavailable_accessibility
        )
        brushButton.isSelected = canvasPreviewView.isBrushMode
        exportLayerButton.isEnabled = available && !busy
        saveProjectCopyButton.isEnabled = currentProject != null && !busy
        exportCanvasButton.isEnabled = currentProject != null && !busy
        compositeButton.isEnabled = currentProject != null && !busy
        cancelExportButton.visibility = if (canvasExportJob != null) View.VISIBLE else View.GONE
        cancelExportButton.isEnabled = canvasExportJob?.cancelled?.get() == false
        navigateButton.text = getString(
            if (canvasPreviewView.isBrushMode) R.string.canvas_tool_navigate
            else R.string.canvas_tool_navigate_active
        )
        navigateButton.isSelected = !canvasPreviewView.isBrushMode
        updateBrushHistoryButtons()
    }

    private fun updateBrushHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        undoButton.isEnabled = !exportFlowActive && !projectOpenInProgress && canvasPreviewView.canUndoBrushStroke
        redoButton.isEnabled = !exportFlowActive && !projectOpenInProgress && canvasPreviewView.canRedoBrushStroke
    }

    private fun requestLayerPngExport() {
        val project = currentProject
        val layer = project?.layers?.firstOrNull { it.id == selectedLayerId }
        val imageUri = layer?.imageFile?.let { file -> project?.imageUris?.get(file) }
        val editingSession = if (project != null && layer != null) {
            layerEditingSessions[layerEditingSessionKey(selectedProjectUri, project, layer)]
        } else {
            null
        }
        if (layer == null || imageUri == null || editingSession == null || !canvasPreviewView.canEnableBrush()) {
            statusText.text = getString(R.string.brush_layer_unavailable_status)
            return
        }

        canvasPreviewView.setBrushMode(false)
        pendingLayerExport = PendingLayerExport(editingSession, imageUri, layer.name)
        setExportFlowActive(true)
        statusText.text = getString(R.string.layer_png_export_choose_status)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, exportFileName(layer.name))
        }
        try {
            startActivityForResult(intent, EXPORT_LAYER_PNG_REQUEST)
        } catch (error: Exception) {
            pendingLayerExport = null
            setExportFlowActive(false)
            statusText.text = getString(R.string.layer_png_export_failed_status, error.message.orEmpty())
        }
    }

    private fun chooseCanvasExportFormat() {
        AlertDialog.Builder(this).setTitle(R.string.canvas_export_format_title)
            .setItems(arrayOf("PNG", "JPEG")) { _, choice ->
                if (choice == 0) requestCanvasExport(CompositeImageExporter.Format.PNG, 100)
                else AlertDialog.Builder(this).setTitle(R.string.canvas_export_quality_title)
                    .setItems(arrayOf("75%", "90%", "100%")) { _, index ->
                        requestCanvasExport(CompositeImageExporter.Format.JPEG, intArrayOf(75, 90, 100)[index])
                    }.show()
            }.show()
    }

    private fun requestCanvasExport(format: CompositeImageExporter.Format, quality: Int) {
        val project = currentProject ?: return
        canvasPreviewView.setBrushMode(false)
        val editedAssets = project.layers.mapNotNull { layer ->
            val file = layer.imageFile ?: return@mapNotNull null
            val session = layerEditingSessions[layerEditingSessionKey(selectedProjectUri, project, layer)]
            if (session?.hasCommittedChanges == true) file to session else null
        }.toMap()
        pendingCanvasExport = PendingCanvasExport(project, editedAssets, format, quality)
        setExportFlowActive(true)
        statusText.text = getString(R.string.canvas_export_choose_status)
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = format.mimeType
                putExtra(Intent.EXTRA_TITLE, "Compositor-Canvas.${format.extension}")
            }, EXPORT_CANVAS_REQUEST)
        } catch (error: Exception) {
            pendingCanvasExport = null
            setExportFlowActive(false)
            statusText.text = getString(R.string.canvas_export_failed_status, error.message.orEmpty())
        }
    }

    private fun startCanvasExport(pending: PendingCanvasExport, targetUri: Uri) {
        val job = CanvasExportJob()
        canvasExportJob = job
        setExportFlowActive(true)
        // This operation owns its completion state across configuration changes. Destruction cancels
        // it, and a replacement Activity keeps editing disabled until cleanup has completed.
        val appContext = applicationContext
        Thread({
            var deleteTarget = false
            try {
                val original = targetUri == pending.project.sourceUri || pending.project.packageAssets.any { it.uri == targetUri }
                require(!original) { "Choose a separate destination so the source project remains unchanged." }
                deleteTarget = true
                require(!isInsideSourcePackage(targetUri, pending.project)) { "Choose a destination outside the source .comp folder." }
                val output = appContext.contentResolver.openOutputStream(targetUri, "w")
                    ?: throw IllegalArgumentException("The selected provider could not create the image.")
                output.use {
                    CompositeImageExporter(appContext).write(it, pending.project, pending.editedAssets,
                        pending.format, pending.quality, { job.cancelled.get() }, { job.progress.set(it) })
                }
                if (job.cancelled.get()) throw java.util.concurrent.CancellationException("Image export was cancelled.")
                job.succeeded = true
            } catch (error: Exception) {
                job.failure = error.message ?: "The image could not be written."
            } catch (_: OutOfMemoryError) {
                job.failure = "There was not enough memory to export this canvas."
            } finally {
                if (!job.succeeded && deleteTarget) {
                    job.cleanupFailed = runCatching { appContext.contentResolver.delete(targetUri, null, null) > 0 }.getOrDefault(false).not()
                }
                job.finished = true
            }
        }, "compositor-canvas-export").start()
        monitorCanvasExport(job)
    }

    private fun monitorCanvasExport(job: CanvasExportJob) {
        if (isFinishing || isDestroyed || canvasExportJob !== job) return
        if (!job.finished) {
            statusText.text = getString(R.string.canvas_export_progress_status, job.progress.get())
            root.postDelayed({ monitorCanvasExport(job) }, 150L)
            return
        }
        canvasExportJob = null
        setExportFlowActive(false)
        statusText.text = when {
            job.succeeded -> getString(R.string.canvas_export_complete_status)
            job.cancelled.get() -> getString(R.string.canvas_export_cancelled_status)
            else -> getString(R.string.canvas_export_failed_status, job.failure.orEmpty())
        }
        if (job.cleanupFailed) statusText.append(" " + getString(R.string.canvas_export_cleanup_failed_status))
    }

    private fun exportFileName(layerName: String): String {
        val safeName = layerName.map { character ->
            if (character == '/' || character == '\\' || Character.isISOControl(character)) '_' else character
        }.joinToString("").trim().ifBlank { "Layer" }
        return "$safeName-Export.png"
    }

    private fun exportLayerPng(pending: PendingLayerExport, targetUri: Uri) {
        setExportFlowActive(true)
        statusText.text = getString(R.string.layer_png_exporting_status, pending.layerName)
        worker.execute {
            var outputWasOpened = false
            var failure: String? = null
            var succeeded = false
            try {
                require(targetUri != pending.sourceUri) {
                    "Choose a separate document so the source project image stays unchanged."
                }
                val output = contentResolver.openOutputStream(targetUri, "w")
                    ?: throw IllegalArgumentException("The selected document provider could not create the PNG.")
                outputWasOpened = true
                output.use { pending.session.writePng(it) }
                succeeded = true
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.layer_png_export_failed_generic)
                if (outputWasOpened) runCatching { contentResolver.delete(targetUri, null, null) }
            } catch (_: OutOfMemoryError) {
                failure = getString(R.string.layer_png_export_memory_failed)
                if (outputWasOpened) runCatching { contentResolver.delete(targetUri, null, null) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setExportFlowActive(false)
                statusText.text = if (succeeded) {
                    getString(R.string.layer_png_export_complete_status, pending.layerName)
                } else {
                    getString(R.string.layer_png_export_failed_status, failure.orEmpty())
                }
            }
        }
    }

    private fun requestProjectArchiveExport() {
        val project = currentProject ?: return
        canvasPreviewView.setBrushMode(false)
        val editedAssets = project.layers.mapNotNull { layer ->
            val filename = layer.imageFile ?: return@mapNotNull null
            val session = layerEditingSessions[layerEditingSessionKey(selectedProjectUri, project, layer)]
            if (session?.hasCommittedChanges == true) filename to session else null
        }.toMap()
        pendingProjectArchive = PendingProjectArchive(project, editedAssets)
        setExportFlowActive(true)
        statusText.text = getString(R.string.project_copy_choose_status)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, CompProjectArchiveWriter(contentResolver).suggestedFilename(project))
        }
        try {
            startActivityForResult(intent, EXPORT_PROJECT_ARCHIVE_REQUEST)
        } catch (error: Exception) {
            pendingProjectArchive = null
            setExportFlowActive(false)
            statusText.text = getString(R.string.project_copy_failed_status, error.message.orEmpty())
        }
    }

    private fun writeProjectArchive(pending: PendingProjectArchive, targetUri: Uri) {
        setExportFlowActive(true)
        statusText.text = getString(R.string.project_copy_writing_status)
        worker.execute {
            var outputWasOpened = false
            var shouldDeleteTarget = false
            var failure: String? = null
            var succeeded = false
            try {
                val isOriginalAsset = targetUri == pending.project.sourceUri ||
                    pending.project.packageAssets.any { it.uri == targetUri }
                require(!isOriginalAsset) {
                    "Choose a separate destination so the source project remains unchanged."
                }
                require(!isInsideSourcePackage(targetUri, pending.project)) {
                    shouldDeleteTarget = true
                    "Choose a destination outside the source .comp project folder."
                }
                val output = contentResolver.openOutputStream(targetUri, "w")
                    ?: throw IllegalArgumentException("The selected document provider could not create the project ZIP.")
                outputWasOpened = true
                shouldDeleteTarget = true
                output.use {
                    CompProjectArchiveWriter(contentResolver).write(it, pending.project, pending.editedAssets)
                }
                succeeded = true
            } catch (error: Exception) {
                failure = error.message ?: getString(R.string.project_copy_failed_generic)
                if (shouldDeleteTarget || outputWasOpened) runCatching { contentResolver.delete(targetUri, null, null) }
            } catch (_: OutOfMemoryError) {
                failure = getString(R.string.project_copy_memory_failed)
                if (shouldDeleteTarget || outputWasOpened) runCatching { contentResolver.delete(targetUri, null, null) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setExportFlowActive(false)
                statusText.text = if (succeeded) {
                    getString(R.string.project_copy_complete_status)
                } else {
                    getString(R.string.project_copy_failed_status, failure.orEmpty())
                }
            }
        }
    }

    private fun isInsideSourcePackage(targetUri: Uri, project: CompProjectReader.Project): Boolean {
        if (targetUri.authority != project.sourceUri.authority) return false
        val sourceId = runCatching { DocumentsContract.getTreeDocumentId(project.sourceUri) }.getOrNull()
            ?: return false
        val targetId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull()
            ?: return false
        return targetId == sourceId || targetId.startsWith("${sourceId.trimEnd('/')}/")
    }

    private fun setExportFlowActive(active: Boolean) {
        exportFlowActive = active
        updateExportFlowControls()
    }

    private fun setProjectOpenActive(active: Boolean) {
        projectOpenInProgress = active
        cancelOpenButton.visibility = if (active) View.VISIBLE else View.GONE
        if (active) canvasPreviewView.setBrushMode(false)
        updateExportFlowControls()
    }

    private fun updateExportFlowControls() {
        val busy = exportFlowActive || projectOpenInProgress
        if (::openButton.isInitialized) openButton.isEnabled = !busy
        if (::layerList.isInitialized) layerList.isEnabled = !busy
        if (::canvasPreviewView.isInitialized) canvasPreviewView.isEnabled = !busy
        if (::canvasToolRow.isInitialized) updateCanvasTools()
    }

    private fun decodePreviewBitmap(uri: Uri, pixelSize: CompProjectReader.PixelSize?): Bitmap {
        requireNotNull(pixelSize) { "The project has no dimensions for this PNG." }
        val sampleSize = AndroidRasterBudget.capture(this).sampleSizeFor(pixelSize.width, pixelSize.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalArgumentException("Could not decode the layer PNG.")
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

    private fun toolButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minWidth = dp(48)
        minHeight = dp(48)
        setPadding(dp(5), dp(2), dp(5), dp(2))
        setOnClickListener { action() }
    }

    private fun layerEditingSessionKey(
        projectUri: Uri?,
        project: CompProjectReader.Project,
        layer: CompProjectReader.Layer
    ): String = "${projectUri?.toString().orEmpty()}|${project.documentId}|${layer.id}"

    private fun clearLayerEditingSessions() {
        layerEditingSessions.values.forEach { runCatching { it.close() } }
        layerEditingSessions.clear()
    }

    private fun cleanupOrphanedArchiveImports() {
        cacheDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(ARCHIVE_CACHE_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    private fun releaseInactiveLayerSources(activeKey: String? = null) {
        layerEditingSessions.forEach { (key, session) ->
            if (key != activeKey) runCatching { session.closeSource() }
        }
    }

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

    private data class PendingLayerExport(
        val session: LayerEditingSession,
        val sourceUri: Uri,
        val layerName: String
    )

    private data class PendingProjectArchive(
        val project: CompProjectReader.Project,
        val editedAssets: Map<String, LayerEditingSession>
    )

    private data class RetainedLayerSessions(
        val sessions: MutableMap<String, LayerEditingSession>,
        val pendingExport: PendingLayerExport?,
        val pendingProjectArchive: PendingProjectArchive?,
        val project: CompProjectReader.Project?,
        val pendingCanvasExport: PendingCanvasExport?,
        val canvasExportJob: CanvasExportJob?
    )

    private data class PendingCanvasExport(
        val project: CompProjectReader.Project,
        val editedAssets: Map<String, LayerEditingSession>,
        val format: CompositeImageExporter.Format,
        val quality: Int
    )

    private class CanvasExportJob {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val progress = AtomicInteger(0)
        @Volatile var finished = false
        @Volatile var succeeded = false
        @Volatile var cleanupFailed = false
        @Volatile var failure: String? = null
    }

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
        private const val EXPORT_LAYER_PNG_REQUEST = 42
        private const val EXPORT_PROJECT_ARCHIVE_REQUEST = 43
        private const val OPEN_PROJECT_ARCHIVE_REQUEST = 44
        private const val EXPORT_CANVAS_REQUEST = 45
        private const val OPEN_IMAGE_REQUEST = 46
        private const val STATE_PROJECT_URI = "projectTreeUri"
        private const val STATE_PROJECT_IS_ARCHIVE = "projectIsArchive"
        private const val STATE_PROJECT_IS_IMAGE = "projectIsImage"
        private const val STATE_LAYER_ID = "selectedLayerId"
        private const val STATE_LAYER_SCROLL_POSITION = "layerScrollPosition"
        private const val EDIT_CHUNK_CACHE_DIVISOR = 6L
        private const val ARCHIVE_CACHE_PREFIX = "compositor-import-"
    }
}
