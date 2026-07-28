package com.logie.pgearhs.ui

import android.view.KeyEvent
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.logie.pgearhs.R

/**
 * The Thor is a dedicated handheld, not a phone - the app should own the
 * full display rather than leaving the system status/navigation bars
 * (which otherwise render as an opaque black strip) on screen.
 *
 * Also gives every activity a shared Gen3-style dialogue box, attached over whatever layout
 * setContentView() loads. Both an activity's own flows (TrainerCallActivity's rematch calls)
 * and background events (GlobalDialogueNotices - e.g. the Mom money transfer, which can fire
 * while any screen is open) go through this one overlay per screen, instead of activities
 * each maintaining a separate copy. Subclasses that add their own onKeyDown handling must
 * call super.onKeyDown() *first* so the dialogue box gets first refusal on DPAD/A input
 * while it's showing.
 */
abstract class BaseImmersiveActivity : AppCompatActivity() {

    private var _dialogueBox: PokemonDialogueBox? = null

    /** Only valid after setContentView() has run. */
    protected val dialogueBox: PokemonDialogueBox
        get() = _dialogueBox ?: error("dialogueBox accessed before setContentView()")

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        val contentRoot = findViewById<ViewGroup>(android.R.id.content)
        val overlayView = layoutInflater.inflate(R.layout.view_pokemon_dialogue_overlay, contentRoot, false)
        contentRoot.addView(overlayView)
        _dialogueBox = PokemonDialogueBox(overlayView)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        GlobalDialogueNotices.register { lines ->
            if (dialogueBox.isVisible) {
                false
            } else {
                dialogueBox.showText(lines) { dialogueBox.hide() }
                true
            }
        }
    }

    override fun onPause() {
        GlobalDialogueNotices.unregister()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (dialogueBox.isVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> {
                    dialogueBox.onAdvance()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dialogueBox.onNavigate(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
