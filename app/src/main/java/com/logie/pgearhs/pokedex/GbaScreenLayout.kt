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
 * so sprites, type icons and text always keep their real proportions.
 *
 * The real chrome's text (`.txt` in the HTML prototype it was ported from) is CSS
 * `white-space:nowrap` with no fixed width - it's anchored at a left/top point and grows
 * naturally to whatever width its content needs. [WRAP] reproduces that: pass it as
 * nativeWidth/nativeHeight to size a child to its natural content size instead of forcing
 * an exact box, which is what real single-line labels (name, category, HT/WT, tab labels)
 * want. Only elements the real chrome actually constrains (type icons, the description
 * paragraph, right-aligned stat values) should get an explicit fixed size.
 *
 * On a screen that isn't exactly 3:2, the leftover margin is NOT black letterboxing: the
 * caller is expected to give this view a background that matches the rest of the chrome
 * (see activity_pokedex_detail.xml), so the extra space reads as more of the real background,
 * not empty bars.
 */
class GbaScreenLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    companion object {
        const val NATIVE_W = 240
        const val NATIVE_H = 160
        const val WRAP = -1
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

    /**
     * Adds a child positioned at exact native (240x160) coordinates. Pass [WRAP] for [w]/[h]
     * to size that axis to the child's natural content size (matching the real chrome's
     * unconstrained single-line text) instead of a forced exact box.
     */
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
            val widthSpec = if (lp.nativeWidth == WRAP)
                MeasureSpec.makeMeasureSpec(myWidth - (lp.nativeLeft * nativeScale).roundToInt(), MeasureSpec.AT_MOST)
            else
                MeasureSpec.makeMeasureSpec((lp.nativeWidth * nativeScale).roundToInt(), MeasureSpec.EXACTLY)
            val heightSpec = if (lp.nativeHeight == WRAP)
                MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.AT_MOST)
            else
                MeasureSpec.makeMeasureSpec((lp.nativeHeight * nativeScale).roundToInt(), MeasureSpec.EXACTLY)
            child.measure(widthSpec, heightSpec)
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
