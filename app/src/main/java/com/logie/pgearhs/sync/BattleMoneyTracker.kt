package com.logie.pgearhs.sync

import android.content.Context
import com.logie.pgearhs.R
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.BattleStateBridge
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.RetroArchMemoryBridge
import com.logie.pgearhs.retroarch.RetroArchOsdPrefs
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
    private const val KEY_SAVING_ENABLED = "battle_mom_saving_enabled"

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastKnownMoney: Int? = null
    private var lastMoneySnapshot: BattleStateBridge.MoneySnapshot? = null
    private var wasInBattle = false
    private var pollCount = 0

    // gBattleOutcome stays at OUTCOME_WON indefinitely after a win - it's only ever
    // overwritten when the *next* battle starts, never reset to 0 on its own (confirmed
    // across real debug reports: idle polls minutes after a win still read outcome=1). A
    // naive wasInBattle-true-to-false edge is NOT a safe "just won" signal on its own, then:
    // if inBattle's bit so much as flickers true-then-false-again even once during the
    // multi-second post-battle sequence (EXP gain, level-up, the money-reward screen, the
    // walk back to the overworld), the stale outcome=1 makes it look like a brand new win
    // every single time it flickers - and each one deducts another 25%. confirmedInBattle
    // requires 2 consecutive "true" polls before counting as a real battle entry at all, and
    // winClaimedForBattle can only be cleared by a *fresh* confirmed entry - together this
    // guarantees at most one deduction per real battle, no matter how the underlying bits
    // jitter afterward.
    private var inBattleStreak = 0
    private var confirmedInBattle = false
    private var winClaimedForBattle = false

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

    /** Whether Mom is currently saving 25% of each win - the Call MOM "Savings" option toggles this. Defaults on (the existing always-on behavior). */
    fun isSavingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAVING_ENABLED, true)

    fun setSavingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVING_ENABLED, enabled).apply()
    }

    private suspend fun poll(context: Context, host: String, port: Int) {
        pollCount++
        val bridge = BattleStateBridge(host, port, onDiagnostic = DebugLog::add)

        // Read quietly first (no logging) so we know whether this poll is a state
        // transition before deciding whether it's worth the log-text cost of a verbose
        // re-read. v1.0.42 logged full hex-dump diagnostics on EVERY poll for the entire
        // duration of a battle (wasInBattle stayed true continuously) plus a full 512-byte
        // window dump every 10th idle poll - that much repeated text reliably filled both
        // DebugLog's 300-entry cap and the 7000-char debug-report URL budget well before a
        // real test (walk to a trainer, fight, win, send report) finished, silently trimming
        // away the one moment (the win itself) that actually mattered. Only the transition
        // polls (battle starting/ending) and an occasional heartbeat are worth the cost now.
        val quietState = bridge.readState(verbose = false) ?: run {
            DebugLog.add("Battle money: readState() returned null this poll.")
            return
        }
        val verbose = (quietState.inBattle != wasInBattle) || pollCount % 30 == 1
        val state = if (verbose) bridge.readState(verbose = true) ?: quietState else quietState

        if (state.inBattle) {
            inBattleStreak++
        } else {
            inBattleStreak = 0
        }
        val nowConfirmedInBattle = inBattleStreak >= 2
        if (nowConfirmedInBattle && !confirmedInBattle) {
            // A genuinely new battle, debounced against single-poll bit flicker - safe to
            // let this battle's win be claimed again.
            winClaimedForBattle = false
            notifyOsd(context, host, port, "PGearHS: entered battle")
        }

        // This one-line summary (not the verbose hex dumps above) is still logged every
        // poll - cheap enough on its own, and useful to see the state leading up to a win
        // even outside the rare verbose polls.
        DebugLog.add(
            "Battle money poll #$pollCount: inBattle=${state.inBattle} outcome=${state.outcome} " +
                "money=${state.money} (lastKnown=$lastKnownMoney, confirmedInBattle=$confirmedInBattle, winClaimed=$winClaimedForBattle)"
        )

        var settledMoney = state.money
        val justWon = confirmedInBattle && !state.inBattle &&
            state.outcome == BattleStateBridge.OUTCOME_WON && !winClaimedForBattle

        if (justWon) {
            winClaimedForBattle = true
            notifyOsd(context, host, port, "PGearHS: won the battle")
        }

        if (justWon && !BattleStateBridge.MONEY_OFFSET_CONFIRMED) {
            // Money's real SaveBlock1 offset isn't confirmed yet (see BattleStateBridge's
            // doc comment) - state.money is diagnostic-only garbage right now, not a real
            // balance. Recording a "won" amount or touching savings from it would just
            // corrupt this app's own tracked totals with nonsense, so detection is logged
            // but nothing is persisted or sent to Mom until that's fixed.
            DebugLog.add("Battle money: win detected, but money offset isn't confirmed yet - not recording an amount.")
            // Instead, auto-narrow which offset is really money without needing the user to
            // state their exact balance anywhere: diff a snapshot from right before this
            // battle against one taken right now, and flag whichever offset(s) went up by a
            // plausible reward amount. Whatever survives this across a few real battles is
            // the real offset.
            val before = lastMoneySnapshot
            val after = bridge.captureMoneySnapshot()
            if (before != null && after != null) {
                // Still unconfirmed, so this is purely a debugging aid, not a real payout -
                // show it anyway so the offset's accuracy can be watched live in the
                // emulator on every real win, not just dug out of a debug report afterward.
                val quickDiff = with(bridge) { before.diffAgainstWin(after) }
                notifyOsd(
                    context, host, port,
                    if (quickDiff != null) "PGearHS: won ~\$$quickDiff (unconfirmed offset)"
                    else "PGearHS: won \$??? (offset not found this battle)"
                )
            } else {
                DebugLog.add("Battle money: couldn't diff for calibration (before=${before != null}, after=${after != null}).")
            }
        } else if (justWon) {
            val before = lastKnownMoney
            val after = state.money
            if (before != null && after != null && after > before) {
                val won = after - before
                val total = addWinnings(context, won)
                DebugLog.add("Battle money: won $won this battle, total tracked $total.")
                settledMoney = if (isSavingEnabled(context)) {
                    sendToMom(context, host, port, bridge, won, after)
                } else {
                    DebugLog.add("Battle money: saving is turned off - not sending anything to Mom.")
                    after
                }
            } else {
                DebugLog.add("Battle money: won, but couldn't compute the amount (before=$before, after=$after).")
            }
        }

        wasInBattle = state.inBattle
        confirmedInBattle = nowConfirmedInBattle
        // Keep both snapshots fresh only while not in battle, so they're always "money
        // right before the next battle starts" whenever one actually does.
        if (!state.inBattle) {
            if (settledMoney != null) lastKnownMoney = settledMoney
            bridge.captureMoneySnapshot()?.let { lastMoneySnapshot = it }
            // Only attempt delivery out of battle, same as every other write here - and only
            // when something's actually queued, so this is a no-op most polls.
            if (MomGiftManager.pendingCount(context) > 0) {
                MomGiftManager.attemptDelivery(context, host, port)
            }
        }
    }

    /** Deducts Mom's cut from [currentMoney] in-game and adds it to the tracked savings pool. Returns the new in-game money if the write succeeded, otherwise [currentMoney] unchanged. */
    private suspend fun sendToMom(
        context: Context, host: String, port: Int,
        bridge: BattleStateBridge, won: Int, currentMoney: Int
    ): Int {
        val momShare = roundToNearestTen(won * MOM_SHARE_FRACTION)
        if (momShare <= 0) {
            notifyOsd(context, host, port, "PGearHS: won \$$won, but 25% rounds to \$0 - nothing deducted")
            return currentMoney
        }

        val newMoney = currentMoney - momShare
        if (!bridge.writeMoney(newMoney)) {
            DebugLog.add("! Battle money: couldn't send \$$momShare to Mom - write failed.")
            notifyOsd(context, host, port, "PGearHS: won \$$won (25%=\$$momShare) but the write FAILED - nothing deducted")
            return currentMoney
        }

        val savingsTotal = addSavings(context, momShare)
        DebugLog.add("Battle money: sent \$$momShare to Mom, savings now \$$savingsTotal.")
        // Proof-of-work debug notice, distinct from the in-game "Sent $Y to MOM" dialogue
        // below - spells out the actual arithmetic (won, cut, before/after totals) so a
        // deduction can be visually verified against the real in-game money each time.
        notifyOsd(
            context, host, port,
            "PGearHS: won \$$won (25%=\$$momShare) \$$currentMoney-\$$momShare=\$$newMoney"
        )
        GlobalDialogueNotices.notify(context, listOf(context.getString(R.string.battle_money_sent_to_mom, momShare)))
        MomGiftManager.grantEligibleGifts(context, host, port, savingsTotal)
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

    /** Pushes [text] to RetroArch's own on-screen notification, if the user has that debug toggle on. */
    private fun notifyOsd(context: Context, host: String, port: Int, text: String) {
        if (!RetroArchOsdPrefs.isBattleOsdEnabled(context)) return
        RetroArchMemoryBridge(host, port).showOsdMessage(text)
    }
}
