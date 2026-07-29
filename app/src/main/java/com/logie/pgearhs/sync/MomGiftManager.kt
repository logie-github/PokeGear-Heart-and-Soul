package com.logie.pgearhs.sync

import android.content.Context
import com.logie.pgearhs.R
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.MomGiftBridge
import com.logie.pgearhs.retroarch.RetroArchMemoryBridge
import com.logie.pgearhs.retroarch.RetroArchOsdPrefs
import com.logie.pgearhs.ui.GlobalDialogueNotices

/**
 * When the player's savings (see BattleMoneyTracker - money sent to Mom out of battle
 * winnings) reach certain thresholds, Mom buys a gift and it's delivered straight to the
 * player's bag - modeled on the real "Mom's savings" mechanic from later Pokemon games, but
 * entirely app-invented here (this hack has no native equivalent - checked, nothing in the
 * decomp source references a delivery man, savings account, or gift system at all).
 *
 * Two kinds of gift:
 *  - A fixed one-time item at a specific dollar threshold (ONCE_ITEMS below) - each only ever
 *    granted once per save.
 *  - Every time savings reach or exceed a new multiple of $3000 that ISN'T also a one-time
 *    threshold, 5 of a random berry instead.
 *
 * Two of the requested one-time items (Choice Scarf, Focus Sash) don't exist in this hack's
 * compiled item list - it only has the Gen3-era equivalents (Choice Band, Focus Band), used
 * here instead per the user's choice. The third (Muscle Band) has no Gen3 equivalent at all
 * and was dropped entirely - nothing is granted at that dollar amount. The berry pool is
 * every berry this hack actually has (Cheri through Enigma, item ids 133-175), not the
 * (nonexistent-here) Gen4 type-resist berries originally described.
 *
 * A gift is "granted" (queued) as soon as its threshold is crossed, but only "delivered"
 * (actually written into the bag) once RetroArch is reachable and the target pocket has
 * room - see PENDING_CAP below for why granting and delivering are different moments.
 */
object MomGiftManager {
    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_PURCHASED_ONCE_ITEMS = "mom_gift_purchased_once_items"
    private const val KEY_NEXT_BERRY_MULTIPLE = "mom_gift_next_berry_multiple"
    private const val KEY_PENDING_ITEMS = "mom_gift_pending_items"
    private const val BERRY_THRESHOLD_STEP = 3000

    // Matches the real mechanic this is modeled on: gifts queue up for delivery rather than
    // being granted instantly, and once 5 are already queued, further threshold crossings
    // wait - not lost, just re-checked on the next deposit once the queue has room again
    // (see grantEligibleGifts). Berry batches are exempt from this cap (best reading of an
    // ambiguous source note saying berries "ignore the pickup limit" - flagged to the user,
    // adjust ONCE_ITEM_PENDING_CAP/this exemption if that reading turns out wrong).
    private const val ONCE_ITEM_PENDING_CAP = 5

    data class OnceItem(val key: String, val thresholdDollars: Int, val itemId: Int, val displayName: String)
    data class PendingGift(val itemId: Int, val quantity: Int, val displayName: String, val pocket: MomGiftBridge.Pocket)

    // Item ids from pokemonHnS-v121/include/constants/items.h (this hack's actual compiled
    // list, not vanilla Emerald's - checked directly, not assumed).
    private const val ITEM_HYPER_POTION = 29
    private const val ITEM_SUPER_POTION = 30
    private const val ITEM_REPEL = 86
    private const val ITEM_MOON_STONE = 94
    private const val ITEM_CHOICE_BAND = 186
    private const val ITEM_FOCUS_BAND = 196
    private const val ITEM_SILK_SCARF = 217

    private val ONCE_ITEMS = listOf(
        OnceItem("super_potion_900", 900, ITEM_SUPER_POTION, "Super Potion"),
        OnceItem("repel_4000", 4000, ITEM_REPEL, "Repel"),
        OnceItem("super_potion_7000", 7000, ITEM_SUPER_POTION, "Super Potion"),
        OnceItem("silk_scarf_10000", 10000, ITEM_SILK_SCARF, "Silk Scarf"),
        OnceItem("moon_stone_15000", 15000, ITEM_MOON_STONE, "Moon Stone"),
        OnceItem("hyper_potion_19000", 19000, ITEM_HYPER_POTION, "Hyper Potion"),
        OnceItem("choice_band_30000", 30000, ITEM_CHOICE_BAND, "Choice Band"),
        // $40000 (originally Muscle Band, which doesn't exist in this hack) intentionally
        // skipped - no gift at that threshold.
        OnceItem("focus_band_50000", 50000, ITEM_FOCUS_BAND, "Focus Band"),
    ).sortedBy { it.thresholdDollars }

    // Cheri (133) through Enigma (175) - this hack's full vanilla Gen3 berry roster, in id
    // order (include/constants/items.h: FIRST_BERRY_INDEX/LAST_BERRY_INDEX).
    private val BERRY_NAMES_BY_ID: Map<Int, String> = listOf(
        "Cheri Berry", "Chesto Berry", "Pecha Berry", "Rawst Berry", "Aspear Berry",
        "Leppa Berry", "Oran Berry", "Persim Berry", "Lum Berry", "Sitrus Berry",
        "Figy Berry", "Wiki Berry", "Mago Berry", "Aguav Berry", "Iapapa Berry",
        "Razz Berry", "Bluk Berry", "Nanab Berry", "Wepear Berry", "Pinap Berry",
        "Pomeg Berry", "Kelpsy Berry", "Qualot Berry", "Hondew Berry", "Grepa Berry",
        "Tamato Berry", "Cornn Berry", "Magost Berry", "Rabuta Berry", "Nomel Berry",
        "Spelon Berry", "Pamtre Berry", "Watmel Berry", "Durin Berry", "Belue Berry",
        "Liechi Berry", "Ganlon Berry", "Salac Berry", "Petaya Berry", "Apicot Berry",
        "Lansat Berry", "Starf Berry", "Enigma Berry"
    ).mapIndexed { index, name -> (133 + index) to name }.toMap()

