package com.logie.pgearhs.retroarch

/**
 * Reads/writes trainer "defeated" flags over the RetroArch memory bridge.
 *
 * Every trainer battle is gated by one bit in gSaveBlock1Ptr->flags[], at flag id
 * TRAINER_FLAGS_START(0x500) + trainerId (src/event_data.c GetFlagPointer/FlagClear in
 * the pokemonHnS-v121 source). gSaveBlock1Ptr's own address (0x03003740, IWRAM) is
 * high-confidence - reproduced across independent from-source rebuilds, same as
 * gSaveBlock2Ptr in PokedexMemoryCalibrator.
 *
 * The flags[] offset within SaveBlock1 is 0x1364, NOT the 0x1270 include/global.h's own
 * comment claims - confirmed 2026-07-27 via calibrateFlagsOffset() against 11 known
 * true/false trainers (Falkner/Joey/the full Sprout Tower roster actually defeated; Kate
 * and the Battle Frontier "brain" Spenser were not, despite showing as defeated at 0x1270).
 * Exactly one candidate offset in a +/-512 byte search satisfied all 11, so this is a very
 * high-confidence fix, not a guess - same story as the Pokedex owned[] offset: this hack's
 * struct comments silently drift from the real compiled layout whenever custom fields get
 * inserted upstream without the comment being updated.
 */
