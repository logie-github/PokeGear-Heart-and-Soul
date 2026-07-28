package com.logie.pgearhs.ui

import android.content.Context
import android.widget.Toast
import com.logie.pgearhs.trainers.TrainerCallPrefs

/**
 * Lets headless background singletons (BattleMoneyTracker, etc.) show a message through
 * whichever activity currently happens to be on screen, without needing a reference to any
 * specific Activity - each BaseImmersiveActivity registers/unregisters itself here in
 * onResume/onPause.
 */
object GlobalDialogueNotices {

    /** Returns true if it displayed the notice, false if it couldn't (e.g. already showing something). */
    private var listener: ((List<String>) -> Boolean)? = null

    fun register(onNotice: (List<String>) -> Boolean) {
        listener = onNotice
    }

    fun unregister() {
        listener = null
    }

    /** Shows [lines] via the foreground activity's dialogue box, or falls back to a Toast. */
    fun notify(context: Context, lines: List<String>) {
        val shown = listener?.takeIf { TrainerCallPrefs.isInGameTextEnabled(context) }?.invoke(lines) ?: false
        if (!shown) {
            Toast.makeText(context, lines.joinToString(" "), Toast.LENGTH_LONG).show()
        }
    }
}
