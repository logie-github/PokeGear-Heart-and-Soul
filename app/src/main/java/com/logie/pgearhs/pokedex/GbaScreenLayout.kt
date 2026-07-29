package com.logie.pgearhs.pokedex

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recreates the real GBA Pokédex screen's coordinate space: every child is placed using the
 * *exact* native 240x160 pixel coordinates read out of pokedex_plus_hgss.c (the same numbers
 * the HTML prototype used). Rather than letterboxing to preserve the GBA's native 3:2 aspect
 * (which leaves black bars on a differently-shaped screen), this fills its container edge to
 * edge: X and Y each get their own scale factor, so the whole layout stretches to exactly match
 * the device's actual screen ratio instead of leaving empty space.
 */
class GbaScreenLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_W = 240
        const val NATIVE_H = 160
    }

    /** Current native-px -> real-px scale factors, valid after layout. */
    var nativeScaleX: Float = 1f
        private set
    var nativeScaleY: Float = 1f
        private set

    class LayoutParams(
        val nativeLeft: Int,
        val nativeTop: Int,
        val nativeWidth: Int,
        val nativeHeight: Int,
        val nativeTextSizePx: Float = 0f
    ) : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    /** Adds a child positioned at exact native (240x160) coordinates. */
    fun addNative(view: View, x: Int, y: Int, w: Int, h: Int, nativeTextSizePx: Float = 0f) {
        addView(view, LayoutParams(x, y, w, h, nativeTextSizePx))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        nativeScaleX = availW.toFloat() / NATIVE_W
        nativeScaleY = if (availH > 0) availH.toFloat() / NATIVE_H else nativeScaleX
        val textScale = sqrt(nativeScaleX * nativeScaleY)

        setMeasuredDimension(availW, availH)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (lp.nativeTextSizePx > 0f && child is TextView) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, lp.nativeTextSizePx * textScale)
            }
            val childWidthSpec = MeasureSpec.makeMeasureSpec((lp.nativeWidth * nativeScaleX).roundToInt(), MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec((lp.nativeHeight * nativeScaleY).roundToInt(), MeasureSpec.EXACTLY)
            child.measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            val left = (lp.nativeLeft * nativeScaleX).roundToInt()
            val top = (lp.nativeTop * nativeScaleY).roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(0, 0, NATIVE_W, NATIVE_H)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams
}
