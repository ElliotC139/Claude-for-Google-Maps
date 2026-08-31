package dev.elliotc.mapsvoice.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The "drop here to turn it off" circle that appears at the bottom of the
 * screen while the bubble is being dragged — the same gesture as a launcher
 * or a chat head.
 */
class DismissTargetView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = IDLE_FILL
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = CROSS_COLOR
    }

    /** True once the bubble is close enough that letting go would dismiss it. */
    var armed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            fillPaint.color = if (value) ARMED_FILL else IDLE_FILL
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val centreX = width / 2f
        val centreY = height / 2f
        val radius = (minOf(width, height) / 2f) * if (armed) 1f else 0.82f

        canvas.drawCircle(centreX, centreY, radius, fillPaint)

        val arm = radius * 0.32f
        canvas.drawLine(centreX - arm, centreY - arm, centreX + arm, centreY + arm, crossPaint)
        canvas.drawLine(centreX + arm, centreY - arm, centreX - arm, centreY + arm, crossPaint)
    }

    private companion object {
        const val IDLE_FILL = 0xCC3B3B3F.toInt()
        const val ARMED_FILL = 0xEED5433C.toInt()
        const val CROSS_COLOR = 0xFFFFFFFF.toInt()
    }
}
