package com.logie.pgearhs.trainers

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.logie.pgearhs.R
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.PlayerProfileBridge
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.TrainerFlagsBridge
import com.logie.pgearhs.ui.BaseImmersiveActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the player pick any trainer they've already beaten and reset them for a rematch. */
class TrainerCallActivity : BaseImmersiveActivity() {

    companion object {
        // Fallback if the live read fails (RetroArch unreachable, garbled bytes, etc.) -
        // reads fine in every RematchCall line.
        private const val PLAYER_NAME_FALLBACK = "Trainer"
    }

    private lateinit var statusLabel: TextView
    private lateinit var adapter: TrainerCallAdapter
    private lateinit var allTrainers: Map<Int, Trainer>
    private var callable: List<Trainer> = emptyList()
    private var playerName: String = PLAYER_NAME_FALLBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trainer_call)

        statusLabel = findViewById(R.id.trainerCallStatus)
        val list = findViewById<RecyclerView>(R.id.trainerCallList)
        list.layoutManager = LinearLayoutManager(this)

        allTrainers = TrainerRepository.loadAll(this).associateBy { it.id }
        adapter = TrainerCallAdapter(emptyList()) { trainer -> confirmRematch(trainer) }
        list.adapter = adapter

        syncDefeatedTrainers()
    }

    private fun syncDefeatedTrainers() {
        statusLabel.text = getString(R.string.trainer_call_status_syncing)
        val host = RetroArchConnection.getHost(this)
        val port = RetroArchConnection.getPort(this)
        DebugLog.add("Trainer call: syncing against $host:$port…")

        lifecycleScope.launch {
            val (result, name) = withContext(Dispatchers.IO) {
                val flagsResult = TrainerFlagsBridge(host, port, onDiagnostic = DebugLog::add)
                    .readDefeatedTrainerIds(allTrainers.keys.toList())
                val nameResult = PlayerProfileBridge(host, port, onDiagnostic = DebugLog::add).readPlayerName()
                flagsResult to nameResult
            }
            if (name != null) {
                playerName = name
                DebugLog.add("Trainer call: player name read as \"$name\".")
            }

            when (result) {
                is TrainerFlagsBridge.ReadResult.Success -> {
                    val everDefeated = TrainerRegistry.recordDefeated(this@TrainerCallActivity, result.defeatedTrainerIds)
                    callable = everDefeated
                        .mapNotNull { allTrainers[it] }
                        .sortedBy { it.displayName }
                    adapter.submit(callable)
                    statusLabel.text = if (callable.isEmpty()) {
                        getString(R.string.trainer_call_status_empty)
                    } else {
                        getString(R.string.trainer_call_status_count, callable.size)
                    }
                    DebugLog.add(
                        "Trainer call: sync succeeded, ${result.defeatedTrainerIds.size} currently defeated, " +
                            "${callable.size} callable overall."
                    )
                }
                is TrainerFlagsBridge.ReadResult.Failure -> {
                    statusLabel.text = getString(R.string.trainer_call_status_failed)
                    DebugLog.add("! Trainer call sync failed: ${result.reason}")
                }
            }
        }
    }

    /**
     * Shows the trainer's side of the call - assembled by [RematchCall] (ported from
     * LazarusDex's outgoing-call flow, ~/Documents/LazarusDex) from their lead Pokemon and
     * battle location - before asking whether to actually reset them for a rematch.
     */
    private fun confirmRematch(trainer: Trainer) {
        val transcript = RematchCall.assemble(
            playerName = playerName,
            pokemonName = trainer.firstPokemon,
            location = LocationPhrasing.naturalize(trainer.location)
        ).joinToString("\n\n")

        AlertDialog.Builder(this)
            .setTitle(trainer.displayName)
            .setMessage(transcript)
            .setPositiveButton(R.string.trainer_call_rematch_confirm) { _, _ -> performRematch(trainer) }
            .setNegativeButton(R.string.trainer_call_rematch_cancel, null)
            .show()
    }

    private fun performRematch(trainer: Trainer) {
        val host = RetroArchConnection.getHost(this)
        val port = RetroArchConnection.getPort(this)
        Toast.makeText(this, getString(R.string.trainer_call_rematch_working, trainer.displayName), Toast.LENGTH_SHORT).show()
        DebugLog.add("Trainer call: resetting ${trainer.displayName} (id=${trainer.id})…")

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                TrainerFlagsBridge(host, port, onDiagnostic = DebugLog::add).resetTrainerFlag(trainer.id)
            }

            if (success) {
                // Trainer stays in `callable` (and the persisted registry) - resetting their
                // flag makes them fightable again, it doesn't un-register their number.
                DebugLog.add("Trainer call: ${trainer.displayName} reset succeeded.")
                Toast.makeText(this@TrainerCallActivity, getString(R.string.trainer_call_rematch_success, trainer.displayName), Toast.LENGTH_LONG).show()
            } else {
                DebugLog.add("! Trainer call: ${trainer.displayName} reset failed.")
                Toast.makeText(this@TrainerCallActivity, getString(R.string.trainer_call_rematch_failed, trainer.displayName), Toast.LENGTH_LONG).show()
            }
        }
    }
}
