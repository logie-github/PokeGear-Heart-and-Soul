package com.logie.pgearhs.sync

import android.content.Context
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.BattleStateBridge
import com.logie.pgearhs.retroarch.RetroArchConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches live battle state continuously (unlike AppSyncManager, which stops once it's
 * synced once) and tallies money won: this hack doesn't persist "last battle's prize money"
 * anywhere readable (see BattleStateBridge's doc comment), so the only way to know what a
 * battle paid out is to snapshot money right before it and right after, and diff them.
 */
object BattleMoneyTracker {
    private const val POLL_INTERVAL_MS = 1000L
    private const val PREFS_NAME = "pgearhs_settings"
    private const val KEY_TOTAL_WINNINGS = "battle_total_winnings"

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastKnownMoney: Int? = null
    private var wasInBattle = false

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

    private suspend fun poll(context: Context, host: String, port: Int) {
        val state = BattleStateBridge(host, port, onDiagnostic = DebugLog::add).readState() ?: return

        val justWon = wasInBattle && !state.inBattle && state.outcome == BattleStateBridge.OUTCOME_WON
        if (justWon) {
            val before = lastKnownMoney
            val after = state.money
            if (before != null && after != null && after > before) {
                val won = after - before
                val total = addWinnings(context, won)
                DebugLog.add("Battle money: won $won this battle, total tracked $total.")
            } else {
                DebugLog.add("Battle money: won, but couldn't compute the amount (before=$before, after=$after).")
            }
        }

        wasInBattle = state.inBattle
        // Keep the snapshot fresh only while not in battle, so it's always "money right
        // before the next battle starts" whenever one actually does.
        if (!state.inBattle && state.money != null) {
            lastKnownMoney = state.money
        }
    }

    private fun addWinnings(context: Context, amount: Int): Int {
        val total = totalWinnings(context) + amount
        prefs(context).edit().putInt(KEY_TOTAL_WINNINGS, total).apply()
        return total
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
