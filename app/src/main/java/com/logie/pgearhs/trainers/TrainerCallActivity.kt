package com.logie.pgearhs.trainers

import android.os.Bundle
import android.view.ViewGroup
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
import com.logie.pgearhs.sync.BattleMoneyTracker
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
    private lateinit var trainerList: RecyclerView
    private lateinit var allTrainers: Map<Int, Trainer>
    private lateinit var rematchPositions: Map<Int, RematchPosition>
    private lateinit var rematchAnchors: Map<Int, Int>
    private var callable: List<Trainer> = emptyList()
    private var playerName: String = PLAYER_NAME_FALLBACK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trainer_call)

        statusLabel = findViewById(R.id.trainerCallStatus)

        trainerList = findViewById(R.id.trainerCallList)
        trainerList.layoutManager = LinearLayoutManager(this)
        val momCallEntry = findViewById<TextView>(R.id.momCallEntry)

        // While the dialogue box is up, the list (and the MOM entry above it) must not be
        // able to hold focus or intercept DPAD_CENTER/a tap - otherwise a still-focused row
        // underneath re-triggers its own click mid-call, clobbering whichever call was in
        // progress with a different one.
        dialogueBox.onVisibilityChanged = { visible ->
            trainerList.descendantFocusability = if (visible) {
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            } else {
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            momCallEntry.isEnabled = !visible
        }

        allTrainers = TrainerRepository.loadAll(this).associateBy { it.id }
        rematchPositions = RematchPositionRepository.loadAll(this)
        rematchAnchors = RematchPositionRepository.loadAnchors(this)
        adapter = TrainerCallAdapter(emptyList()) { trainer -> confirmRematch(trainer) }
        trainerList.adapter = adapter

        momCallEntry.setOnClickListener { confirmMomCall() }

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
     * Selecting a contact first asks "Call <name>?" - only on Yes does the call actually
     * connect and play out ([placeCall]). Uses the in-game dialogue box or a plain popup
     * depending on the "In-Game Text" Settings toggle ([TrainerCallPrefs]).
     */
    private fun confirmRematch(trainer: Trainer) {
        val prompt = getString(R.string.trainer_call_prompt, trainer.displayName)

        if (TrainerCallPrefs.isInGameTextEnabled(this)) {
            dialogueBox.showText(listOf(prompt)) {
                dialogueBox.showYesNo { callThem ->
                    if (callThem) placeCall(trainer) else dialogueBox.hide()
                }
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle(trainer.displayName)
                .setMessage(prompt)
                .setPositiveButton(R.string.trainer_call_rematch_confirm) { _, _ -> placeCall(trainer) }
                .setNegativeButton(R.string.trainer_call_rematch_cancel, null)
                .show()
        }
    }

    /**
     * Plays the trainer's side of the call - assembled by [RematchCall] (ported from
     * LazarusDex's outgoing-call flow, ~/Documents/LazarusDex) from their lead Pokemon and
     * battle location - then goes straight into resetting them; confirming the call itself
     * (see [confirmRematch]) already is the "yes, I want this rematch" decision.
     */
    private fun placeCall(trainer: Trainer) {
        val lines = RematchCall.assemble(
            playerName = playerName,
            pokemonName = trainer.firstPokemon,
            location = LocationPhrasing.naturalize(trainer.location)
        )

        if (TrainerCallPrefs.isInGameTextEnabled(this)) {
            dialogueBox.showText(lines) { performRematch(trainer) }
        } else {
            AlertDialog.Builder(this)
                .setTitle(trainer.displayName)
                .setMessage(lines.joinToString("\n\n"))
                .setPositiveButton(android.R.string.ok) { _, _ -> performRematch(trainer) }
                .setCancelable(false)
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

    // --- Call MOM -----------------------------------------------------------------------
    //
    // Modeled on pokecrystal's real Mom phone call (engine/phone/scripts/mom.asm), researched
    // directly from source rather than assumed - but adapted, not copied verbatim, since it's
    // a different Mom in a different game. Two real differences from the source:
    //  - pokecrystal's call is one linear script (greeting, then a location comment, then the
    //    savings prompt, every single call) - there's no Talk/Savings/Bye menu to extract,
    //    that structure is new here, built to give the player a choice of which part to hear.
    //  - The source's location dialogue does real landmark detection (getcurlandmarkname,
    //    environment/town-vs-route checks, per-city special text for 5 named cities via
    //    getlandmarkname). This hack has an equivalent region-map system
    //    (constants/region_map_sections.h) but wiring live landmark detection to it is a
    //    separate follow-up - Talk's response is generic for now, not location-aware yet.
    //  - The savings prompt has 4 real variants gated on (is Mom currently saving, is there a
    //    nonzero balance) - reproduced faithfully here using BattleMoneyTracker's real
    //    isSavingEnabled()/savings() state instead of pokecrystal's ENGINE_MOM_SAVING_MONEY
    //    flag and checkmoney MOMS_MONEY check, which read the same shape of information.
    //    Accepting always sets saving on ("OK. I'll save your money"); declining always turns
    //    it off ("OK. I won't save your money") - pokecrystal has no separate "already off"
    //    message for declining, matched here.
    //
    // Always uses the in-game dialogue box regardless of the "In-Game Text" Settings toggle -
    // the menu requires it (there's no AlertDialog equivalent built for 3-option menus), and
    // Mom's call is meant to be the flagship version of this UI.

    private fun confirmMomCall() {
        dialogueBox.showText(listOf(getString(R.string.mom_call_prompt))) {
            dialogueBox.showYesNo { callHer -> if (callHer) placeMomCall() else dialogueBox.hide() }
        }
    }

    private fun placeMomCall() {
        dialogueBox.showText(listOf(getString(R.string.mom_call_greeting, playerName))) {
            showMomMenu()
        }
    }

    private fun showMomMenu() {
        val options = listOf(
            getString(R.string.mom_menu_talk),
            getString(R.string.mom_menu_savings),
            getString(R.string.mom_menu_bye)
        )
        dialogueBox.showMenu(options) { index ->
            when (index) {
                0 -> momTalk()
                1 -> momSavings()
                else -> momBye()
            }
        }
    }

    private fun momTalk() {
        dialogueBox.showText(listOf(getString(R.string.mom_talk_response, playerName))) {
            showMomMenu()
        }
    }

    private fun momSavings() {
        val isSaving = BattleMoneyTracker.isSavingEnabled(this)
        val balance = BattleMoneyTracker.savings(this)
        val prompt = when {
            isSaving && balance > 0 -> getString(R.string.mom_savings_prompt_saving_with_balance, balance)
            isSaving -> getString(R.string.mom_savings_prompt_saving_no_balance)
            !isSaving && balance > 0 -> getString(R.string.mom_savings_prompt_not_saving_with_balance, balance)
            else -> getString(R.string.mom_savings_prompt_not_saving_no_balance, playerName)
        }
        dialogueBox.showText(listOf(prompt)) {
            dialogueBox.showYesNo { yes ->
                BattleMoneyTracker.setSavingEnabled(this, yes)
                DebugLog.add("Call MOM: savings toggled to $yes.")
                val response = getString(if (yes) R.string.mom_savings_yes else R.string.mom_savings_no)
                dialogueBox.showText(listOf(response)) { showMomMenu() }
            }
        }
    }

    private fun momBye() {
        dialogueBox.showText(listOf(getString(R.string.mom_bye, playerName))) { dialogueBox.hide() }
    }
}
