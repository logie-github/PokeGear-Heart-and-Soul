package com.logie.pgearhs.retroarch

/**
 * SaveBlock1/SaveBlock2 are runtime-allocated - there's no fixed compile-time address to
 * read from (no .map/.elf exists for this exact build). Two strategies, tried in order:
 *
 * 1. Known-pointer fast path: tries a short list of candidate fixed IWRAM addresses for
 *    gSaveBlock2Ptr itself (a global pointer *variable*, not the struct - the struct it
 *    points to is dynamically allocated in EWRAM). See KNOWN_SAVEBLOCK2_PTR_ADDRS for what
 *    each candidate is and its confidence level. If any resolves to a valid pokedex struct,
 *    that's the real runtime EWRAM address directly - no scanning needed at all.
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
    private val onDiagnostic: (String) -> Unit = {},
    /**
     * If you know exactly how many species are currently owned/seen (e.g. the player just
     * told you), this searches for an exact bit-count match *without* the owned⊆seen
     * subset constraint or the SaveBlock2 header plausibility checks - useful when those
     * assumptions might themselves be wrong and are hiding the real struct. Temporary/
     * manual debugging aid for now; worth wiring into a real UI field if it keeps proving
     * useful.
     */
    private val knownOwnedCount: Int? = null,
    private val knownSeenCount: Int? = null
) {

    companion object {
        // Candidate addresses for gSaveBlock2Ptr's own IWRAM location, tried in order.
        // - 0x03003744: high confidence. Reproduced identically across 4 independent
        //   from-source rebuilds - two GCC major versions (14.2.1, 13.2.1) AND a
        //   self-built GCC 13.2.0 matching the exact devkitARM version this project's own
        //   build log (log.txt) shows was used, AND two source revisions (the official
        //   repo's main branch and the tagged Release-v1.2.1 the actual distribution comes
        //   from - github.com/PokemonHnS-Development/pokemonHnS). All four converge on the
        //   same address, and a real debug report (app 1.0.8) dereferencing it returned a
        //   plausible in-range EWRAM address (0x0200e784), not garbage. It was previously
        //   rejected as "not a valid pokedex struct" only because of a real bug: the code
        //   read bytes starting at the pokedex struct itself rather than at SaveBlock2,
        //   so validateSaveBlock2Header() could never see the header fields it checks
        //   (negative offset -> its own guard clause rejected every candidate here
        //   unconditionally, regardless of whether the address was right). Fixed below by
        //   reading from saveBlock2Address instead of pokedexAddress.
        // - 0x03005D90: pret/pokeemerald symbols (github.com/pret/pokeemerald, `symbols`
        //   branch), the public reference for vanilla Emerald. Confirmed NOT to hold for
        //   this hack (every real debug report resolves it outside EWRAM) - kept as a
        //   last-resort fallback since trying it costs one cheap extra UDP round trip.
        private val KNOWN_SAVEBLOCK2_PTR_ADDRS = listOf(0x03003744, 0x03005D90)

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE

        // IWRAM is where a real gSaveBlock2Ptr global pointer variable would live (see
        // KNOWN_SAVEBLOCK2_PTR_ADDR above) - only 32KB, cheap to scan in full.
        private const val IWRAM_BASE = 0x03000000
        private const val IWRAM_SIZE = 0x8000

        private const val SAVEBLOCK2_TO_POKEDEX = 0x18
        private const val POKEDEX_NATIONAL_MAGIC_OFFSET = 0x02
        private const val NUM_DEX_FLAG_BYTES = 58 // ROUND_BITS_TO_BYTES(SPECIES_EGG=462)
        // 0x14, not vanilla's 0x10 - confirmed 2026-07-27 from a real sync where every one
        // of 7 owned species came back shifted by exactly 4 bytes/32 bits (same bit-in-byte,
        // byteIndex+4) versus what the player actually caught. This hack's Pokedex struct
        // has 4 extra bytes of something (not investigated) between unknown3 and owned[]
        // that vanilla Emerald's include/global.h doesn't have.
        private const val POKEDEX_OWNED_OFFSET = 0x14
        private const val POKEDEX_SEEN_OFFSET = POKEDEX_OWNED_OFFSET + NUM_DEX_FLAG_BYTES
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

    /** Tries each candidate gSaveBlock2Ptr address in turn. Null = none panned out, fall through. */
    private fun tryKnownPointer(): Result? {
        for (addr in KNOWN_SAVEBLOCK2_PTR_ADDRS) {
            tryKnownPointerAt(addr)?.let { return it }
        }
        return null
    }

    private fun tryKnownPointerAt(knownAddr: Int): Result? {
        val bridge = RetroArchMemoryBridge(host, port, commandMode = RetroArchMemoryBridge.CommandMode.CORE_MEMORY)

        val ptrBytes = bridge.readMemory(knownAddr, 4) ?: run {
            onDiagnostic("Calibration: couldn't read candidate gSaveBlock2Ptr address 0x${knownAddr.toString(16)}.")
            return null
        }
        val saveBlock2Address = readU32LE(ptrBytes, 0)
        onDiagnostic("Calibration: gSaveBlock2Ptr @0x${knownAddr.toString(16)} = 0x${saveBlock2Address.toString(16)}")

        if (saveBlock2Address < EWRAM_BASE || saveBlock2Address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Calibration: that's outside EWRAM - not this hack's real gSaveBlock2Ptr location.")
            return null
        }

        // Read from saveBlock2Address itself, not pokedexAddress - validatePokedexStruct()
        // checks SaveBlock2 header fields (playerName/gender/playtime) that live *before*
        // the pokedex struct (at SaveBlock2+0x18), via validateSaveBlock2Header(bytes,
        // offset - SAVEBLOCK2_TO_POKEDEX). Reading starting at pokedexAddress left those
        // header bytes inaccessible (negative offset), so validateSaveBlock2Header's
        // `if (saveBlock2Offset < 0) return false` guard rejected every candidate here
        // unconditionally - this path could never succeed regardless of address, a bug
        // independent of whether the known-pointer address itself is right.
        val readLength = SAVEBLOCK2_TO_POKEDEX + POKEDEX_STRUCT_READ_BYTES
        val block = bridge.readMemory(saveBlock2Address, readLength) ?: run {
            onDiagnostic("Calibration: pointer looked valid but reading SaveBlock2 failed.")
            return null
        }

        logKnownPointerDiagnostics(block)

        // requireKnownMagic = false: this address is already trusted independently (real
        // IWRAM pointer, from-source rebuild, and a header that validates + advances
        // correctly across sessions - see the class doc comment), so the nationalMagic
        // byte isn't needed as a discriminator here the way it is in the full EWRAM scan.
        // Real debug reports show it consistently reading 0xc3 - not vanilla Emerald's
        // 0xDA/0x00 - most likely because this hack uses a different anti-tamper constant,
        // not because the struct is misaligned (everything else at this offset - header,
        // zero-padding region, owned[] bit count advancing plausibly between sessions -
        // lines up exactly where expected).
        val validated = validatePokedexStruct(block, offset = SAVEBLOCK2_TO_POKEDEX, requireKnownMagic = false)
        if (!validated) {
            onDiagnostic("Calibration: candidate pointer resolved to EWRAM, but the data there isn't a valid pokedex struct.")
            return null
        }

        return buildSuccess(block, offset = SAVEBLOCK2_TO_POKEDEX, source = "known pointer 0x${knownAddr.toString(16)}")
    }

    /**
     * Verbatim diagnostic dump of everything validatePokedexStruct() checks for the
     * known-pointer path, logged unconditionally (pass or fail) - this address is
     * confirmed correct (byte-verified directly against the real ROM's own literal pool,
     * not inferred from a rebuild), so if validation still fails here the bug is in one of
     * these checks/offsets, not the address. This pinpoints which one.
     */
    private fun logKnownPointerDiagnostics(block: ByteArray) {
        val pokedexOffset = SAVEBLOCK2_TO_POKEDEX
        val magic = block.getOrNull(pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET)?.toUByteValue()
        val owned = countSetBitsAt(block, pokedexOffset + POKEDEX_OWNED_OFFSET)
        val seen = countSetBitsAt(block, pokedexOffset + POKEDEX_SEEN_OFFSET)

        val gender = block.getOrNull(PLAYER_GENDER_OFFSET)?.toUByteValue()
        val hours = readU16LE(block, PLAYTIME_HOURS_OFFSET)
        val minutes = block.getOrNull(PLAYTIME_MINUTES_OFFSET)?.toUByteValue()
        val seconds = block.getOrNull(PLAYTIME_SECONDS_OFFSET)?.toUByteValue()
        val vblanks = block.getOrNull(PLAYTIME_VBLANKS_OFFSET)?.toUByteValue()
        val nameBytes = (0 until PLAYER_NAME_LENGTH).map { block.getOrNull(PLAYER_NAME_OFFSET + it)?.toUByteValue() }
        val headerOk = validateSaveBlock2Header(block, 0)

        val dumpLen = block.size
        val hex = block.copyOfRange(0, dumpLen).joinToString(" ") { "%02x".format(it.toUByteValue()) }

        onDiagnostic(
            "Calibration diag: nationalMagic=0x${magic?.toString(16) ?: "?"} ownedBits=$owned seenBits=$seen " +
                "headerPlausible=$headerOk"
        )
        onDiagnostic(
            "Calibration diag: gender=$gender hours=$hours minutes=$minutes seconds=$seconds vblanks=$vblanks " +
                "nameBytes=$nameBytes"
        )
        onDiagnostic("Calibration diag: first $dumpLen bytes from SaveBlock2 = $hex")
    }

    private suspend fun attemptStructuralScan(mode: RetroArchMemoryBridge.CommandMode): Result {
        val bridge = RetroArchMemoryBridge(host, port, commandMode = mode)

        val dumpStart = System.currentTimeMillis()
        val snapshot = bridge.dumpEwram() ?: run {
            onDiagnostic("Calibration ($mode): EWRAM dump failed (chunked read error).")
            return Result.Failure("Memory read failed.")
        }
        onDiagnostic("Calibration ($mode): dump took ${System.currentTimeMillis() - dumpStart}ms (${snapshot.size} bytes).")

        if (knownOwnedCount != null && knownSeenCount != null) {
            val exactMatches = findExactCountCandidates(snapshot, knownOwnedCount, knownSeenCount)
            onDiagnostic(
                "Calibration ($mode): exact-count search (owned=$knownOwnedCount seen=$knownSeenCount, " +
                    "no subset/header constraints) found ${exactMatches.size} match(es)" +
                    (if (exactMatches.isNotEmpty()) " @ ${exactMatches.joinToString { "0x" + it.toString(16) }}" else "")
            )
            if (exactMatches.size == 1) {
                return buildSuccess(snapshot, offset = exactMatches[0], source = "$mode exact-count match")
            }
            if (exactMatches.size > 1) {
                // An exact owned/seen bit-count match is a far stronger signal than the
                // structural checks below - worth disambiguating on its own terms (real
                // IWRAM pointer, then header plausibility) before falling through to the
                // much noisier structural scan.
                if (mode == RetroArchMemoryBridge.CommandMode.CORE_MEMORY) {
                    resolveViaIwramPointer(exactMatches, snapshot, source = "$mode exact-count + IWRAM pointer")?.let { return it }
                }
                val headerValidated = exactMatches.filter { validateSaveBlock2Header(snapshot, it - SAVEBLOCK2_TO_POKEDEX) }
                onDiagnostic(
                    "Calibration ($mode): of those, ${headerValidated.size} also pass SaveBlock2 header " +
                        "plausibility" + (if (headerValidated.isNotEmpty()) " @ ${headerValidated.joinToString { "0x" + it.toString(16) }}" else "")
                )
                if (headerValidated.size == 1) {
                    return buildSuccess(snapshot, offset = headerValidated[0], source = "$mode exact-count + header match")
                }
            }
        }

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
    private fun resolveViaIwramPointer(candidates: List<Int>, snapshot: ByteArray, source: String = "IWRAM pointer match"): Result? {
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
            1 -> buildSuccess(snapshot, offset = matches.first(), source = source)
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

    private fun validatePokedexStruct(bytes: ByteArray, offset: Int, requireKnownMagic: Boolean = true): Boolean {
        if (requireKnownMagic) {
            val magic = bytes.getOrNull(offset + POKEDEX_NATIONAL_MAGIC_OFFSET) ?: return false
            if (magic != NATIONAL_MAGIC_ENABLED && magic != NATIONAL_MAGIC_DISABLED) return false
        }

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
     * Bare-minimum search: only requires a valid nationalMagic byte, then an exact bit-count
     * match on owned[]/seen[] - no subset constraint, no header plausibility checks. Used
     * when we know the real counts (the player just told us) and want to rule out one of
     * the other assumptions being wrong and hiding the real struct.
     */
    private fun findExactCountCandidates(snapshot: ByteArray, expectedOwned: Int, expectedSeen: Int): List<Int> {
        val candidates = mutableListOf<Int>()
        val lastOwnedOffset = snapshot.size - 2 * NUM_DEX_FLAG_BYTES

        var ownedOffset = NATIONAL_MAGIC_TO_OWNED
        while (ownedOffset < lastOwnedOffset) {
            val pokedexOffset = ownedOffset - POKEDEX_OWNED_OFFSET
            val magic = snapshot.getOrNull(pokedexOffset + POKEDEX_NATIONAL_MAGIC_OFFSET)
            if (magic == NATIONAL_MAGIC_ENABLED || magic == NATIONAL_MAGIC_DISABLED) {
                val owned = countSetBitsAt(snapshot, ownedOffset)
                if (owned == expectedOwned) {
                    val seen = countSetBitsAt(snapshot, ownedOffset + NUM_DEX_FLAG_BYTES)
                    if (seen == expectedSeen) candidates.add(pokedexOffset)
                }
            }
            ownedOffset++
        }

        return candidates
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
