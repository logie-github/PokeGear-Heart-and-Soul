package com.logie.pgearhs.sync

import android.content.Context
import com.logie.pgearhs.R
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.BattleStateBridge
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.ui.GlobalDialogueNotices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Watches live battle state continuously (unlike AppSyncManager, which stops once it's
 * synced once) and tallies money won: this hack doesn't persist "last battle's prize money"
 * anywhere readable (see BattleStateBridge's doc comment), so the only way to know what a
 * battle paid out is to snapshot money right before it and right after, and diff them.
 *
 * On every win, also sends 25% of that win (rounded to the nearest $10) to a "Mom's savings"
 * pool tracked only by this app - actually deducted from the player's in-game money via
 * BattleStateBridge.writeMoney(), not just a number shown on screen, and announced through
 * GlobalDialogueNotices the same way the real games have Mom's savings messages appear.
 */
object BattleMoneyTracker {
    private const val POLL_INTERVAL_MS = 1000L
    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_TOTAL_WINNINGS = "battle_total_winnings"
    private const val KEY_SAVINGS = "battle_mom_savings"
    private const val MOM_SHARE_FRACTION = 0.25
    private const val MOM_SHARE_ROUND_TO = 10

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastKnownMoney: Int? = null
    private var wasInBattle = false
    private var pollCount = 0

    fun startIfNeeded(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        scope.launch {
            val host = RetroArchConnection.getHost(appContext)
            val port = RetroArchConnection.getPort(appContext)

            while (isActive) {
                try {
                    poll(appContext, host, port)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.add("! Battle money tracker attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun totalWinnings(context: Context): Int =
        prefs(context).getInt(KEY_TOTAL_WINNINGS, 0)

    fun savings(context: Context): Int =
        prefs(context).getInt(KEY_SAVINGS, 0)

    private suspend fun poll(context: Context, host: String, port: Int) {
        pollCount++
        // Full hex-dump diagnostics (see BattleStateBridge) are expensive log-wise - only
        // ask for them once every 10 polls while idle, but for every poll while a battle is
        // actually in progress (wasInBattle carries over from the previous cycle), since
        // that's the window that actually matters for confirming a win/money change. Doing
        // this for every single poll would fill DebugLog's 300-entry cap in under 2 minutes,
        // well before a real test (walk to a trainer, fight, win) finishes.
        val verbose = wasInBattle || pollCount % 10 == 1
        val bridge = BattleStateBridge(host, port, onDiagnostic = DebugLog::add)
        val state = bridge.readState(verbose) ?: run {
            DebugLog.add("Battle money: readState() returned null this poll (see diagnostics above).")
            return
        }

        // Logged every poll, not just on notable events - this feature has no live
        // confirmation yet (gMain/gBattleOutcome/money's offset are all unverified
        // hypotheses), so a debug report needs to show the raw numbers every cycle to be
        // useful at all, not just "it didn't detect a win."
        DebugLog.add(
            "Battle money poll #$pollCount: inBattle=${state.inBattle} outcome=${state.outcome} " +
                "money=${state.money} (lastKnown=$lastKnownMoney, wasInBattle=$wasInBattle)"
        )

        var settledMoney = state.money
        val justWon = wasInBattle && !state.inBattle && state.outcome == BattleStateBridge.OUTCOME_WON

        if (justWon) {
            val before = lastKnownMoney
            val after = state.money
            if (before != null && after != null && after > before) {
                val won = after - before
                val total = addWinnings(context, won)
                DebugLog.add("Battle money: won $won this battle, total tracked $total.")
                settledMoney = sendToMom(context, bridge, won, after)
            } else {
                DebugLog.add("Battle money: won, but couldn't compute the amount (before=$before, after=$after).")
            }
        }

        wasInBattle = state.inBattle
        // Keep the snapshot fresh only while not in battle, so it's always "money right
        // before the next battle starts" whenever one actually does.
        if (!state.inBattle && settledMoney != null) {
            lastKnownMoney = settledMoney
        }
    }

    /** Deducts Mom's cut from [currentMoney] in-game and adds it to the tracked savings pool. Returns the new in-game money if the write succeeded, otherwise [currentMoney] unchanged. */
    private suspend fun sendToMom(context: Context, bridge: BattleStateBridge, won: Int, currentMoney: Int): Int {
        val momShare = roundToNearestTen(won * MOM_SHARE_FRACTION)
        if (momShare <= 0) return currentMoney

        val newMoney = currentMoney - momShare
        if (!bridge.writeMoney(newMoney)) {
            DebugLog.add("! Battle money: couldn't send \$$momShare to Mom - write failed.")
            return currentMoney
        }

        val savingsTotal = addSavings(context, momShare)
        DebugLog.add("Battle money: sent \$$momShare to Mom, savings now \$$savingsTotal.")
        GlobalDialogueNotices.notify(context, listOf(context.getString(R.string.battle_money_sent_to_mom, momShare)))
        return newMoney
    }

    private fun roundToNearestTen(value: Double): Int =
        ((value / MOM_SHARE_ROUND_TO).roundToInt()) * MOM_SHARE_ROUND_TO

    private fun addWinnings(context: Context, amount: Int): Int {
        val total = totalWinnings(context) + amount
        prefs(context).edit().putInt(KEY_TOTAL_WINNINGS, total).apply()
        return total
    }

    private fun addSavings(context: Context, amount: Int): Int {
        val total = savings(context) + amount
        prefs(context).edit().putInt(KEY_SAVINGS, total).apply()
        return total
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
