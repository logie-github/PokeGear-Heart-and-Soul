package com.logie.pgearhs.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.logie.pgearhs.R

/**
 * Plays a vertically-stacked sprite sheet (N equal-height frames, each as
 * wide as the sheet) as a looping frame animation.
 *
 * Like [ScrollingTiledBackgroundView], frame advancement is driven by
 * elapsed real time between Choreographer callbacks rather than a fixed
 * per-frame step, so the animation speed (framesPerSecond) stays correct
 * regardless of the display's refresh rate.
 */
class SpriteAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var framesPerSecond: Float = 8f

    private val sheet: Bitmap = decodeUnscaled(context, R.drawable.nav_icon)
    private val frameCount = 8
    private val frameWidth = sheet.width
    private val frameHeight = sheet.height / frameCount

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = false
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var currentFrame = 0
    private var frameElapsedSeconds = 0f
    private var lastFrameTimeNanos = 0L
    private var isRunning = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (lastFrameTimeNanos != 0L) {
                val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                val frameDuration = 1f / framesPerSecond
                frameElapsedSeconds += deltaSeconds
                while (frameElapsedSeconds >= frameDuration) {
                    frameElapsedSeconds -= frameDuration
                    currentFrame = (currentFrame + 1) % frameCount
                }
            }
            lastFrameTimeNanos = frameTimeNanos

            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun decodeUnscaled(context: Context, resId: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = currentFrame * frameHeight
        srcRect.set(0, top, frameWidth, top + frameHeight)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(sheet, srcRect, dstRect, paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimating()
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startAnimating() else stopAnimating()
    }

    fun startAnimating() {
        if (isRunning) return
        isRunning = true
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stopAnimating() {
        isRunning = false
    }
}
