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
 * Relative offsets below come from this build's include/global.h, with one correction:
 *   struct SaveBlock2 { ... playTimeMinutes @0x10, playTimeSeconds @0x11, playTimeVBlanks
 *   @0x12 ... pokedex @0x18 { order, mode, nationalMagic @+0x02, ... owned[] @+0x10,
 *   seen[] @+0x44 (comment) } }
 * The struct's own "0x44" offset comment for seen[] is stale: NUM_DEX_FLAG_BYTES =
 * ROUND_BITS_TO_BYTES(NUM_SPECIES) where NUM_SPECIES = SPECIES_EGG = 462 (species.h),
 * giving 58 bytes per bitfield, not the 52 the comment's offset implies - so seen[]'s real
 * offset is owned[]'s start (+0x10) plus 58 bytes = +0x4A, six bytes later than commented.
 * Reading from +0x44 shifts almost the whole seen[] bitfield by 6 bytes (48 bits) relative
 * to the real dex numbering - confirmed the hard way via a real debug report showing ~250
 * unrelated species incorrectly marked visible.
 *
 * This only locates SaveBlock2. The game's full national-dex check also cross-references
 * VAR_NATIONAL_DEX and FLAG_SYS_NATIONAL_DEX in SaveBlock1 (a separately-allocated struct,
 * not calibrated here) - nationalMagic alone is used as the signal, which the source itself
 * calls "the single most reliable poll point" even though the real game checks all three.
 *
 * [onDiagnostic] is an optional hook for logging step-by-step timing/candidate-count info
 * (wire it to DebugLog.add) - each 256KB dump is 64 chunked UDP round trips, which can take
 * a while over real Wi-Fi, so knowing how long it actually took (and how many candidates
 * the timing filter vs. the nationalMagic filter eliminated) is the difference between a
 * useful "not found" and a useless one on a real device.
 */
