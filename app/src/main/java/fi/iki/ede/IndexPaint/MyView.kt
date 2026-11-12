package fi.iki.ede.IndexPaint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.EmbossMaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

/** Displays a paper-sized document in a device-sized, zoomable viewport. */
class MyView(private val app: PaintApp) : View(app) {
    val localDraw = DrawDetails(remote = false, myView = this, paintApp = app)
    val remoteDraw = DrawDetails(remote = true, myView = this, paintApp = app)

    private val backgroundPaint = Paint().apply { color = context.getColor(R.color.paint_workspace) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private data class Stroke(val path: Path, val paint: Paint, val handoff: Boolean, val eraser: Boolean)
    data class TextObject(
        val id: String, var text: String, var x: Float, var y: Float,
        var size: Float, var color: Int, var font: String, val handoff: Boolean,
    )
    private val textObjects = mutableListOf<TextObject>()
    private var selectedTextId: String? = null
    private var movingTextId: String? = null
    private var textMoveOffsetX = 0f
    private var textMoveOffsetY = 0f
    private var baseBitmap: Bitmap = app.localBitmap.copy(Bitmap.Config.ARGB_8888, true)
    private var handoffBaseBitmap: Bitmap = Bitmap.createBitmap(app.localBitmap.width, app.localBitmap.height, Bitmap.Config.ARGB_8888)
    private var pendingHandoffBitmap: Bitmap = Bitmap.createBitmap(app.localBitmap.width, app.localBitmap.height, Bitmap.Config.ARGB_8888)
    private var lastHandoffBitmap: Bitmap? = null
    private var baseHandoffMarkCount = 0
    private val undoStack = ArrayDeque<Stroke>()
    private val redoStack = ArrayDeque<Stroke>()
    var onHistoryChanged: ((undoCount: Int, redoCount: Int) -> Unit)? = null
    private var systemTopInset = 0
    private var systemBottomInset = 0
    private var bottomControlsHeight = 0
    private var actionBarHeight = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var drawingStroke = false
    private var drawingHandoffStroke = false
    private var adjustingFoldBoundary = false
    var handoffHintsVisible: Boolean = app.getSharedPreferences(HINTS_PREFS, android.content.Context.MODE_PRIVATE)
        .getBoolean(HANDOFF_HINTS_VISIBLE, true)
        private set
    private var previousGestureX = 0f
    private var previousGestureY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = fitScale() * zoom
            if (oldScale <= 0f) return false
            val focusX = detector.focusX
            val focusY = detector.focusY
            val centerX = width / 2f
            val centerY = contentTop() + contentHeight() / 2f
            val docX = (focusX - centerX - panX) / oldScale + app.localBitmap.width / 2f
            val docY = (focusY - centerY - panY) / oldScale + app.localBitmap.height / 2f
            zoom = (zoom * detector.scaleFactor).coerceIn(DrawingDocument.MIN_ZOOM, DrawingDocument.MAX_ZOOM)
            val newScale = fitScale() * zoom
            panX = focusX - centerX - (docX - app.localBitmap.width / 2f) * newScale
            panY = focusY - centerY - (docY - app.localBitmap.height / 2f) * newScale
            clampPan()
            invalidate()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemTopInset = bars.top
            systemBottomInset = bars.bottom
            actionBarHeight = app.actionBarHeightPx
            invalidate()
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        actionBarHeight = app.actionBarHeightPx
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val contentTop = contentTop()
        val contentBottom = contentTop + contentHeight()
        canvas.drawRect(0f, contentTop, width.toFloat(), contentBottom, backgroundPaint)
        canvas.save()
        canvas.clipRect(0f, contentTop, width.toFloat(), contentBottom)
        val page = pageRect()
        canvas.drawRect(page, pageShadowPaint)
        if (app.foldSessionActive) {
            drawFoldSession(canvas, page)
        } else {
            canvas.drawBitmap(app.localBitmap, null, page, bitmapPaint)
            val scale = fitScale() * zoom
            canvas.save()
            canvas.translate(page.left, page.top)
            canvas.scale(scale, scale)
            localDraw.onDraw(canvas)
            remoteDraw.onDraw(canvas)
            canvas.restore()
            drawTextObjects(canvas, page, textObjects.filterNot { it.handoff })
        }
        canvas.restore()
    }