    /**
     * Call right after savings actually increases (see BattleMoneyTracker.addSavings).
     * Grants (queues) every not-yet-purchased one-time item and every not-yet-granted $3000
     * berry multiple that [newTotal] now covers, respecting the pending-item cap, then
     * announces each newly-queued gift. Safe to call even if nothing new is eligible.
     */
    fun grantEligibleGifts(context: Context, host: String, port: Int, newTotal: Int) {
        val purchased = purchasedOnceItemKeys(context).toMutableSet()
        var nextBerryMultiple = nextBerryMultiple(context)
        val pending = pendingItems(context).toMutableList()

        val newlyGranted = mutableListOf<String>()

        for (item in ONCE_ITEMS) {
            if (item.key in purchased) continue
            if (item.thresholdDollars > newTotal) break
            if (pending.size >= ONCE_ITEM_PENDING_CAP) {
                DebugLog.add("Mom gift: ${item.displayName} eligible but pending queue is full ($ONCE_ITEM_PENDING_CAP) - will retry after the next deposit.")
                continue
            }
            purchased += item.key
            pending += PendingGift(item.itemId, 1, item.displayName, MomGiftBridge.Pocket.ITEMS)
            newlyGranted += item.displayName
        }

        while (nextBerryMultiple <= newTotal) {
            val coincidesWithOnceItem = ONCE_ITEMS.any { it.thresholdDollars == nextBerryMultiple }
            if (!coincidesWithOnceItem) {
                val berryId = BERRY_NAMES_BY_ID.keys.random()
                val berryName = BERRY_NAMES_BY_ID.getValue(berryId)
                pending += PendingGift(berryId, 5, berryName, MomGiftBridge.Pocket.BERRIES)
                newlyGranted += "$berryName x5"
            }
            nextBerryMultiple += BERRY_THRESHOLD_STEP
        }

        savePurchasedOnceItemKeys(context, purchased)
        saveNextBerryMultiple(context, nextBerryMultiple)
        savePendingItems(context, pending)

        for (name in newlyGranted) {
            DebugLog.add("Mom gift: granted $name, queued for delivery.")
            notifyOsd(context, host, port, "PGearHS: Mom bought you a $name!")
        }
    }

    /** Attempts to deliver every currently-pending gift; delivered ones are removed from the queue. */
    suspend fun attemptDelivery(context: Context, host: String, port: Int) {
        val pending = pendingItems(context)
        if (pending.isEmpty()) return

        val bridge = MomGiftBridge(host, port, onDiagnostic = DebugLog::add)
        val stillPending = mutableListOf<PendingGift>()
        for (gift in pending) {
            val delivered = bridge.addItem(gift.itemId, gift.quantity, gift.pocket)
            if (delivered) {
                DebugLog.add("Mom gift: delivered ${gift.displayName} x${gift.quantity}.")
                notifyOsd(context, host, port, "PGearHS: ${gift.displayName} added to your bag!")
                GlobalDialogueNotices.notify(
                    context,
                    listOf(context.getString(R.string.mom_gift_delivered, gift.displayName))
                )
            } else {
                stillPending += gift
            }
        }
        if (stillPending.size != pending.size) {
            savePendingItems(context, stillPending)
        }
    }

    fun pendingCount(context: Context): Int = pendingItems(context).size

    fun purchasedOnceItemCount(context: Context): Int = purchasedOnceItemKeys(context).size

    fun totalOnceItemCount(): Int = ONCE_ITEMS.size

    private fun purchasedOnceItemKeys(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PURCHASED_ONCE_ITEMS, emptySet()) ?: emptySet()

    private fun savePurchasedOnceItemKeys(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PURCHASED_ONCE_ITEMS, keys).apply()
    }

    private fun nextBerryMultiple(context: Context): Int =
        prefs(context).getInt(KEY_NEXT_BERRY_MULTIPLE, BERRY_THRESHOLD_STEP)

    private fun saveNextBerryMultiple(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_NEXT_BERRY_MULTIPLE, value).apply()
    }

    private fun pendingItems(context: Context): List<PendingGift> {
        val raw = prefs(context).getString(KEY_PENDING_ITEMS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size != 4) return@mapNotNull null
            val itemId = parts[0].toIntOrNull() ?: return@mapNotNull null
            val quantity = parts[1].toIntOrNull() ?: return@mapNotNull null
            val pocket = runCatching { MomGiftBridge.Pocket.valueOf(parts[3]) }.getOrNull() ?: return@mapNotNull null
            PendingGift(itemId, quantity, parts[2], pocket)
        }
    }

    private fun savePendingItems(context: Context, items: List<PendingGift>) {
        val encoded = items.joinToString(";") { "${it.itemId},${it.quantity},${it.displayName},${it.pocket.name}" }
        prefs(context).edit().putString(KEY_PENDING_ITEMS, encoded).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun notifyOsd(context: Context, host: String, port: Int, text: String) {
        if (!RetroArchOsdPrefs.isBattleOsdEnabled(context)) return
        RetroArchMemoryBridge(host, port).showOsdMessage(text)
    }
}
