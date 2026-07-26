package com.logie.pgearhs.pokedex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.sin

/**
 * Shows a species' two-frame anim_front.png sheet. [playAnimation] runs the
 * 1-2-1-2-1-2-2-2-1 frame sequence over one second while the view itself
 * bounces (translationY) and wobbles (rotation) to look alive; once it
 * finishes, the view holds on frame 0 (idle) until triggered again.
 *
 * Like the other animated views in this app, timing is computed from real
 * elapsed time (here: time since the animation started) rather than a fixed
 * per-frame step.
 */
class PokemonPreviewSpriteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        // 1-indexed in the spec (1,2,1,2,1,2,2,2,1) -> 0-indexed frame numbers.
        val FRAME_SEQUENCE = intArrayOf(0, 1, 0, 1, 0, 1, 1, 1, 0)
    }

    var totalDurationMs: Long = 1000L
    var bounceAmplitudeDp: Float = 14f
    var wobbleAmplitudeDegrees: Float = 6f
    var wobbleCycles: Float = 2.5f

    private var sheet: Bitmap? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var currentFrame = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = false
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    private var animStartNanos = 0L
    private var isAnimating = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAnimating) return

            if (animStartNanos == 0L) animStartNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - animStartNanos) / 1_000_000
            val t = (elapsedMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)

            val stepIndex = (t * FRAME_SEQUENCE.size).toInt().coerceIn(0, FRAME_SEQUENCE.size - 1)
            currentFrame = FRAME_SEQUENCE[stepIndex]

            val density = resources.displayMetrics.density
            translationY = -bounceAmplitudeDp * density * sin(Math.PI * t).toFloat()
            rotation = wobbleAmplitudeDegrees * sin(2.0 * Math.PI * wobbleCycles * t).toFloat()

            invalidate()

            if (t >= 1f) {
                isAnimating = false
                currentFrame = 0
                translationY = 0f
                rotation = 0f
                invalidate()
            } else {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /** Loads a species' anim_front.png sheet from assets/pokemon/&lt;folder&gt;/anim_front.png. */
    fun loadSpecies(assetFolder: String) {
        isAnimating = false
        sheet?.recycle()

        val stream = context.assets.open("pokemon/$assetFolder/anim_front.png")
        val bitmap = stream.use { BitmapFactory.decodeStream(it) }
        sheet = bitmap
        frameWidth = bitmap.width
        frameHeight = bitmap.height / 2

        currentFrame = 0
        translationY = 0f
        rotation = 0f
        invalidate()
    }

    fun playAnimation() {
        isAnimating = true
        animStartNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sheet ?: return
        val top = currentFrame * frameHeight
        srcRect.set(0, top, frameWidth, top + frameHeight)
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    override fun onDetachedFromWindow() {
        isAnimating = false
        sheet?.recycle()
        sheet = null
        super.onDetachedFromWindow()
    }
}
