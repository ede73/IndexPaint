package fi.iki.ede.IndexPaint

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.sqrt

class ColorPickerDialog(context: Context, private val listener: OnColorChangedListener, private val initialColor: Int) : Dialog(context) {
    fun interface OnColorChangedListener { fun colorChanged(color: Int) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ColorPickerView(context, initialColor) { color -> listener.colorChanged(color); dismiss() })
        setTitle(R.string.COLOR_PICK_COLOR)
    }

    private class ColorPickerView(context: Context, color: Int, private val onColorChanged: (Int) -> Unit) : View(context) {
        private val colors = intArrayOf(0xFFFF0000.toInt(), 0xFFFF00FF.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(), 0xFFFF0000.toInt())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = SweepGradient(0f, 0f, colors, null); style = Paint.Style.STROKE; strokeWidth = 32f }
        private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 5f }
        private var trackingCenter = false
        private var highlightCenter = false

        override fun onDraw(canvas: Canvas) {
            val radius = CENTER - paint.strokeWidth * .5f
            canvas.translate(CENTER.toFloat(), CENTER.toFloat())
            canvas.drawOval(RectF(-radius, -radius, radius, radius), paint)
            canvas.drawCircle(0f, 0f, CENTER_RADIUS, centerPaint)
            if (trackingCenter) {
                val color = centerPaint.color
                centerPaint.style = Paint.Style.STROKE
                centerPaint.alpha = if (highlightCenter) 0xFF else 0x80
                canvas.drawCircle(0f, 0f, CENTER_RADIUS + centerPaint.strokeWidth, centerPaint)
                centerPaint.style = Paint.Style.FILL
                centerPaint.color = color
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(CENTER * 2, CENTER * 2)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x - CENTER
            val y = event.y - CENTER
            val inCenter = sqrt(x * x + y * y) <= CENTER_RADIUS
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    trackingCenter = inCenter
                    if (inCenter) { highlightCenter = true; invalidate(); return true }
                    updateColor(x, y)
                }
                MotionEvent.ACTION_MOVE -> if (trackingCenter) {
                    if (highlightCenter != inCenter) { highlightCenter = inCenter; invalidate() }
                } else updateColor(x, y)
                MotionEvent.ACTION_UP -> if (trackingCenter) {
                    if (inCenter) onColorChanged(centerPaint.color)
                    trackingCenter = false
                    invalidate()
                } else {
                    // Tapping or dragging the hue ring selects that color. The center
                    // swatch remains an optional way to confirm the current preview.
                    onColorChanged(centerPaint.color)
                }
            }
            return true
        }

        private fun updateColor(x: Float, y: Float) {
            var unit = atan2(y, x) / (2f * PI)
            if (unit < 0f) unit += 1f
            centerPaint.color = interpolate(colors, unit)
            invalidate()
        }

        private fun interpolate(colors: IntArray, unit: Float): Int {
            val p = (unit.coerceIn(0f, 1f) * (colors.size - 1))
            val index = p.toInt().coerceAtMost(colors.lastIndex - 1)
            val fraction = p - index
            fun channel(start: Int, end: Int) = start + (fraction * (end - start)).toInt()
            val a = colors[index]; val b = colors[index + 1]
            return Color.argb(channel(Color.alpha(a), Color.alpha(b)), channel(Color.red(a), Color.red(b)), channel(Color.green(a), Color.green(b)), channel(Color.blue(a), Color.blue(b)))
        }

        companion object { private const val CENTER = 100; private const val CENTER_RADIUS = 32f; private const val PI = 3.1415926535f }
    }
}
