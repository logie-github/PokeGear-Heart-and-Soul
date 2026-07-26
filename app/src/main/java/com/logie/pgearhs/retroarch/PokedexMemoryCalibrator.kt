package com.logie.pgearhs.retroarch

/**
 * SaveBlock1/SaveBlock2 are runtime-allocated - there's no fixed compile-time address to
 * read from (no .map/.elf exists for this exact build). Instead of guessing an address, or
 * indirectly hunting for a live counter (play time) and walking to the pokedex struct from
 * there, this searches directly for the pokedex data itself using a structural constraint
 * that's true regardless of what's actually been caught: every bit set in owned[] must also
 * be set in seen[] (you can't catch something you've never seen), and owned[]/seen[]/
 * nationalMagic sit at fixed, known distances from each other. That's specific enough to
 * find in a single EWRAM dump, no diffing or waiting required.
 *
 * Relative offsets below come from this build's include/global.h, with one correction:
 *   struct Pokedex { order, mode, nationalMagic @+0x02, ... owned[] @+0x10, seen[] @+0x44
 *   (comment) }
 * The struct's own "0x44" offset comment for seen[] is stale: NUM_DEX_FLAG_BYTES =
 * ROUND_BITS_TO_BYTES(NUM_SPECIES) where NUM_SPECIES = SPECIES_EGG = 462 (species.h),
 * giving 58 bytes per bitfield, not the 52 the comment's offset implies - so seen[]'s real
 * offset is owned[]'s start (+0x10) plus 58 bytes = +0x4A, six bytes later than commented.
 *
 * [onDiagnostic] is an optional hook for logging step-by-step timing/candidate-count info
 * (wire it to DebugLog.add).
 */
class PokedexMemoryCalibrator(
    private val bridge: RetroArchMemoryBridge,
    private val onDiagnostic: (String) -> Unit = {}
) {

    companion object {
        private const val POKEDEX_NATIONAL_MAGIC_OFFSET = 0x02
        private const val NUM_DEX_FLAG_BYTES = 58 // ROUND_BITS_TO_BYTES(SPECIES_EGG=462)
        private const val POKEDEX_OWNED_OFFSET = 0x10
        private const val POKEDEX_SEEN_OFFSET = POKEDEX_OWNED_OFFSET + NUM_DEX_FLAG_BYTES // 0x4A, not the stale 0x44 comment
        private const val NATIONAL_MAGIC_TO_OWNED = POKEDEX_OWNED_OFFSET - POKEDEX_NATIONAL_MAGIC_OFFSET

        private const val NATIONAL_MAGIC_ENABLED: Byte = 0xDA.toByte()
        private const val NATIONAL_MAGIC_DISABLED: Byte = 0x00
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

        val dumpStart = System.currentTimeMillis()
        val snapshot = bridge.dumpEwram() ?: run {
            onDiagnostic("Calibration: EWRAM dump failed (chunked read error).")
            return Result.Failure("Memory read failed.")
        }
        onDiagnostic("Calibration: dump took ${System.currentTimeMillis() - dumpStart}ms (${snapshot.size} bytes).")

        val candidates = findPokedexCandidates(snapshot)
        onDiagnostic("Calibration: ${candidates.size} candidate(s) had owned[] ⊆ seen[] with a valid nationalMagic byte.")

        return when (candidates.size) {
            0 -> Result.Failure(
                "Couldn't find the registered-Pokemon data in memory. Make sure a save is " +
                    "actually loaded (past the title/intro screen)."
            )
            1 -> {
                val pokedexOffset = candidates[0]
                val nationalEnabled = snapshot[pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET] == NATIONAL_MAGIC_ENABLED
                val owned = snapshot.copyOfRange(
                    pokedexOffset + POKEDEX_OWNED_OFFSET,
                    pokedexOffset + POKEDEX_OWNED_OFFSET + NUM_DEX_FLAG_BYTES
                )
                val seen = snapshot.copyOfRange(
                    pokedexOffset + POKEDEX_SEEN_OFFSET,
                    pokedexOffset + POKEDEX_SEEN_OFFSET + NUM_DEX_FLAG_BYTES
                )
                onDiagnostic(
                    "Calibration: pokedex struct at dump offset 0x${pokedexOffset.toString(16)}, " +
                        "nationalMagic=${if (nationalEnabled) "0xDA" else "0x00"}, " +
                        "owned bits set=${owned.countSetBits()}, seen bits set=${seen.countSetBits()}"
                )
                Result.Success(nationalEnabled, owned, seen)
            }
            else -> Result.Failure(
                "Found ${candidates.size} possible matches in memory - too ambiguous to trust. Try again."
            )
        }
    }

    /**
     * Scans the dump for a byte offset where nationalMagic is valid (0x00/0xDA) and the two
     * 58-byte bitfields that follow satisfy owned[] ⊆ seen[] bit-for-bit. Returns pokedex
     * struct offsets (i.e. nationalMagic's offset minus 0x02).
     */
    private fun findPokedexCandidates(snapshot: ByteArray): List<Int> {
        val candidates = mutableListOf<Int>()
        val lastOwnedOffset = snapshot.size - 2 * NUM_DEX_FLAG_BYTES

        var ownedOffset = NATIONAL_MAGIC_TO_OWNED
        while (ownedOffset < lastOwnedOffset) {
            val magic = snapshot[ownedOffset - NATIONAL_MAGIC_TO_OWNED]
            if (magic == NATIONAL_MAGIC_ENABLED || magic == NATIONAL_MAGIC_DISABLED) {
                val seenOffset = ownedOffset + NUM_DEX_FLAG_BYTES
                var isSubset = true
                var seenHasAnyBitSet = false
                for (i in 0 until NUM_DEX_FLAG_BYTES) {
                    val ownedByte = snapshot[ownedOffset + i].toUByteValue()
                    val seenByte = snapshot[seenOffset + i].toUByteValue()
                    if (seenByte != 0) seenHasAnyBitSet = true
                    if (ownedByte and seenByte.inv() != 0) {
                        isSubset = false
                        break
                    }
                }
                // Reject the trivial all-zero match - blank/unused memory regions satisfy
                // "owned ⊆ seen" vacuously, so require at least one real seen species.
                if (isSubset && seenHasAnyBitSet) {
                    candidates.add(ownedOffset - POKEDEX_OWNED_OFFSET)
                }
            }
            ownedOffset++
        }

        return candidates
    }

    private fun Byte.toUByteValue(): Int = this.toInt() and 0xFF

    private fun ByteArray.countSetBits(): Int = sumOf { Integer.bitCount(it.toUByteValue()) }
}
