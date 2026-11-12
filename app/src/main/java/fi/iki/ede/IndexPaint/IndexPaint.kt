package fi.iki.ede.IndexPaint

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fi.iki.ede.IndexPaint.libraries.BluetoothTransport

class IndexPaint : AppCompatActivity(), ColorPickerDialog.OnColorChangedListener {
    private val app: PaintApp get() = application as PaintApp
    private var colorButton: Button? = null
    private var widthLabel: TextView? = null
    private var brushButton: Button? = null
    private var eraserButton: Button? = null
    private var embossButton: Button? = null
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private var widthSlider: SeekBar? = null
    private var foldRow: LinearLayout? = null
    private var foldProgressLabel: TextView? = null
    private var foldProgressSlider: SeekBar? = null
    private var endTurnButton: Button? = null
    private var foldActionRow: LinearLayout? = null
    private var handoffDoneButton: Button? = null
    private var drawingToolbar: LinearLayout? = null
    private var sideToolbar = false
    private var toolbarBottomInset = 0
    private var connectionStatusItem: MenuItem? = null
    private var sessionModeDialog: AlertDialog? = null
    private var sessionModeStatus: TextView? = null
    private var foldGameButton: Button? = null
    private var freeDrawButton: Button? = null
    private var textEditorColor: Int? = null

    override fun onStart() {
        super.onStart()
        app.setCurrentActivity(this)
        installCanvasUi(checkNotNull(app.view))
        showConnectionStatus(app.connectionStatus)
        app.onMainActivityVisible()
    }

    fun showConnectionStatus(status: String) {
        connectionStatusItem?.apply {
            icon = ConnectionStatusIcon(statusColor(status))
            title = when {
                status.startsWith("Connected") -> "Connected"
                status == "Not connected" -> "Connect"
                else -> "Connecting"
            }
            contentDescription = status
        }
    }

    override fun onStop() {
        app.view?.onHistoryChanged = null
        app.clearActivity(this)
        super.onStop()
    }

