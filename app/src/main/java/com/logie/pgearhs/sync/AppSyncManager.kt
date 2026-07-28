package com.logie.pgearhs.sync

import android.content.Context
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.LiveDexState
import com.logie.pgearhs.retroarch.PokedexMemoryCalibrator
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.TrainerFlagsBridge
import com.logie.pgearhs.trainers.TrainerRegistry
import com.logie.pgearhs.trainers.TrainerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Starts syncing (Pokedex + trainer flags) the moment the app launches, instead of waiting
 * for the player to open the Pokedex/Call screen. RetroArch/the emulator is often not up yet
 * when the app is first opened, so this retries the real sync once a second until it
 * succeeds - by the time the player actually opens a screen that needs this data, it's
 * usually already there.
 *
 * This used to gate each attempt behind a separate quick reachability probe with a short
 * (400ms) timeout, on the theory that skipping the slower real sync when RetroArch obviously
 * wasn't up yet would be more efficient. That was a mistake: if 400ms was ever too short for
 * this device/network's actual round-trip - which there was no way to verify - the probe
 * would report "unreachable" forever and the real sync underneath would never even be
 * attempted, even though it would have succeeded on its own with its normal, longer default
 * timeout. Since "syncs 100% automatically, every time" matters far more here than shaving
 * time off a handful of retries while waiting for RetroArch to start, just attempt the real
 * sync directly every cycle - it already no-ops safely (returns false, doesn't throw) when
 * RetroArch isn't reachable, via RetroArchMemoryBridge's own hardened default-timeout path.
 */
object AppSyncManager {
    private const val POLL_INTERVAL_MS = 1000L

    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startIfNeeded(context: Context) {
        if (started) return
        started = true

        val appContext = context.applicationContext
        scope.launch {
            val host = RetroArchConnection.getHost(appContext)
            val port = RetroArchConnection.getPort(appContext)
            DebugLog.add("App sync: waiting for RetroArch at $host:$port…")

            while (isActive) {
                try {
                    if (trySync(appContext, host, port)) {
                        DebugLog.add("App sync: succeeded on launch.")
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Must never let this loop die - RetroArchMemoryBridge now catches its
                    // own network exceptions, but this is defense-in-depth against anything
                    // else in the sync path (asset/JSON parsing, etc.) doing the same thing:
                    // one bad attempt should just get logged and retried, not permanently end
                    // "sync automatically on launch" for the rest of the session.
                    DebugLog.add("! App sync attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun trySync(context: Context, host: String, port: Int): Boolean {
        val pokedexResult = PokedexMemoryCalibrator(host, port, onDiagnostic = DebugLog::add).calibrateAndRead()
        val pokedexOk = if (pokedexResult is PokedexMemoryCalibrator.Result.Success) {
            LiveDexState.applySyncResult(pokedexResult.nationalDexEnabled, pokedexResult.owned, pokedexResult.seen)
            true
        } else {
            false
        }

        val trainerIds = TrainerRepository.loadAll(context).map { it.id }
        val trainerResult = TrainerFlagsBridge(host, port, onDiagnostic = DebugLog::add).readDefeatedTrainerIds(trainerIds)
        val trainersOk = if (trainerResult is TrainerFlagsBridge.ReadResult.Success) {
            TrainerRegistry.recordDefeated(context, trainerResult.defeatedTrainerIds)
            true
        } else {
            false
        }

        return pokedexOk && trainersOk
    }
}
