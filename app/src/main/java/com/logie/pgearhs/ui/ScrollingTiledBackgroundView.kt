package com.logie.pgearhs.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a bitmap tiled edge-to-edge, animated according to [movementPattern].
 *
 * Motion is driven by Choreographer with real elapsed time between frames
 * (not a fixed per-frame step), so speed stays correct at whatever refresh
 * rate the current display is running - 60Hz on the Thor's bottom screen,
 * 120Hz on the top one - while still updating every vsync for the smoothest
 * motion that screen can show.
 *
 * The source bitmap should already be scaled to its final on-screen pixel
 * size (pre-upscaled with nearest-neighbor) and is loaded unscaled here;
 * filtering is disabled so the tile stays crisp instead of blurring.
 */
class ScrollingTiledBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class MovementPattern {
        LEFT,
        FIGURE_EIGHT,
        NONE
    }

    /** Scroll speed in dp/second for [MovementPattern.LEFT]. */
    var scrollSpeedDpPerSecond: Float = 96f

    /** Half-width of the figure-8 loop in dp for [MovementPattern.FIGURE_EIGHT]. */
    var figureEightAmplitudeDp: Float = 40f

    /** Time in seconds to complete one full figure-8 loop. */
    var figureEightPeriodSeconds: Float = 6f

    var movementPattern: MovementPattern = MovementPattern.LEFT
        set(value) {
            val resuming = field == MovementPattern.NONE && value != MovementPattern.NONE
            field = value
            if (resuming && isRunning) {
                lastFrameTimeNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

    private val tileBitmap: Bitmap
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = false
    }
    private val shaderMatrix = Matrix()
    private var offsetX = 0f
    private var offsetY = 0f
    private var figureEightElapsedSeconds = 0f
    private var lastFrameTimeNanos = 0L
    private var isRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (movementPattern == MovementPattern.NONE) {
                // Nothing to animate - stop scheduling until the pattern changes again.
                lastFrameTimeNanos = 0L
                return
            }

            if (lastFrameTimeNanos != 0L) {
                val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                val density = resources.displayMetrics.density

                when (movementPattern) {
                    MovementPattern.LEFT -> {
                        val speedPxPerSecond = scrollSpeedDpPerSecond * density
                        offsetX -= speedPxPerSecond * deltaSeconds
                        val tileWidth = tileBitmap.width
                        if (tileWidth > 0) offsetX %= tileWidth
                    }
                    MovementPattern.FIGURE_EIGHT -> {
                        figureEightElapsedSeconds += deltaSeconds
                        val angle = figureEightElapsedSeconds * (2f * Math.PI.toFloat() / figureEightPeriodSeconds)
                        val amplitudePx = figureEightAmplitudeDp * density
                        // Lemniscate-style path: traces a figure-8 as angle sweeps through 2*PI.
                        offsetX = amplitudePx * sin(angle)
                        offsetY = amplitudePx * sin(angle) * cos(angle)
                    }
                    MovementPattern.NONE -> Unit
                }
            }
            lastFrameTimeNanos = frameTimeNanos

            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        tileBitmap = decodeUnscaled(context, com.logie.pgearhs.R.drawable.bg_dots)
        paint.shader = BitmapShader(tileBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun decodeUnscaled(context: Context, resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shaderMatrix.setTranslate(offsetX, offsetY)
        paint.shader?.setLocalMatrix(shaderMatrix)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startScrolling()
    }

    override fun onDetachedFromWindow() {
        stopScrolling()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startScrolling() else stopScrolling()
    }

    fun startScrolling() {
        if (isRunning) return
        isRunning = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopScrolling() {
        isRunning = false
    }
}