class TrainerFlagsBridge(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {
    companion object {
        private const val SAVEBLOCK1_PTR_ADDR = 0x03003740
        private const val FLAGS_OFFSET_IN_SAVEBLOCK1 = 0x1364
        private const val TRAINER_FLAGS_START = 0x500
        private const val PLAYER_PARTY_COUNT_OFFSET = 0x234

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE

        /**
         * Some trainers are gated behind a second, dedicated flag on top of the generic
         * per-trainer one above - mainly gym leaders and Elite Four members, whose map
         * scripts check `goto_if_set FLAG_DEFEATED_<GYM>, ...Defeated` *before* ever
         * reaching the trainerbattle call that checks the per-trainer flag. Clearing only
         * the per-trainer flag leaves the script routing straight to the "already beaten"
         * dialogue without a fight - confirmed 2026-07-27 when Falkner's reset reported
         * success but didn't actually let him be rematched. Extracted from every map's
         * scripts.inc in pokemonHnS-v121: every `goto_if_set FLAG_DEFEATED_...` line
         * whose target block also contains a `trainerbattle` call for the same trainer
         * (flag values from include/constants/flags.h; these are ordinary sub-0x4000
         * flags, so they live in the same flags[] array as the per-trainer ones).
         *
         * Not exhaustive: multi-tier gyms whose flag check and battle trigger live in
         * separate script blocks (e.g. Chuck/Pryce/Jasmine's interlocking 3-gym rotation)
         * weren't resolvable by this static, single-block extraction and aren't covered.
         */
        private val MILESTONE_FLAG_BY_TRAINER_ID = mapOf(
            19 to 0x4f0,  // Falkner (TRAINER_FALKNER_1) -> FLAG_DEFEATED_VIOLET_GYM
            116 to 0xbf,  // Wai (TRAINER_WAI) -> FLAG_DEFEATED_GRUNT_SPACE_CENTER_1F
            261 to 0x4fb, // Sidney (TRAINER_SIDNEY) -> FLAG_DEFEATED_ELITE_4_WILL
            262 to 0x4fc, // Phoebe (TRAINER_PHOEBE) -> FLAG_DEFEATED_ELITE_4_KOGA
            263 to 0x4fd, // Glacia (TRAINER_GLACIA) -> FLAG_DEFEATED_ELITE_4_BRUNO
            264 to 0x4fe, // Kip/Drake (TRAINER_KIP) -> FLAG_DEFEATED_ELITE_4_KAREN
            302 to 0x26f, // Lt. Surge (TRAINER_LTSURGE) -> FLAG_DEFEATED_VERMILION_GYM
            303 to 0x270, // Erika (TRAINER_ERIKA) -> FLAG_DEFEATED_CELADON_GYM
            304 to 0x271, // Sabrina (TRAINER_SABRINA) -> FLAG_DEFEATED_SAFFRON_GYM
            305 to 0x272, // Janine (TRAINER_JANINE) -> FLAG_DEFEATED_FUCHSIA_GYM
            306 to 0x273, // Blaine (TRAINER_BLAINE) -> FLAG_DEFEATED_CINNABAR_ISLAND_GYM
            543 to 0x26d, // Brock (TRAINER_BROCK) -> FLAG_DEFEATED_PEWTER_GYM
            544 to 0x26e, // Misty (TRAINER_MISTY) -> FLAG_DEFEATED_CERULEAN_GYM
            595 to 0x274, // Blue (TRAINER_BLUE) -> FLAG_DEFEATED_VIRIDIAN_GYM
            596 to 0x4f1, // Bugsy (TRAINER_BUGSY_1) -> FLAG_DEFEATED_AZALEA_TOWN_GYM
            604 to 0x4f2, // Whitney (TRAINER_WHITNEY_1) -> FLAG_DEFEATED_GOLDENROD_CITY_GYM
            608 to 0x4f3, // Morty (TRAINER_MORTY_1) -> FLAG_DEFEATED_ECRUTEAK_CITY_GYM
            804 to 0x4f8  // Steven (TRAINER_STEVEN) -> FLAG_DEFEATED_RED
        )
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
        onDiagnostic("Trainer flags: defeated ids = ${defeated.sorted()}")
        onDiagnostic(
            "Trainer flags: raw dump @0x${(saveBlock1Address + FLAGS_OFFSET_IN_SAVEBLOCK1).toString(16)} " +
                "(${flagsBlock.size} bytes) = " +
                flagsBlock.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        )
        return ReadResult.Success(defeated)
    }

    /**
     * Clears trainerId's defeated flag so the game treats them as never fought, plus their
     * milestone flag if they have one (see [MILESTONE_FLAG_BY_TRAINER_ID] - required for
     * gym leaders/Elite Four to actually be rematchable, not just for the per-trainer flag
     * to read clear). Returns true only if every flag that needed clearing was confirmed.
     */
    suspend fun resetTrainerFlag(trainerId: Int): Boolean {
        val bridge = RetroArchMemoryBridge(host, port)
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return false

        val flagId = TRAINER_FLAGS_START + trainerId
        var success = clearFlagBit(bridge, saveBlock1Address, flagId, "trainer flag for id=$trainerId")

        MILESTONE_FLAG_BY_TRAINER_ID[trainerId]?.let { milestoneFlagId ->
            val milestoneOk = clearFlagBit(bridge, saveBlock1Address, milestoneFlagId, "milestone flag for id=$trainerId")
            success = success && milestoneOk
        }

        return success
    }

    private fun clearFlagBit(bridge: RetroArchMemoryBridge, saveBlock1Address: Int, flagId: Int, label: String): Boolean {
        val byteAddress = saveBlock1Address + FLAGS_OFFSET_IN_SAVEBLOCK1 + (flagId / 8)
        val bit = flagId and 7

        val currentByte = bridge.readMemory(byteAddress, 1)?.getOrNull(0)?.toInt()?.and(0xFF) ?: run {
            onDiagnostic("! Reset failed for $label: couldn't read flag byte.")
            return false
        }
        val clearedByte = currentByte and (1 shl bit).inv() and 0xFF

        onDiagnostic(
            "Reset $label: flagId=0x${flagId.toString(16)} " +
                "byte@0x${byteAddress.toString(16)} 0x${currentByte.toString(16)} -> 0x${clearedByte.toString(16)}"
        )
        if (!bridge.writeMemory(byteAddress, byteArrayOf(clearedByte.toByte()))) {
            onDiagnostic("! Reset for $label: RetroArch did not confirm the write.")
            return false
        }

        // Don't just trust the write confirmation - read the byte back, since a bug here
        // (wrong offset, wrong bit) would otherwise report false success. See the
        // RetroArchMemoryBridge.writeMemory doc comment for why the confirmation alone
        // isn't proof either.
        val verifyByte = bridge.readMemory(byteAddress, 1)?.getOrNull(0)?.toInt()?.and(0xFF)
        val verified = verifyByte == clearedByte
        onDiagnostic(
            if (verified) "Reset for $label verified (byte now 0x${verifyByte?.toString(16)})."
            else "! Reset for $label did not verify - byte reads 0x${verifyByte?.toString(16) ?: "?"}, expected 0x${clearedByte.toString(16)}."
        )
        return verified
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
