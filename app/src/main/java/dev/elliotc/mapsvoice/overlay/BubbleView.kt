package dev.elliotc.mapsvoice.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import dev.elliotc.mapsvoice.R

/**
 * The bubble itself: a filled circle whose colour is the entire interface.
 * Nothing to read, nothing to aim at precisely — the driver's eyes stay on
 * the road and the colour is legible in peripheral vision.
 */
class BubbleView(context: Context) : View(context) {

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING, ERROR
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE_DP * resources.displayMetrics.density
    }

    /** 0f..1f, drives the halo that says "the mic is open". */
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null

    var state: State = State.IDLE
        set(value) {
            if (field == value) return
            field = value
            fillPaint.color = ContextCompat.getColor(context, colorFor(value))
            if (value == State.LISTENING) startPulse() else stopPulse()
            invalidate()
        }

    init {
        fillPaint.color = ContextCompat.getColor(context, colorFor(State.IDLE))
        ringPaint.color = ContextCompat.getColor(context, R.color.bubble_listening)
    }

    override fun onDraw(canvas: Canvas) {
        val centreX = width / 2f
        val centreY = height / 2f
        // Leave room for the pulse ring at its widest.
        val coreRadius = (minOf(width, height) / 2f) * CORE_FRACTION

        if (pulse > 0f) {
            ringPaint.alpha = ((1f - pulse) * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(
                centreX,
                centreY,
                coreRadius + pulse * (minOf(width, height) / 2f - coreRadius),
                ringPaint
            )
        }

        canvas.drawCircle(centreX, centreY, coreRadius, fillPaint)
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION_MILLIS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulse = 0f
    }

    private fun colorFor(state: State): Int = when (state) {
        State.IDLE -> R.color.bubble_idle
        State.LISTENING -> R.color.bubble_listening
        State.THINKING -> R.color.bubble_thinking
        State.SPEAKING -> R.color.bubble_speaking
        State.ERROR -> R.color.bubble_error
    }

    private companion object {
        const val CORE_FRACTION = 0.62f
        const val RING_STROKE_DP = 2f
        const val PULSE_DURATION_MILLIS = 1200L
    }
}
