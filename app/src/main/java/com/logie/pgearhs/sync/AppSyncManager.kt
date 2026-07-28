package com.logie.pgearhs.sync

import android.content.Context
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.LiveDexState
import com.logie.pgearhs.retroarch.PokedexMemoryCalibrator
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.RetroArchMemoryBridge
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
 * when the app is first opened, so this polls with a quick, short-timeout reachability probe
 * once a second until it connects, then runs the real (slower) sync once - by the time the
 * player actually opens a screen that needs this data, it's usually already there.
 */
object AppSyncManager {
    private const val POLL_INTERVAL_MS = 1000L
    private const val PROBE_TIMEOUT_MS = 400

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
                    if (isReachable(host, port) && trySync(appContext, host, port)) {
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

    private fun isReachable(host: String, port: Int): Boolean =
        RetroArchMemoryBridge(host, port, timeoutMs = PROBE_TIMEOUT_MS, retries = 0).isReachable()

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
