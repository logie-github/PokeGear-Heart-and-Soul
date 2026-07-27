package com.logie.pgearhs.retroarch

/**
 * Reads/writes trainer "defeated" flags over the RetroArch memory bridge.
 *
 * Every trainer battle is gated by one bit in gSaveBlock1Ptr->flags[], at flag id
 * TRAINER_FLAGS_START(0x500) + trainerId (src/event_data.c GetFlagPointer/FlagClear in
 * the pokemonHnS-v121 source). gSaveBlock1Ptr's own address (0x03003740, IWRAM) is
 * high-confidence - reproduced across independent from-source rebuilds, same as
 * gSaveBlock2Ptr in PokedexMemoryCalibrator. The flags[] offset within SaveBlock1
 * (0x1270) is NOT independently verified against live memory the way the Pokedex
 * offset eventually was - it's this hack's own include/global.h comment, and that file's
 * comments already proved unreliable once (the Pokedex owned[] struct silently drifted
 * 4 bytes from its documented offset because of an upstream custom field the comment
 * never accounted for). [readDefeatedTrainerIds] sanity-checks the resolved SaveBlock1
 * address via playerPartyCount (a field before any of this hack's custom insertions),
 * but that only confirms the pointer, not the flags[] offset itself - if the defeated
 * list looks wrong on a real device, that offset is the first thing to re-derive.
 */
class TrainerFlagsBridge(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {
    companion object {
        private const val SAVEBLOCK1_PTR_ADDR = 0x03003740
        private const val FLAGS_OFFSET_IN_SAVEBLOCK1 = 0x1270
        private const val TRAINER_FLAGS_START = 0x500
        private const val PLAYER_PARTY_COUNT_OFFSET = 0x234

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE
    }

    sealed class ReadResult {
        data class Success(val defeatedTrainerIds: Set<Int>) : ReadResult()
        data class Failure(val reason: String) : ReadResult()
    }

    private var cachedSaveBlock1Address: Int? = null

    /** Which of [knownTrainerIds] currently have their defeated flag set. */
    suspend fun readDefeatedTrainerIds(knownTrainerIds: List<Int>): ReadResult {
        val bridge = RetroArchMemoryBridge(host, port)
        if (!bridge.isReachable()) {
            return ReadResult.Failure("Can't reach RetroArch - check host/port and that it's running.")
        }
        if (knownTrainerIds.isEmpty()) return ReadResult.Success(emptySet())

        val saveBlock1Address = resolveSaveBlock1Address(bridge)
            ?: return ReadResult.Failure("Could not locate save data in memory.")

        val maxFlagId = TRAINER_FLAGS_START + knownTrainerIds.max()
        val flagBytesNeeded = (maxFlagId / 8) + 1
        val flagsBlock = bridge.readRegion(saveBlock1Address + FLAGS_OFFSET_IN_SAVEBLOCK1, flagBytesNeeded)
            ?: return ReadResult.Failure("Could not read trainer flag data.")

        val defeated = knownTrainerIds.filterTo(mutableSetOf()) { trainerId ->
            val flagId = TRAINER_FLAGS_START + trainerId
            val byte = flagsBlock[flagId / 8].toInt() and 0xFF
            (byte shr (flagId % 8)) and 1 == 1
        }

        onDiagnostic("Trainer flags: ${defeated.size} of ${knownTrainerIds.size} known trainers read as defeated.")
        return ReadResult.Success(defeated)
    }

    /** Clears trainerId's defeated flag so the game treats them as never fought. Returns success. */
    suspend fun resetTrainerFlag(trainerId: Int): Boolean {
        val bridge = RetroArchMemoryBridge(host, port)
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return false

        val flagId = TRAINER_FLAGS_START + trainerId
        val byteAddress = saveBlock1Address + FLAGS_OFFSET_IN_SAVEBLOCK1 + (flagId / 8)
        val bit = flagId and 7

        val currentByte = bridge.readMemory(byteAddress, 1)?.getOrNull(0)?.toInt()?.and(0xFF) ?: run {
            onDiagnostic("! Trainer flag reset failed: couldn't read flag byte for trainer $trainerId.")
            return false
        }
        val clearedByte = currentByte and (1 shl bit).inv() and 0xFF

        onDiagnostic(
            "Trainer flag reset: id=$trainerId flagId=0x${flagId.toString(16)} " +
                "byte@0x${byteAddress.toString(16)} 0x${currentByte.toString(16)} -> 0x${clearedByte.toString(16)}"
        )
        return bridge.writeMemory(byteAddress, byteArrayOf(clearedByte.toByte()))
    }

    private fun resolveSaveBlock1Address(bridge: RetroArchMemoryBridge): Int? {
        cachedSaveBlock1Address?.let { return it }

        val ptrBytes = bridge.readMemory(SAVEBLOCK1_PTR_ADDR, 4) ?: run {
            onDiagnostic("Trainer flags: couldn't read gSaveBlock1Ptr.")
            return null
        }
        val address = readU32LE(ptrBytes, 0)
        if (address < EWRAM_BASE || address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Trainer flags: gSaveBlock1Ptr resolved outside EWRAM (0x${address.toString(16)}).")
            return null
        }

        // playerPartyCount sits near SaveBlock1's start, ahead of anything this hack is
        // known to have inserted - a plausible 1..6 here confirms the pointer itself,
        // though not the flags[] offset (see class doc comment).
        val partyCount = bridge.readMemory(address + PLAYER_PARTY_COUNT_OFFSET, 1)?.getOrNull(0)?.toInt()?.and(0xFF)
        onDiagnostic("Trainer flags: gSaveBlock1Ptr = 0x${address.toString(16)}, playerPartyCount=$partyCount")
        if (partyCount == null || partyCount !in 1..6) {
            onDiagnostic("Trainer flags: playerPartyCount implausible - SaveBlock1 address may be wrong.")
            return null
        }

        cachedSaveBlock1Address = address
        return address
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
