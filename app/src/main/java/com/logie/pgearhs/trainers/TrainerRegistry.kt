package com.logie.pgearhs.trainers

import android.content.Context

/**
 * Persists which trainers have ever been read as defeated, so they stay reachable in the
 * Call list even after a rematch clears their live flag - the live flag alone can't tell
 * "never fought" apart from "fought, then reset for a rematch," and once you've got a
 * trainer's number you should be able to call them any time.
 */
object TrainerRegistry {

    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_EVER_DEFEATED = "trainer_ever_defeated_ids"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun everDefeatedIds(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY_EVER_DEFEATED, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    /** Merges [liveDefeatedIds] into the persisted set (never removes). Returns the new full set. */
    fun recordDefeated(context: Context, liveDefeatedIds: Set<Int>): Set<Int> {
        val merged = everDefeatedIds(context) + liveDefeatedIds
        prefs(context).edit()
            .putStringSet(KEY_EVER_DEFEATED, merged.map { it.toString() }.toSet())
            .apply()
        return merged
    }
}
