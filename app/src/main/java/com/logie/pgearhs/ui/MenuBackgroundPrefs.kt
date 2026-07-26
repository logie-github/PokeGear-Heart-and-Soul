package com.logie.pgearhs.ui

import android.content.Context

object MenuBackgroundPrefs {
    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_MOVEMENT_PATTERN = "menu_dots_movement_pattern"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMovementPattern(context: Context): ScrollingTiledBackgroundView.MovementPattern {
        val name = prefs(context).getString(KEY_MOVEMENT_PATTERN, null)
        return ScrollingTiledBackgroundView.MovementPattern.entries.firstOrNull { it.name == name }
            ?: ScrollingTiledBackgroundView.MovementPattern.LEFT
    }

    fun setMovementPattern(context: Context, pattern: ScrollingTiledBackgroundView.MovementPattern) {
        prefs(context).edit().putString(KEY_MOVEMENT_PATTERN, pattern.name).apply()
    }
}
