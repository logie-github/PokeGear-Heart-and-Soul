package com.logie.pgearhs.trainers

import android.os.Bundle
import android.view.KeyEvent
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
import com.logie.pgearhs.ui.PokemonDialogueBox
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
    private lateinit var dialogueBox: PokemonDialogueBox
    private lateinit var allTrainers: Map<Int, Trainer>
    private lateinit var rematchPositions: Map<Int, RematchPosition>
    private lateinit var rematchAnchors: Map<Int, Int>
    private var callable: List<Trainer> = emptyList()
    private var playerName: String = PLAYER_NAME_FALLBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trainer_call)

        statusLabel = findViewById(R.id.trainerCallStatus)
        dialogueBox = PokemonDialogueBox(findViewById(R.id.dialogueOverlay))
        val list = findViewById<RecyclerView>(R.id.trainerCallList)
        list.layoutManager = LinearLayoutManager(this)

        allTrainers = TrainerRepository.loadAll(this).associateBy { it.id }
        rematchPositions = RematchPositionRepository.loadAll(this)
        rematchAnchors = RematchPositionRepository.loadAnchors(this)
        adapter = TrainerCallAdapter(emptyList()) { trainer -> confirmRematch(trainer) }
        list.adapter = adapter

        syncDefeatedTrainers()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (dialogueBox.isVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> {
                    dialogueBox.onAdvance()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    dialogueBox.onNavigate(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
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
     * Plays the trainer's side of the call - assembled by [RematchCall] (ported from
     * LazarusDex's outgoing-call flow, ~/Documents/LazarusDex) from their lead Pokemon and
     * battle location - before asking whether to actually reset them for a rematch. Uses the
     * in-game dialogue box or a plain popup depending on the "In-Game Text" Settings toggle
     * ([TrainerCallPrefs]).
     */
    private fun confirmRematch(trainer: Trainer) {
        val lines = RematchCall.assemble(
            playerName = playerName,
            pokemonName = trainer.firstPokemon,
            location = LocationPhrasing.naturalize(trainer.location)
        )

        if (TrainerCallPrefs.isInGameTextEnabled(this)) {
            dialogueBox.showText(lines) {
                dialogueBox.showYesNo { rematch ->
                    if (rematch) performRematch(trainer) else dialogueBox.hide()
                }
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle(trainer.displayName)
                .setMessage(lines.joinToString("\n\n"))
                .setPositiveButton(R.string.trainer_call_rematch_confirm) { _, _ -> performRematch(trainer) }
                .setNegativeButton(R.string.trainer_call_rematch_cancel, null)
                .show()
        }
    }

    private fun performRematch(trainer: Trainer) {
        val host = RetroArchConnection.getHost(this)
        val port = RetroArchConnection.getPort(this)
        val useDialogueBox = TrainerCallPrefs.isInGameTextEnabled(this)
        val workingMessage = getString(R.string.trainer_call_rematch_working, trainer.displayName)
        if (useDialogueBox) {
            dialogueBox.showText(listOf(workingMessage)) {}
        } else {
            Toast.makeText(this, workingMessage, Toast.LENGTH_SHORT).show()
        }

        // Prefer the real native rematch-ready switch (proper rematch dialogue, next tier
        // of their team) over just resetting the plain defeated flag (which replays their
        // original first-encounter script from scratch) - only available for trainers who
        // are chain positions 1-4 in gRematchTable. Position 0 (their original encounter)
        // and one-off trainers have no such switch, so fall back to resetTrainerFlag.
        val rematchPosition = rematchPositions[trainer.id]
        if (rematchPosition != null) {
            DebugLog.add(
                "Trainer call: flipping rematch-ready switch for ${trainer.displayName} " +
                    "(id=${trainer.id}, tableId=${rematchPosition.tableId}, position=${rematchPosition.position})…"
            )
        } else {
            DebugLog.add("Trainer call: resetting ${trainer.displayName} (id=${trainer.id})…")
        }

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                val bridge = TrainerFlagsBridge(host, port, onDiagnostic = DebugLog::add)
                val anchorId = rematchPosition?.let { rematchAnchors[it.tableId] }
                if (rematchPosition != null && anchorId != null) {
                    bridge.setRematchReady(rematchPosition.tableId, rematchPosition.position, anchorId)
                } else {
                    bridge.resetTrainerFlag(trainer.id)
                }
            }

            val resultLine = if (success) {
                // Trainer stays in `callable` (and the persisted registry) - resetting their
                // flag makes them fightable again, it doesn't un-register their number.
                DebugLog.add("Trainer call: ${trainer.displayName} reset succeeded.")
                getString(R.string.trainer_call_rematch_success, trainer.displayName)
            } else {
                DebugLog.add("! Trainer call: ${trainer.displayName} reset failed.")
                getString(R.string.trainer_call_rematch_failed, trainer.displayName)
            }
            if (useDialogueBox) {
                dialogueBox.showText(listOf(resultLine)) { dialogueBox.hide() }
            } else {
                Toast.makeText(this@TrainerCallActivity, resultLine, Toast.LENGTH_LONG).show()
            }
        }
    }
}
