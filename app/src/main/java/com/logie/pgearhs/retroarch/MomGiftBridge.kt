package com.logie.pgearhs.retroarch

/**
 * Writes purchased items straight into the player's bag over the RetroArch bridge - for
 * MomGiftManager's "Mom bought you an item" feature, not anything native to this hack.
 *
 * Pocket base offsets within SaveBlock1 and slot capacities are taken directly from
 * disassembling `SetBagItemsPointers()` in pokemonHnS-v121/pokemonHnS.elf (the build matching
 * this ROM's tag) - not the documented/vanilla offsets, which this hack has already been shown
 * to drift from. `gBagPockets[ITEMS_POCKET].itemSlots = gSaveBlock1Ptr + 0x560` (unshifted -
 * matches vanilla, same as money's own offset) and every pocket *after* Items is shifted by a
 * consistent +0xF0 from its documented position, fully explained by this hack raising
 * `BAG_ITEMS_COUNT` from vanilla's 30 to 90 (60 extra 4-byte slots = 0xF0 bytes), which pushes
 * every subsequent pocket back by exactly that much:
 *   ITEMS_POCKET   @ SaveBlock1+0x560, 90 slots (src/item.c: BAG_ITEMS_COUNT)
 *   BERRIES_POCKET @ SaveBlock1+0x880, 46 slots (src/item.c: BAG_BERRIES_COUNT)
 * Only these two pockets are wired up - every item MomGiftManager can grant is either a
 * regular item/held item (ITEMS_POCKET) or a berry (BERRIES_POCKET).
 *
 * Each slot is `struct ItemSlot { u16 itemId; u16 quantity; }` (4 bytes) - quantity is
 * XOR-encrypted against `gSaveBlock2Ptr->encryptionKey` exactly like money (`item.c`'s
 * `GetBagItemQuantity`/`SetBagItemQuantity`), using the same confirmed 0xBC key offset.
 */
class MomGiftBridge(
    private val host: String,
    private val port: Int,
    private val onDiagnostic: (String) -> Unit = {}
) {
    enum class Pocket(val offsetInSaveBlock1: Int, val capacity: Int) {
        ITEMS(0x560, 90),
        BERRIES(0x880, 46)
    }

    companion object {
        private const val SAVEBLOCK1_PTR_ADDR = 0x03003740
        private const val SAVEBLOCK2_PTR_ADDR = 0x03003744
        private const val ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2 = 0xBC
        private const val SLOT_SIZE = 4
        private const val ITEM_NONE = 0

        private val EWRAM_BASE = RetroArchMemoryBridge.CommandMode.CORE_MEMORY.ewramBase
        private const val EWRAM_SIZE = RetroArchMemoryBridge.EWRAM_SIZE
    }

    /**
     * Adds [quantity] of [itemId] to [pocket] - stacks onto an existing slot for the same
     * item if one exists (and has room), otherwise uses the first empty slot. Returns false
     * (without partially writing anything) if the pocket has no existing stack AND no empty
     * slot - a genuinely full bag, which the caller should treat as "still pending, retry
     * later" rather than losing the gift.
     */
    suspend fun addItem(itemId: Int, quantity: Int, pocket: Pocket): Boolean {
        val bridge = RetroArchMemoryBridge(host, port)
        val saveBlock1Address = resolveSaveBlock1Address(bridge) ?: return false
        val encryptionKey = readEncryptionKey(bridge) ?: run {
            onDiagnostic("! Mom gift: couldn't read encryptionKey, can't deliver.")
            return false
        }

        val pocketAddress = saveBlock1Address + pocket.offsetInSaveBlock1
        val raw = bridge.readRegion(pocketAddress, pocket.capacity * SLOT_SIZE) ?: run {
            onDiagnostic("! Mom gift: couldn't read ${pocket.name} pocket.")
            return false
        }

        var emptySlotIndex = -1
        for (i in 0 until pocket.capacity) {
            val slotItemId = readU16LE(raw, i * SLOT_SIZE)
            if (slotItemId == itemId) {
                val encryptedQuantity = readU16LE(raw, i * SLOT_SIZE + 2)
                val currentQuantity = encryptedQuantity xor (encryptionKey and 0xFFFF)
                val newQuantity = (currentQuantity + quantity).coerceAtMost(999)
                return writeSlot(bridge, pocketAddress + i * SLOT_SIZE, itemId, newQuantity, encryptionKey)
            }
            if (slotItemId == ITEM_NONE && emptySlotIndex == -1) {
                emptySlotIndex = i
            }
        }

        if (emptySlotIndex == -1) {
            onDiagnostic("! Mom gift: ${pocket.name} pocket is full, item $itemId x$quantity still pending.")
            return false
        }
        return writeSlot(bridge, pocketAddress + emptySlotIndex * SLOT_SIZE, itemId, quantity, encryptionKey)
    }

    private fun writeSlot(bridge: RetroArchMemoryBridge, slotAddress: Int, itemId: Int, quantity: Int, encryptionKey: Int): Boolean {
        val encryptedQuantity = quantity xor (encryptionKey and 0xFFFF)
        val bytes = byteArrayOf(
            (itemId and 0xFF).toByte(),
            ((itemId shr 8) and 0xFF).toByte(),
            (encryptedQuantity and 0xFF).toByte(),
            ((encryptedQuantity shr 8) and 0xFF).toByte()
        )
        val ok = bridge.writeMemory(slotAddress, bytes)
        onDiagnostic(
            if (ok) "Mom gift: wrote item $itemId x$quantity @0x${slotAddress.toString(16)}."
            else "! Mom gift: write to 0x${slotAddress.toString(16)} not confirmed by RetroArch."
        )
        return ok
    }

    private fun readEncryptionKey(bridge: RetroArchMemoryBridge): Int? {
        val ptrBytes = bridge.readMemory(SAVEBLOCK2_PTR_ADDR, 4) ?: return null
        val saveBlock2Address = readU32LE(ptrBytes, 0)
        if (saveBlock2Address < EWRAM_BASE || saveBlock2Address >= EWRAM_BASE + EWRAM_SIZE) return null
        return bridge.readMemory(saveBlock2Address + ENCRYPTION_KEY_OFFSET_IN_SAVEBLOCK2, 4)?.let { readU32LE(it, 0) }
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
