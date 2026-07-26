package com.logie.pgearhs.retroarch

/**
 * SaveBlock1/SaveBlock2 are runtime-allocated - there's no fixed compile-time address to
 * read from (no .map/.elf exists for this exact build). Two strategies, tried in order:
 *
 * 1. Known-pointer fast path: this hack is pokeemerald-based, and pret's public symbol
 *    table for vanilla pokeemerald (github.com/pret/pokeemerald, `symbols` branch) gives
 *    gSaveBlock2Ptr's own fixed IWRAM address as 0x03005D90 (a global pointer *variable*,
 *    not the struct itself - the struct it points to is dynamically allocated in EWRAM).
 *    If this hack kept the same global variable layout as vanilla pokeemerald (plausible
 *    for a decomp-based hack that hasn't restructured its globals), reading 4 bytes there
 *    gives the real runtime EWRAM address directly - no scanning needed at all.
 * 2. Structural fallback: if that address doesn't resolve to something valid (the hack
 *    shifted its global layout), search EWRAM directly for the pokedex data itself using a
 *    constraint that's true regardless of what's been caught: every bit set in owned[]
 *    must also be set in seen[] (can't catch what you've never seen), with owned[]/seen[]/
 *    nationalMagic at their known fixed distances from each other.
 *
 * Relative offsets below come from this build's include/global.h, with one correction:
 *   struct Pokedex { order, mode, nationalMagic @+0x02, ... owned[] @+0x10, seen[] @+0x44
 *   (comment) }
 * The struct's own "0x44" offset comment for seen[] is stale: NUM_DEX_FLAG_BYTES =
 * ROUND_BITS_TO_BYTES(NUM_SPECIES) where NUM_SPECIES = SPECIES_EGG = 462 (species.h),
 * giving 58 bytes per bitfield, not the 52 the comment's offset implies - so seen[]'s real
 * offset is owned[]'s start (+0x10) plus 58 bytes = +0x4A, six bytes later than commented.
 *
 * Both strategies try RetroArchMemoryBridge.CommandMode.CORE_MEMORY first, then CORE_RAM -
 * these two commands can address memory differently depending on whether the running core
 * implements full libretro memory-map descriptors (see RetroArchMemoryBridge's doc
 * comment); PokemonResort's Gen2/Gen3 support retries with the other command for the same
 * reason. The known-pointer path specifically needs CORE_MEMORY's real bus addressing
 * (0x0300xxxx for IWRAM) to make sense, so CORE_RAM is only tried for the fallback scan.
 *
 * [onDiagnostic] is an optional hook for logging step-by-step info (wire it to DebugLog.add).
 */
