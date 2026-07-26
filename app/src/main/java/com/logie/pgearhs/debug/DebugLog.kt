package com.logie.pgearhs.debug

/**
 * In-memory rotating diagnostic log, shared app-wide as a singleton. Entries are plain
 * strings (prefix with "!" for errors, matching the convention used elsewhere in this
 * log) - no timestamp/tag, no disk persistence. Capped at 300 entries, oldest dropped
 * first.
 */
object DebugLog {
    private const val MAX_ENTRIES = 300

    private val entries = mutableListOf<String>()

    @Synchronized
    fun add(message: String) {
        entries += message
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()
}