    private fun drawFoldSession(canvas: Canvas, page: RectF) {
        val density = resources.displayMetrics.density
        hintTextPaint.textSize = 14f * density
        canvas.drawRect(page, bitmapPaperPaint)
        val localTurn = app.isLocalFoldTurn()
        val metadataPhase = app.isHandoffMetadataPhase()
        val handoffY = page.top + page.height() * app.foldPreviousBoundary
        val foldY = page.top + page.height() * app.foldBoundary
        val spill = page.height() * FOLD_REVEAL_FRACTION

        if (localTurn && app.foldIndex > 0) {
            // The next drawer gets a thin preview across the previous crease.
            app.foldLayers[app.foldIndex - 1]?.let { layer ->
                canvas.save()
                canvas.clipRect(page.left, handoffY - spill / 2f, page.right, handoffY + spill / 2f)
                canvas.drawBitmap(layer, null, page, bitmapPaint)
                canvas.restore()
            }
            canvas.drawLine(page.left, handoffY, page.right, handoffY, foldHandoffPaint)
        } else if (!localTurn && app.foldIndex > 0) {
            // The drawer who ended the turn sees the part they drew and its spill.
            app.foldLayers[app.foldIndex - 1]?.let { layer ->
                val start = page.top + page.height() * app.lastFoldStartBoundary
                val end = page.top + page.height() * app.lastFoldEndBoundary
                canvas.save()
                canvas.clipRect(page.left, start, page.right, min(page.bottom, end + spill))
                canvas.drawBitmap(layer, null, page, bitmapPaint)
                canvas.restore()
            }
            val committedY = page.top + page.height() * app.lastFoldEndBoundary
            drawFoldCrease(canvas, page, committedY)
        }

        if (app.isLocalFoldTurn()) {
            val top = page.top + page.height() * app.foldPreviousBoundary
            val visibleBottom = foldY + if (metadataPhase) spill else 0f
            val pictureLayer = if (app.foldTurnEnded) app.foldLayers[app.foldIndex] ?: app.localBitmap else app.localBitmap
            canvas.save()
            canvas.clipRect(page.left, top, page.right, visibleBottom)
            canvas.drawBitmap(pictureLayer, null, page, bitmapPaint)
            if (!metadataPhase) {
                val scale = fitScale() * zoom
                canvas.save()
                canvas.translate(page.left, page.top)
                canvas.scale(scale, scale)
                localDraw.onDraw(canvas)
                canvas.restore()
                drawTextObjects(canvas, page, textObjects.filterNot { it.handoff })
            }
            canvas.restore()
        }

        if (localTurn || app.foldIndex == 0) {
            val flapY = if (metadataPhase) foldY + spill else foldY
            canvas.drawRect(page.left, flapY, page.right, page.bottom, foldFlapPaint)
            drawFoldCrease(canvas, page, foldY, adjustable = localTurn && !metadataPhase)
        } else {
            val flapY = page.top + page.height() * app.lastFoldEndBoundary + spill
            canvas.drawRect(page.left, flapY, page.right, page.bottom, foldFlapPaint)
        }
        if (!localTurn && handoffHintsVisible) drawPeerFoldHints(canvas, page)
        if (handoffHintsVisible) lastHandoffBitmap?.let { canvas.drawBitmap(it, null, page, bitmapPaint) }
        canvas.drawBitmap(pendingHandoffBitmap, null, page, bitmapPaint)
        drawTextObjects(canvas, page, textObjects.filter { it.handoff })
        if (metadataPhase) {
            val scale = fitScale() * zoom
            canvas.save()
            canvas.translate(page.left, page.top)
            canvas.scale(scale, scale)
            localDraw.onDraw(canvas)
            canvas.restore()
        }
        drawFoldTurnStatus(canvas, page)
    }