class PokedexMemoryCalibrator(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {

    companion object {
        // pret/pokeemerald symbols (github.com/pret/pokeemerald, `symbols` branch), a
        // public reference for vanilla Emerald - may or may not still hold for this hack.
        private const val KNOWN_SAVEBLOCK2_PTR_ADDR = 0x03005D90

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE

        // IWRAM is where a real gSaveBlock2Ptr global pointer variable would live (see
        // KNOWN_SAVEBLOCK2_PTR_ADDR above) - only 32KB, cheap to scan in full.
        private const val IWRAM_BASE = 0x03000000
        private const val IWRAM_SIZE = 0x8000

        private const val SAVEBLOCK2_TO_POKEDEX = 0x18
        private const val POKEDEX_NATIONAL_MAGIC_OFFSET = 0x02
        private const val NUM_DEX_FLAG_BYTES = 58 // ROUND_BITS_TO_BYTES(SPECIES_EGG=462)
        private const val POKEDEX_OWNED_OFFSET = 0x10
        private const val POKEDEX_SEEN_OFFSET = POKEDEX_OWNED_OFFSET + NUM_DEX_FLAG_BYTES // 0x4A, not the stale 0x44 comment
        private const val NATIONAL_MAGIC_TO_OWNED = POKEDEX_OWNED_OFFSET - POKEDEX_NATIONAL_MAGIC_OFFSET
        private const val POKEDEX_STRUCT_READ_BYTES = POKEDEX_SEEN_OFFSET + NUM_DEX_FLAG_BYTES

        private const val NATIONAL_MAGIC_ENABLED: Byte = 0xDA.toByte()
        private const val NATIONAL_MAGIC_DISABLED: Byte = 0x00

        // Extra SaveBlock2-header plausibility checks, all single-snapshot range/shape
        // checks (no diffing, no waiting) - "owned ⊆ seen" alone is too weak once the
        // pokedex is sparse (a fresh/early save has few owned bits, so almost any random
        // seen[]-shaped bytes satisfy the subset constraint). These use fields at fixed,
        // known offsets from SaveBlock2's start (include/global.h):
        //   playerName[7] @+0x00, playerGender @+0x08, playTimeHours (u16) @+0x0E,
        //   playTimeMinutes @+0x10, playTimeSeconds @+0x11, playTimeVBlanks @+0x12
        private const val PLAYER_NAME_OFFSET = 0x00
        private const val PLAYER_NAME_LENGTH = 7 // PLAYER_NAME_LENGTH, constants/global.h
        private const val PLAYER_GENDER_OFFSET = 0x08
        private const val PLAYTIME_HOURS_OFFSET = 0x0E
        private const val PLAYTIME_MINUTES_OFFSET = 0x10
        private const val PLAYTIME_SECONDS_OFFSET = 0x11
        private const val PLAYTIME_VBLANKS_OFFSET = 0x12
        private const val MAX_PLAUSIBLE_PLAYTIME_HOURS = 999
        private const val WRAP_MINUTES_SECONDS = 60

        // Random noise satisfying "owned ⊆ seen" tends to have ~50% of bits set (~230 of
        // 462); a real save this early has only a handful seen. This one check does more
        // work than all the others combined.
        private const val MAX_PLAUSIBLE_SEEN_COUNT = 100

        private const val MAX_LOGGED_CANDIDATES = 500
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
        val probeBridge = RetroArchMemoryBridge(host, port)
        if (!probeBridge.isReachable()) {
            onDiagnostic("Calibration: RetroArch unreachable (GET_STATUS failed).")
            return Result.Failure("Can't reach RetroArch - check host/port and that it's running.")
        }

        tryKnownPointer()?.let { return it }

        val memoryResult = attemptStructuralScan(RetroArchMemoryBridge.CommandMode.CORE_MEMORY)
        if (memoryResult is Result.Success) return memoryResult

        onDiagnostic("Calibration: CORE_MEMORY scan found nothing usable, retrying with CORE_RAM…")
        val ramResult = attemptStructuralScan(RetroArchMemoryBridge.CommandMode.CORE_RAM)
        if (ramResult is Result.Success) return ramResult

        // Prefer whichever failure is more specific than a bare "not found".
        return if (memoryResult is Result.Failure && !memoryResult.reason.startsWith("Couldn't find")) {
            memoryResult
        } else {
            ramResult
        }
    }

    /** Tries pret's known vanilla-pokeemerald gSaveBlock2Ptr address. Null = didn't pan out, fall through. */
    private fun tryKnownPointer(): Result? {
        val bridge = RetroArchMemoryBridge(host, port, commandMode = RetroArchMemoryBridge.CommandMode.CORE_MEMORY)

        val ptrBytes = bridge.readMemory(KNOWN_SAVEBLOCK2_PTR_ADDR, 4) ?: run {
            onDiagnostic("Calibration: couldn't read known gSaveBlock2Ptr address 0x${KNOWN_SAVEBLOCK2_PTR_ADDR.toString(16)}.")
            return null
        }
        val saveBlock2Address = readU32LE(ptrBytes, 0)
        onDiagnostic("Calibration: gSaveBlock2Ptr (known pokeemerald addr) = 0x${saveBlock2Address.toString(16)}")

        if (saveBlock2Address < EWRAM_BASE || saveBlock2Address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Calibration: that's outside EWRAM - this hack's global layout doesn't match vanilla pokeemerald here.")
            return null
        }

        val pokedexAddress = saveBlock2Address + SAVEBLOCK2_TO_POKEDEX
        val structBytes = bridge.readMemory(pokedexAddress, POKEDEX_STRUCT_READ_BYTES) ?: run {
            onDiagnostic("Calibration: pointer looked valid but reading the pokedex struct failed.")
            return null
        }

        val validated = validatePokedexStruct(structBytes, offset = 0)
        if (!validated) {
            onDiagnostic("Calibration: known pointer resolved to EWRAM, but the data there isn't a valid pokedex struct.")
            return null
        }

        return buildSuccess(structBytes, offset = 0, source = "known pointer")
    }

    private suspend fun attemptStructuralScan(mode: RetroArchMemoryBridge.CommandMode): Result {
        val bridge = RetroArchMemoryBridge(host, port, commandMode = mode)

        val dumpStart = System.currentTimeMillis()
        val snapshot = bridge.dumpEwram() ?: run {
            onDiagnostic("Calibration ($mode): EWRAM dump failed (chunked read error).")
            return Result.Failure("Memory read failed.")
        }
        onDiagnostic("Calibration ($mode): dump took ${System.currentTimeMillis() - dumpStart}ms (${snapshot.size} bytes).")

        val candidates = findPokedexCandidates(snapshot)
        onDiagnostic(
            "Calibration ($mode): ${candidates.size} candidate(s) passed nationalMagic + header " +
                "plausibility + owned[] ⊆ seen[] + seen count <= $MAX_PLAUSIBLE_SEEN_COUNT."
        )

        return when (candidates.size) {
            0 -> Result.Failure(
                "Couldn't find the registered-Pokemon data in memory. Make sure a save is " +
                    "actually loaded (past the title/intro screen)."
            )
            1 -> buildSuccess(snapshot, offset = candidates[0], source = "$mode scan")
            else -> {
                candidates.take(MAX_LOGGED_CANDIDATES).forEach { offset ->
                    val owned = countSetBitsAt(snapshot, offset + POKEDEX_OWNED_OFFSET)
                    val seen = countSetBitsAt(snapshot, offset + POKEDEX_SEEN_OFFSET)
                    onDiagnostic("Calibration ($mode): candidate @0x${offset.toString(16)} - owned=$owned seen=$seen")
                }

                if (mode == RetroArchMemoryBridge.CommandMode.CORE_MEMORY) {
                    resolveViaIwramPointer(candidates, snapshot)?.let { return it }
                }

                Result.Failure(
                    "Found ${candidates.size} possible matches in memory - too ambiguous to trust. Try again."
                )
            }
        }
    }

    /**
     * A real gSaveBlock2Ptr global pointer variable lives somewhere in IWRAM (only 32KB -
     * cheap to scan in full) holding the exact address of the true SaveBlock2. Rather than
     * guess one fixed address (which failed - this hack's globals don't match vanilla
     * pokeemerald), scan all of IWRAM for a pointer matching any of our already-validated
     * candidates' computed addresses. A real pointer landing exactly on one of them is a
     * much stronger signal than any plausibility heuristic - it's not a coincidence.
     */
    private fun resolveViaIwramPointer(candidates: List<Int>, snapshot: ByteArray): Result? {
        val bridge = RetroArchMemoryBridge(host, port, commandMode = RetroArchMemoryBridge.CommandMode.CORE_MEMORY)
        // readRegion, not readMemory - a 32KB read as hex text in one UDP response would
        // blow past the receive buffer and silently fail to parse, same as dumpEwram()
        // needs chunking for its much larger 256KB read.
        val iwram = bridge.readRegion(IWRAM_BASE, IWRAM_SIZE) ?: run {
            onDiagnostic("Calibration: couldn't read IWRAM to disambiguate candidates.")
            return null
        }

        val expectedAddressToOffset = candidates.associateBy { offset -> EWRAM_BASE + offset - SAVEBLOCK2_TO_POKEDEX }

        val matches = mutableSetOf<Int>()
        for (i in 0..iwram.size - 4) {
            val value = readU32LE(iwram, i)
            expectedAddressToOffset[value]?.let { matches.add(it) }
        }

        onDiagnostic("Calibration: IWRAM pointer scan found ${matches.size} candidate(s) with a real pointer aimed at them.")

        return when (matches.size) {
            1 -> buildSuccess(snapshot, offset = matches.first(), source = "IWRAM pointer match")
            else -> null
        }
    }

    private fun countSetBitsAt(bytes: ByteArray, offset: Int): Int {
        var total = 0
        for (i in 0 until NUM_DEX_FLAG_BYTES) total += Integer.bitCount(bytes[offset + i].toUByteValue())
        return total
    }

    private fun buildSuccess(bytes: ByteArray, offset: Int, source: String): Result.Success {
        val nationalEnabled = bytes[offset + POKEDEX_NATIONAL_MAGIC_OFFSET] == NATIONAL_MAGIC_ENABLED
        val owned = bytes.copyOfRange(offset + POKEDEX_OWNED_OFFSET, offset + POKEDEX_OWNED_OFFSET + NUM_DEX_FLAG_BYTES)
        val seen = bytes.copyOfRange(offset + POKEDEX_SEEN_OFFSET, offset + POKEDEX_SEEN_OFFSET + NUM_DEX_FLAG_BYTES)
        onDiagnostic(
            "Calibration ($source): nationalMagic=${if (nationalEnabled) "0xDA" else "0x00"}, " +
                "owned bits set=${owned.countSetBits()}, seen bits set=${seen.countSetBits()}"
        )
        return Result.Success(nationalEnabled, owned, seen)
    }

    private fun validatePokedexStruct(bytes: ByteArray, offset: Int): Boolean {
        val magic = bytes.getOrNull(offset + POKEDEX_NATIONAL_MAGIC_OFFSET) ?: return false
        if (magic != NATIONAL_MAGIC_ENABLED && magic != NATIONAL_MAGIC_DISABLED) return false

        if (!validateSaveBlock2Header(bytes, offset - SAVEBLOCK2_TO_POKEDEX)) return false

        var ownedBitCount = 0
        var seenBitCount = 0
        for (i in 0 until NUM_DEX_FLAG_BYTES) {
            val ownedByte = bytes[offset + POKEDEX_OWNED_OFFSET + i].toUByteValue()
            val seenByte = bytes[offset + POKEDEX_SEEN_OFFSET + i].toUByteValue()
            ownedBitCount += Integer.bitCount(ownedByte)
            seenBitCount += Integer.bitCount(seenByte)
            if (ownedByte and seenByte.inv() != 0) return false
        }
        // Almost all coincidental matches have owned=0 (trivially satisfies the subset
        // constraint regardless of seen[]) - requiring at least one caught species cuts out
        // the vast majority of them, confirmed empirically: a real debug report showed
        // ~7800 candidates, all but a handful with owned=0.
        return ownedBitCount in 1..MAX_PLAUSIBLE_SEEN_COUNT && seenBitCount in ownedBitCount..MAX_PLAUSIBLE_SEEN_COUNT
    }

    /**
     * Cheap, single-snapshot plausibility checks on SaveBlock2's header fields (all fixed,
     * known offsets from include/global.h) - applied before the pricier owned/seen subset
     * scan, to cut down candidates that only coincidentally satisfy that constraint.
     */
    private fun validateSaveBlock2Header(bytes: ByteArray, saveBlock2Offset: Int): Boolean {
        if (saveBlock2Offset < 0) return false

        val gender = bytes.getOrNull(saveBlock2Offset + PLAYER_GENDER_OFFSET) ?: return false
        if (gender.toUByteValue() !in 0..1) return false

        val hours = readU16LE(bytes, saveBlock2Offset + PLAYTIME_HOURS_OFFSET)
        if (hours > MAX_PLAUSIBLE_PLAYTIME_HOURS) return false

        val minutes = bytes.getOrNull(saveBlock2Offset + PLAYTIME_MINUTES_OFFSET)?.toUByteValue() ?: return false
        if (minutes >= WRAP_MINUTES_SECONDS) return false

        val seconds = bytes.getOrNull(saveBlock2Offset + PLAYTIME_SECONDS_OFFSET)?.toUByteValue() ?: return false
        if (seconds >= WRAP_MINUTES_SECONDS) return false

        val vblanks = bytes.getOrNull(saveBlock2Offset + PLAYTIME_VBLANKS_OFFSET)?.toUByteValue() ?: return false
        if (vblanks >= WRAP_MINUTES_SECONDS) return false

        // Real names don't contain Gen3 text control codes (scroll/paragraph/newline).
        for (i in 0 until PLAYER_NAME_LENGTH) {
            val b = bytes.getOrNull(saveBlock2Offset + PLAYER_NAME_OFFSET + i)?.toUByteValue() ?: return false
            if (b in 0xFA..0xFE) return false
        }

        return true
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
            val pokedexOffset = ownedOffset - POKEDEX_OWNED_OFFSET
            if (validatePokedexStruct(snapshot, pokedexOffset)) {
                candidates.add(pokedexOffset)
            }
            ownedOffset++
        }

        return candidates
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toUByteValue()) or
            (bytes[offset + 1].toUByteValue() shl 8) or
            (bytes[offset + 2].toUByteValue() shl 16) or
            (bytes[offset + 3].toUByteValue() shl 24)

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toUByteValue()) or (bytes[offset + 1].toUByteValue() shl 8)

    private fun Byte.toUByteValue(): Int = this.toInt() and 0xFF

    private fun ByteArray.countSetBits(): Int = sumOf { Integer.bitCount(it.toUByteValue()) }
}
