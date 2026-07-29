package com.logie.pgearhs.pokedex

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Recreates the real GBA Pokédex screen's coordinate space: every child is placed using the
 * *exact* native 240x160 pixel coordinates read out of pokedex_plus_hgss.c (the same numbers
 * the HTML prototype used). This is scaled by ONE uniform factor - never stretched/warped -
 * so sprites, type icons and text always keep their real proportions. On a screen that isn't
 * exactly 3:2, the leftover margin is NOT black letterboxing: the caller is expected to give
 * this view a background that's the same repeating grid-paper tile used everywhere else in the
 * chrome (see activity_pokedex_detail.xml), so the extra space reads as "more of the same real
 * background", not empty bars - i.e. the screen is rearranged onto the real tileset, not
 * stretched onto it.
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

    /** Current native-px -> real-px uniform scale factor, valid after layout. */
    var nativeScale: Float = 1f
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
        val byWidth = availW.toFloat() / NATIVE_W
        val byHeight = if (availH > 0) availH.toFloat() / NATIVE_H else byWidth
        nativeScale = if (availH > 0) min(byWidth, byHeight) else byWidth

        val myWidth = (NATIVE_W * nativeScale).roundToInt()
        val myHeight = (NATIVE_H * nativeScale).roundToInt()
        setMeasuredDimension(myWidth, myHeight)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            if (lp.nativeTextSizePx > 0f && child is TextView) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, lp.nativeTextSizePx * nativeScale)
            }
            val childWidthSpec = MeasureSpec.makeMeasureSpec((lp.nativeWidth * nativeScale).roundToInt(), MeasureSpec.EXACTLY)
            val childHeightSpec = MeasureSpec.makeMeasureSpec((lp.nativeHeight * nativeScale).roundToInt(), MeasureSpec.EXACTLY)
            child.measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as? LayoutParams ?: continue
            val left = (lp.nativeLeft * nativeScale).roundToInt()
            val top = (lp.nativeTop * nativeScale).roundToInt()
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        LayoutParams(0, 0, NATIVE_W, NATIVE_H)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams
}
