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
import android.widget.EditText
import android.text.InputType
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
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
    private lateinit var saveProjectFolderButton: Button
    private lateinit var exportCanvasButton: Button
    private lateinit var cancelExportButton: Button
    private lateinit var compositeButton: Button
    private lateinit var moveLayerUpButton: Button
    private lateinit var moveLayerDownButton: Button
    private lateinit var transformLayerButton: Button
    private lateinit var layerPropertiesButton: Button
    private lateinit var addImageButton: Button
    private lateinit var eraserButton: Button
    private lateinit var brushSettingsButton: Button
    private lateinit var documentHistory: com.wonderassembly.compositor.core.DocumentHistory<DocumentEdit>
    private var documentStructureChanged = false
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
    private var exportTask: ExportTask? = null
    private var exportFlowActive = false
    private var projectOpenInProgress = false
    private lateinit var recoveryStatusText: TextView
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recoveryTask: RecoveryTask? = null
    private var recoveryDocumentId: String? = null
    private var recoveryRevision = 0L
    private var recoveredRevision = -1L
    private var recoveryEpoch = java.util.UUID.randomUUID().toString()
    private val recoveryTick = object : Runnable {
        override fun run() {
            requestInternalRecovery()
            recoveryHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val retained = lastNonConfigurationInstance as? RetainedLayerSessions
        layerEditingSessions = retained?.sessions ?: linkedMapOf()
        documentStructureChanged = retained?.structureChanged ?: false
        recoveryTask = retained?.recoveryTask
        recoveryDocumentId = retained?.recoveryDocumentId
        recoveryRevision = retained?.recoveryRevision ?: 0L
        recoveredRevision = retained?.recoveredRevision ?: -1L
        recoveryEpoch = retained?.recoveryEpoch ?: recoveryEpoch
        documentHistory = retained?.history ?: com.wonderassembly.compositor.core.DocumentHistory(
            runCatching { AndroidRasterBudget.capture(this).maxEditableDocumentBytes / 12L }.getOrDefault(0L))
        pendingLayerExport = retained?.pendingExport
        pendingProjectArchive = retained?.pendingProjectArchive
        pendingCanvasExport = retained?.pendingCanvasExport
        exportTask = retained?.exportTask
        exportFlowActive = pendingLayerExport != null || pendingProjectArchive != null || pendingCanvasExport != null || exportTask?.result == null && exportTask != null
        if (retained == null) cleanupOrphanedArchiveImports()
        buildHome()
        savedInstanceState?.let {
            canvasPreviewView.brushDiameter = it.getDouble("brushDiameter", 32.0)
            canvasPreviewView.brushOpacity = it.getDouble("brushOpacity", 1.0)
            canvasPreviewView.brushHardness = it.getDouble("brushHardness", 1.0)
            canvasPreviewView.brushColor = it.getInt("brushColor", Color.BLACK)
            canvasPreviewView.eraser = it.getBoolean("eraser", false)
        }
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
        exportTask?.let(::monitorExportTask)
        recoveryTask?.let(::monitorRecoveryTask)
        if (retained == null) offerInternalRecovery()
    }

    override fun onStart() {
        super.onStart()
        recoveryHandler.postDelayed(recoveryTick, 30_000)
    }

    override fun onStop() {
        recoveryHandler.removeCallbacks(recoveryTick)
        requestInternalRecovery()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putDouble("brushDiameter", canvasPreviewView.brushDiameter)
        outState.putDouble("brushOpacity", canvasPreviewView.brushOpacity)
        outState.putDouble("brushHardness", canvasPreviewView.brushHardness)
        outState.putInt("brushColor", canvasPreviewView.brushColor)
        outState.putBoolean("eraser", canvasPreviewView.eraser)
        outState.putString(STATE_PROJECT_URI, selectedProjectUri?.toString())
        outState.putBoolean(STATE_PROJECT_IS_ARCHIVE, selectedProjectIsArchive)
        outState.putBoolean(STATE_PROJECT_IS_IMAGE, selectedProjectIsImage)
        outState.putString(STATE_LAYER_ID, selectedLayerId)
        outState.putInt(STATE_LAYER_SCROLL_POSITION, if (::layerList.isInitialized) layerList.firstVisiblePosition else 0)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        recoveryHandler.removeCallbacksAndMessages(null)
        previewRequestId.incrementAndGet()
        projectOpenRequestId.incrementAndGet()
        if (!isChangingConfigurations) exportTask?.cancel()
        worker.shutdownNow()
        if (::canvasPreviewView.isInitialized) canvasPreviewView.clear(getString(R.string.choose_project_status))
        if (!isChangingConfigurations) {
            val sessions = layerEditingSessions.values.toList()
            val project = currentProject
            val release = { sessions.forEach { it.close() }; project?.let(RecoveryTask::releaseProjectWhenFinished); Unit }
            exportTask?.releaseWhenFinished(release) ?: release()
        }
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any =
        RetainedLayerSessions(layerEditingSessions, pendingLayerExport, pendingProjectArchive, currentProject,
            pendingCanvasExport, exportTask, documentStructureChanged, documentHistory,
            recoveryTask, recoveryDocumentId, recoveryRevision, recoveredRevision, recoveryEpoch)

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
        recoveryStatusText = text(getString(R.string.recovery_initial_status), 14, secondaryText)

        projectSummaryText = text("", 15, primaryText)
        projectSummaryText.setPadding(dp(12), dp(10), dp(12), dp(10))
        projectSummaryText.setBackgroundColor(surface)
        projectSummaryText.visibility = View.GONE

        canvasTitle = text(getString(R.string.canvas_composite_preview), 18, primaryText, bold = true)
        canvasTitle.visibility = View.GONE

        canvasPreviewView = CanvasPreviewView(this).apply { visibility = View.GONE }
        canvasPreviewView.recordStroke = { layerId, patch ->
            refreshHistoryBudget()
            documentHistory.record(DocumentEdit.Stroke(layerId, patch), patch.retainedSnapshotBytes)
        }
        canvasPreviewView.onBrushStrokeCommitted = { layerName ->
            markRecoveryEdited()
            reclaimUnusedDocumentResources()
            statusText.text = getString(R.string.brush_stroke_committed_status, layerName)
        }
        canvasPreviewView.onBrushStrokeCancelled = {
            statusText.text = getString(R.string.brush_stroke_cancelled_status)
        }
        canvasPreviewView.onBrushError = { reason ->
            statusText.text = getString(
                R.string.brush_edit_failed_status,
                reason
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
            canvasPreviewView.setBrushMode(false)
            canvasPreviewView.eraser = false
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
        eraserButton = toolButton(getString(R.string.canvas_tool_eraser)) {
            canvasPreviewView.setBrushMode(false)
            canvasPreviewView.eraser = true
            canvasPreviewView.setBrushMode(true)
            updateCanvasTools()
        }
        canvasToolRow.addView(eraserButton)
        brushSettingsButton = toolButton(getString(R.string.brush_settings)) { showBrushSettings() }
        canvasToolRow.addView(brushSettingsButton)
        undoButton = toolButton(getString(R.string.canvas_tool_undo)) {
            applyDocumentHistory(redo = false)
        }
        undoButton.contentDescription = getString(R.string.canvas_tool_undo_accessibility)
        canvasToolRow.addView(undoButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })
        redoButton = toolButton(getString(R.string.canvas_tool_redo)) {
            applyDocumentHistory(redo = true)
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
        saveProjectFolderButton = toolButton(getString(R.string.project_folder_button)) {
            AlertDialog.Builder(this).setTitle(R.string.project_folder_button)
                .setMessage(R.string.project_folder_explanation)
                .setPositiveButton(R.string.project_folder_choose) { _, _ -> requestProjectArchiveExport(folder = true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        canvasToolRow.addView(saveProjectFolderButton)
        exportCanvasButton = toolButton(getString(R.string.canvas_tool_export_canvas)) { chooseCanvasExportFormat() }
        exportCanvasButton.contentDescription = getString(R.string.canvas_tool_export_canvas_accessibility)
        canvasToolRow.addView(exportCanvasButton)
        cancelExportButton = toolButton(getString(R.string.canvas_export_cancel_button)) {
            exportTask?.cancel()
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
        addImageButton = toolButton(getString(R.string.canvas_tool_add_image)) { chooseImage(append = true) }
        canvasToolRow.addView(toolButton(getString(R.string.recovery_copies_button)) { showRecoveryCopies() })
        canvasToolRow.addView(addImageButton)
        layerPropertiesButton = toolButton(getString(R.string.layer_properties)) { showLayerProperties() }
        canvasToolRow.addView(layerPropertiesButton)
        transformLayerButton = toolButton(getString(R.string.layer_transform)) { showLayerTransform() }
        canvasToolRow.addView(transformLayerButton)
        moveLayerUpButton = toolButton(getString(R.string.layer_move_up)) { moveSelectedLayer(1) }
        moveLayerDownButton = toolButton(getString(R.string.layer_move_down)) { moveSelectedLayer(-1) }
        canvasToolRow.addView(moveLayerUpButton)
        canvasToolRow.addView(moveLayerDownButton)
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
        details.addView(recoveryStatusText.withTopMargin(4))
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
        previewPanel.addView(recoveryStatusText.withTopMargin(4))
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
        if (exportTask?.result != null) exportTask = null
        if (documentStructureChanged || layerEditingSessions.values.any { it.hasCommittedChanges }) {
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
                getString(R.string.open_image_option),
                getString(R.string.new_project_option),
                getString(R.string.recovery_copies_button)
            )) { _, choice ->
                when (choice) {
                    0 -> chooseProjectFolder()
                    1 -> chooseProjectArchive()
                    2 -> chooseImage()
                    3 -> showNewProjectDialog()
                    else -> showRecoveryCopies()
                }
            }
            .show()
    }

    private fun showBrushSettings() {
        canvasPreviewView.setBrushMode(false)
        updateCanvasTools()
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun field(label: String, value: String, numeric: Boolean = true): EditText {
            fields.addView(text(label, 16, primaryText))
            return EditText(this).apply {
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
                setText(value); contentDescription = label; minHeight = dp(48); fields.addView(this)
            }
        }
        val diameter = field(getString(R.string.brush_size), canvasPreviewView.brushDiameter.toString())
        val opacity = field(getString(R.string.brush_opacity), (canvasPreviewView.brushOpacity * 100).toString())
        val hardness = field(getString(R.string.brush_hardness), (canvasPreviewView.brushHardness * 100).toString())
        val color = field(getString(R.string.brush_color), String.format(java.util.Locale.ROOT, "#%08X", canvasPreviewView.brushColor), false)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.brush_settings)
            .setView(android.widget.ScrollView(this).apply { addView(fields) })
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.apply_settings, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val d = diameter.text.toString().toDoubleOrNull()
                val o = opacity.text.toString().toDoubleOrNull()
                val h = hardness.text.toString().toDoubleOrNull()
                val c = runCatching { Color.parseColor(color.text.toString()) }.getOrNull()
                if (d == null || d !in 1.0..1024.0 || o == null || o !in 0.0..100.0 || h == null || h !in 0.0..100.0 || c == null) {
                    diameter.error = getString(R.string.brush_invalid_settings)
                } else {
                    canvasPreviewView.brushDiameter = d; canvasPreviewView.brushOpacity = o / 100
                    canvasPreviewView.brushHardness = h / 100; canvasPreviewView.brushColor = c
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showNewProjectDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun numberField(label: Int, value: String, decimal: Boolean = false): EditText {
            fields.addView(text(getString(label), 16, primaryText))
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                setText(value)
                contentDescription = getString(label)
                minHeight = dp(48)
                fields.addView(this)
            }
        }
        val width = numberField(R.string.new_project_width, "2048")
        val height = numberField(R.string.new_project_height, "2048")
        val resolution = numberField(R.string.new_project_resolution, "72", decimal = true)
        val presets = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.canvas_presets))
            contentDescription = getString(R.string.canvas_preset)
        }
        fields.addView(presets, 0)
        presets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val size = when (position) { 1 -> 2048 to 2048; 2 -> 1920 to 1080; 3 -> 1080 to 1920; else -> null }
                size?.let {
                    width.setText(String.format(java.util.Locale.ROOT, "%d", it.first))
                    height.setText(String.format(java.util.Locale.ROOT, "%d", it.second))
                }
            }
        }
        fields.addView(text(getString(R.string.canvas_background), 16, primaryText))
        val backgroundChoice = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.canvas_backgrounds))
            contentDescription = getString(R.string.canvas_background)
        }
        fields.addView(backgroundChoice)
        fields.addView(text(getString(R.string.new_project_transparent_note), 14, secondaryText))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.new_project_option)
            .setView(android.widget.ScrollView(this).apply { addView(fields) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.new_project_create, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val w = width.text.toString().toIntOrNull()
                val h = height.text.toString().toIntOrNull()
                val ppi = resolution.text.toString().toDoubleOrNull()
                if (w == null || h == null || ppi == null || w !in 1..30000 || h !in 1..30000 ||
                    !ppi.isFinite() || ppi !in 1.0..9600.0 || w.toLong() * h > 100_000_000L) {
                    statusText.text = getString(R.string.new_project_invalid_status)
                } else {
                    dialog.dismiss()
                    createBlankProject(w, h, ppi, BlankProjectCreator.Background.entries[backgroundChoice.selectedItemPosition])
                }
            }
        }
        dialog.show()
    }

    private fun createBlankProject(width: Int, height: Int, resolution: Double, background: BlankProjectCreator.Background) {
        val request = projectOpenRequestId.incrementAndGet()
        setProjectOpenActive(true)
        statusText.text = getString(R.string.new_project_creating_status)
        worker.execute {
            var candidate: CompProjectReader.Project? = null
            var failure: String? = null
            val cancelled = { request != projectOpenRequestId.get() || isFinishing || isDestroyed || Thread.currentThread().isInterrupted }
            try {
                val project = BlankProjectCreator(applicationContext).create(width, height, resolution, background, cancelled)
                candidate = project
                validateProjectAssets(project) { cancelled() }
            } catch (error: Exception) {
                candidate?.archiveCacheDirectory?.deleteRecursively()
                candidate = null
                failure = error.message ?: getString(R.string.project_open_failed_status)
            } catch (_: OutOfMemoryError) {
                candidate?.archiveCacheDirectory?.deleteRecursively()
                candidate = null
                failure = getString(R.string.project_open_memory_failure_status)
            }
            val completed = candidate
            runOnUiThread {
                if (cancelled()) {
                    completed?.archiveCacheDirectory?.deleteRecursively()
                    return@runOnUiThread
                }
                setProjectOpenActive(false)
                if (completed == null) {
                    statusText.text = getString(R.string.project_open_preserved_status, failure.orEmpty())
                    return@runOnUiThread
                }
                val previousProject = currentProject
                previewRequestId.incrementAndGet()
                canvasPreviewView.clear(getString(R.string.choose_project_status))
                clearLayerEditingSessions()
                previousProject?.let(RecoveryTask::releaseProjectWhenFinished)
                documentStructureChanged = true
                selectedProjectUri = completed.sourceUri
                selectedProjectIsArchive = false
                selectedProjectIsImage = false
                selectedLayerId = completed.activeLayerId
                savedLayerScrollPosition = 0
                showProject(completed)
                requestInternalRecovery()
            }
        }
    }

    private fun chooseImage(append: Boolean = false) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, if (append) APPEND_IMAGE_REQUEST else OPEN_IMAGE_REQUEST)
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
        if (requestCode == APPEND_IMAGE_REQUEST) {
            if (resultCode == RESULT_OK) data?.data?.let(::appendImageLayer)
            return
        }
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
        if (requestCode == EXPORT_PROJECT_ARCHIVE_REQUEST || requestCode == SAVE_PROJECT_FOLDER_REQUEST) {
            val pending = pendingProjectArchive
            pendingProjectArchive = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null || pending == null) {
                setExportFlowActive(false)
                statusText.text = getString(R.string.project_copy_cancelled_status)
                return
            }
            if (requestCode == SAVE_PROJECT_FOLDER_REQUEST) writeProjectFolder(pending, uri)
            else writeProjectArchive(pending, uri)
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
                    val previousProject = currentProject
                    previewRequestId.incrementAndGet()
                    canvasPreviewView.clear(getString(R.string.choose_project_status))
                    clearLayerEditingSessions()
                    previousProject?.let(RecoveryTask::releaseProjectWhenFinished)
                    documentStructureChanged = false
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

                val previousProject = currentProject
                previewRequestId.incrementAndGet()
                canvasPreviewView.clear(getString(R.string.choose_project_status))
                clearLayerEditingSessions()
                previousProject?.let(RecoveryTask::releaseProjectWhenFinished)
                documentStructureChanged = importImage
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
                if (importImage) requestInternalRecovery()
            }
        }
    }

    private fun showLayerTransform() {
        val base = currentProject ?: return
        val layer = base.layers.find { it.id == selectedLayerId } ?: return
        if (exportFlowActive || projectOpenInProgress || canvasPreviewView.hasActiveStroke || layer.group || layer.adjustment || layer.imageFile == null) return
        val transform = layer.transform
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val labels = listOf("X", "Y", "Width", "Height", "Rotation (°)")
        val values = listOf(transform.x, transform.y, transform.width, transform.height, transform.rotation)
        val inputs = values.mapIndexed { index, value ->
            fields.addView(text(labels[index], 14, primaryText))
            EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(NumberFormat.getNumberInstance(java.util.Locale.US).apply {
                    isGroupingUsed = false; maximumFractionDigits = 340
                }.format(value))
                fields.addView(this)
            }
        }
        val flipX = android.widget.CheckBox(this).apply { text = getString(R.string.layer_flip_horizontal); isChecked = transform.flipX }
        val flipY = android.widget.CheckBox(this).apply { text = getString(R.string.layer_flip_vertical); isChecked = transform.flipY }
        fields.addView(flipX); fields.addView(flipY)
        fields.addView(text(getString(R.string.layer_sampling), 14, primaryText))
        val modes = listOf("Nearest", "Smooth", "High quality")
        val sampling = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes)
            setSelection(modes.indexOf(transform.sampling))
        }
        fields.addView(sampling)
        val scroll = android.widget.ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.layer_transform).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val numbers = inputs.map { it.text.toString().toDoubleOrNull() }
                val invalid = numbers.indexOfFirst { it == null || !it.isFinite() }
                if (invalid >= 0) { inputs[invalid].error = getString(R.string.layer_transform_invalid); return@setOnClickListener }
                val valid = numbers.map { requireNotNull(it) }
                val bad = valid.indices.firstOrNull { index ->
                    when (index) {
                        0, 1 -> kotlin.math.abs(valid[index]) > 1_000_000
                        2, 3 -> valid[index] !in 1.0..300_000.0
                        else -> kotlin.math.abs(valid[index]) > 360
                    }
                }
                if (bad != null) { inputs[bad].error = getString(R.string.layer_transform_invalid); return@setOnClickListener }
                if (currentProject !== base || selectedLayerId != layer.id) { dialog.dismiss(); return@setOnClickListener }
                val changed = transform.copy(x = valid[0], y = valid[1], width = valid[2], height = valid[3],
                    rotation = valid[4], flipX = flipX.isChecked, flipY = flipY.isChecked, sampling = modes[sampling.selectedItemPosition])
                changeLayerMetadata(base, layer.id, layer.name, layer.visible, layer.opacity, transform = changed)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun moveSelectedLayer(direction: Int) {
        val base = currentProject ?: return
        val layer = base.layers.find { it.id == selectedLayerId } ?: return
        changeLayerMetadata(base, layer.id, layer.name, layer.visible, layer.opacity, direction)
    }

    private fun showLayerProperties() {
        val base = currentProject ?: return
        val layer = base.layers.find { it.id == selectedLayerId } ?: return
        if (exportFlowActive || projectOpenInProgress || canvasPreviewView.hasActiveStroke) return
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val name = EditText(this).apply { setText(layer.name); hint = getString(R.string.layer_name) }
        val opacity = EditText(this).apply {
            setText(NumberFormat.getNumberInstance(java.util.Locale.US).apply {
                isGroupingUsed = false; maximumFractionDigits = 340
            }.format(layer.opacity * 100)); hint = getString(R.string.layer_opacity)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val visible = android.widget.CheckBox(this).apply { text = getString(R.string.layer_visible); isChecked = layer.visible }
        fields.addView(text(getString(R.string.layer_name), 14, primaryText))
        fields.addView(name)
        fields.addView(text(getString(R.string.layer_opacity), 14, primaryText))
        fields.addView(opacity)
        fields.addView(visible)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.layer_properties).setView(fields)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = name.text.toString()
                val percent = opacity.text.toString().toDoubleOrNull()
                if (newName.isBlank() || newName.toByteArray(Charsets.UTF_8).size > 16384) {
                    name.error = getString(R.string.layer_name_invalid); return@setOnClickListener
                }
                if (percent == null || !percent.isFinite() || percent !in 0.0..100.0) {
                    opacity.error = getString(R.string.layer_opacity_invalid); return@setOnClickListener
                }
                if (currentProject !== base || selectedLayerId != layer.id) { dialog.dismiss(); return@setOnClickListener }
                changeLayerMetadata(base, layer.id, newName, visible.isChecked,
                    if (percent == layer.opacity * 100) layer.opacity else percent / 100)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun changeLayerMetadata(base: CompProjectReader.Project, layerId: String,
        name: String, visible: Boolean, opacity: Double, move: Int = 0, transform: CompProjectReader.LayerTransform? = null) {
        if (exportFlowActive || projectOpenInProgress || canvasPreviewView.hasActiveStroke) return
        val request = projectOpenRequestId.incrementAndGet()
        setProjectOpenActive(true)
        worker.execute {
            var candidate: CompProjectReader.Project? = null
            var failure: String? = null
            try {
                val editor = ProjectLayerMetadataEditor(applicationContext)
                candidate = if (transform != null) ProjectLayerTransformEditor(applicationContext).update(base, layerId, transform)
                    else if (move == 0) editor.update(base, layerId, name, visible, opacity)
                    else editor.move(base, layerId, move)
            } catch (error: Exception) { failure = error.message }
            catch (_: OutOfMemoryError) { failure = getString(R.string.project_open_memory_failure_status) }
            val completed = candidate
            runOnUiThread {
                if (request != projectOpenRequestId.get() || isFinishing || isDestroyed || currentProject !== base) return@runOnUiThread
                setProjectOpenActive(false)
                if (completed == null) {
                    statusText.text = getString(R.string.project_open_preserved_status, failure.orEmpty())
                    return@runOnUiThread
                }
                if (completed === base) return@runOnUiThread
                refreshHistoryBudget()
                val edit = DocumentEdit.Metadata(base, completed, layerId, documentStructureChanged)
                if (!documentHistory.record(edit, base.originalManifestBytes.size.toLong() + completed.originalManifestBytes.size)) {
                    statusText.text = getString(R.string.brush_history_budget_exceeded_status)
                    return@runOnUiThread
                }
                previewRequestId.incrementAndGet()
                canvasPreviewView.clear(getString(R.string.choose_project_status))
                documentStructureChanged = true
                markRecoveryEdited()
                if (exportTask?.result != null) exportTask = null
                selectedLayerId = layerId
                showProject(completed)
                reclaimUnusedDocumentResources()
                requestInternalRecovery()
            }
        }
    }

    private fun appendImageLayer(uri: Uri) {
        val base = currentProject ?: return
        if (exportFlowActive || projectOpenInProgress) return
        val request = projectOpenRequestId.incrementAndGet()
        setProjectOpenActive(true)
        statusText.text = getString(R.string.image_import_loading_status)
        worker.execute {
            var imported: CompProjectReader.Project? = null
            var candidate: CompProjectReader.Project? = null
            var failure: String? = null
            val cancelled = { request != projectOpenRequestId.get() || isFinishing || isDestroyed || Thread.currentThread().isInterrupted }
            try {
                require(base.layers.size < 10) { "The current editing limit is 10 layers." }
                imported = ProjectImageImporter(applicationContext).read(uri, cancelled)
                candidate = ProjectLayerAppender(applicationContext).append(base, requireNotNull(imported))
                validateProjectAssets(requireNotNull(candidate), cancelled)
            } catch (error: Exception) {
                candidate = null
                failure = error.message
            } catch (_: OutOfMemoryError) {
                candidate = null
                failure = getString(R.string.project_open_memory_failure_status)
            }
            val completed = candidate
            runOnUiThread {
                if (cancelled() || currentProject !== base) {
                    imported?.releasePrivateAssets()
                    return@runOnUiThread
                }
                setProjectOpenActive(false)
                if (completed == null) {
                    imported?.releasePrivateAssets()
                    statusText.text = getString(R.string.project_open_preserved_status, failure.orEmpty())
                } else {
                    previewRequestId.incrementAndGet()
                    canvasPreviewView.clear(getString(R.string.choose_project_status))
                    val edit = DocumentEdit.Append(base, completed, selectedLayerId, documentStructureChanged)
                    val bytes = base.originalManifestBytes.size.toLong() + completed.originalManifestBytes.size
                    refreshHistoryBudget()
                    if (!documentHistory.record(edit, bytes)) {
                        imported?.releasePrivateAssets()
                        showProject(base)
                        statusText.text = getString(R.string.brush_history_budget_exceeded_status)
                        return@runOnUiThread
                    }
                    documentStructureChanged = true
                    markRecoveryEdited()
                    if (exportTask?.result != null) exportTask = null
                    selectedLayerId = completed.activeLayerId
                    showProject(completed)
                    reclaimUnusedDocumentResources()
                    requestInternalRecovery()
                }
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
        if (recoveryDocumentId != project.documentId) {
            recoveryDocumentId = project.documentId
            recoveryRevision = 0L
            recoveredRevision = -1L
            recoveryStatusText.text = getString(R.string.recovery_initial_status)
        }
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
                                    if (exportTask == null) statusText.text = getString(R.string.composite_progress_status, completed, totalLayers)
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
                exportTask?.takeIf { it.result != null }?.let(::monitorExportTask)
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
                exportTask?.takeIf { it.result != null }?.let(::monitorExportTask)
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
        eraserButton.isEnabled = available && !busy
        brushSettingsButton.isEnabled = !busy
        eraserButton.isSelected = canvasPreviewView.isBrushMode && canvasPreviewView.eraser
        eraserButton.text = getString(if (eraserButton.isSelected) R.string.canvas_tool_eraser_active else R.string.canvas_tool_eraser)
        brushButton.text = getString(when {
            !available -> R.string.canvas_tool_brush_unavailable
            canvasPreviewView.isBrushMode && !canvasPreviewView.eraser -> R.string.canvas_tool_brush_active
            else -> R.string.canvas_tool_brush
        })
        brushButton.contentDescription = getString(
            if (available) R.string.canvas_tool_brush_accessibility
            else R.string.canvas_tool_brush_unavailable_accessibility
        )
        brushButton.isSelected = canvasPreviewView.isBrushMode && !canvasPreviewView.eraser
        exportLayerButton.isEnabled = available && !busy
        saveProjectCopyButton.isEnabled = currentProject != null && !busy
        exportCanvasButton.isEnabled = currentProject != null && !busy
        saveProjectFolderButton.isEnabled = currentProject != null && !busy
        compositeButton.isEnabled = currentProject != null && !busy
        val selected = currentProject?.layers?.find { it.id == selectedLayerId }
        transformLayerButton.isEnabled = !busy && !canvasPreviewView.hasActiveStroke && selected?.imageFile != null && !selected.group && !selected.adjustment
        val siblings = currentProject?.layers.orEmpty().filter { selected != null && it.parentId == selected.parentId }
        val position = siblings.indexOf(selected)
        moveLayerUpButton.isEnabled = !busy && !canvasPreviewView.hasActiveStroke && position >= 0 && position < siblings.lastIndex
        moveLayerDownButton.isEnabled = !busy && !canvasPreviewView.hasActiveStroke && position > 0
        layerPropertiesButton.isEnabled = selectedLayerId != null && !busy && !canvasPreviewView.hasActiveStroke
        addImageButton.isEnabled = currentProject != null && !busy && currentProject!!.layers.size < 10
        cancelExportButton.visibility = if (exportTask?.result == null && exportTask != null) View.VISIBLE else View.GONE
        cancelExportButton.isEnabled = exportTask?.cancellationRequested == false
        navigateButton.text = getString(
            if (canvasPreviewView.isBrushMode) R.string.canvas_tool_navigate
            else R.string.canvas_tool_navigate_active
        )
        navigateButton.isSelected = !canvasPreviewView.isBrushMode
        updateBrushHistoryButtons()
    }

    private fun updateBrushHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        undoButton.isEnabled = !exportFlowActive && !projectOpenInProgress && (!canvasPreviewView.hasActiveStroke && documentHistory.canUndo)
        redoButton.isEnabled = !exportFlowActive && !projectOpenInProgress && (!canvasPreviewView.hasActiveStroke && documentHistory.canRedo)
    }

    private fun requestLayerPngExport() {
        if (exportTask?.result != null) exportTask = null
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
        if (exportTask?.result != null) exportTask = null
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
        val appContext = applicationContext
        startExport(ExportTask(ExportTask.Kind.CANVAS), targetUri, pending.project) { output, cancelled, progress ->
            CompositeImageExporter(appContext).write(output, pending.project, pending.editedAssets,
                pending.format, pending.quality, cancelled, progress)
        }
    }

    private fun startExport(
        task: ExportTask,
        targetUri: Uri,
        project: CompProjectReader.Project,
        encode: (java.io.OutputStream, () -> Boolean, (Int) -> Unit) -> Unit
    ) {
        exportTask = task
        setExportFlowActive(true)
        val appContext = applicationContext
        task.start(appContext.cacheDir, {
            require(targetUri != project.sourceUri && project.packageAssets.none { it.uri == targetUri } &&
                !isInsideSourcePackage(targetUri, project)) {
                "Choose a separate destination outside the source project."
            }
        }, {
            appContext.contentResolver.openOutputStream(targetUri, "w")
                ?: throw IllegalArgumentException("The selected provider could not create the export.")
        }, {
            appContext.contentResolver.delete(targetUri, null, null) > 0
        }, encode)
        monitorExportTask(task)
    }

    private fun monitorExportTask(task: ExportTask) {
        if (isFinishing || isDestroyed || exportTask !== task) return
        val result = task.result
        if (result == null) {
            statusText.text = getString(R.string.canvas_export_progress_status, task.progress.get())
            root.postDelayed({ monitorExportTask(task) }, 150L)
            return
        }
        setExportFlowActive(false)
        statusText.text = when (task.kind) {
            ExportTask.Kind.LAYER -> when {
                result.succeeded -> getString(R.string.layer_png_export_complete_status, task.layerName)
                result.cancelled -> getString(R.string.layer_png_export_cancelled_status)
                else -> getString(R.string.layer_png_export_failed_status, result.failure.orEmpty())
            }
            ExportTask.Kind.CANVAS -> when {
                result.succeeded -> getString(R.string.canvas_export_complete_status)
                result.cancelled -> getString(R.string.canvas_export_cancelled_status)
                else -> getString(R.string.canvas_export_failed_status, result.failure.orEmpty())
            }
            ExportTask.Kind.FOLDER -> when {
                result.succeeded -> getString(R.string.project_folder_complete_status, task.publishedFolderName.orEmpty())
                result.cancelled -> getString(R.string.project_copy_cancelled_status)
                else -> getString(R.string.project_copy_failed_status, result.failure.orEmpty())
            }
            ExportTask.Kind.ARCHIVE -> when {
                result.succeeded -> getString(R.string.project_copy_complete_status)
                result.cancelled -> getString(R.string.project_copy_cancelled_status)
                else -> getString(R.string.project_copy_failed_status, result.failure.orEmpty())
            }
        }
        if (result.cleanupFailed) statusText.append(" " + getString(R.string.canvas_export_cleanup_failed_status))
        updateCanvasTools()
    }

    private fun exportFileName(layerName: String): String {
        val safeName = layerName.map { character ->
            if (character == '/' || character == '\\' || Character.isISOControl(character)) '_' else character
        }.joinToString("").trim().ifBlank { "Layer" }
        return "$safeName-Export.png"
    }

    private fun exportLayerPng(pending: PendingLayerExport, targetUri: Uri) {
        val project = currentProject ?: return
        startExport(ExportTask(ExportTask.Kind.LAYER, pending.layerName), targetUri, project) { output, cancelled, progress ->
            pending.session.writePng(output, cancelled, progress)
        }
    }

    private fun requestProjectArchiveExport(folder: Boolean = false) {
        if (exportTask?.result != null) exportTask = null
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
        val intent = Intent(if (folder) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_CREATE_DOCUMENT).apply {
            if (!folder) addCategory(Intent.CATEGORY_OPENABLE)
            if (!folder) type = "application/zip"
            if (folder) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, CompProjectArchiveWriter(contentResolver).suggestedFilename(project))
        }
        try {
            startActivityForResult(intent, if (folder) SAVE_PROJECT_FOLDER_REQUEST else EXPORT_PROJECT_ARCHIVE_REQUEST)
        } catch (error: Exception) {
            pendingProjectArchive = null
            setExportFlowActive(false)
            statusText.text = getString(R.string.project_copy_failed_status, error.message.orEmpty())
        }
    }

    private fun writeProjectFolder(pending: PendingProjectArchive, tree: Uri) {
        val task = ExportTask(ExportTask.Kind.FOLDER)
        exportTask = task
        val app = applicationContext
        task.startWithPublication(app.cacheDir, {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            require(!isInsideSourcePackage(parent, pending.project)) { "Choose a folder outside the source project." }
        }, { error("Folder publication owns its output streams.") }, { false }, { archive, cancelled ->
            try {
                val saved = ProjectFolderSave(app).publish(archive, tree, pending.project, cancelled)
                task.publishedFolder = saved
                task.publishedFolderName = runCatching {
                    app.contentResolver.query(saved, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        android.os.Bundle(), null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: "Project folder"
            }
            catch (error: ProjectFolderPublisher.PublicationFailure) {
                task.publicationCleanupFailed = error.cleanupFailed
                if (error.cause is java.util.concurrent.CancellationException) throw requireNotNull(error.cause)
                throw error
            }
        }, { output, cancelled, _ ->
            CompProjectArchiveWriter(app.contentResolver).write(output, pending.project, pending.editedAssets, cancelled)
        })
        monitorExportTask(task)
    }

    private fun writeProjectArchive(pending: PendingProjectArchive, targetUri: Uri) {
        val resolver = applicationContext.contentResolver
        startExport(ExportTask(ExportTask.Kind.ARCHIVE), targetUri, pending.project) { output, cancelled, _ ->
            CompProjectArchiveWriter(resolver).write(output, pending.project, pending.editedAssets, cancelled)
        }
    }

    private fun setExportFlowActive(active: Boolean) {
        exportFlowActive = active
        updateExportFlowControls()
        if (!active) requestInternalRecovery()
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

    private fun refreshHistoryBudget() {
        if (documentHistory.maxBytes == 0L) {
            documentHistory = com.wonderassembly.compositor.core.DocumentHistory(
                runCatching { AndroidRasterBudget.capture(this).maxEditableDocumentBytes / 12L }.getOrDefault(0L))
        }
    }

    private fun applyDocumentHistory(redo: Boolean) {
        if (exportFlowActive || projectOpenInProgress || canvasPreviewView.hasActiveStroke) return
        val project = currentProject ?: return
        try {
            val apply: (DocumentEdit) -> Unit = { edit ->
                when (edit) {
                    is DocumentEdit.Stroke -> {
                        if (redo) edit.patch.redo() else edit.patch.undo()
                        selectedLayerId = edit.layerId
                    }
                    is DocumentEdit.Metadata -> {
                        val target = if (redo) edit.after else edit.before
                        currentProject = target.copy(additionalCacheDirectories =
                            (target.additionalCacheDirectories + project.additionalCacheDirectories).distinct())
                        selectedLayerId = edit.layerId
                        documentStructureChanged = if (redo) true else edit.previousStructureChanged
                    }
                    is DocumentEdit.Append -> {
                        val target = if (redo) edit.after else edit.before
                        // Retain private files while the history or a background task still uses them.
                        currentProject = target.copy(additionalCacheDirectories =
                            (target.additionalCacheDirectories + project.additionalCacheDirectories).distinct())
                        selectedLayerId = if (redo) target.activeLayerId else edit.previousLayerId
                        documentStructureChanged = if (redo) true else edit.previousStructureChanged
                    }
                }
            }
            val edit = if (redo) documentHistory.redo(apply) else documentHistory.undo(apply)
            if (edit == null) return
            markRecoveryEdited()
            if (exportTask?.result != null) exportTask = null
            if (edit !is DocumentEdit.Stroke || !canvasPreviewView.refreshHistoryLayer(edit.layerId, edit.patch)) {
                previewRequestId.incrementAndGet()
                canvasPreviewView.clear(getString(R.string.choose_project_status))
                showProject(requireNotNull(currentProject))
            }
            reclaimUnusedDocumentResources()
            statusText.text = getString(if (redo) R.string.document_redone_status else R.string.document_undone_status)
            updateCanvasTools()
        } catch (error: Exception) {
            statusText.text = error.message ?: getString(R.string.document_history_failed_status)
        } catch (_: OutOfMemoryError) {
            statusText.text = getString(R.string.document_history_memory_status)
        }
    }

    private fun reclaimUnusedDocumentResources() {
        val project = currentProject ?: return
        if (exportFlowActive || projectOpenInProgress) return
        val edits = documentHistory.retainedEdits()
        val projects = listOf(project) + edits.flatMap {
            when (it) {
                is DocumentEdit.Append -> listOf(it.before, it.after)
                is DocumentEdit.Metadata -> listOf(it.before, it.after)
                is DocumentEdit.Stroke -> emptyList()
            }
        }
        val layerIds = projects.flatMap { it.layers }.map { it.id }.toSet() +
            edits.filterIsInstance<DocumentEdit.Stroke>().map { it.layerId }
        val obsolete = layerEditingSessions.keys.filter { it.substringAfterLast('|') !in layerIds }
        obsolete.forEach { key -> layerEditingSessions.remove(key)?.close() }
        val directories = (project.additionalCacheDirectories + projects.flatMap { it.additionalCacheDirectories }).distinct()
        val assetPaths = projects.flatMap { it.packageAssets }.mapNotNull { asset ->
            if (asset.uri.scheme == "file") asset.uri.path?.let { java.io.File(it).canonicalPath } else null
        }
        val unused = directories.filter { directory ->
            val prefix = directory.canonicalPath + java.io.File.separator
            assetPaths.none { it.startsWith(prefix) }
        }
        if (unused.isNotEmpty()) RecoveryTask.releaseProjectWhenFinished(project.copy(
            archiveCacheDirectory = null, additionalCacheDirectories = unused))
        currentProject = project.copy(additionalCacheDirectories = directories - unused.toSet())
    }

    private fun clearLayerEditingSessions() {
        layerEditingSessions.values.forEach { runCatching { it.close() } }
        layerEditingSessions.clear()
        documentHistory.clear()
        recoveryDocumentId = null
        recoveryRevision = 0L
        recoveredRevision = -1L
        recoveryEpoch = java.util.UUID.randomUUID().toString()
    }

    private fun requestInternalRecovery(force: Boolean = false) {
        val project = currentProject ?: return
        if (project.documentId != recoveryDocumentId || projectOpenInProgress || exportFlowActive ||
            canvasPreviewView.hasActiveStroke || recoveryTask?.result == null && recoveryTask != null) return
        recoveryTask?.let(::consumeRecoveryResult)
        if (!force && recoveredRevision == recoveryRevision) return
        val frozen = linkedMapOf<String, FrozenLayerExport>()
        try {
            val budget = AndroidRasterBudget.capture(this)
            var remaining = budget.maxEditableDocumentBytes / 6L
            for (layer in project.layers) {
                val filename = layer.imageFile ?: continue
                val session = layerEditingSessions[layerEditingSessionKey(selectedProjectUri, project, layer)] ?: continue
                if (!session.hasCommittedChanges || filename in frozen) continue
                val stripBytes = session.raster.width.toLong() * 16 * 8 +
                    com.wonderassembly.compositor.core.ReferencePngWriter.rowEncodingWorkingBytes(session.raster.width)
                require(stripBytes <= budget.maxSingleBitmapBytes) { getString(R.string.recovery_budget_failure) }
                val captured = session.freezeForRecovery(remaining)
                frozen[filename] = captured
                remaining -= captured.retainedBytes
            }
            val task = RecoveryTask(applicationContext, project, recoveryRevision, recoveryEpoch, frozen, RecoveryTask.store(this))
            recoveryTask = task
            recoveryStatusText.text = getString(R.string.recovery_saving_status)
            task.start()
            monitorRecoveryTask(task)
        } catch (error: Exception) {
            frozen.values.forEach { runCatching { it.close() } }
            recoveryStatusText.text = getString(R.string.recovery_failed_status, error.message.orEmpty())
        } catch (_: OutOfMemoryError) {
            frozen.values.forEach { runCatching { it.close() } }
            recoveryStatusText.text = getString(R.string.recovery_failed_status, getString(R.string.recovery_budget_failure))
        }
    }

    private fun markRecoveryEdited() {
        recoveryRevision++
        recoveryTask?.takeIf { it.result != null }?.let(::consumeRecoveryResult)
    }

    private fun monitorRecoveryTask(task: RecoveryTask) {
        if (isDestroyed || recoveryTask !== task) return
        if (task.result == null) recoveryHandler.postDelayed({ monitorRecoveryTask(task) }, 250)
        else consumeRecoveryResult(task)
    }

    private fun consumeRecoveryResult(task: RecoveryTask) {
        val result = task.result ?: return
        if (currentProject?.documentId != task.project.documentId || recoveryEpoch != task.epoch) return
        if (result.saved != null) {
            recoveredRevision = task.revision
            recoveryStatusText.text = getString(R.string.recovery_saved_status, recoveryTime(result.saved.time))
            if (recoveryRevision != task.revision) recoveryStatusText.append(" " + getString(R.string.recovery_newer_edits_status))
            if (result.cleanupFailed) recoveryStatusText.append(" " + getString(R.string.recovery_cleanup_failed_status))
        } else recoveryStatusText.text = getString(R.string.recovery_failed_status, result.failure.orEmpty())
    }

    private fun offerInternalRecovery() {
        val store = RecoveryTask.store(this)
        worker.execute {
            val copies = runCatching {
                if (!RecoveryTask.isRunning) store.cleanupPending()
                store.copies()
            }.getOrDefault(emptyList())
            if (copies.isEmpty()) return@execute
            runOnUiThread {
                fun offer() {
                    if (isFinishing || isDestroyed) return
                    if (projectOpenInProgress) {
                        recoveryHandler.postDelayed({ offer() }, 500)
                        return
                    }
                    AlertDialog.Builder(this).setTitle(R.string.recovery_available_title)
                        .setMessage(getString(R.string.recovery_offer_message, recoveryTime(copies.first().time)))
                        .setNegativeButton(R.string.recovery_later, null)
                        .setPositiveButton(R.string.recovery_copies_button) { _, _ -> showRecoveryCopies() }.show()
                }
                offer()
            }
        }
    }

    private fun showRecoveryCopies() {
        if (projectOpenInProgress || exportFlowActive) return
        val store = RecoveryTask.store(this)
        worker.execute {
            val copies = runCatching { store.copies() }.getOrDefault(emptyList())
            val rows = copies.map { copy ->
                getString(R.string.recovery_copy_row, recoveryTime(copy.time),
                    copy.projectName() ?: getString(R.string.recovery_project_name_fallback), copy.bytes / 1024)
            }.toTypedArray()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val dialog = AlertDialog.Builder(this).setTitle(R.string.recovery_copies_title)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.recovery_save_now) { _, _ -> requestInternalRecovery(force = true) }
                if (copies.isEmpty()) dialog.setMessage(R.string.recovery_no_copies_message)
                else dialog.setItems(rows) { _, index -> chooseRecoveryCopy(copies[index]) }
                dialog.show()
            }
        }
    }

    private fun chooseRecoveryCopy(copy: RecoveryStore.Copy) {
        AlertDialog.Builder(this).setTitle(R.string.recovery_copy_title)
            .setMessage(getString(R.string.recovery_restore_message, recoveryTime(copy.time)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recovery_restore_button) { _, _ ->
                val restore = { loadProjectArchive(Uri.fromFile(copy.file)); Unit }
                if (documentStructureChanged || layerEditingSessions.values.any { it.hasCommittedChanges }) {
                    AlertDialog.Builder(this).setTitle(R.string.open_unsaved_title).setMessage(R.string.open_unsaved_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.open_unsaved_continue) { _, _ -> restore() }.show()
                } else restore()
            }
            .setNeutralButton(R.string.recovery_delete_button) { _, _ ->
                AlertDialog.Builder(this).setTitle(R.string.recovery_delete_button)
                    .setMessage(R.string.recovery_delete_message).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.recovery_delete_button) { _, _ ->
                        worker.execute {
                            val deleted = runCatching { RecoveryTask.store(this).delete(copy) }.getOrDefault(false)
                            runOnUiThread {
                                if (!isDestroyed) recoveryStatusText.text = getString(
                                    if (deleted) R.string.recovery_deleted_status else R.string.recovery_delete_failed_status)
                            }
                        }
                    }.show()
            }.show()
    }

    private fun recoveryTime(time: Long): String = java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(time))

    private fun cleanupOrphanedArchiveImports() {
        if (RecoveryTask.isRunning) return
        cacheDir.listFiles()
            ?.filter { (it.isDirectory && it.name.startsWith(ARCHIVE_CACHE_PREFIX)) ||
                (it.isFile && it.name.startsWith("compositor-export-") && it.extension == "tmp") }
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
        val exportTask: ExportTask?,
        val structureChanged: Boolean,
        val history: com.wonderassembly.compositor.core.DocumentHistory<DocumentEdit>,
        val recoveryTask: RecoveryTask?,
        val recoveryDocumentId: String?,
        val recoveryRevision: Long,
        val recoveredRevision: Long,
        val recoveryEpoch: String
    )

    private data class PendingCanvasExport(
        val project: CompProjectReader.Project,
        val editedAssets: Map<String, LayerEditingSession>,
        val format: CompositeImageExporter.Format,
        val quality: Int
    )

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
        private const val APPEND_IMAGE_REQUEST = 108
        private const val SAVE_PROJECT_FOLDER_REQUEST = 109
        private const val STATE_PROJECT_URI = "projectTreeUri"
        private const val STATE_PROJECT_IS_ARCHIVE = "projectIsArchive"
        private const val STATE_PROJECT_IS_IMAGE = "projectIsImage"
        private const val STATE_LAYER_ID = "selectedLayerId"
        private const val STATE_LAYER_SCROLL_POSITION = "layerScrollPosition"
        private const val EDIT_CHUNK_CACHE_DIVISOR = 6L
        private const val ARCHIVE_CACHE_PREFIX = "compositor-import-"
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
