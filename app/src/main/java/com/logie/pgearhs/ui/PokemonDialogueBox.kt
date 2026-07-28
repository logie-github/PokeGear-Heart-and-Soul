package com.logie.pgearhs.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.logie.pgearhs.R

/**
 * Drives a fixed-size, bottom-anchored Gen3-style dialogue box (view_pokemon_dialogue_overlay.xml)
 * in place of a system AlertDialog - the box never resizes to fit content; instead, text is
 * paginated into screens of at most 2 lines (wrapped to the box's actual width via
 * StaticLayout, the same way the real games break text), advanced one screen at a time.
 *
 * Usage: [showText] with a list of message "beats" (each gets its own page, further split if
 * a single beat doesn't fit in 2 lines), then optionally [showYesNo] for a native Yes/No
 * prompt. [onAdvance]/[onNavigate] should be wired to the host Activity's key handling and/or
 * the box's own click listener so both touch and d-pad work.
 */
class PokemonDialogueBox(overlayRoot: View) {

    // overlayRoot IS the overlay's root view already - callers get it via
    // findViewById(R.id.dialogueOverlay), and <include android:id="..."> overrides the
    // included layout's own root id, so a *nested* findViewById(R.id.dialogueOverlayRoot)
    // from here would search for an id that no longer exists in this tree and return null,
    // crashing the instant a TextView field below tried to use it.
    private val root: View = overlayRoot
    private val dialogueBox: View = overlayRoot.findViewById(R.id.dialogueBox)
    private val dialogueText: TextView = overlayRoot.findViewById(R.id.dialogueText)
    private val nextIndicator: View = overlayRoot.findViewById(R.id.dialogueNextIndicator)
    private val yesNoBox: View = overlayRoot.findViewById(R.id.yesNoBox)
    private val yesOption: TextView = overlayRoot.findViewById(R.id.yesOption)
    private val noOption: TextView = overlayRoot.findViewById(R.id.noOption)

    private val selectedColor = ContextCompat.getColor(overlayRoot.context, R.color.dialogueBoxOptionSelected)
    private val unselectedColor = ContextCompat.getColor(overlayRoot.context, R.color.dialogueBoxText)

    private enum class Mode { HIDDEN, TEXT, YES_NO }

    private var mode = Mode.HIDDEN
    private var pages: List<String> = emptyList()
    private var pageIndex = 0
    private var onTextFinished: (() -> Unit)? = null
    private var onYesNoChosen: ((Boolean) -> Unit)? = null
    private var yesSelected = true
    private var blinkAnimator: ValueAnimator? = null

    val isVisible: Boolean get() = mode != Mode.HIDDEN

    /**
     * Fires whenever [isVisible] changes. The overlay covers the whole screen and swallows
     * touches while visible, but the trainer list *underneath* it can still hold d-pad focus
     * and intercept DPAD_CENTER before this box's own key handling ever sees it, re-triggering
     * a list item's click mid-call and clobbering whichever trainer's dialogue was showing -
     * hosts should block the list's focusability while this is true (see TrainerCallActivity).
     */
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    init {
        root.setOnClickListener { onAdvance() }
        dialogueBox.setOnClickListener { onAdvance() }
    }

    /** Shows [messages] one "beat" at a time (each wrapped/paginated to fit 2 lines), then calls [onFinished]. */
    fun showText(messages: List<String>, onFinished: () -> Unit) {
        pages = messages.flatMap { paginate(it) }.ifEmpty { listOf("") }
        pageIndex = 0
        onTextFinished = onFinished
        setMode(Mode.TEXT)
        root.visibility = View.VISIBLE
        dialogueBox.visibility = View.VISIBLE
        yesNoBox.visibility = View.GONE
        dialogueBox.requestFocus()
        renderPage()
    }

    /** Shows the Yes/No prompt over the dialogue box; [onChosen] fires with true for Yes. */
    fun showYesNo(onChosen: (Boolean) -> Unit) {
        setMode(Mode.YES_NO)
        onYesNoChosen = onChosen
        yesSelected = true
        nextIndicator.visibility = View.INVISIBLE
        stopBlink()
        yesNoBox.visibility = View.VISIBLE
        root.requestFocus()
        updateYesNoHighlight()
    }

    fun hide() {
        setMode(Mode.HIDDEN)
        stopBlink()
        root.visibility = View.GONE
        dialogueBox.visibility = View.GONE
        yesNoBox.visibility = View.GONE
    }

    private fun setMode(newMode: Mode) {
        val wasVisible = isVisible
        mode = newMode
        if (wasVisible != isVisible) onVisibilityChanged?.invoke(isVisible)
    }

    /** Wire to A/DPAD_CENTER/ENTER and to touch taps on the box. */
    fun onAdvance() {
        when (mode) {
            Mode.TEXT -> {
                if (pageIndex < pages.size - 1) {
                    pageIndex++
                    renderPage()
                } else {
                    val callback = onTextFinished
                    onTextFinished = null
                    callback?.invoke()
                }
            }
            Mode.YES_NO -> {
                val callback = onYesNoChosen
                onYesNoChosen = null
                yesNoBox.visibility = View.GONE
                callback?.invoke(yesSelected)
            }
            Mode.HIDDEN -> Unit
        }
    }

    /** Wire to DPAD_UP ([delta]=-1) / DPAD_DOWN ([delta]=+1) - only affects the Yes/No prompt. */
    fun onNavigate(delta: Int) {
        if (mode != Mode.YES_NO) return
        if (delta != 0) yesSelected = !yesSelected
        updateYesNoHighlight()
    }

    private fun renderPage() {
        dialogueText.text = pages[pageIndex]
        val hasMore = pageIndex < pages.size - 1
        if (hasMore) startBlink() else stopBlink()
    }

    private fun updateYesNoHighlight() {
        yesOption.setTextColor(if (yesSelected) selectedColor else unselectedColor)
        noOption.setTextColor(if (!yesSelected) selectedColor else unselectedColor)
    }

    private fun startBlink() {
        if (blinkAnimator != null) return
        nextIndicator.visibility = View.VISIBLE
        nextIndicator.alpha = 1f
        blinkAnimator = ObjectAnimator.ofFloat(nextIndicator, View.ALPHA, 1f, 0f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        nextIndicator.visibility = View.INVISIBLE
        nextIndicator.alpha = 1f
    }

    /** Word-wraps [message] to the box's actual text width, then groups every 2 lines into a page. */
    private fun paginate(message: String): List<String> {
        // dialogueText.width is 0 the first time this runs, since a View.GONE view isn't
        // laid out until the visibility change takes effect on the next pass - fall back to
        // the same math the layout XML uses (80% of screen width, minus dialogueText's own
        // margins+padding) so the very first page wraps the same as every later one.
        val width = (dialogueText.width.takeIf { it > 0 } ?: run {
            val density = dialogueText.resources.displayMetrics.density
            val screenWidthPx = dialogueText.resources.displayMetrics.widthPixels
            val horizontalInsetPx = (8 + 40) * density // 4dp+4dp margins, 20dp+20dp padding
            (screenWidthPx * 0.8f - horizontalInsetPx).toInt()
        }).coerceAtLeast(1)
        val paint = TextPaint(dialogueText.paint)

        val layout = StaticLayout.Builder
            .obtain(message, 0, message.length, paint, width)
            .setLineSpacing(0f, 1f)
            .build()

        val lines = (0 until layout.lineCount).map { i ->
            message.substring(layout.getLineStart(i), layout.getLineEnd(i)).trimEnd()
        }
        return lines.chunked(2).map { it.joinToString("\n") }
    }
}