    @Deprecated("Legacy activity result API retained for Android device discovery")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PaintApp.REQUEST_INITIATE_CONNECTION, PaintApp.REQUEST_ENABLE_BT,
            PaintApp.REQUEST_DISCOVERABLE, PaintApp.REQUEST_DEVICE_LIST -> app.onConnectionResult(requestCode, resultCode, data)
            SELECT_PICTURE -> if (resultCode == RESULT_OK) data?.data?.let(::drawSelectedImage)
        }
    }

    private fun drawSelectedImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream -> app.view?.drawBitmap(android.graphics.BitmapFactory.decodeStream(stream)) }
        } catch (error: Exception) { Log.e(TAG, "Unable to open selected image", error) }
    }

    override fun colorChanged(color: Int) {
        if (textEditorColor != null) textEditorColor = color
        else {
            app.view?.localDraw?.setColor(color)
            updateColorButton(color)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.optmenu, menu)
        connectionStatusItem = menu.add(Menu.NONE, MENU_CONNECTION_STATUS, Menu.NONE, "Connect").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            icon = ConnectionStatusIcon(statusColor(app.connectionStatus))
            contentDescription = app.connectionStatus
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.mnuToggleFoldHints)?.apply {
            val show = app.view?.handoffHintsVisible != false
            isVisible = app.foldSessionActive
            title = getString(if (show) R.string.mnuHideFoldHints else R.string.mnuShowFoldHints)
            contentDescription = if (show) "Hide handoff notes and peer cursor" else "Show handoff notes and peer cursor"
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val draw = app.view?.localDraw ?: return super.onOptionsItemSelected(item)
        when (item.itemId) {
            MENU_CONNECTION_STATUS -> {
                if (app.connectionStatus == "Not connected") requestBluetoothAndConnect()
                return true
            }
            R.id.mnuMiscClear -> app.view?.clearAndInitialize()
            R.id.mnuMiscOpen -> startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), SELECT_PICTURE)
            R.id.mnuMiscSave -> app.saveBitmap()
            R.id.mnuMiscShareAs -> app.shareBitmap()
            R.id.mnuToggleFoldHints -> {
                app.view?.toggleHandoffHints()
                invalidateOptionsMenu()
            }
            R.id.mnuPaperA4 -> app.changePaperSize(PaperSize.A4)
            R.id.mnuPaperLetter -> app.changePaperSize(PaperSize.LETTER)
            R.id.mnuZoomIn -> app.view?.zoomIn()
            R.id.mnuZoomOut -> app.view?.zoomOut()
            R.id.mnuZoomReset -> app.view?.resetZoom()
            R.id.mnuFilterBlur -> draw.toggleBlur()
            R.id.mnuDisconnect -> app.disconnect()
            R.id.mnuConnect -> requestBluetoothAndConnect()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun requestBluetoothAndConnect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { app.disconnect(); app.connect(); return }
        val missing = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), BLUETOOTH_PERMISSION_REQUEST)
        else { app.disconnect(); app.connect() }
    }

    private fun installCanvasUi(canvas: MyView) {
        val draw = canvas.localDraw
        (canvas.parent as? ViewGroup)?.removeView(canvas)
        foldActionRow = null
        sideToolbar = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP &&
            resources.configuration.screenWidthDp >= TABLET_LANDSCAPE_WIDTH_DP

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (sideToolbar) 18 else 14), dp(12), dp(if (sideToolbar) 18 else 14), dp(12))
            background = toolbarBackground()
            elevation = dp(14).toFloat()
            contentDescription = "Drawing tools"
        }
        drawingToolbar = toolbar
        val toolButtons = mutableListOf<Button>()
        undoButton = toolButton("Undo", "Undo") { canvas.undo() }.also { toolButtons += it }
        redoButton = toolButton("Redo", "Redo") { canvas.redo() }.also { toolButtons += it }
        colorButton = toolButton("●", "Choose color") {
            ColorPickerDialog(this, this, draw.getColor()).show()
        }.also {
            it.text = "Color"
            updateColorButton(draw.getColor())
            toolButtons += it
        }
        brushButton = toolButton(brushLabel(draw.getBrushStyle()), "Change brush") {
            val styles = BrushStyle.values()
            val next = styles[(styles.indexOf(draw.getBrushStyle()) + 1) % styles.size]
            draw.setBrush(next)
            updateBrushButton(next)
            updateEraserButton(draw.isEraserEnabled())
        }.also { toolButtons += it }
        updateBrushButton(draw.getBrushStyle())
        embossButton = toolButton("Emboss", "Toggle emboss") {
            draw.toggleEmboss()
            updateEmbossButton(draw.isEmbossEnabled())
        }.also { toolButtons += it }
        updateEmbossButton(draw.isEmbossEnabled())
        eraserButton = toolButton("Eraser", "Toggle eraser") {
            draw.setEraser(!draw.isEraserEnabled())
            updateEraserButton(draw.isEraserEnabled())
        }.also { toolButtons += it }
        updateEraserButton(draw.isEraserEnabled())
        toolbar.addView(sectionLabel(if (sideToolbar) "TOOLS" else "DRAWING TOOLS"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        val actionRow = LinearLayout(this).apply {
            orientation = if (sideToolbar) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolButtons.forEach { button ->
            if (sideToolbar) {
                actionRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    bottomMargin = dp(6)
                })
            } else {
                actionRow.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                })
            }
        }
        toolbar.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (sideToolbar) ViewGroup.LayoutParams.WRAP_CONTENT else dp(54)))

        val brushSection = LinearLayout(this).apply {
            orientation = if (sideToolbar) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (sideToolbar) setPadding(0, dp(12), 0, dp(8)) else setPadding(0, dp(6), 0, 0)
        }
        if (sideToolbar) brushSection.addView(sectionLabel("BRUSH"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        brushSection.addView(toolButton("Text", "Add or edit movable text") { showTextEditor(canvas) }, LinearLayout.LayoutParams(if (sideToolbar) ViewGroup.LayoutParams.MATCH_PARENT else dp(64), dp(44)).apply {
            if (!sideToolbar) marginEnd = dp(8)
        })
        widthLabel = TextView(this).apply {
            text = "Width ${draw.getDrawWidth().toInt()} px"
            textSize = 13f
            setTextColor(getColor(R.color.paint_text))
            gravity = Gravity.CENTER_VERTICAL
        }
        brushSection.addView(widthLabel, LinearLayout.LayoutParams(if (sideToolbar) ViewGroup.LayoutParams.MATCH_PARENT else dp(82), dp(44)))
        widthSlider = SeekBar(this).apply {
            max = MAX_BRUSH_WIDTH - MIN_BRUSH_WIDTH
            progress = draw.getDrawWidth().toInt().coerceIn(MIN_BRUSH_WIDTH, MAX_BRUSH_WIDTH) - MIN_BRUSH_WIDTH
            progressTintList = ColorStateList.valueOf(getColor(R.color.teal_200))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.teal_200))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.paint_control))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val width = (progress + MIN_BRUSH_WIDTH).toFloat()
                    widthLabel?.text = "Width ${width.toInt()} px"
                    if (fromUser) draw.setDrawWidth(width)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        brushSection.addView(widthSlider, LinearLayout.LayoutParams(if (sideToolbar) ViewGroup.LayoutParams.MATCH_PARENT else 0, dp(44), if (sideToolbar) 0f else 1f))
        toolbar.addView(brushSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (sideToolbar) ViewGroup.LayoutParams.WRAP_CONTENT else dp(54)))

        val compactToolbar = resources.configuration.screenWidthDp < COMPACT_WIDTH_DP &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val foldControls = LinearLayout(this).apply {
            orientation = if (sideToolbar || compactToolbar) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            if (sideToolbar) setPadding(0, dp(12), 0, 0) else setPadding(0, dp(4), 0, 0)
        }
        foldProgressLabel = TextView(this).apply {
            text = "Fold position · 50%"
            textSize = 13f
            setTextColor(getColor(R.color.paint_text))
            gravity = Gravity.CENTER_VERTICAL
        }
        foldControls.addView(foldProgressLabel, LinearLayout.LayoutParams(if (sideToolbar || compactToolbar) ViewGroup.LayoutParams.MATCH_PARENT else dp(102), dp(40)))
        foldProgressSlider = SeekBar(this).apply {
            max = 100
            progress = 50
            progressTintList = ColorStateList.valueOf(getColor(R.color.teal_200))
            thumbTintList = ColorStateList.valueOf(getColor(R.color.teal_200))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.paint_control))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    foldProgressLabel?.text = "Fold ${progress.coerceIn(5, 100)}%"
                    if (fromUser) app.setFoldProgressPercent(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        foldControls.addView(foldProgressSlider, LinearLayout.LayoutParams(if (sideToolbar || compactToolbar) ViewGroup.LayoutParams.MATCH_PARENT else 0, dp(44), if (sideToolbar || compactToolbar) 0f else 1f))
        endTurnButton = toolButton("End Turn", "Choose whether to add a handoff note or finish and reveal the drawing") { showFoldTurnEndChoice() }
        if (sideToolbar || compactToolbar) {
            val foldActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            foldActionRow = foldActions
            foldActions.addView(endTurnButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            foldControls.addView(foldActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        } else {
            foldControls.addView(endTurnButton, LinearLayout.LayoutParams(dp(102), dp(44)).apply { marginStart = dp(4) })
        }
        toolbar.addView(foldControls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (sideToolbar || compactToolbar) ViewGroup.LayoutParams.WRAP_CONTENT else dp(52),
        ))
        foldRow = foldControls

        if (sideToolbar) {
            val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            content.setBackgroundColor(getColor(R.color.paint_workspace))
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(toolbar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            content.addView(scroll, LinearLayout.LayoutParams(dp(TABLET_TOOLBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
            val canvasFrame = FrameLayout(this)
            canvasFrame.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            handoffDoneButton = createHandoffDoneButton()
            canvasFrame.addView(handoffDoneButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(24)
                bottomMargin = dp(24)
            })
            content.addView(canvasFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
                canvas.setBottomControlsHeight(0)
                insets
            }
            setContentView(content)
        } else {
            val root = FrameLayout(this).apply {
                setBackgroundColor(getColor(R.color.paint_workspace))
            }
            root.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            handoffDoneButton = createHandoffDoneButton()
            root.addView(handoffDoneButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(20)
                bottomMargin = dp(24)
            })
            toolbar.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                updateCanvasBottomControlsHeight(view)
                updateHandoffDoneButtonPosition(view)
            }
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                toolbarBottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                (toolbar.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = toolbarBottomInset }
                updateCanvasBottomControlsHeight(toolbar)
                updateHandoffDoneButtonPosition(toolbar)
                insets
            }
            setContentView(root)
        }
        canvas.onHistoryChanged = { undoCount, redoCount ->
            undoButton?.apply { isEnabled = undoCount > 0; contentDescription = "Undo ($undoCount available)" }
            redoButton?.apply { isEnabled = redoCount > 0; contentDescription = "Redo ($redoCount available)" }
            app.publishFoldPreview()
            refreshFoldControls()
        }
        canvas.onHistoryChanged?.invoke(canvas.undoCount(), canvas.redoCount())
        ViewCompat.requestApplyInsets(canvas.parent as View)
        refreshFoldControls()
    }

    private fun sectionLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(getColor(R.color.paint_muted))
        gravity = Gravity.CENTER_VERTICAL
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun updateCanvasBottomControlsHeight(toolbar: View) {
        if (sideToolbar) app.view?.setBottomControlsHeight(0)
        else app.view?.setBottomControlsHeight(toolbar.height)
    }

    fun onFoldSessionChanged() {
        if (app.foldSessionActive) sessionModeDialog?.dismiss()
        refreshFoldControls()
        invalidateOptionsMenu()
    }

    private fun showFoldTurnEndChoice() {
        val peer = app.peerDisplayName().takeUnless { it.isBlank() || it.equals("peer", ignoreCase = true) } ?: "your partner"
        val canPass = app.foldProgressPercent() < 100
        val choices = if (canPass) {
            arrayOf("Add a note for $peer, then pass the turn", "Finish the drawing and reveal it to both of you")
        } else {
            arrayOf("Finish the drawing and reveal it to both of you")
        }
        AlertDialog.Builder(this)
            .setTitle("End your turn")
            .setMessage(if (canPass) "What would you like to do next?" else "This is the final fold. Reveal the finished drawing to both of you.")
            .setItems(choices) { _, which ->
                if (canPass && which == 0) app.endTurnOrYield()
                else app.requestFoldFinish()
            }
            .setNegativeButton("Keep drawing", null)
            .show()
    }

    private fun createHandoffDoneButton() = toolButton("Done", "Send your handoff note and pass the turn") {
        app.endTurnOrYield()
    }.apply {
        textSize = 14f
        minWidth = dp(112)
        setPadding(dp(20), 0, dp(20), 0)
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.purple_500))
        elevation = dp(8).toFloat()
    }

    private fun updateHandoffDoneButtonPosition(toolbar: View) {
        if (sideToolbar) return
        val params = handoffDoneButton?.layoutParams as? FrameLayout.LayoutParams ?: return
        val bottomMargin = toolbar.height + toolbarBottomInset + dp(16)
        if (params.bottomMargin != bottomMargin) {
            params.bottomMargin = bottomMargin
            handoffDoneButton?.layoutParams = params
        }
    }

    private fun showTextEditor(canvas: MyView) {
        val existing = canvas.selectedTextObject()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val textInput = EditText(this).apply {
            hint = "Type text"
            setSingleLine(true)
            setText(existing?.text.orEmpty())
            selectAll()
        }
        content.addView(textInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        content.addView(TextView(this).apply { text = "Text size" })
        val sizeSlider = SeekBar(this).apply {
            max = TEXT_SIZE_MAX - TEXT_SIZE_MIN
            progress = ((existing?.size ?: DEFAULT_TEXT_SIZE) - TEXT_SIZE_MIN).toInt().coerceIn(0, max)
        }
        content.addView(sizeSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        val fontPicker = Spinner(this).apply {
            adapter = ArrayAdapter(this@IndexPaint, android.R.layout.simple_spinner_dropdown_item, TEXT_FONTS.map { it.first })
            setSelection(TEXT_FONTS.indexOfFirst { it.second == existing?.font }.takeIf { it >= 0 } ?: 0)
        }
        content.addView(fontPicker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val colorChoice = toolButton("●  Text color", "Choose text color") {}.apply {
            setTextColor(existing?.color ?: canvas.localDraw.getColor())
            setOnClickListener {
                val start = textEditorColor ?: existing?.color ?: canvas.localDraw.getColor()
                textEditorColor = start
                ColorPickerDialog(this@IndexPaint, this@IndexPaint, start).show()
            }
        }
        content.addView(colorChoice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add text" else "Edit text")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .create()
        dialog.setOnDismissListener { textEditorColor = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = textInput.text.toString().trim()
            if (value.isEmpty()) {
                textInput.error = "Enter text for the object"
                return@setOnClickListener
            }
            val size = (sizeSlider.progress + TEXT_SIZE_MIN).toFloat()
            val font = TEXT_FONTS[fontPicker.selectedItemPosition].second
            val color = textEditorColor ?: existing?.color ?: canvas.localDraw.getColor()
            if (existing == null) canvas.addTextObject(value, size, color, font)
            else canvas.updateSelectedText(value, size, color, font)
            textEditorColor = null
            dialog.dismiss()
        }
    }

    private fun refreshFoldControls() {
        val active = app.foldSessionActive
        foldRow?.visibility = if (active) View.VISIBLE else View.GONE
        drawingToolbar?.requestLayout()
        drawingToolbar?.post { drawingToolbar?.let(::updateCanvasBottomControlsHeight) }
        val localTurn = active && app.isLocalFoldTurn() && !app.foldFinishPending && !app.foldYieldPending
        listOf(undoButton, redoButton, colorButton, brushButton, embossButton, eraserButton).forEach { it?.isEnabled = !active || localTurn }
        widthSlider?.isEnabled = !active || localTurn
        foldProgressSlider?.apply {
            isEnabled = localTurn && !app.foldTurnEnded
            val progress = app.foldProgressPercent()
            if (this.progress != progress) this.progress = progress
        }
        foldProgressLabel?.apply {
            text = "Fold position · ${app.foldProgressPercent()}%"
            contentDescription = "Fold position ${app.foldProgressPercent()} percent. Drag the fold line or this slider to adjust it"
        }
        endTurnButton?.apply {
            visibility = if (app.foldTurnEnded) View.GONE else View.VISIBLE
            isEnabled = localTurn
            text = if (!localTurn) "Wait" else "End Turn"
            contentDescription = when {
                app.foldYieldPending -> "Sending the drawing and handoff notes; waiting for your partner to confirm receipt"
                !localTurn -> "Waiting for ${app.peerDisplayName()}"
                else -> "Choose whether to add a handoff note or finish and reveal the drawing"
            }
        }
        handoffDoneButton?.apply {
            visibility = if (active && app.foldTurnEnded && app.isLocalFoldTurn()) View.VISIBLE else View.GONE
            isEnabled = localTurn
            text = if (app.foldYieldPending) "Sending…" else "Done"
            contentDescription = if (app.foldYieldPending) "Sending the drawing and handoff notes; waiting for your partner to confirm receipt"
                else "Send ${app.view?.pendingHandoffMarkCount() ?: 0} handoff notes and pass the turn to ${app.peerDisplayName()}"
        }
        foldActionRow?.visibility = if (app.foldTurnEnded) View.GONE else View.VISIBLE
    }

    fun showSessionModeChoice(peerName: String, localChoice: SessionMode?, peerChoice: SessionMode?) {
        if (isFinishing || app.foldSessionActive || app.sessionMode != null) return
        if (sessionModeDialog?.isShowing == true) {
            updateSessionModeChoices(peerName, localChoice, peerChoice)
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        content.addView(TextView(this).apply {
            text = "Choose the same mode as the other drawer. Drawing starts after you both agree."
        })
        sessionModeStatus = TextView(this).apply { setPadding(0, dp(12), 0, dp(4)) }
        content.addView(sessionModeStatus)

        sessionModeDialog = AlertDialog.Builder(this)
            .setTitle("Choose a shared mode")
            .setView(content)
            .setPositiveButton("Fold game", null)
            .setNegativeButton("Free draw", null)
            .create()
        sessionModeDialog?.setCancelable(false)
        sessionModeDialog?.setOnDismissListener {
            sessionModeDialog = null
            sessionModeStatus = null
            foldGameButton = null
            freeDrawButton = null
        }
        sessionModeDialog?.show()
        foldGameButton = sessionModeDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
        freeDrawButton = sessionModeDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)
        foldGameButton?.setOnClickListener { app.chooseSessionMode(SessionMode.FOLD_GAME) }
        freeDrawButton?.setOnClickListener { app.chooseSessionMode(SessionMode.FREE_DRAW) }
        updateSessionModeChoices(peerName, localChoice, peerChoice)
    }

    fun updateSessionModeChoices(peerName: String, localChoice: SessionMode?, peerChoice: SessionMode?) {
        val status = sessionModeStatus ?: return
        val peer = peerName.ifBlank { "Peer" }
        status.text = when {
            localChoice == null && peerChoice == null -> "Choose Free draw or Fold game."
            localChoice != null && peerChoice == null -> "You chose ${localChoice.displayName()}. Waiting for $peer."
            localChoice == null && peerChoice != null -> "$peer chose ${peerChoice.displayName()}. Choose the same mode to continue."
            localChoice == peerChoice -> "Both chose ${localChoice?.displayName()}. Starting…"
            else -> "Choices differ: you chose ${localChoice?.displayName()}, $peer chose ${peerChoice?.displayName()}. Change yours to match."
        }
    }

    fun onSessionModeAgreed(mode: SessionMode) {
        sessionModeDialog?.dismiss()
        sessionModeDialog = null
        if (mode == SessionMode.FREE_DRAW) app.view?.localDraw?.sendCurrentState()
        app.view?.invalidate()
    }

    fun onSessionDisconnected() {
        sessionModeDialog?.dismiss()
        sessionModeDialog = null
        app.view?.invalidate()
    }

    private fun toolButton(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        minWidth = 0
        minHeight = dp(48)
        setPadding(dp(5), 0, dp(5), 0)
        textSize = 12f
        setTextColor(getColor(R.color.paint_text))
        background = controlBackground()
        foreground = RippleDrawable(
            ColorStateList.valueOf(0x28FFFFFF),
            null,
            controlBackground(),
        )
        elevation = dp(2).toFloat()
        backgroundTintList = ColorStateList.valueOf(getColor(R.color.paint_control))
        setOnClickListener { action() }
    }

    private fun toolbarBackground(): Drawable {
        val colors = intArrayOf(getColor(R.color.paint_surface), getColor(R.color.purple_700))
        val radii = if (sideToolbar) {
            floatArrayOf(0f, 0f, dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f)
        } else {
            floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadii = radii
            setStroke(dp(1), 0x263DCDBE)
        }
    }

    private fun controlBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(15).toFloat()
        setColor(getColor(R.color.paint_control))
        setStroke(dp(1), 0x354F5976)
    }

    private fun updateColorButton(color: Int) {
        colorButton?.apply {
            val brightness = Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114
            setTextColor(if (brightness > 150) 0xFF20333D.toInt() else Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(color)
            elevation = dp(4).toFloat()
            contentDescription = "Choose color, current color ${String.format("#%08X", color)}"
        }
    }

    private fun updateBrushButton(style: BrushStyle) {
        brushButton?.apply {
            text = brushLabel(style)
            isSelected = true
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.paint_control_selected))
            setTextColor(getColor(R.color.white))
            contentDescription = "Brush: ${style.name.lowercase()}"
        }
    }

    private fun updateEraserButton(enabled: Boolean) {
        eraserButton?.apply {
            text = if (enabled) "Erasing" else "Eraser"
            isSelected = enabled
            contentDescription = if (enabled) "Eraser on" else "Eraser off"
            backgroundTintList = ColorStateList.valueOf(getColor(if (enabled) R.color.paint_control_warm else R.color.paint_control))
            setTextColor(getColor(if (enabled) R.color.purple_700 else R.color.paint_text))
        }
    }

    private fun updateEmbossButton(enabled: Boolean) {
        embossButton?.apply {
            isSelected = enabled
            text = "Emboss"
            contentDescription = if (enabled) "Emboss on" else "Emboss off"
            backgroundTintList = ColorStateList.valueOf(getColor(if (enabled) R.color.paint_control_warm else R.color.paint_control))
            setTextColor(getColor(if (enabled) R.color.purple_700 else R.color.paint_text))
        }
    }

    private fun brushLabel(style: BrushStyle): String = when (style) {
        BrushStyle.PEN -> "Pen"
        BrushStyle.MARKER -> "Marker"
        BrushStyle.PENCIL -> "Pencil"
    }

    private fun SessionMode.displayName(): String = when (this) {
        SessionMode.FREE_DRAW -> "Free draw"
        SessionMode.FOLD_GAME -> "Fold game"
    }

    private fun statusColor(status: String): Int = when {
        status.startsWith("Connected") -> 0xFF38C172.toInt()
        status == "Not connected" -> 0xFFFF5B5B.toInt()
        else -> 0xFFFFC247.toInt()
    }

    private inner class ConnectionStatusIcon(private val color: Int) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = this@ConnectionStatusIcon.color }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = Color.WHITE
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = dp(6f)
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, ring)
        }

        override fun setAlpha(alpha: Int) { fill.alpha = alpha; ring.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { fill.colorFilter = colorFilter; ring.colorFilter = colorFilter }
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = dp(24f).toInt()
        override fun getIntrinsicHeight(): Int = dp(24f).toInt()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            app.disconnect(); app.connect()
        }
    }

    companion object {
        private const val TAG = "IndexPaint"
        private const val SELECT_PICTURE = 55
        private const val BLUETOOTH_PERMISSION_REQUEST = 666
        private const val MENU_CONNECTION_STATUS = 0x1A71
        private const val MIN_BRUSH_WIDTH = 2
        private const val MAX_BRUSH_WIDTH = 40
        private const val TEXT_SIZE_MIN = 12
        private const val TEXT_SIZE_MAX = 180
        private const val DEFAULT_TEXT_SIZE = 44f
        private const val TABLET_LANDSCAPE_WIDTH_DP = 840
        private const val TABLET_MIN_WIDTH_DP = 600
        private const val TABLET_TOOLBAR_WIDTH_DP = 248
        private const val COMPACT_WIDTH_DP = 600
        // Semantic family names stay stable over the wire; each platform maps them to its system face.
        private val TEXT_FONTS = listOf("Sans" to "sans", "Serif" to "serif", "Monospace" to "mono")
    }
}