    private fun drawFoldCrease(canvas: Canvas, page: RectF, foldY: Float, adjustable: Boolean = false) {
        val creaseDepth = dp(18f)
        foldCreasePaint.shader = android.graphics.LinearGradient(
            0f, foldY - dp(5f), 0f, foldY + creaseDepth,
            intArrayOf(0x00000000, 0x66000000, 0x22000000, 0x00000000),
            floatArrayOf(0f, 0.2f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(page.left, foldY - dp(5f), page.right, foldY + creaseDepth, foldCreasePaint)
        foldCreasePaint.shader = null
        canvas.drawLine(page.left, foldY, page.right, foldY, foldEdgePaint)
        if (adjustable) {
            val centerX = page.centerX()
            canvas.drawRoundRect(centerX - dp(39f), foldY - dp(15f), centerX + dp(39f), foldY + dp(15f), dp(15f), dp(15f), foldHandlePaint)
            canvas.drawRoundRect(centerX - dp(22f), foldY - dp(5f), centerX - dp(16f), foldY + dp(5f), dp(3f), dp(3f), foldHandleGripPaint)
            canvas.drawRoundRect(centerX - dp(3f), foldY - dp(5f), centerX + dp(3f), foldY + dp(5f), dp(3f), dp(3f), foldHandleGripPaint)
            canvas.drawRoundRect(centerX + dp(16f), foldY - dp(5f), centerX + dp(22f), foldY + dp(5f), dp(3f), dp(3f), foldHandleGripPaint)
        }
        // A turned corner makes the paper fold readable even at a glance.
        foldCornerPath.reset()
        foldCornerPath.moveTo(page.right - dp(30f), foldY)
        foldCornerPath.lineTo(page.right, foldY)
        foldCornerPath.lineTo(page.right, foldY + dp(30f))
        foldCornerPath.close()
        canvas.drawPath(foldCornerPath, foldCornerPaint)
    }

    private fun drawPeerFoldHints(canvas: Canvas, page: RectF) {
        val preview = app.peerFoldPreview ?: return
        val density = resources.displayMetrics.density
        val left = page.left + 10f * density
        val top = page.top + 72f * density
        val cardWidth = min(page.width() - 20f * density, 250f * density)
        val cardHeight = 52f * density
        canvas.drawRoundRect(left, top, left + cardWidth, top + cardHeight, 10f * density, 10f * density, hintCardPaint)
        hintColorPaint.color = preview.color
        canvas.drawCircle(left + 18f * density, top + 18f * density, 7f * density, hintColorPaint)
        val tool = if (preview.erasing) "Eraser" else preview.brush.name.lowercase().replaceFirstChar(Char::uppercase)
        val firstLine = "$tool · ${preview.width.toInt()} px${if (preview.erasing) "" else " · Undo ${preview.undoCount} / Redo ${preview.redoCount}"}"
        canvas.drawText(firstLine, left + 32f * density, top + 22f * density, hintTextPaint)
        canvas.drawText("Fold progress ${((preview.boundary * 100).toInt())}%", left + 12f * density, top + 43f * density, hintTextPaint)

        val cursorX = page.left + page.width() * preview.x
        val cursorY = page.top + page.height() * preview.y
        hintColorPaint.style = Paint.Style.STROKE
        hintColorPaint.strokeWidth = 2.5f * density
        canvas.drawCircle(cursorX, cursorY, 12f * density, hintColorPaint)
        hintColorPaint.style = Paint.Style.FILL
        canvas.drawCircle(cursorX, cursorY, if (preview.drawing) 4f * density else 2.5f * density, hintColorPaint)
    }

    private fun drawFoldTurnStatus(canvas: Canvas, page: RectF) {
        val density = resources.displayMetrics.density
        val localTurn = app.isLocalFoldTurn()
        val left = page.left + 10f * density
        val top = page.top + 10f * density
        val width = min(page.width() - 20f * density, 300f * density)
        val height = 54f * density
        if (width <= 0f) return

        turnStatusBackgroundPaint.color = if (localTurn) 0xE600695C.toInt() else 0xE6334551.toInt()
        canvas.drawRoundRect(left, top, left + width, top + height, 10f * density, 10f * density, turnStatusBackgroundPaint)
        turnStatusTextPaint.textSize = 16f * density
        turnStatusSubtitlePaint.textSize = 12f * density
        val title = when {
            !localTurn -> "WAITING FOR PEER"
            app.foldTurnEnded -> "ADD A HANDOFF NOTE"
            else -> "YOUR TURN"
        }
        val subtitle = when {
            !localTurn -> "Turn ${app.foldIndex + 1} · ${app.peerDisplayName()} is drawing"
            app.foldTurnEnded -> "Draw or tap Text · Done passes the turn"
            else -> "Turn ${app.foldIndex + 1} · End Turn to choose what happens next"
        }
        canvas.drawText(title, left + 12f * density, top + 23f * density, turnStatusTextPaint)
        canvas.drawText(subtitle, left + 12f * density, top + 43f * density, turnStatusSubtitlePaint)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    fun beginFoldSession() {
        textObjects.clear()
        selectedTextId = null
        clearHandoffAnnotations()
        resetFoldLayer()
        invalidate()
    }

    fun beginNextFold() {
        textObjects.removeAll { it.handoff || !it.handoff }
        selectedTextId = null
        resetFoldLayer()
        invalidate()
    }

    fun beginHandoffMetadata() {
        baseBitmap.recycle()
        baseBitmap = app.localBitmap.copy(Bitmap.Config.ARGB_8888, true)
        handoffBaseBitmap.eraseColor(Color.TRANSPARENT)
        pendingHandoffBitmap.eraseColor(Color.TRANSPARENT)
        baseHandoffMarkCount = 0
        undoStack.clear()
        redoStack.clear()
        localDraw.resetCurrentPath()
        notifyHistoryChanged()
        invalidate()
    }

    fun hasPendingHandoffMarks(): Boolean = baseHandoffMarkCount > 0 ||
        undoStack.any { it.handoff && !it.eraser } || textObjects.any { it.handoff }

    fun pendingHandoffMarkCount(): Int = baseHandoffMarkCount + undoStack.count { it.handoff && !it.eraser } + textObjects.count { it.handoff }

    fun hasLastHandoffMetadata(): Boolean = lastHandoffBitmap?.isRecycled == false

    fun toggleHandoffHints() {
        handoffHintsVisible = !handoffHintsVisible
        app.getSharedPreferences(HINTS_PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putBoolean(HANDOFF_HINTS_VISIBLE, handoffHintsVisible)
            .apply()
        invalidate()
    }

    fun copyPendingHandoffBitmap(): Bitmap = pendingHandoffBitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
        drawTextItems(Canvas(bitmap), textObjects.filter { it.handoff }, selected = false)
    }

    fun snapshotDrawingLayer(): Bitmap = app.localBitmap.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
        drawTextItems(Canvas(bitmap), textObjects.filterNot { it.handoff }, selected = false)
    }

    fun addTextObject(text: String, size: Float, color: Int, font: String, metadata: Boolean = app.isHandoffMetadataPhase()): TextObject {
        val drawingStart = app.foldPreviousBoundary * app.localBitmap.height
        val drawingEnd = app.foldBoundary * app.localBitmap.height
        val defaultY = if (app.foldSessionActive && !metadata) {
            ((drawingStart + drawingEnd) / 2f).coerceAtLeast(size)
        } else app.localBitmap.height * 0.32f
        val item = TextObject(java.util.UUID.randomUUID().toString(), text, app.localBitmap.width * 0.18f,
            defaultY, size, color, font, metadata)
        textObjects += item
        selectedTextId = item.id
        invalidate()
        if (!metadata && !app.foldSessionActive) sendTextObject(item)
        notifyHistoryChanged()
        return item
    }

    fun selectedTextObject(): TextObject? = textObjects.firstOrNull { it.id == selectedTextId }

    fun updateSelectedText(text: String, size: Float, color: Int, font: String) {
        val item = selectedTextObject() ?: return
        item.text = text; item.size = size; item.color = color; item.font = font
        invalidate()
        if (!item.handoff && !app.foldSessionActive) sendTextObject(item)
        notifyHistoryChanged()
    }

    fun applyRemoteText(message: DrawingMessage.TextUpdate) {
        textObjects.removeAll { it.id == message.id }
        if (!message.deleted) {
            val text = runCatching { String(android.util.Base64.decode(message.text64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP), Charsets.UTF_8) }.getOrDefault("")
            if (text.isNotEmpty()) textObjects += TextObject(message.id, text, message.x, message.y, message.size, message.color, message.font, false)
        }
        invalidate()
    }

    private fun sendTextObject(item: TextObject) {
        val encoded = android.util.Base64.encodeToString(item.text.toByteArray(Charsets.UTF_8), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        app.sendMessage(DrawingMessage.TextUpdate(item.id, encoded, item.x, item.y, item.size, item.color, item.font, false))
    }

    private fun drawTextObjects(canvas: Canvas, page: RectF, objects: List<TextObject>) {
        if (objects.isEmpty()) return
        val scale = fitScale() * zoom
        canvas.save()
        canvas.translate(page.left, page.top)
        canvas.scale(scale, scale)
        drawTextItems(canvas, objects, selected = true, displayScale = scale)
        canvas.restore()
    }

    private fun drawTextItems(canvas: Canvas, objects: List<TextObject>, selected: Boolean, displayScale: Float = 1f) {
        objects.forEach { item ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = item.color; textSize = item.size; style = Paint.Style.FILL
                typeface = familyTypeface(item.font)
            }
            canvas.drawText(item.text, item.x, item.y, paint)
            if (selected && selectedTextId == item.id) {
                val bounds = android.graphics.Rect()
                paint.getTextBounds(item.text, 0, item.text.length, bounds)
                selectionPaint.strokeWidth = max(1f, 1.5f / displayScale.coerceAtLeast(0.01f))
                canvas.drawRect(item.x - 4f, item.y - bounds.height() - 4f, item.x + paint.measureText(item.text) + 4f, item.y + 4f, selectionPaint)
            }
        }
    }

    private fun hitText(x: Float, y: Float): TextObject? = textObjects.asReversed().firstOrNull { item ->
        if (item.handoff != app.isHandoffMetadataPhase()) return@firstOrNull false
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = item.size; typeface = familyTypeface(item.font) }
        val bounds = android.graphics.Rect(); paint.getTextBounds(item.text, 0, item.text.length, bounds)
        x in (item.x - 8f)..(item.x + paint.measureText(item.text) + 8f) && y in (item.y - bounds.height() - 8f)..(item.y + 8f)
    }

    private fun familyTypeface(family: String): Typeface = when (family) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }

