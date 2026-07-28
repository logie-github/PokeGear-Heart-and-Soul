package com.logie.pgearhs.trainers

import android.content.Context

/** Whether the Call screen plays rematch calls through the in-game-style dialogue box. */
object TrainerCallPrefs {
    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_IN_GAME_TEXT = "trainer_call_in_game_text_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isInGameTextEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IN_GAME_TEXT, true)

    fun setInGameTextEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_IN_GAME_TEXT, enabled).apply()
    }
}
