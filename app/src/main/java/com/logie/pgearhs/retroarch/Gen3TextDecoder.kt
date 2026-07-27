package com.logie.pgearhs.retroarch

/**
 * Decodes Gen3 save-data text, which uses a custom single-byte character table instead of
 * ASCII (pret pokeemerald's charmap.txt / include/constants/characters.h convention - 0x00
 * is space, 0xA1-0xAA are '0'-'9', 0xBB-0xD4 are 'A'-'Z', 0xD5-0xEE are 'a'-'z', 0xFF
 * terminates the string). Only covers what trainer/player names actually use; anything else
 * renders as '?' rather than failing outright.
 */
object Gen3TextDecoder {
    private const val TERMINATOR = 0xFF

    fun decode(bytes: ByteArray): String {
        val builder = StringBuilder()
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            if (b == TERMINATOR) break
            builder.append(charFor(b))
        }
        return builder.toString()
    }

    private fun charFor(b: Int): Char = when (b) {
        0x00 -> ' '
        in 0xA1..0xAA -> '0' + (b - 0xA1)
        in 0xBB..0xD4 -> 'A' + (b - 0xBB)
        in 0xD5..0xEE -> 'a' + (b - 0xD5)
        0xAB -> '!'
        0xAC -> '?'
        0xAD -> '.'
        0xAE -> '-'
        0xB0 -> '\''
        else -> '?'
    }
}
