package com.logie.pgearhs.retroarch

/**
 * Holds the result of the last successful RetroArch live-sync calibration, in memory only
 * (not persisted - the calibrated address is only valid for the current RetroArch session).
 */
object LiveDexState {
    var isSynced: Boolean = false
        private set
    var nationalDexEnabled: Boolean = false
        private set

    private var owned: Set<Int> = emptySet()
    private var seen: Set<Int> = emptySet()
    var registeredCount: Int = 0
        private set

    fun applySyncResult(nationalEnabled: Boolean, ownedBytes: ByteArray, seenBytes: ByteArray) {
        nationalDexEnabled = nationalEnabled
        owned = extractSetBits(ownedBytes)
        seen = extractSetBits(seenBytes)
        registeredCount = owned.size
        isSynced = true
    }

    fun clear() {
        isSynced = false
        owned = emptySet()
        seen = emptySet()
        registeredCount = 0
    }

    /** True if not synced (nothing to filter by yet) or the species has been owned/seen. */
    fun isVisible(nationalDexNumber: Int): Boolean =
        !isSynced || owned.contains(nationalDexNumber) || seen.contains(nationalDexNumber)

    /** Bit n of a dex bitfield = species whose National Dex number is n+1. */
    private fun extractSetBits(bytes: ByteArray): Set<Int> {
        val result = mutableSetOf<Int>()
        for (byteIndex in bytes.indices) {
            val byteValue = bytes[byteIndex].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (byteValue and (1 shl bit) != 0) {
                    result.add(byteIndex * 8 + bit + 1)
                }
            }
        }
        return result
    }
}
