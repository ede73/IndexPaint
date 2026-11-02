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
import android.graphics.drawable.ColorDrawable
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
    private var yieldButton: Button? = null
    private var drawingToolbar: LinearLayout? = null
    private var connectionStatusItem: MenuItem? = null
    private var sessionModeDialog: AlertDialog? = null
    private var foldFinishDialog: AlertDialog? = null
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
        connectionStatusItem = menu.add(Menu.NONE, MENU_CONNECTION_STATUS, Menu.NONE, "Connection status").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            icon = ConnectionStatusIcon(statusColor(app.connectionStatus))
            contentDescription = app.connectionStatus
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.mnuFoldFinish)?.apply {
            isVisible = app.foldSessionActive && app.isLocalFoldTurn() && !app.foldTurnEnded && !app.foldFinishPending
            isEnabled = isVisible
        }
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
            R.id.mnuFoldFinish -> confirmFoldFinish()
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
        val root = FrameLayout(this)
        root.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = ColorDrawable(0xFFF4F4F4.toInt())
            elevation = dp(4).toFloat()
        }
        drawingToolbar = toolbar
        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }

        undoButton = toolButton("↶", "Undo") { canvas.undo() }.also { addTool(actionRow, it, 34) }
        redoButton = toolButton("↷", "Redo") { canvas.redo() }.also { addTool(actionRow, it, 34) }
        colorButton = toolButton("●", "Choose color") {
            ColorPickerDialog(this, this, draw.getColor()).show()
        }.also {
            it.textSize = 21f
            it.backgroundTintList = ColorStateList.valueOf(0xFFE1E1E1.toInt())
            updateColorButton(draw.getColor())
            addTool(actionRow, it, 40)
        }
        brushButton = toolButton(brushLabel(draw.getBrushStyle()), "Change brush") {
            val styles = BrushStyle.values()
            val next = styles[(styles.indexOf(draw.getBrushStyle()) + 1) % styles.size]
            draw.setBrush(next)
            updateBrushButton(next)
            updateEraserButton(draw.isEraserEnabled())
        }.also { addTool(actionRow, it, 50) }
        embossButton = toolButton("Emboss", "Toggle emboss") {
            draw.toggleEmboss()
            updateEmbossButton(draw.isEmbossEnabled())
        }.also { addTool(actionRow, it, 56) }
        updateEmbossButton(draw.isEmbossEnabled())
        eraserButton = toolButton("Erase", "Toggle eraser") {
            draw.setEraser(!draw.isEraserEnabled())
            updateEraserButton(draw.isEraserEnabled())
        }.also { addTool(actionRow, it, 48) }
        updateEraserButton(draw.isEraserEnabled())
        toolbar.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val widthRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        widthRow.addView(toolButton("Text", "Add or edit movable text") { showTextEditor(canvas) }, LinearLayout.LayoutParams(dp(62), dp(40)))
        widthLabel = TextView(this).apply { text = "Width ${draw.getDrawWidth().toInt()} px" }
        widthRow.addView(widthLabel, LinearLayout.LayoutParams(dp(70), dp(40)))
        widthSlider = SeekBar(this).apply {
            max = MAX_BRUSH_WIDTH - MIN_BRUSH_WIDTH
            progress = draw.getDrawWidth().toInt().coerceIn(MIN_BRUSH_WIDTH, MAX_BRUSH_WIDTH) - MIN_BRUSH_WIDTH
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
        widthRow.addView(widthSlider, LinearLayout.LayoutParams(0, dp(40), 1f))
        toolbar.addView(widthRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val foldControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        foldProgressLabel = TextView(this).apply { text = "Fold 50%"; textSize = 12f }
        foldControls.addView(foldProgressLabel, LinearLayout.LayoutParams(dp(64), dp(40)))
        foldProgressSlider = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    foldProgressLabel?.text = "Fold ${progress.coerceIn(5, 95)}%"
                    if (fromUser) app.setFoldProgressPercent(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        foldControls.addView(foldProgressSlider, LinearLayout.LayoutParams(0, dp(40), 1f))
        yieldButton = toolButton("End turn", "End your drawing turn and add handoff metadata") { app.endTurnOrYield() }
        foldControls.addView(yieldButton, LinearLayout.LayoutParams(dp(72), dp(40)))
        toolbar.addView(foldControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        foldRow = foldControls

        val toolbarParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96), Gravity.BOTTOM)
        root.addView(toolbar, toolbarParams)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            (toolbar.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = bottomInset }
            canvas.setBottomControlsHeight(if (app.foldSessionActive) dp(136) else dp(96))
            insets
        }
        canvas.onHistoryChanged = { undoCount, redoCount ->
            undoButton?.apply { isEnabled = undoCount > 0; contentDescription = "Undo ($undoCount available)" }
            redoButton?.apply { isEnabled = redoCount > 0; contentDescription = "Redo ($redoCount available)" }
            app.publishFoldPreview()
            refreshFoldControls()
        }
        canvas.onHistoryChanged?.invoke(canvas.undoCount(), canvas.redoCount())
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        refreshFoldControls()
    }

    fun onFoldSessionChanged() {
        if (app.foldSessionActive) sessionModeDialog?.dismiss()
        refreshFoldControls()
        invalidateOptionsMenu()
    }

    private fun confirmFoldFinish() {
        AlertDialog.Builder(this)
            .setTitle("Reveal the whole drawing?")
            .setMessage("This asks your partner to reveal every fold and end the Fold game.")
            .setNegativeButton("Keep drawing", null)
            .setPositiveButton("Ask partner") { _, _ -> app.requestFoldFinish() }
            .show()
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
        drawingToolbar?.layoutParams?.let { params ->
            val targetHeight = dp(if (active) 136 else 96)
            if (params.height != targetHeight) {
                params.height = targetHeight
                drawingToolbar?.layoutParams = params
            }
        }
        app.view?.setBottomControlsHeight(dp(if (active) 136 else 96))
        val localTurn = active && app.isLocalFoldTurn() && !app.foldFinishPending
        listOf(undoButton, redoButton, colorButton, brushButton, embossButton, eraserButton).forEach { it?.isEnabled = !active || localTurn }
        widthSlider?.isEnabled = !active || localTurn
        foldProgressSlider?.apply {
            isEnabled = localTurn && !app.foldTurnEnded
            val progress = app.foldProgressPercent()
            if (this.progress != progress) this.progress = progress
        }
        foldProgressLabel?.apply {
            text = "Fold ${app.foldProgressPercent()}%"
            contentDescription = "Fold position ${app.foldProgressPercent()} percent. Drag the fold line or this slider to adjust it"
        }
        yieldButton?.apply {
            val hasMetadata = app.view?.hasPendingHandoffMarks() == true
            isEnabled = localTurn && (!app.foldTurnEnded || hasMetadata)
            text = if (!localTurn) "Wait" else if (app.foldTurnEnded) "Yield" else "End turn"
            contentDescription = when {
                !localTurn -> "Waiting for ${app.peerDisplayName()}"
                !app.foldTurnEnded -> "End your drawing turn to add handoff notes"
                !hasMetadata -> "Draw a note with your finger or add Text before yielding"
                else -> "Yield and send ${app.view?.pendingHandoffMarkCount() ?: 0} notes; they stay out of the final picture"
            }
        }
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

    fun onFoldFinishRequested() {
        if (isFinishing || foldFinishDialog?.isShowing == true) return
        foldFinishDialog = AlertDialog.Builder(this)
            .setTitle("Reveal the whole drawing?")
            .setMessage("The other drawer wants to end the Fold game. Reveal every hidden fold now?")
            .setPositiveButton("Reveal all folds") { _, _ -> app.respondToFoldFinish(true) }
            .setNegativeButton("Keep drawing") { _, _ -> app.respondToFoldFinish(false) }
            .setOnDismissListener { foldFinishDialog = null }
            .show()
    }

    private fun toolButton(label: String, description: String, action: () -> Unit) = Button(this).apply {
        text = label
        contentDescription = description
        isAllCaps = false
        minWidth = 0
        setPadding(dp(2), 0, dp(2), 0)
        textSize = 14f
        setOnClickListener { action() }
    }

    private fun addTool(row: LinearLayout, button: Button, widthDp: Int) {
        row.addView(button, LinearLayout.LayoutParams(dp(widthDp), dp(44)).apply {
            marginStart = dp(1)
            marginEnd = dp(1)
        })
    }

    private fun updateColorButton(color: Int) {
        colorButton?.apply {
            setTextColor(color)
            contentDescription = "Choose color, current color ${String.format("#%08X", color)}"
        }
    }

    private fun updateBrushButton(style: BrushStyle) {
        brushButton?.apply {
            text = brushLabel(style)
            contentDescription = "Brush: ${style.name.lowercase()}"
        }
    }

    private fun updateEraserButton(enabled: Boolean) {
        eraserButton?.apply {
            text = if (enabled) "Erasing" else "Erase"
            isSelected = enabled
            contentDescription = if (enabled) "Eraser on" else "Eraser off"
            backgroundTintList = ColorStateList.valueOf(if (enabled) 0xFFFFD180.toInt() else 0xFFE1E1E1.toInt())
        }
    }

    private fun updateEmbossButton(enabled: Boolean) {
        embossButton?.apply {
            isSelected = enabled
            contentDescription = if (enabled) "Emboss on" else "Emboss off"
            backgroundTintList = ColorStateList.valueOf(if (enabled) 0xFFFFD180.toInt() else 0xFFE1E1E1.toInt())
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
        // Semantic family names stay stable over the wire; each platform maps them to its system face.
        private val TEXT_FONTS = listOf("Sans" to "sans", "Serif" to "serif", "Monospace" to "mono")
    }
}
