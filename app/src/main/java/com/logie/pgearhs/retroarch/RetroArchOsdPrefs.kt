package com.logie.pgearhs.retroarch

import android.content.Context

/**
 * Whether battle state (entered/won/money-won) gets pushed to RetroArch's own on-screen
 * notification queue (SHOW_MSG) as it's detected - a debugging aid so money-detection
 * accuracy can be watched live in the emulator itself, not just dug out of a debug report
 * after the fact.
 */
object RetroArchOsdPrefs {
    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_BATTLE_OSD_NOTICES = "retroarch_osd_battle_notices_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBattleOsdEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTLE_OSD_NOTICES, true)

    fun setBattleOsdEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BATTLE_OSD_NOTICES, enabled).apply()
    }
}
