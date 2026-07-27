package com.logie.pgearhs.retroarch

/**
 * Reads the player's trainer name out of live memory over the RetroArch bridge.
 *
 * SaveBlock2 starts with `playerName[PLAYER_NAME_LENGTH + 1]` at offset 0x00 (see
 * PokedexMemoryCalibrator's doc comment for the struct layout and why 0x03003744 is a
 * high-confidence address for gSaveBlock2Ptr - this reuses the same known-pointer, no
 * further validation needed since the header bytes decode as expected text or they don't).
 */
class PlayerProfileBridge(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {
    companion object {
        private const val SAVEBLOCK2_PTR_ADDR = 0x03003744
        private const val PLAYER_NAME_LENGTH = 7

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE
    }

    /** Null if the name couldn't be read/decoded to anything usable. */
    suspend fun readPlayerName(): String? {
        val bridge = RetroArchMemoryBridge(host, port)
        if (!bridge.isReachable()) {
            onDiagnostic("Player name: RetroArch unreachable.")
            return null
        }

        val ptrBytes = bridge.readMemory(SAVEBLOCK2_PTR_ADDR, 4) ?: run {
            onDiagnostic("Player name: couldn't read gSaveBlock2Ptr.")
            return null
        }
        val saveBlock2Address = readU32LE(ptrBytes, 0)
        if (saveBlock2Address < EWRAM_BASE || saveBlock2Address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Player name: gSaveBlock2Ptr resolved outside EWRAM (0x${saveBlock2Address.toString(16)}).")
            return null
        }

        val nameBytes = bridge.readMemory(saveBlock2Address, PLAYER_NAME_LENGTH) ?: run {
            onDiagnostic("Player name: couldn't read playerName bytes.")
            return null
        }
        val name = Gen3TextDecoder.decode(nameBytes).trim()
        onDiagnostic("Player name: decoded \"$name\" from ${nameBytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }}")
        return name.takeIf { it.isNotBlank() }
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