class PokedexMemoryCalibrator(
    private val bridge: RetroArchMemoryBridge,
    private val onDiagnostic: (String) -> Unit = {}
) {

    companion object {
        private const val PLAYTIME_HOURS_OFFSET = 0x0E // u16, immediately before minutes
        private const val PLAYTIME_MINUTES_OFFSET = 0x10
        private const val PLAYTIME_VBLANKS_OFFSET = 0x12
        private const val MAX_PLAUSIBLE_PLAYTIME_HOURS = 999
        private const val SAVEBLOCK2_TO_POKEDEX = 0x18
        private const val POKEDEX_NATIONAL_MAGIC_OFFSET = 0x02
        private const val NUM_DEX_FLAG_BYTES = 58 // ROUND_BITS_TO_BYTES(SPECIES_EGG=462)
        private const val POKEDEX_OWNED_OFFSET = 0x10
        private const val POKEDEX_SEEN_OFFSET = POKEDEX_OWNED_OFFSET + NUM_DEX_FLAG_BYTES // 0x4A, not the stale 0x44 comment
        private const val DEX_BITFIELD_READ_BYTES = NUM_DEX_FLAG_BYTES

        private const val NATIONAL_MAGIC_ENABLED: Byte = 0xDA.toByte()
        private const val NATIONAL_MAGIC_DISABLED: Byte = 0x00

        private const val CALIBRATION_WAIT_MS = 4000L
        private const val WRAP_MINUTES = 60

        // Slack added on top of measured dump durations, to absorb the fact that any given
        // byte is sampled at some unknown point during a dump's 64 chunked reads, not
        // exactly at a single instant.
        private const val EXTRA_SLACK_SECONDS = 2
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
            onDiagnostic("Calibration: RetroArch unreachable (GET_STATUS failed).")
            return Result.Failure("Can't reach RetroArch - check host/port and that it's running.")
        }

        val dumpAStart = System.currentTimeMillis()
        val snapshotA = bridge.dumpEwram() ?: run {
            onDiagnostic("Calibration: first EWRAM dump failed (chunked read error).")
            return Result.Failure("First memory read failed.")
        }
        val dumpADurationMs = System.currentTimeMillis() - dumpAStart
        onDiagnostic("Calibration: first dump took ${dumpADurationMs}ms (${snapshotA.size} bytes).")

        delay(CALIBRATION_WAIT_MS)

        val dumpBStart = System.currentTimeMillis()
        val snapshotB = bridge.dumpEwram() ?: run {
            onDiagnostic("Calibration: second EWRAM dump failed (chunked read error).")
            return Result.Failure("Second memory read failed.")
        }
        val dumpBDurationMs = System.currentTimeMillis() - dumpBStart
        val elapsedSeconds = ((dumpBStart - dumpAStart) / 1000).toInt()
        onDiagnostic(
            "Calibration: second dump took ${dumpBDurationMs}ms (${snapshotB.size} bytes); " +
                "~${elapsedSeconds}s between dumps."
        )

        val dumpSlackSeconds = ((dumpADurationMs + dumpBDurationMs) / 1000).toInt() + EXTRA_SLACK_SECONDS
        val candidates = findSaveBlock2Candidates(snapshotA, snapshotB, elapsedSeconds, dumpSlackSeconds)
        val validated = candidates.filter { pokedexOffset ->
            val magic = snapshotB.getOrNull(pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET)
            magic == NATIONAL_MAGIC_ENABLED || magic == NATIONAL_MAGIC_DISABLED
        }
        onDiagnostic(
            "Calibration: ${candidates.size} raw candidate(s) matched the play-time+hours " +
                "timing signature, ${validated.size} passed the nationalMagic check."
        )
        if (candidates.isNotEmpty() && validated.isEmpty()) {
            val seenMagicBytes = candidates.joinToString(", ") { pokedexOffset ->
                val magic = snapshotB.getOrNull(pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET)
                "0x" + (magic?.toInt()?.and(0xFF) ?: -1).toString(16)
            }
            onDiagnostic("Calibration: rejected candidate(s) had nationalMagic byte(s): $seenMagicBytes")
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
                onDiagnostic(
                    "Calibration: pokedex struct at dump offset 0x${pokedexOffset.toString(16)}, " +
                        "nationalMagic=${if (nationalEnabled) "0xDA" else "0x00"}, " +
                        "owned bits set=${owned.countSetBits()}, seen bits set=${seen.countSetBits()}"
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
        elapsedSeconds: Int,
        slackSeconds: Int
    ): List<Int> {
        val candidates = mutableListOf<Int>()
        val lastIndex = minOf(before.size, after.size) - (PLAYTIME_VBLANKS_OFFSET + 1)

        for (i in PLAYTIME_MINUTES_OFFSET - PLAYTIME_HOURS_OFFSET until lastIndex) {
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

            // Allow slack for however long the dumps themselves took, since a given byte is
            // sampled at some unknown point during a dump's chunked reads, plus rounding.
            val minExpected = maxOf(0, elapsedSeconds - slackSeconds)
            val maxExpected = elapsedSeconds + slackSeconds
            if (delta < minExpected || delta > maxExpected) continue

            // Cross-check playTimeHours (u16, immediately before minutes): must be a small,
            // non-decreasing number - rules out coincidental matches elsewhere in RAM whose
            // "minutes/seconds/vblanks"-shaped bytes just happened to satisfy the above.
            val hoursOffset = i - (PLAYTIME_MINUTES_OFFSET - PLAYTIME_HOURS_OFFSET)
            val hoursA = readU16LE(before, hoursOffset)
            val hoursB = readU16LE(after, hoursOffset)
            if (hoursA > MAX_PLAUSIBLE_PLAYTIME_HOURS || hoursB > MAX_PLAUSIBLE_PLAYTIME_HOURS) continue
            if (hoursB < hoursA) continue

            val pokedexOffset = i - PLAYTIME_MINUTES_OFFSET + SAVEBLOCK2_TO_POKEDEX
            if (pokedexOffset < 0 || pokedexOffset + POKEDEX_SEEN_OFFSET + DEX_BITFIELD_READ_BYTES > after.size) continue

            candidates.add(pokedexOffset)
        }

        return candidates
    }

    private fun Byte.toUByteValue(): Int = this.toInt() and 0xFF

    /** GBA is little-endian ARM. */
    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toUByteValue() or (bytes[offset + 1].toUByteValue() shl 8)

    private fun ByteArray.countSetBits(): Int = sumOf { Integer.bitCount(it.toUByteValue()) }
}
