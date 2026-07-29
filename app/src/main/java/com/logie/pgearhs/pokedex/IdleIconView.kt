package com.logie.pgearhs.pokedex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * A small idle-loop icon: continuously bobs between the two frames of a species'
 * anim_front.png sheet, one frame every 500ms (2FPS), matching the in-game menu
 * icon's idle animation. Unlike [PokemonPreviewSpriteView] this never stops and
 * never bounces/wobbles - it's meant for a Stats-header or evolution-chain icon,
 * not the one-shot list-selection preview.
 */
class IdleIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        const val FRAME_MS = 500L
    }

    private var sheet: Bitmap? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var currentFrame = 0
    private var running = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = false
        isDither = false
    }
    private val srcRect = Rect()
    private val dstRect = Rect()

    private val reduceMotion: Boolean
        get() = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f

    private var startNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (startNanos == 0L) startNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - startNanos) / 1_000_000
            val next = ((elapsedMs / FRAME_MS) % 2).toInt()
            if (next != currentFrame) {
                currentFrame = next
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Loads a species' anim_front.png sheet from assets/pokemon/&lt;folder&gt;/anim_front.png. */
    fun loadSpecies(assetFolder: String) {
        stop()
        sheet?.recycle()
        val bitmap = context.assets.open("pokemon/$assetFolder/anim_front.png")
            .use { BitmapFactory.decodeStream(it) }
        sheet = bitmap
        frameWidth = bitmap.width
        frameHeight = bitmap.height / 2
        currentFrame = 0
        invalidate()
        start()
    }

    private fun start() {
        if (reduceMotion) return
        running = true
        startNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stop() {
        running = false
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
        stop()
        sheet?.recycle()
        sheet = null
        super.onDetachedFromWindow()
    }
}
