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
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.retroarch.TrainerFlagsBridge
import com.logie.pgearhs.ui.BaseImmersiveActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the player pick any trainer they've already beaten and reset them for a rematch. */
class TrainerCallActivity : BaseImmersiveActivity() {

    private lateinit var statusLabel: TextView
    private lateinit var adapter: TrainerCallAdapter
    private lateinit var allTrainers: Map<Int, Trainer>
    private var defeated: List<Trainer> = emptyList()

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
            val result = withContext(Dispatchers.IO) {
                TrainerFlagsBridge(host, port, onDiagnostic = DebugLog::add)
                    .readDefeatedTrainerIds(allTrainers.keys.toList())
            }

            when (result) {
                is TrainerFlagsBridge.ReadResult.Success -> {
                    defeated = result.defeatedTrainerIds
                        .mapNotNull { allTrainers[it] }
                        .sortedBy { it.displayName }
                    adapter.submit(defeated)
                    statusLabel.text = if (defeated.isEmpty()) {
                        getString(R.string.trainer_call_status_empty)
                    } else {
                        getString(R.string.trainer_call_status_count, defeated.size)
                    }
                    DebugLog.add("Trainer call: sync succeeded, ${defeated.size} defeated.")
                }
                is TrainerFlagsBridge.ReadResult.Failure -> {
                    statusLabel.text = getString(R.string.trainer_call_status_failed)
                    DebugLog.add("! Trainer call sync failed: ${result.reason}")
                }
            }
        }
    }

    private fun confirmRematch(trainer: Trainer) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trainer_call_rematch_title)
            .setMessage(getString(R.string.trainer_call_rematch_message, trainer.displayName))
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
                DebugLog.add("Trainer call: ${trainer.displayName} reset succeeded.")
                Toast.makeText(this@TrainerCallActivity, getString(R.string.trainer_call_rematch_success, trainer.displayName), Toast.LENGTH_LONG).show()
                defeated = defeated.filterNot { it.id == trainer.id }
                adapter.submit(defeated)
                statusLabel.text = if (defeated.isEmpty()) {
                    getString(R.string.trainer_call_status_empty)
                } else {
                    getString(R.string.trainer_call_status_count, defeated.size)
                }
            } else {
                DebugLog.add("! Trainer call: ${trainer.displayName} reset failed.")
                Toast.makeText(this@TrainerCallActivity, getString(R.string.trainer_call_rematch_failed, trainer.displayName), Toast.LENGTH_LONG).show()
            }
        }
    }
}
