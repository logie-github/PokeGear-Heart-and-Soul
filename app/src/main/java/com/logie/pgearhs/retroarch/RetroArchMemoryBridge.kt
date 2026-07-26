package com.logie.pgearhs.retroarch

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Talks to RetroArch's UDP Network Command Interface (the same protocol behind its
 * remote-control feature) to peek at a running core's memory. This is NOT RetroArch
 * netplay - see the pokemonresort-retroarch-udp project note for that distinction.
 *
 * GBA cores (mGBA, the common default) expose EWRAM via the real GBA CPU bus address,
 * starting at 0x02000000 for 0x40000 (256KB) bytes - that's the assumption baked into
 * [EWRAM_BASE]/[EWRAM_SIZE] below. Unverified against a real running instance.
 */
class RetroArchMemoryBridge(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 2000,
    private val retries: Int = 2
) {
    companion object {
        const val EWRAM_BASE = 0x02000000
        const val EWRAM_SIZE = 0x40000
        private const val CHUNK_SIZE = 4096
        private const val RECEIVE_BUFFER_SIZE = 16384
    }

    /** Raw command/response round-trip. Returns null if no reply was received. */
    fun sendCommand(command: String): String? {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(host)
            val requestBytes = command.toByteArray(StandardCharsets.US_ASCII)
            val requestPacket = DatagramPacket(requestBytes, requestBytes.size, address, port)

            repeat(retries + 1) {
                try {
                    socket.send(requestPacket)
                    val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)
                    return String(buffer, 0, responsePacket.length, StandardCharsets.US_ASCII)
                } catch (_: java.net.SocketTimeoutException) {
                    // retry
                }
            }
            return null
        }
    }

    fun isReachable(): Boolean = sendCommand("GET_STATUS") != null

    /** Reads [length] bytes starting at absolute [address]. Null on failure/no reply. */
    fun readMemory(address: Int, length: Int): ByteArray? {
        val response = sendCommand("READ_CORE_MEMORY ${address.toString(16)} $length") ?: return null
        return parseReadResponse(response)
    }

    private fun parseReadResponse(response: String): ByteArray? {
        val tokens = response.trim().split(Regex("\\s+"))
        // Expected shape: "READ_CORE_MEMORY <addr> <hex bytes...>"
        if (tokens.size < 3 || !tokens[0].equals("READ_CORE_MEMORY", ignoreCase = true)) return null

        val hexTokens = tokens.drop(2)
        val hex = hexTokens.joinToString("")
        if (hex.isEmpty() || hex.length % 2 != 0) return null

        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Reads a large region in [CHUNK_SIZE] pieces. Returns null if any chunk fails. */
    fun readRegion(baseAddress: Int, totalLength: Int): ByteArray? {
        val result = ByteArray(totalLength)
        var offset = 0
        while (offset < totalLength) {
            val thisChunkSize = minOf(CHUNK_SIZE, totalLength - offset)
            val chunk = readMemory(baseAddress + offset, thisChunkSize) ?: return null
            if (chunk.size != thisChunkSize) return null
            chunk.copyInto(result, offset)
            offset += thisChunkSize
        }
        return result
    }

    fun dumpEwram(): ByteArray? = readRegion(EWRAM_BASE, EWRAM_SIZE)
}
