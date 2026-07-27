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
 * RetroArch exposes two different read commands, which can address memory differently
 * depending on whether the running core implements the full libretro memory-map
 * descriptor interface:
 *   - READ_CORE_MEMORY: addresses via the core's memory map, i.e. the real GBA CPU bus
 *     address for cores that implement it (EWRAM at 0x02000000).
 *   - READ_CORE_RAM: addresses via the simpler retro_get_memory_data/size interface, a
 *     flat 0-based offset into the core's system RAM buffer (EWRAM at 0x0).
 * If the running core doesn't implement memory-map descriptors, READ_CORE_MEMORY can
 * silently return the wrong region (no error, just not EWRAM) rather than failing
 * outright - PokemonResort's Gen2/Gen3 support retries with the other command on failure
 * for exactly this reason.
 */
class RetroArchMemoryBridge(
    private val host: String,
    private val port: Int,
    private val commandMode: CommandMode = CommandMode.CORE_MEMORY,
    private val timeoutMs: Int = 2000,
    private val retries: Int = 2
) {
    enum class CommandMode(val readCommand: String, val writeCommand: String, val ewramBase: Int) {
        CORE_MEMORY("READ_CORE_MEMORY", "WRITE_CORE_MEMORY", 0x02000000),
        CORE_RAM("READ_CORE_RAM", "WRITE_CORE_RAM", 0x00000000)
    }

    companion object {
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
        val response = sendCommand("${commandMode.readCommand} ${address.toString(16)} $length") ?: return null
        return parseReadResponse(response)
    }

    /**
     * Writes [bytes] starting at absolute [address]. RetroArch doesn't echo the written
     * bytes back - a non-null reply to the WRITE_* command is the only confirmation
     * available, so this can't verify the write actually landed the way [readMemory] can
     * verify a read. Callers that need certainty should read the address back afterward.
     */
    fun writeMemory(address: Int, bytes: ByteArray): Boolean {
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return sendCommand("${commandMode.writeCommand} ${address.toString(16)} $hex") != null
    }

    private fun parseReadResponse(response: String): ByteArray? {
        val tokens = response.trim().split(Regex("\\s+"))
        // Expected shape: "<READ_CORE_MEMORY|READ_CORE_RAM> <addr> <hex bytes...>"
        if (tokens.size < 3 || !tokens[0].equals(commandMode.readCommand, ignoreCase = true)) return null

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

    fun dumpEwram(): ByteArray? = readRegion(commandMode.ewramBase, EWRAM_SIZE)
}
