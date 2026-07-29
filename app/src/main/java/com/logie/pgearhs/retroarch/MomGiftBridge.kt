package com.logie.pgearhs.retroarch

/**
 * Writes purchased items straight into the player's PC item storage over the RetroArch
 * bridge - for MomGiftManager's "Mom bought you an item" feature, not anything native to
 * this hack.
 *
 * `gSaveBlock1Ptr->pcItems[PC_ITEMS_COUNT]` is a genuinely separate storage array from the
 * bag (`src/player_pc.c`'s "Item Storage" PC menu option - distinct from "Decoration" and
 * "Mailbox" in the same menu, so it really is general item storage, not a decoration-only
 * thing). Confirmed via disassembling `AddPCItem()` in pokemonHnS-v121/pokemonHnS.elf (the
 * build matching this ROM's tag) rather than trusting the documented offset:
 * `movs r3,#147; lsls r3,r3,#3` = 147*8 = 0x498, matching the vanilla-documented position
 * exactly (unshifted, same as money's own offset - makes sense, it sits right after money in
 * the struct and nothing between them has grown). Capacity 50 slots (`PC_ITEMS_COUNT`,
 * `AllocZeroed(200)` = 50 * 4-byte slots, also matches documented).
 *
 * Unlike bag items, PC item quantities are NOT encrypted - checked `GetPCItemQuantity`/
 * `SetPCItemQuantity` in `src/item.c` directly, they're a plain `*quantity = newValue`, no
 * XOR against `encryptionKey` at all. Only `itemId` needs to be a valid, sane u16; no
 * encryption key lookup needed for PC delivery.
 */
class MomGiftBridge(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {
    companion object {
        private const val SAVEBLOCK1_PTR_ADDR = 0x03003740
        private const val PC_ITEMS_OFFSET_IN_SAVEBLOCK1 = 0x498
        private const val PC_ITEMS_CAPACITY = 50
        private const val SLOT_SIZE = 4
        private const val ITEM_NONE = 0
        private const val MAX_PC_ITEM_CAPACITY = 999

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE
    }

    /**
     * Adds [quantity] of [itemId] to PC storage - stacks onto an existing slot for the same
     * item if one exists (and has room), otherwise uses the first empty slot. Returns false
     * (without partially writing anything) if there's no existing stack AND no empty slot - a
     * genuinely full PC, which the caller should treat as "still pending, retry later" rather
     * than losing the gift.
     */
    suspend fun addToPC(itemId: Int, quantity: Int): Boolean {
        val bridge = RetroArchMemoryBridge(host, port)
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return false

        val pcAddress = saveBlock1Address + PC_ITEMS_OFFSET_IN_SAVEBLOCK1
        val raw = bridge.readRegion(pcAddress, PC_ITEMS_CAPACITY * SLOT_SIZE) ?: run {
            onDiagnostic("! Mom gift: couldn't read PC item storage.")
            return false
        }

        var emptySlotIndex = -1
        for (i in 0 until PC_ITEMS_CAPACITY) {
            val slotItemId = readU16LE(raw, i * SLOT_SIZE)
            if (slotItemId == itemId) {
                val currentQuantity = readU16LE(raw, i * SLOT_SIZE + 2)
                val newQuantity = (currentQuantity + quantity).coerceAtMost(MAX_PC_ITEM_CAPACITY)
                return writeSlot(bridge, pcAddress + i * SLOT_SIZE, itemId, newQuantity)
            }
            if (slotItemId == ITEM_NONE && emptySlotIndex == -1) {
                emptySlotIndex = i
            }
        }

        if (emptySlotIndex == -1) {
            onDiagnostic("! Mom gift: PC item storage is full, item $itemId x$quantity still pending.")
            return false
        }
        return writeSlot(bridge, pcAddress + emptySlotIndex * SLOT_SIZE, itemId, quantity)
    }

    private fun writeSlot(bridge: RetroArchMemoryBridge, slotAddress: Int, itemId: Int, quantity: Int): Boolean {
        val bytes = byteArrayOf(
            (itemId and 0xFF).toByte(),
            ((itemId shr 8) and 0xFF).toByte(),
            (quantity and 0xFF).toByte(),
            ((quantity shr 8) and 0xFF).toByte()
        )
        val ok = bridge.writeMemory(slotAddress, bytes)
        onDiagnostic(
            if (ok) "Mom gift: wrote item $itemId x$quantity to PC @0x${slotAddress.toString(16)}."
            else "! Mom gift: write to 0x${slotAddress.toString(16)} not confirmed by RetroArch."
        )
        return ok
    }

    private fun resolveSaveBlock1Address(bridge: RetroArchMemoryBridge): Int? {
        val ptrBytes = bridge.readMemory(SAVEBLOCK1_PTR_ADDR, 4) ?: return null
        val address = readU32LE(ptrBytes, 0)
        if (address < EWRAM_BASE || address >= EWRAM_BASE + EWRAM_SIZE) {
            onDiagnostic("Mom gift: gSaveBlock1Ptr resolved outside EWRAM (0x${address.toString(16)}).")
            return null
        }
        return address
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
