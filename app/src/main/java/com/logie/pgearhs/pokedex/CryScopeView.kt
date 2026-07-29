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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real Pokédex CRY screen has two live displays while a cry plays, per pokedex_cry_screen.c:
 * a VU-meter needle (MIN_NEEDLE_POS/MAX_NEEDLE_POS = +-32, i.e. +-90 degrees off vertical) and
 * a scrolling oscilloscope waveform (WAVEFORM_WINDOW_HEIGHT = 56). This redraws both from the
 * cry's actual PCM samples, synced to real MediaPlayer playback position - not a canned loop.
 */
class CryScopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var samples: ShortArray = ShortArray(0)
    private var sampleRateHz = 44100
    private var player: MediaPlayer? = null

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#911121")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val gaugePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gaugeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F9F9F9")
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E93131")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val waveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E130E")
        style = Paint.Style.FILL
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
            // Ease the needle toward its target every frame regardless, for a settling motion.
            needleAngleDeg += (targetAngleDeg - needleAngleDeg) * 0.35f
            invalidate()
            if (p == null || !p.isPlaying) {
                if (kotlin.math.abs(needleAngleDeg) > 0.5f) {
                    Choreographer.getInstance().postFrameCallback(this)
                }
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
        // Map RMS (0..~0.6 typical for a cry) to the real needle's +-32/256 turn range ~ +-45 degrees.
        val signedBias = if (centerSample % 2 == 0) 1 else -1
        targetAngleDeg = (min(1.0, rms / 0.5) * 45.0 * signedBias).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // ---- VU meter (top band) ----
        val meterH = h * 0.42f
        val cx = w / 2f
        val cy = meterH * 0.92f
        val radius = meterH * 0.8f
        canvas.drawRect(0f, 0f, w, meterH, gaugeFillPaint)
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, 200f, 140f, false, gaugePaint)
        for (deg in -45..45 step 15) {
            val rad = Math.toRadians((deg - 90).toDouble())
            val x1 = cx + (radius * 0.85f) * cos(rad).toFloat()
            val y1 = cy + (radius * 0.85f) * sin(rad).toFloat()
            val x2 = cx + radius * cos(rad).toFloat()
            val y2 = cy + radius * sin(rad).toFloat()
            canvas.drawLine(x1, y1, x2, y2, gaugePaint)
        }
        val needleRad = Math.toRadians((needleAngleDeg - 90).toDouble())
        val nx = cx + (radius * 0.9f) * cos(needleRad).toFloat()
        val ny = cy + (radius * 0.9f) * sin(needleRad).toFloat()
        canvas.drawLine(cx, cy, nx, ny, needlePaint)
        canvas.drawCircle(cx, cy, 5f, needlePaint)

        // ---- Waveform scope (bottom band) ----
        val waveTop = meterH + h * 0.06f
        val waveBottom = h
        val waveH = waveBottom - waveTop
        canvas.drawRoundRect(0f, waveTop, w, waveBottom, 6f, 6f, waveBgPaint)

        val playing = player?.isPlaying == true
        if (!playing || samples.isEmpty()) {
            canvas.drawLine(4f, waveTop + waveH / 2f, w - 4f, waveTop + waveH / 2f, flatlinePaint)
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
            val y = waveTop + waveH / 2f - amp * (waveH / 2f - 4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, wavePaint)
    }

    override fun onDetachedFromWindow() {
        player = null
        super.onDetachedFromWindow()
    }
}
