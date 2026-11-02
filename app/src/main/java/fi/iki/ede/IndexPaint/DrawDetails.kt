package fi.iki.ede.IndexPaint

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.EmbossMaskFilter
import android.graphics.MaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

class DrawDetails(
    private val remote: Boolean,
    private val myView: MyView,
    private val paintApp: PaintApp,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
        alpha = 255
        color = 0xFFFF0000.toInt()
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 9f
    }
    private val path = Path()
    private val emboss: MaskFilter = EmbossMaskFilter(floatArrayOf(1f, 1f, 1f), 0.4f, 6f, 3.5f)
    private val blur: MaskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    private var lastX = 0f
    private var lastY = 0f
    private var brushStyle = BrushStyle.PEN
    private var eraserEnabled = false
    private var blendMode: PorterDuff.Mode? = null
    private var blendAlpha = 255
    private var maskStyle = DrawingProtocol.MASK_CLEAR

    fun clearAndInitialize(silent: Boolean = false) {
        paint.alpha = 255
        paint.color = 0xFFFF0000.toInt()
        paint.strokeWidth = 9f
        paint.maskFilter = null
        paint.xfermode = null
        brushStyle = BrushStyle.PEN
        eraserEnabled = false
        blendMode = null
        blendAlpha = 255
        maskStyle = DrawingProtocol.MASK_CLEAR
        path.reset()
        if (!remote && !silent) paintApp.sendMessage(DrawingMessage.Initialize)
    }

    fun getColor(): Int = paint.color
    fun getDrawWidth(): Float = paint.strokeWidth
    fun getBrushStyle(): BrushStyle = brushStyle

    fun sendCurrentState() {
        if (remote) return
        paintApp.sendMessage(DrawingMessage.SetColor(paint.color))
        paintApp.sendMessage(DrawingMessage.SetBrush(brushStyle))
        paintApp.sendMessage(DrawingMessage.SetWidth(paint.strokeWidth))
        if (maskStyle == DrawingProtocol.MASK_CLEAR) paintApp.sendMessage(DrawingMessage.SetMask(DrawingProtocol.MASK_CLEAR))
        else paintApp.sendMessage(DrawingMessage.SetMask(maskStyle))
        val mode = blendMode
        if (mode == null) paintApp.sendMessage(DrawingMessage.ClearBlendMode)
        else paintApp.sendMessage(DrawingMessage.SetBlendMode(mode.name, blendAlpha))
        paintApp.sendMessage(DrawingMessage.SetAlpha(paint.alpha))
    }

    fun setColor(color: Int) {
        paint.color = color
        if (!remote) paintApp.sendMessage(DrawingMessage.SetColor(color))
    }

    fun setBrush(style: BrushStyle) {
        if (eraserEnabled) clearXfermode()
        brushStyle = style
        paint.alpha = brushAlpha(style)
        paint.strokeCap = if (style == BrushStyle.PENCIL) Paint.Cap.SQUARE else Paint.Cap.ROUND
        if (!remote) paintApp.sendMessage(DrawingMessage.SetBrush(style))
    }

    fun setEraser(enabled: Boolean) {
        if (enabled) setPorterDuffMode(PorterDuff.Mode.CLEAR, 255) else clearXfermode()
    }

    fun isEraserEnabled(): Boolean = eraserEnabled

    fun setDrawWidth(width: Float) {
        paint.strokeWidth = width
        if (!remote) paintApp.sendMessage(DrawingMessage.SetWidth(width))
    }

    fun touchStart(x: Float, y: Float, pressure: Float) {
        if (!remote) paintApp.sendMessage(DrawingMessage.TouchStart(x, y, pressure))
        path.reset()
        path.moveTo(x, y)
        lastX = x
        lastY = y
    }

    fun touchMove(x: Float, y: Float, pressure: Float) {
        if (!remote) paintApp.sendMessage(DrawingMessage.TouchMove(x, y, pressure))
        if (kotlin.math.abs(x - lastX) >= TOUCH_TOLERANCE || kotlin.math.abs(y - lastY) >= TOUCH_TOLERANCE) {
            path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
            lastX = x
            lastY = y
        }
    }

    fun touchUp() {
        if (!remote) paintApp.sendMessage(DrawingMessage.TouchUp)
        path.lineTo(lastX, lastY)
        myView.drawPath(path, paint, myView.isDrawingHandoffStroke(), eraserEnabled)
        path.reset()
    }

    fun onDraw(canvas: Canvas) { if (!path.isEmpty) myView.drawPreview(canvas, path, paint) }

    fun resetCurrentPath() { path.reset() }

    fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        if (!remote) paintApp.sendMessage(DrawingMessage.SetAlpha(alpha))
    }

    fun toggleBlur() = toggleMask(blur, DrawingProtocol.MASK_BLUR)
    fun toggleEmboss() = toggleMask(emboss, DrawingProtocol.MASK_EMBOSS)
    fun isEmbossEnabled(): Boolean = maskStyle == DrawingProtocol.MASK_EMBOSS

    fun clearMask() {
        paint.maskFilter = null
        maskStyle = DrawingProtocol.MASK_CLEAR
    }

    private fun toggleMask(mask: MaskFilter, command: String) {
        val enabling = paint.maskFilter !== mask
        paint.maskFilter = if (enabling) mask else null
        maskStyle = if (enabling) command else DrawingProtocol.MASK_CLEAR
        if (!remote) paintApp.sendMessage(DrawingMessage.SetMask(maskStyle))
    }

    fun setPorterDuffMode(mode: PorterDuff.Mode, alpha: Int) {
        paint.xfermode = PorterDuffXfermode(mode)
        paint.alpha = alpha
        eraserEnabled = mode == PorterDuff.Mode.CLEAR
        blendMode = mode
        blendAlpha = alpha
        if (!remote) paintApp.sendMessage(DrawingMessage.SetBlendMode(mode.name, alpha))
    }

    fun clearXfermode() {
        paint.xfermode = null
        paint.alpha = brushAlpha(brushStyle)
        eraserEnabled = false
        blendMode = null
        blendAlpha = 255
        if (!remote) paintApp.sendMessage(DrawingMessage.ClearBlendMode)
    }

    private fun brushAlpha(style: BrushStyle) = when (style) {
        BrushStyle.PEN -> 255
        BrushStyle.MARKER -> 105
        BrushStyle.PENCIL -> 185
    }

    companion object { private const val TOUCH_TOLERANCE = 3f }
}
