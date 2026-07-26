package com.logie.pgearhs.ui

import android.graphics.PorterDuff
import android.view.Choreographer
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlin.math.sin

/**
 * Drives keyboard/d-pad selection across a vertical list of button
 * ImageViews. The selected button pulses white via a color filter (so
 * the pulse follows the art's own alpha shape instead of a rectangular
 * overlay) and slides 50px further onto screen; the rest sit at their
 * resting (fully hung-off) position.
 *
 * Like the other animated views in this app, the pulse is driven by
 * Choreographer using real elapsed time between frames rather than a
 * fixed per-frame step. The slide-in on selection change is a one-shot
 * transition, so it uses a plain ViewPropertyAnimator instead - still
 * vsync-driven under the hood, just not a continuous per-frame value.
 */
class ButtonSelectionController(private val buttons: List<ImageView>) {

    /** Pulses per second. */
    var pulsesPerSecond: Float = 2f

    /** Peak pulse opacity, 0f-1f. */
    var maxAlpha: Float = 0.5f

    /** How far (px) the selected button slides onto screen from its resting position. */
    var selectedSlidePx: Float = 50f

    /** Duration of the slide-in/out transition when selection changes. */
    var slideDurationMs: Long = 160L

    var selectedIndex = 0
        private set

    /** Invoked whenever the selection actually moves to a different button. */
    var onSelectionChanged: (() -> Unit)? = null

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
        buttons.forEachIndexed { index, button ->
            button.translationX = if (index == selectedIndex) -selectedSlidePx else 0f
        }
        if (isRunning) return
        isRunning = true
        lastFrameTimeNanos = 0L
        applyPulse(0f)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        isRunning = false
        buttons.forEach {
            it.animate().cancel()
            it.clearColorFilter()
        }
    }

    fun moveSelection(delta: Int) {
        val newIndex = (selectedIndex + delta).coerceIn(0, buttons.lastIndex)
        if (newIndex == selectedIndex) return

        val oldIndex = selectedIndex
        buttons[oldIndex].clearColorFilter()
        selectedIndex = newIndex

        slideTo(buttons[oldIndex], 0f)
        slideTo(buttons[newIndex], -selectedSlidePx)

        onSelectionChanged?.invoke()
    }

    private fun slideTo(button: ImageView, targetTranslationX: Float) {
        button.animate()
            .translationX(targetTranslationX)
            .setDuration(slideDurationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun applyPulse(alpha: Float) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        val argb = (a shl 24) or 0x00FFFFFF
        buttons[selectedIndex].setColorFilter(argb, PorterDuff.Mode.SRC_ATOP)
    }
}
