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

/**
 * Draws a bitmap tiled edge-to-edge and continuously scrolling to the left.
 *
 * Motion is driven by Choreographer with real elapsed time between frames
 * (not a fixed per-frame step), so scroll speed stays correct at whatever
 * refresh rate the current display is running - 60Hz on the Thor's bottom
 * screen, 120Hz on the top one - while still updating every vsync for the
 * smoothest motion that screen can show.
 *
 * The source bitmap should already be scaled to its final on-screen pixel
 * size (e.g. pre-upscaled 4x with nearest-neighbor) and loaded unscaled;
 * filtering is disabled here so the tile stays crisp instead of blurring.
 */
class ScrollingTiledBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Scroll speed in dp/second; negative scrolls left. */
    var scrollSpeedDpPerSecond: Float = 24f

    private val tileBitmap: Bitmap
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = false
    }
    private val shaderMatrix = Matrix()
    private var offsetPx = 0f
    private var lastFrameTimeNanos = 0L
    private var isRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (lastFrameTimeNanos != 0L) {
                val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                val speedPxPerSecond = scrollSpeedDpPerSecond * resources.displayMetrics.density
                offsetPx -= speedPxPerSecond * deltaSeconds

                val tileWidth = tileBitmap.width
                if (tileWidth > 0) {
                    offsetPx %= tileWidth
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
        shaderMatrix.setTranslate(offsetPx, 0f)
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