    fun setLastHandoffAnnotation(bitmap: Bitmap) {
        lastHandoffBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastHandoffBitmap = bitmap
        invalidate()
    }

    fun clearHandoffAnnotations() {
        lastHandoffBitmap?.takeIf { !it.isRecycled }?.recycle()
        lastHandoffBitmap = null
        pendingHandoffBitmap.eraseColor(Color.TRANSPARENT)
        handoffBaseBitmap.eraseColor(Color.TRANSPARENT)
        baseHandoffMarkCount = 0
        invalidate()
    }

    fun clearFoldTextObjects() {
        textObjects.clear()
        selectedTextId = null
        invalidate()
    }

    private fun resetFoldLayer() {
        app.localBitmap.eraseColor(Color.TRANSPARENT)
        baseBitmap.eraseColor(Color.TRANSPARENT)
        handoffBaseBitmap.eraseColor(Color.TRANSPARENT)
        pendingHandoffBitmap.eraseColor(Color.TRANSPARENT)
        baseHandoffMarkCount = 0
        undoStack.clear()
        redoStack.clear()
        localDraw.resetCurrentPath()
        remoteDraw.resetCurrentPath()
        notifyHistoryChanged()
    }

    fun clearAndInitialize(silent: Boolean = false) {
        localDraw.clearAndInitialize(silent)
        remoteDraw.clearAndInitialize(true)
        app.localBitmap.eraseColor(if (app.foldSessionActive) Color.TRANSPARENT else Color.WHITE)
        baseBitmap.eraseColor(if (app.foldSessionActive) Color.TRANSPARENT else Color.WHITE)
        handoffBaseBitmap.eraseColor(Color.TRANSPARENT)
        pendingHandoffBitmap.eraseColor(Color.TRANSPARENT)
        baseHandoffMarkCount = 0
        undoStack.clear()
        redoStack.clear()
        notifyHistoryChanged()
        invalidate()
    }

