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

    /**
     * [verbose] controls the expensive wide-window hex dumps (3 extra reads + 3 log lines) -
     * callers polling frequently should only ask for these occasionally (a heartbeat, or on
     * a state transition), or DebugLog's 300-entry cap fills before a real test (walk to a
     * trainer, fight, win) finishes.
     */
    suspend fun readState(verbose: Boolean = true): BattleState? {
        val bridge = RetroArchMemoryBridge(host, port)
        if (!bridge.isReachable()) return null

        // Wider window than just the single bitfield byte - these three addresses have
        // never been confirmed against a live device, so if the interpreted values below
        // look wrong, this gives enough surrounding bytes to re-derive the real offsets the
        // same way flags[]'s offset was found.
        val gMainWindow = bridge.readMemory(GMAIN_ADDR + IN_BATTLE_BYTE_OFFSET - 8, 24)
        if (verbose) {
            onDiagnostic(
                "Battle state: gMain+0x${IN_BATTLE_BYTE_OFFSET.toString(16)} window (8 bytes before) = " +
                    (gMainWindow?.hexDump() ?: "read failed")
            )
        }

        val inBattleByte = gMainWindow?.getOrNull(8)?.toInt()?.and(0xFF) ?: run {
            onDiagnostic("Battle state: couldn't read gMain.inBattle.")
            return null
        }
        val inBattle = (inBattleByte shr IN_BATTLE_BIT) and 1 == 1

        val outcomeWindow = bridge.readMemory(BATTLE_OUTCOME_ADDR - 4, 12)
        if (verbose) {
            onDiagnostic("Battle state: gBattleOutcome window (4 bytes before) = ${outcomeWindow?.hexDump() ?: "read failed"}")
        }
        val outcome = outcomeWindow?.getOrNull(4)?.toInt()?.and(0xFF) ?: run {
            onDiagnostic("Battle state: couldn't read gBattleOutcome.")
            return null
        }

        val money = resolveSaveBlock1Address(bridge)?.let { saveBlock1Address ->
            val moneyAddress = saveBlock1Address + MONEY_OFFSET_IN_SAVEBLOCK1
            val moneyWindow = bridge.readMemory(moneyAddress - 16, 48)
            if (verbose) {
                onDiagnostic("Battle state: money window @0x${moneyAddress.toString(16)} (16 bytes before) = ${moneyWindow?.hexDump() ?: "read failed"}")
            }
            moneyWindow?.let { readU32LE(it, 16) }
        }

        return BattleState(inBattle, outcome, money)
    }

    private fun ByteArray.hexDump(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

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
