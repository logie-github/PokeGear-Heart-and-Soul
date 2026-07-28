package com.logie.pgearhs.retroarch

/**
 * Reads live battle state over the RetroArch bridge: whether a battle is in progress, its
 * outcome once it ends, and the player's current money (for diffing before/after a win,
 * since this hack doesn't persist "last battle's prize money" anywhere readable - see
 * BattleMoneyTracker).
 *
 * gMain and gBattleOutcome are plain fixed globals (not pointers, unlike the SaveBlock
 * structs) - their addresses come straight from pokemonHnS-v121's own build (pokemonHnS.map,
 * the same verified rebuild gSaveBlock1Ptr/gSaveBlock2Ptr came from), so this carries the
 * same confidence tier those had *before* they were confirmed against live device data:
 * plausible, reproducible, but not yet proven against an actual running game.
 *   gBattleOutcome @ 0x020016f0 (EWRAM)      - include/battle.h, values in constants/battle.h
 *   gMain          @ 0x03001574 (IWRAM)      - include/main.h; inBattle is bit 1 of +0x439
 * money's offset within SaveBlock1 is hypothesized at 0x584 (documented as 0x490, +0xF4 -
 * the same delta already found for flags[]/trainerRematches[], both also downstream of
 * playerParty[] - unverified specifically for money, same caveat as those two.
 */
class BattleStateBridge(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {
    companion object {
        private const val GMAIN_ADDR = 0x03001574
        private const val IN_BATTLE_BYTE_OFFSET = 0x439
        private const val IN_BATTLE_BIT = 1

        private const val BATTLE_OUTCOME_ADDR = 0x020016f0
        const val OUTCOME_WON = 1

        private const val SAVEBLOCK1_PTR_ADDR = 0x03003740
        private const val MONEY_OFFSET_IN_SAVEBLOCK1 = 0x584

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE
    }

    data class BattleState(val inBattle: Boolean, val outcome: Int, val money: Int?)

    suspend fun readState(): BattleState? {
        val bridge = RetroArchMemoryBridge(host, port)
        if (!bridge.isReachable()) return null

        val inBattleByte = bridge.readMemory(GMAIN_ADDR + IN_BATTLE_BYTE_OFFSET, 1)
            ?.getOrNull(0)?.toInt()?.and(0xFF) ?: run {
            onDiagnostic("Battle state: couldn't read gMain.inBattle.")
            return null
        }
        val inBattle = (inBattleByte shr IN_BATTLE_BIT) and 1 == 1

        val outcome = bridge.readMemory(BATTLE_OUTCOME_ADDR, 1)?.getOrNull(0)?.toInt()?.and(0xFF) ?: run {
            onDiagnostic("Battle state: couldn't read gBattleOutcome.")
            return null
        }

        val money = resolveSaveBlock1Address(bridge)?.let { saveBlock1Address ->
            bridge.readMemory(saveBlock1Address + MONEY_OFFSET_IN_SAVEBLOCK1, 4)?.let { readU32LE(it, 0) }
        }

        return BattleState(inBattle, outcome, money)
    }

    /** Overwrites the player's money with [newMoney]. Returns true only once read back and confirmed. */
    suspend fun writeMoney(newMoney: Int): Boolean {
        val bridge = RetroArchMemoryBridge(host, port)
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return false
        val moneyAddress = saveBlock1Address + MONEY_OFFSET_IN_SAVEBLOCK1

        val bytes = byteArrayOf(
            (newMoney and 0xFF).toByte(),
            ((newMoney shr 8) and 0xFF).toByte(),
            ((newMoney shr 16) and 0xFF).toByte(),
            ((newMoney shr 24) and 0xFF).toByte()
        )
        if (!bridge.writeMemory(moneyAddress, bytes)) {
            onDiagnostic("! Money write to 0x${moneyAddress.toString(16)} not confirmed by RetroArch.")
            return false
        }

        val verified = bridge.readMemory(moneyAddress, 4)?.let { readU32LE(it, 0) }
        val ok = verified == newMoney
        onDiagnostic(
            if (ok) "Money write verified (now $verified)."
            else "! Money write did not verify - reads $verified, expected $newMoney."
        )
        return ok
    }

    private fun resolveSaveBlock1Address(bridge: RetroArchMemoryBridge): Int? {
        val ptrBytes = bridge.readMemory(SAVEBLOCK1_PTR_ADDR, 4) ?: return null
        val address = readU32LE(ptrBytes, 0)
        if (address < EWRAM_BASE || address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Battle state: gSaveBlock1Ptr resolved outside EWRAM (0x${address.toString(16)}).")
            return null
        }
        return address
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
