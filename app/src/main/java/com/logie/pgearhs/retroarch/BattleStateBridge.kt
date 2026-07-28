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
 * out the key will never look like a real balance. `readState()` now does that decryption.
 *
 * Both `MONEY_OFFSET_IN_SAVEBLOCK1` (0x490) and `ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2` (0xBC)
 * are now taken directly from disassembling `pokemonHnS-v121/pokemonHnS.elf` - the actual
 * compiled build matching the Release-v1.2.1 tag this ROM comes from - not reasoned from
 * struct definitions. `GetMoney`/`SetMoney`/`AddMoney`/`RemoveMoney` all load the key via
 * `movs r3, #188` (`0xBC`) before XOR-ing; `Cmd_getmoneyreward`'s actual payout computes
 * `gSaveBlock1Ptr + (0x92 << 3)` = `+0x490` before calling `RemoveMoney`. The previous
 * `0xAC` guess for the key (the plain vanilla-documented offset) was simply wrong - that's
 * why every prior decrypt attempt produced garbage even once `money`'s own offset (0x490)
 * turned out to be right all along. This is what "where can you find it" should have been
 * from the start: read what the compiler actually emitted, not what the header comments say.
 *
 * `writeMoney()` still refuses to run while `MONEY_OFFSET_CONFIRMED = false` - this is real
 * evidence, not a guess, but it hasn't been seen decrypting to a plausible number on the
 * live device yet. Flip once that's actually observed in a report.
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
        private const val MONEY_OFFSET_IN_SAVEBLOCK1 = 0x490
        private const val ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2 = 0xBC
        private const val MAX_MONEY = 9_999_999

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
            val raw = moneyWindow?.let { readU32LE(it, 16) }
            val encryptionKey = readEncryptionKey(bridge)
            if (verbose) {
                onDiagnostic("Battle state: encryptionKey = ${encryptionKey?.let { "0x${it.toString(16)}" } ?: "read failed"}, money raw=$raw decrypted=${encryptionKey?.let { raw?.xor(it) }}")
            }
            raw?.let { r -> encryptionKey?.let { r xor it } ?: r }
        }

        return BattleState(inBattle, outcome, money)
    }

    /** A raw snapshot of the money calibration window, for diffing before/after a win - see [diffAgainstWin]. */
    data class MoneySnapshot(val saveBlock1Address: Int, val windowStart: Int, val window: ByteArray, val encryptionKey: Int?)

    // Not a window "near" the guessed offset - the whole of SaveBlock1, so calibration finds
    // the real offset by scanning everything rather than trusting where MONEY_OFFSET_IN_SAVEBLOCK1
    // *currently* guesses it is. src/save.c reserves SaveBlock1 across sectors 1-4
    // (SECTOR_DATA_SIZE=4084 each, include/save.h) - 4084*4=16336 bytes is the real upper
    // bound (statically asserted in save.c), rounded up here for margin.
    private val moneyWindowStart = 0
    private val moneyWindowSize = 0x4000

    private suspend fun readEncryptionKey(bridge: RetroArchMemoryBridge): Int? {
        val saveBlock2Address = resolveSaveBlock2Address(bridge) ?: return null
        return bridge.readMemory(saveBlock2Address + ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2, 4)?.let { readU32LE(it, 0) }
    }

    suspend fun captureMoneySnapshot(bridge: RetroArchMemoryBridge = RetroArchMemoryBridge(host, port)): MoneySnapshot? {
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return null
        val encryptionKey = readEncryptionKey(bridge)
        val window = bridge.readRegion(saveBlock1Address + moneyWindowStart, moneyWindowSize) ?: return null
        return MoneySnapshot(saveBlock1Address, moneyWindowStart, window, encryptionKey)
    }

    private fun MoneySnapshot.candidates(): List<Pair<Int, Int>> {
        // Flag anything (raw, or XOR-decrypted if we have a key) that could plausibly be a
        // money value - this hack's MAX_MONEY is 9999999 (src/money.c), not vanilla's 999999.
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until window.size - 3) {
            val raw = readU32LE(window, i)
            val decrypted = encryptionKey?.let { raw xor it }
            val value = decrypted?.takeIf { it in 0..MAX_MONEY } ?: raw.takeIf { it in 0..MAX_MONEY }
            if (value != null) out.add(i to value)
        }
        return out
    }

    /**
     * Compares a snapshot taken right before a battle against one taken right after a win,
     * looking for an offset whose interpreted value (raw or decrypted, whichever was
     * plausible) went UP by a plausible reward amount - without needing the user to state
     * their exact money anywhere. Only logs candidates; still never writes anything.
     */
    fun MoneySnapshot.diffAgainstWin(after: MoneySnapshot) {
        if (windowStart != after.windowStart || window.size != after.window.size) {
            onDiagnostic("Money calibration: before/after window mismatch, can't diff.")
            return
        }
        val beforeByOffset = candidates().toMap()
        val afterByOffset = after.candidates().toMap()
        val matches = beforeByOffset.keys.intersect(afterByOffset.keys).mapNotNull { i ->
            val before = beforeByOffset.getValue(i)
            val after = afterByOffset.getValue(i)
            val delta = after - before
            if (delta in 1..MAX_MONEY) i to Triple(before, after, delta) else null
        }
        onDiagnostic(
            if (matches.isEmpty()) {
                "Money calibration: no offset increased by a plausible amount across the win " +
                    "(before had ${beforeByOffset.size} candidate(s), after had ${afterByOffset.size})."
            } else {
                "Money calibration - offsets that increased plausibly across this win:\n" +
                    matches.joinToString("\n") { (i, t) ->
                        val (before, afterVal, delta) = t
                        "0x${(saveBlock1Address + windowStart + i).toString(16)} (SaveBlock1+0x${(windowStart + i).toString(16)}): $before -> $afterVal (+$delta)"
                    }
            }
        )
    }

    private fun ByteArray.hexDump(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Overwrites the player's money with [newMoney] (encrypting it the same way the game
     * does before writing). Refuses unconditionally while [MONEY_OFFSET_CONFIRMED] is false -
     * see the class doc comment for why the offset is now well-reasoned but still not
     * live-confirmed. Once a real diff (see `diffAgainstWin`) confirms it, flip that flag.
     */
    suspend fun writeMoney(newMoney: Int): Boolean {
        if (!MONEY_OFFSET_CONFIRMED) {
            onDiagnostic("! Money write skipped - offset not yet confirmed against real ground truth (see BattleStateBridge doc comment).")
            return false
        }

        val bridge = RetroArchMemoryBridge(host, port)
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return false
        val encryptionKey = readEncryptionKey(bridge) ?: run {
            onDiagnostic("! Money write skipped - couldn't read encryptionKey to encrypt the new value.")
            return false
        }
        val moneyAddress = saveBlock1Address + MONEY_OFFSET_IN_SAVEBLOCK1
        val encrypted = newMoney xor encryptionKey

        val bytes = byteArrayOf(
            (encrypted and 0xFF).toByte(),
            ((encrypted shr 8) and 0xFF).toByte(),
            ((encrypted shr 16) and 0xFF).toByte(),
            ((encrypted shr 24) and 0xFF).toByte()
        )
        if (!bridge.writeMemory(moneyAddress, bytes)) {
            onDiagnostic("! Money write to 0x${moneyAddress.toString(16)} not confirmed by RetroArch.")
            return false
        }

        val verified = bridge.readMemory(moneyAddress, 4)?.let { readU32LE(it, 0) xor encryptionKey }
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
