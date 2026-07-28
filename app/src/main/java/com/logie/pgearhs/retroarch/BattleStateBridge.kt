package com.logie.pgearhs.retroarch

/**
 * Reads live battle state over the RetroArch bridge: whether a battle is in progress, its
 * outcome once it ends, and the player's current money (for diffing before/after a win,
 * since this hack doesn't persist "last battle's prize money" anywhere readable - see
 * BattleMoneyTracker).
 *
 * gMain and gBattleOutcome are plain fixed globals (not pointers, unlike the SaveBlock
 * structs) - their addresses come straight from pokemonHnS-v121's own build (pokemonHnS.map,
 * the same verified rebuild gSaveBlock1Ptr/gSaveBlock2Ptr came from).
 *   gMain          @ 0x03001574 (IWRAM)      - include/main.h; inBattle is bit 1 of +0x439
 *     CONFIRMED against a live debug report (issue #30, v1.0.40): the bit flips 1->0 exactly
 *     when the summary log's own inBattle transitions true->false.
 *   gBattleOutcome @ 0x020016f4 (EWRAM)      - include/battle.h, values in constants/battle.h
 *     The doc comment says 0x020016f0, but that address never once read anything but 0
 *     across a full real battle+win in issue #30 - the byte that actually settles to 1
 *     (OUTCOME_WON) and *stays* 1 after the battle truly ends is 4 bytes later, at
 *     0x020016f4. Corrected here from that live data, not from source.
 *
 * money is NOT a plain scalar read - `src/money.c` XORs it against
 * `gSaveBlock2Ptr->encryptionKey` on every access (`GetMoney`/`SetMoney`), the same pattern
 * used for coins/item quantities/berry powder in this codebase - a raw read without XOR-ing
 * out the key will never look like a real balance. On top of that, `MONEY_OFFSET_IN_SAVEBLOCK1`
 * (hypothesized 0x584, i.e. documented 0x490 + the same +0xF4 delta found for flags[]) is
 * ALSO still unconfirmed, and issue #30's raw dump at that address shows a suspicious,
 * tightly-repeating 4-byte pattern across 44+ bytes - not what a single scalar field
 * surrounded by unrelated struct fields should look like. Given both are unverified, `readState()`
 * does NOT trust the interpreted `money`/`moneyOffsetConfirmed=false` value for anything beyond
 * diagnostics yet, and `writeMoney()` refuses to run at all (see its doc comment) - writing to
 * an unconfirmed offset risks corrupting some other, unrelated save field instead of money.
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

        private const val BATTLE_OUTCOME_ADDR = 0x020016f4
        const val OUTCOME_WON = 1

        private const val SAVEBLOCK1_PTR_ADDR = 0x03003740
        private const val SAVEBLOCK2_PTR_ADDR = 0x03003744
        private const val MONEY_OFFSET_IN_SAVEBLOCK1 = 0x584
        private const val ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2 = 0xAC

        // Not yet confirmed against real ground truth (a known displayed in-game money
        // amount) - see the class doc comment. Flips to true only once that's done.
        const val MONEY_OFFSET_CONFIRMED = false

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

        // The money offset/encryption are unconfirmed (see class doc comment) - only when
        // out of battle (gSaveBlock1Ptr resolves to something different mid-battle, per
        // issue #30) dump a wide window plus every candidate money reading (raw and
        // XOR-decrypted against encryptionKey) so a future debug report can be matched
        // against a known real in-game money amount without more guesswork.
        if (verbose && !inBattle) {
            dumpMoneyCalibrationWindow(bridge)
        }

        return BattleState(inBattle, outcome, money)
    }

    private suspend fun dumpMoneyCalibrationWindow(bridge: RetroArchMemoryBridge) {
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return
        val saveBlock2Address = resolveSaveBlock2Address(bridge)
        val encryptionKeyBytes = saveBlock2Address?.let {
            bridge.readMemory(it + ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2, 4)
        }
        val encryptionKey = encryptionKeyBytes?.let { readU32LE(it, 0) }
        onDiagnostic(
            if (encryptionKey != null) "Money calibration: encryptionKey = 0x${encryptionKey.toString(16)}"
            else "Money calibration: couldn't read encryptionKey (gSaveBlock2Ptr=${saveBlock2Address?.let { "0x${it.toString(16)}" } ?: "unresolved"})."
        )

        val windowStart = MONEY_OFFSET_IN_SAVEBLOCK1 - 0x100
        val windowSize = 0x200
        val window = bridge.readMemory(saveBlock1Address + windowStart, windowSize)
        if (window == null) {
            onDiagnostic("Money calibration: window read failed.")
            return
        }
        onDiagnostic(
            "Money calibration: SaveBlock1+0x${windowStart.toString(16)}..0x${(windowStart + windowSize).toString(16)} = ${window.hexDump()}"
        )

        // MAX_MONEY in vanilla Emerald-derived games is 999999 - flag anything (raw, or
        // XOR-decrypted if we have a key) that could plausibly be a money value so the
        // real offset can be spotted at a glance instead of hand-decoding hex.
        val candidates = mutableListOf<String>()
        for (i in 0 until windowSize - 3) {
            val raw = readU32LE(window, i)
            val decrypted = encryptionKey?.let { raw xor it }
            val plausible = (raw in 0..999_999) || (decrypted != null && decrypted in 0..999_999)
            if (plausible) {
                val addr = saveBlock1Address + windowStart + i
                candidates.add(
                    "0x${addr.toString(16)} (SaveBlock1+0x${(windowStart + i).toString(16)}): raw=$raw" +
                        (decrypted?.let { " decrypted=$it" } ?: "")
                )
            }
        }
        onDiagnostic(
            if (candidates.isEmpty()) "Money calibration: no plausible (0-999999) candidates in window."
            else "Money calibration candidates:\n" + candidates.joinToString("\n")
        )
    }

    private fun ByteArray.hexDump(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Overwrites the player's money with [newMoney]. Refuses unconditionally while
     * [MONEY_OFFSET_CONFIRMED] is false - issue #30 showed `MONEY_OFFSET_IN_SAVEBLOCK1`
     * currently lands on what looks like a repeating array, not a scalar field, so writing
     * there would risk corrupting some other, unrelated piece of save data instead of
     * actually touching money. Once calibration (see `dumpMoneyCalibrationWindow`) finds
     * and confirms the real offset, flip that flag and this starts working.
     */
    suspend fun writeMoney(newMoney: Int): Boolean {
        if (!MONEY_OFFSET_CONFIRMED) {
            onDiagnostic("! Money write skipped - offset not yet confirmed against real ground truth (see BattleStateBridge doc comment).")
            return false
        }

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

    private fun resolveSaveBlock2Address(bridge: RetroArchMemoryBridge): Int? {
        val ptrBytes = bridge.readMemory(SAVEBLOCK2_PTR_ADDR, 4) ?: return null
        val address = readU32LE(ptrBytes, 0)
        if (address < EWRAM_BASE || address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Battle state: gSaveBlock2Ptr resolved outside EWRAM (0x${address.toString(16)}).")
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
