package com.logie.pgearhs.ui

import android.graphics.PorterDuff
import android.view.Choreographer
import android.widget.ImageView
import kotlin.math.sin

/**
 * Drives keyboard/d-pad selection across a vertical list of button
 * ImageViews, and pulses the selected one white via a color filter (so
 * the pulse follows the art's own alpha shape instead of a rectangular
 * overlay).
 *
 * Like the other animated views in this app, the pulse is driven by
 * Choreographer using real elapsed time between frames rather than a
 * fixed per-frame step.
 */
class ButtonSelectionController(private val buttons: List<ImageView>) {

    /** Pulses per second. */
    var pulsesPerSecond: Float = 2f

    /** Peak pulse opacity, 0f-1f. */
    var maxAlpha: Float = 0.5f

    var selectedIndex = 0
        private set

    private var elapsedSeconds = 0f
    private var lastFrameTimeNanos = 0L
    private var isRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (lastFrameTimeNanos != 0L) {
                val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                elapsedSeconds += deltaSeconds
                val phase = sin(2.0 * Math.PI * pulsesPerSecond * elapsedSeconds).toFloat()
                val alpha = maxAlpha * (0.5f + 0.5f * phase)
                applyPulse(alpha)
            }
            lastFrameTimeNanos = frameTimeNanos

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastFrameTimeNanos = 0L
        buttons.getOrNull(selectedIndex)?.let { applyPulse(0f) }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        isRunning = false
        buttons.forEach { it.clearColorFilter() }
    }

    fun moveSelection(delta: Int) {
        val newIndex = (selectedIndex + delta).coerceIn(0, buttons.lastIndex)
        if (newIndex == selectedIndex) return
        buttons[selectedIndex].clearColorFilter()
        selectedIndex = newIndex
    }

    private fun applyPulse(alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val argb = (a shl 24) or 0x00FFFFFF
        buttons[selectedIndex].setColorFilter(argb, PorterDuff.Mode.SRC_ATOP)
    }
}
