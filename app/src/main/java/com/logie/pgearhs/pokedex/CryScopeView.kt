package com.logie.pgearhs.pokedex

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The real CRY screen's oscilloscope waveform trace (the left of its two real 64x64/80x64
 * display boxes - see HGSS_tilemap_cry_screen.bin), redrawn from the cry's actual PCM samples
 * synced to real MediaPlayer position - not a canned loop. The VU-meter needle (the right box)
 * is handled separately in the activity by rotating the real cry_meter_needle.png over the real
 * cry_meter.png, driven by [onNeedleAngle] so both displays share one Choreographer loop.
 */
class CryScopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onNeedleAngle: ((Float) -> Unit)? = null

    private var samples: ShortArray = ShortArray(0)
    private var sampleRateHz = 44100
    private var player: MediaPlayer? = null

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E93131")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val flatlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A9A9A9")
        strokeWidth = 2f
    }

    private var needleAngleDeg = 0f
    private var targetAngleDeg = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val p = player
            if (p != null && p.isPlaying) {
                updateFromPlayback(p.currentPosition)
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                targetAngleDeg = 0f
            }
            needleAngleDeg += (targetAngleDeg - needleAngleDeg) * 0.35f
            onNeedleAngle?.invoke(needleAngleDeg)
            invalidate()
            if ((p == null || !p.isPlaying) && abs(needleAngleDeg) > 0.5f) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /** Loads raw PCM16 samples straight out of the bundled cry WAV (real waveform data, not synthetic). */
    fun loadWav(context: Context, assetPath: String) {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        // Minimal RIFF/WAVE parse: walk chunks to find "fmt " (sample rate) and "data" (PCM16 samples).
        var pos = 12
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            val body = pos + 8
            if (id == "fmt ") {
                sampleRateHz = (bytes[body + 4].toInt() and 0xFF) or
                    ((bytes[body + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[body + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[body + 7].toInt() and 0xFF) shl 24)
            } else if (id == "data") {
                dataOffset = body
                dataSize = size
            }
            pos = body + size + (size and 1)
        }
        if (dataOffset < 0) {
            samples = ShortArray(0)
            return
        }
        val count = dataSize / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[dataOffset + i * 2].toInt() and 0xFF
            val hi = bytes[dataOffset + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        samples = out
    }

    fun attachPlayer(mediaPlayer: MediaPlayer) {
        player = mediaPlayer
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        player = null
        targetAngleDeg = 0f
    }

    private fun updateFromPlayback(positionMs: Int) {
        if (samples.isEmpty()) return
        val centerSample = (positionMs / 1000f * sampleRateHz).toInt().coerceIn(0, samples.size - 1)
        val windowRadius = 400
        val start = max(0, centerSample - windowRadius)
        val end = min(samples.size, centerSample + windowRadius)
        var sumSquares = 0.0
        for (i in start until end) sumSquares += (samples[i].toDouble() / 32768.0).let { it * it }
        val rms = sqrt(sumSquares / max(1, end - start))
        // Real needle range is MIN_NEEDLE_POS/MAX_NEEDLE_POS = +-32 (of 256, i.e. +-45 degrees).
        val signedBias = if (centerSample % 2 == 0) 1 else -1
        targetAngleDeg = (min(1.0, rms / 0.5) * 45.0 * signedBias).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val playing = player?.isPlaying == true
        if (!playing || samples.isEmpty()) {
            canvas.drawLine(4f, h / 2f, w - 4f, h / 2f, flatlinePaint)
            return
        }

        val positionMs = player?.currentPosition ?: 0
        val centerSample = (positionMs / 1000f * sampleRateHz).toInt().coerceIn(0, samples.size - 1)
        val points = 72
        val span = 900 // samples shown across the scope width, centered on playback
        val path = Path()
        for (i in 0 until points) {
            val t = i / (points - 1).toFloat()
            val sampleIdx = (centerSample - span / 2 + (t * span)).toInt().coerceIn(0, samples.size - 1)
            val amp = samples[sampleIdx].toFloat() / 32768f
            val x = 4f + t * (w - 8f)
            val y = h / 2f - amp * (h / 2f - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, wavePaint)
    }

    override fun onDetachedFromWindow() {
        player = null
        super.onDetachedFromWindow()
    }
}
