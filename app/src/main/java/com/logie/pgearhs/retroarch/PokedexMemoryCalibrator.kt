package com.logie.pgearhs.retroarch

import kotlinx.coroutines.delay

/**
 * SaveBlock1/SaveBlock2 are runtime-allocated - there's no fixed compile-time address to
 * read from (no .map/.elf exists for this exact build). Instead of guessing, this finds
 * SaveBlock2 live by diffing two EWRAM snapshots a few seconds apart and looking for the
 * struct's own play-time counters, which increment passively just from the game running -
 * no player action needed. Once located, national-dex-enabled state and the owned/seen
 * bitfields are read directly out of the already-captured snapshot (no extra round trips).
 *
 * Relative offsets below come straight from this build's include/global.h:
 *   struct SaveBlock2 { ... playTimeMinutes @0x10, playTimeSeconds @0x11, playTimeVBlanks
 *   @0x12 ... pokedex @0x18 { order, mode, nationalMagic @+0x02, ... owned[] @+0x10,
 *   seen[] @+0x44 } }
 *
 * This only locates SaveBlock2. The game's full national-dex check also cross-references
 * VAR_NATIONAL_DEX and FLAG_SYS_NATIONAL_DEX in SaveBlock1 (a separately-allocated struct,
 * not calibrated here) - nationalMagic alone is used as the signal, which the source itself
 * calls "the single most reliable poll point" even though the real game checks all three.
 * Not verified against a real running instance - the EWRAM base-address assumption
 * (0x02000000, matching mGBA's typical GBA memory map) is unconfirmed.
 */
class PokedexMemoryCalibrator(private val bridge: RetroArchMemoryBridge) {

    companion object {
        private const val PLAYTIME_MINUTES_OFFSET = 0x10
        private const val PLAYTIME_SECONDS_OFFSET = 0x11
        private const val PLAYTIME_VBLANKS_OFFSET = 0x12
        private const val SAVEBLOCK2_TO_POKEDEX = 0x18
        private const val POKEDEX_NATIONAL_MAGIC_OFFSET = 0x02
        private const val POKEDEX_OWNED_OFFSET = 0x10
        private const val POKEDEX_SEEN_OFFSET = 0x44
        private const val DEX_BITFIELD_READ_BYTES = 64 // generous - covers up to dex #512

        private const val NATIONAL_MAGIC_ENABLED: Byte = 0xDA.toByte()
        private const val NATIONAL_MAGIC_DISABLED: Byte = 0x00

        private const val CALIBRATION_WAIT_MS = 4000L
        private const val WRAP_MINUTES = 60
    }

    sealed class Result {
        data class Success(
            val nationalDexEnabled: Boolean,
            val owned: ByteArray,
            val seen: ByteArray
        ) : Result()

        data class Failure(val reason: String) : Result()
    }

    suspend fun calibrateAndRead(): Result {
        if (!bridge.isReachable()) {
            return Result.Failure("Can't reach RetroArch - check host/port and that it's running.")
        }

        val snapshotA = bridge.dumpEwram() ?: return Result.Failure("First memory read failed.")
        val startA = System.currentTimeMillis()
        delay(CALIBRATION_WAIT_MS)
        val snapshotB = bridge.dumpEwram() ?: return Result.Failure("Second memory read failed.")
        val elapsedSeconds = ((System.currentTimeMillis() - startA) / 1000).toInt()

        val candidates = findSaveBlock2Candidates(snapshotA, snapshotB, elapsedSeconds)
        val validated = candidates.filter { pokedexOffset ->
            val magic = snapshotB.getOrNull(pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET)
            magic == NATIONAL_MAGIC_ENABLED || magic == NATIONAL_MAGIC_DISABLED
        }

        return when (validated.size) {
            0 -> Result.Failure(
                "Couldn't find the save data in memory. Make sure the game is running, " +
                    "in the overworld (not a menu/battle), and left idle during calibration."
            )
            1 -> {
                val pokedexOffset = validated[0]
                val nationalEnabled = snapshotB[pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET] == NATIONAL_MAGIC_ENABLED
                val owned = snapshotB.copyOfRange(
                    pokedexOffset + POKEDEX_OWNED_OFFSET,
                    pokedexOffset + POKEDEX_OWNED_OFFSET + DEX_BITFIELD_READ_BYTES
                )
                val seen = snapshotB.copyOfRange(
                    pokedexOffset + POKEDEX_SEEN_OFFSET,
                    pokedexOffset + POKEDEX_SEEN_OFFSET + DEX_BITFIELD_READ_BYTES
                )
                Result.Success(nationalEnabled, owned, seen)
            }
            else -> Result.Failure(
                "Found ${validated.size} possible matches in memory - too ambiguous to trust. Try again."
            )
        }
    }

    /**
     * Scans both snapshots for a byte offset X where X=minutes, X+1=seconds, X+2=vblanks
     * behave consistently with the real elapsed time between snapshots, then returns the
     * corresponding candidate offset(s) of the *pokedex struct* (not SaveBlock2 itself).
     */
    private fun findSaveBlock2Candidates(
        before: ByteArray,
        after: ByteArray,
        elapsedSeconds: Int
    ): List<Int> {
        val candidates = mutableListOf<Int>()
        val lastIndex = minOf(before.size, after.size) - (PLAYTIME_VBLANKS_OFFSET + 1)

        for (i in 0 until lastIndex) {
            val minutesA = before[i].toUByteValue()
            val secondsA = before[i + 1].toUByteValue()
            val vblanksA = before[i + 2].toUByteValue()
            val minutesB = after[i].toUByteValue()
            val secondsB = after[i + 1].toUByteValue()
            val vblanksB = after[i + 2].toUByteValue()

            if (minutesA >= WRAP_MINUTES || minutesB >= WRAP_MINUTES) continue
            if (secondsA >= WRAP_MINUTES || secondsB >= WRAP_MINUTES) continue
            if (vblanksA == vblanksB) continue // must have visibly ticked over several seconds

            val totalA = minutesA * WRAP_MINUTES + secondsA
            val totalB = minutesB * WRAP_MINUTES + secondsB
            val delta = (totalB - totalA + WRAP_MINUTES * WRAP_MINUTES) % (WRAP_MINUTES * WRAP_MINUTES)

            // Allow slack for the time the dumps themselves took plus rounding.
            val minExpected = maxOf(0, elapsedSeconds - 3)
            val maxExpected = elapsedSeconds + 6
            if (delta < minExpected || delta > maxExpected) continue

            val pokedexOffset = i - PLAYTIME_MINUTES_OFFSET + SAVEBLOCK2_TO_POKEDEX
            if (pokedexOffset < 0 || pokedexOffset + POKEDEX_SEEN_OFFSET + DEX_BITFIELD_READ_BYTES > after.size) continue

            candidates.add(pokedexOffset)
        }

        return candidates
    }

    private fun Byte.toUByteValue(): Int = this.toInt() and 0xFF
}