    fun isDrawingHandoffStroke(): Boolean = drawingHandoffStroke

    fun drawPath(path: Path, paint: Paint, handoff: Boolean = false, eraser: Boolean = false) {
        val stroke = Stroke(Path(path), Paint(paint), handoff, eraser)
        val target = if (handoff) pendingHandoffBitmap else app.localBitmap
        renderStroke(Canvas(target), stroke.path, stroke.paint)
        if (undoStack.size == MAX_UNDO_STROKES) {
            val expired = undoStack.removeFirst()
            val expiredCanvas = Canvas(if (expired.handoff) handoffBaseBitmap else baseBitmap)
            renderStroke(expiredCanvas, expired.path, expired.paint)
            if (expired.handoff && !expired.eraser) baseHandoffMarkCount++
        }
        undoStack.addLast(stroke)
        redoStack.clear()
        notifyHistoryChanged()
    }

    fun drawBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return
        Canvas(app.localBitmap).drawBitmap(bitmap, null, Rect(0, 0, app.localBitmap.width, app.localBitmap.height), bitmapPaint)
        checkpointDocument()
        invalidate()
    }

    fun onDocumentBitmapReplaced() {
        baseBitmap.recycle()
        baseBitmap = app.localBitmap.copy(Bitmap.Config.ARGB_8888, true)
        resizeHandoffBitmaps()
        undoStack.clear()
        redoStack.clear()
        notifyHistoryChanged()
        invalidate()
    }

    fun undo(silent: Boolean = false): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(undoStack.removeLast())
        renderHistory()
        notifyHistoryChanged()
        if (!silent) app.sendMessage(DrawingMessage.Undo)
        return true
    }

    fun redo(silent: Boolean = false): Boolean {
        if (redoStack.isEmpty()) return false
        val stroke = redoStack.removeLast()
        if (undoStack.size == MAX_UNDO_STROKES) {
            val expired = undoStack.removeFirst()
            renderStroke(Canvas(if (expired.handoff) handoffBaseBitmap else baseBitmap), expired.path, expired.paint)
            if (expired.handoff && !expired.eraser) baseHandoffMarkCount++
        }
        undoStack.addLast(stroke)
        renderHistory()
        notifyHistoryChanged()
        if (!silent) app.sendMessage(DrawingMessage.Redo)
        return true
    }

    fun setBottomControlsHeight(height: Int) {
        bottomControlsHeight = height.coerceAtLeast(0)
        invalidate()
    }

    fun undoCount(): Int = undoStack.size
    fun redoCount(): Int = redoStack.size

    private fun checkpointDocument() {
        baseBitmap.recycle()
        baseBitmap = app.localBitmap.copy(Bitmap.Config.ARGB_8888, true)
        undoStack.clear()
        redoStack.clear()
        notifyHistoryChanged()
    }

    private fun renderHistory() {
        Canvas(app.localBitmap).drawBitmap(baseBitmap, 0f, 0f, null)
        Canvas(pendingHandoffBitmap).drawBitmap(handoffBaseBitmap, 0f, 0f, null)
        undoStack.forEach { stroke ->
            renderStroke(Canvas(if (stroke.handoff) pendingHandoffBitmap else app.localBitmap), stroke.path, stroke.paint)
        }
        invalidate()
    }

    private fun resizeHandoffBitmaps() {
        fun resized(bitmap: Bitmap): Bitmap = Bitmap.createBitmap(app.localBitmap.width, app.localBitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, null, Rect(0, 0, it.width, it.height), bitmapPaint)
            bitmap.recycle()
        }
        pendingHandoffBitmap = resized(pendingHandoffBitmap)
        handoffBaseBitmap = resized(handoffBaseBitmap)
        lastHandoffBitmap = lastHandoffBitmap?.let(::resized)
    }

    fun drawPreview(canvas: Canvas, path: Path, paint: Paint) {
        renderStroke(canvas, path, paint)
    }

    private fun renderStroke(canvas: Canvas, path: Path, paint: Paint) {
        if (paint.maskFilter !is EmbossMaskFilter || paint.xfermode != null) {
            canvas.drawPath(path, paint)
            return
        }

        val offset = max(1f, paint.strokeWidth * 0.2f)
        val shadow = Paint(paint).apply {
            maskFilter = null
            xfermode = null
            color = shadeColor(paint.color, -0.58f)
            alpha = (paint.alpha * 0.82f).toInt()
        }
        val highlight = Paint(paint).apply {
            maskFilter = null
            xfermode = null
            color = shadeColor(paint.color, 0.68f)
            alpha = (paint.alpha * 0.88f).toInt()
        }

        canvas.save()
        canvas.translate(offset, offset)
        canvas.drawPath(path, shadow)
        canvas.restore()
        canvas.save()
        canvas.translate(-offset, -offset)
        canvas.drawPath(path, highlight)
        canvas.restore()
        canvas.drawPath(path, paint)
    }

    private fun shadeColor(color: Int, amount: Float): Int {
        fun channel(value: Int): Int = if (amount > 0f) {
            (value + (255 - value) * amount).toInt()
        } else {
            (value * (1f + amount)).toInt()
        }.coerceIn(0, 255)
        return Color.argb(Color.alpha(color), channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
    }

    private fun notifyHistoryChanged() {
        onHistoryChanged?.invoke(undoStack.size, redoStack.size)
    }

    fun zoomIn() = setZoom(zoom * 1.25f)
    fun zoomOut() = setZoom(zoom / 1.25f)
    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun setZoom(value: Float) {
        zoom = value.coerceIn(DrawingDocument.MIN_ZOOM, DrawingDocument.MAX_ZOOM)
        clampPan()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (app.isConnectedToPeer() && !app.isSharedDrawingEnabled() && !app.foldSessionActive) return true
        scaleDetector.onTouchEvent(event)
        if (app.foldSessionActive && !app.isLocalFoldTurn()) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousGestureX = event.x
                previousGestureY = event.y
                drawingHandoffStroke = app.isHandoffMetadataPhase()
                val page = pageRect()
                val foldY = page.top + page.height() * app.foldBoundary
                if (app.foldSessionActive && app.isLocalFoldTurn() && !app.foldTurnEnded &&
                    page.contains(event.x, event.y) && kotlin.math.abs(event.y - foldY) <= dp(32f)
                ) {
                    adjustingFoldBoundary = true
                    drawingStroke = false
                    drawingHandoffStroke = false
                    return true
                }
                val point = screenToDocument(event.x, event.y, respectFoldBounds = !drawingHandoffStroke)
                val found = point?.let { hitText(it.first, it.second) }
                if (found != null) {
                    selectedTextId = found.id; movingTextId = found.id
                    textMoveOffsetX = found.x - point.first; textMoveOffsetY = found.y - point.second
                    drawingStroke = false
                    invalidate()
                    return true
                }
                if (selectedTextId != null) { selectedTextId = null; invalidate() }
                drawingStroke = point != null
                if (point != null) localDraw.touchStart(point.first, point.second, event.getPressure(0))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (drawingStroke) {
                    localDraw.touchUp()
                    drawingStroke = false
                    drawingHandoffStroke = false
                }
                midpoint(event)?.let {
                    previousGestureX = it.first
                    previousGestureY = it.second
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (adjustingFoldBoundary && event.pointerCount == 1) {
                    screenToDocument(event.x, event.y, respectFoldBounds = false)?.let { (_, documentY) ->
                        val remaining = (1f - app.foldPreviousBoundary).coerceAtLeast(0.0001f)
                        val progress = (((documentY / app.localBitmap.height - app.foldPreviousBoundary) / remaining) * 100f).toInt()
                        app.setFoldProgressPercent(progress)
                    }
                } else if (event.pointerCount >= 2) {
                    midpoint(event)?.let { (x, y) ->
                        if (zoom > 1f && !scaleDetector.isInProgress) {
                            panX += x - previousGestureX
                            panY += y - previousGestureY
                            clampPan()
                        }
                        previousGestureX = x
                        previousGestureY = y
                    }
                } else if (drawingStroke && !scaleDetector.isInProgress) {
                    screenToDocument(event.x, event.y, respectFoldBounds = !drawingHandoffStroke)?.let { (x, y) -> localDraw.touchMove(x, y, event.pressure) }
                } else if (movingTextId != null) {
                    screenToDocument(event.x, event.y, respectFoldBounds = false)?.let { (x, y) ->
                        textObjects.firstOrNull { it.id == movingTextId }?.apply { this.x = x + textMoveOffsetX; this.y = y + textMoveOffsetY }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawingStroke) localDraw.touchUp()
                adjustingFoldBoundary = false
                movingTextId?.let { id -> textObjects.firstOrNull { it.id == id }?.takeIf { !it.handoff && !app.foldSessionActive }?.let(::sendTextObject) }
                movingTextId = null
                drawingStroke = false
                drawingHandoffStroke = false
            }
        }
        invalidate()
        return true
    }

    private fun screenToDocument(x: Float, y: Float, respectFoldBounds: Boolean = true): Pair<Float, Float>? {
        val page = pageRect()
        if (!page.contains(x, y)) return null
        val scale = fitScale() * zoom
        if (scale <= 0f) return null
        val documentX = ((x - page.left) / scale).coerceIn(0f, app.localBitmap.width.toFloat())
        val documentY = ((y - page.top) / scale).coerceIn(0f, app.localBitmap.height.toFloat())
        if (app.foldSessionActive && respectFoldBounds) {
            val minY = app.localBitmap.height * app.foldPreviousBoundary
            val maxY = app.localBitmap.height * app.foldBoundary
            if (documentY !in minY..maxY) return null
        }
        return documentX to documentY
    }

    private fun pageRect(): RectF {
        val scale = fitScale() * zoom
        val pageWidth = app.localBitmap.width * scale
        val pageHeight = app.localBitmap.height * scale
        val centerX = width / 2f + panX
        val centerY = contentTop() + contentHeight() / 2f + panY
        return RectF(centerX - pageWidth / 2f, centerY - pageHeight / 2f, centerX + pageWidth / 2f, centerY + pageHeight / 2f)
    }

    private fun fitScale(): Float {
        val availableWidth = width.toFloat()
        val availableHeight = contentHeight()
        if (availableWidth <= 0f || availableHeight <= 0f) return 0f
        return min(availableWidth / app.localBitmap.width, availableHeight / app.localBitmap.height)
    }

    private fun contentTop(): Float = (systemTopInset + actionBarHeight).toFloat()

    private fun contentHeight(): Float = max(0f, height - contentTop() - systemBottomInset - bottomControlsHeight)

    private fun clampPan() {
        if (width <= 0 || height <= 0) return
        val scale = fitScale() * zoom
        val maxX = max(0f, app.localBitmap.width * scale / 2f - width / 2f)
        val maxY = max(0f, app.localBitmap.height * scale / 2f - contentHeight() / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun midpoint(event: MotionEvent): Pair<Float, Float>? {
        if (event.pointerCount < 2) return null
        return (event.getX(0) + event.getX(1)) / 2f to (event.getY(0) + event.getY(1)) / 2f
    }

    companion object {
        private const val HINTS_PREFS = "indexpaint_display"
        private const val HANDOFF_HINTS_VISIBLE = "handoff_hints_visible"
        private const val MAX_UNDO_STROKES = 10
        private val bitmapPaperPaint = Paint().apply { color = Color.WHITE }
        private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val foldFlapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F3F2.toInt() }
        private val foldCreasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val foldEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00695C.toInt(); strokeWidth = 5f }
        private val foldHandoffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00695C.toInt(); strokeWidth = 2f }
        private val foldCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8D8D2.toInt() }
        private val foldHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00695C.toInt() }
        private val foldHandleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val foldCornerPath = Path()
        private val turnStatusBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val turnStatusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        private val turnStatusSubtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        private val hintCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEFFFFFFF.toInt() }
        private val hintColorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL }
        private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt(); textSize = 14f }
        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF008577.toInt(); style = Paint.Style.STROKE }
        // About a few rendered screen pixels at normal fit-to-page zoom.
        private const val FOLD_REVEAL_FRACTION = 0.01f
    }
}
