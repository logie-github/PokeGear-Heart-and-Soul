package com.logie.pgearhs.pokedex

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.logie.pgearhs.R
import com.logie.pgearhs.debug.DebugLog
import com.logie.pgearhs.retroarch.LiveDexState
import com.logie.pgearhs.retroarch.PokedexMemoryCalibrator
import com.logie.pgearhs.retroarch.RetroArchConnection
import com.logie.pgearhs.ui.BaseImmersiveActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PokedexActivity : BaseImmersiveActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PokedexAdapter
    private lateinit var previewSprite: PokemonPreviewSpriteView
    private lateinit var previewName: TextView
    private lateinit var previewNumber: TextView
    private lateinit var previewType: TextView
    private lateinit var syncStatusLabel: TextView
    private lateinit var nationalButton: TextView
    private lateinit var regionalButton: TextView

    private var dexMode = DexMode.NATIONAL
    private var entries: List<PokedexEntry> = emptyList()
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokedex)

        recyclerView = findViewById(R.id.pokedexList)
        previewSprite = findViewById(R.id.previewSprite)
        previewName = findViewById(R.id.previewName)
        previewNumber = findViewById(R.id.previewNumber)
        previewType = findViewById(R.id.previewType)
        syncStatusLabel = findViewById(R.id.syncStatusLabel)
        nationalButton = findViewById(R.id.nationalDexButton)
        regionalButton = findViewById(R.id.regionalDexButton)

        recyclerView.layoutManager = LinearLayoutManager(this)

        // If a live sync has run, default to whichever dex the save actually has unlocked.
        dexMode = if (LiveDexState.isSynced && !LiveDexState.nationalDexEnabled) {
            DexMode.REGIONAL
        } else {
            DexMode.NATIONAL
        }

        entries = loadEntriesForCurrentMode()
        adapter = PokedexAdapter(entries, dexMode, selectedIndex) { position ->
            selectedIndex = position
            adapter.setSelectedIndex(position)
            updatePreview(animate = true)
            openDetail()
        }
        recyclerView.adapter = adapter

        nationalButton.setOnClickListener { setDexMode(DexMode.NATIONAL) }
        regionalButton.setOnClickListener { setDexMode(DexMode.REGIONAL) }

        updateDexModeButtons()
        updateSyncStatusLabel()
        updatePreview(animate = false)

        if (!LiveDexState.isSynced) {
            autoSync()
        }
    }

    /** Runs a live sync automatically, no Settings visit or button press required. */
    private fun autoSync() {
        val host = RetroArchConnection.getHost(this)
        val port = RetroArchConnection.getPort(this)
        syncStatusLabel.text = getString(R.string.pokedex_sync_status_syncing)
        DebugLog.add("Pokedex auto-sync: attempting against $host:$port…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PokedexMemoryCalibrator(
                    host, port,
                    onDiagnostic = DebugLog::add
                ).calibrateAndRead()
            }

            when (result) {
                is PokedexMemoryCalibrator.Result.Success -> {
                    LiveDexState.applySyncResult(result.nationalDexEnabled, result.owned, result.seen)
                    DebugLog.add(
                        "Pokedex auto-sync succeeded: " +
                            "${if (result.nationalDexEnabled) "National" else "Regional"} Dex, " +
                            "${LiveDexState.registeredCount} registered."
                    )
                    dexMode = if (result.nationalDexEnabled) DexMode.NATIONAL else DexMode.REGIONAL
                    refreshAfterSync()
                }
                is PokedexMemoryCalibrator.Result.Failure -> {
                    DebugLog.add("! Pokedex auto-sync failed: ${result.reason}")
                    updateSyncStatusLabel()
                }
            }
        }
    }

    private fun refreshAfterSync() {
        val currentSpeciesId = entries.getOrNull(selectedIndex)?.speciesId
        entries = loadEntriesForCurrentMode()
        selectedIndex = entries.indexOfFirst { it.speciesId == currentSpeciesId }.coerceAtLeast(0)

        adapter.submit(entries, dexMode)
        adapter.setSelectedIndex(selectedIndex)
        recyclerView.scrollToPosition(selectedIndex)
        updateDexModeButtons()
        updateSyncStatusLabel()
        updatePreview(animate = false)
    }

    private fun loadEntriesForCurrentMode(): List<PokedexEntry> {
        return if (dexMode == DexMode.NATIONAL) {
            PokedexRepository.byNationalDex(this)
        } else {
            PokedexRepository.byRegionalDex(this)
        }
    }

    private fun updateSyncStatusLabel() {
        syncStatusLabel.text = if (LiveDexState.isSynced) {
            getString(R.string.pokedex_sync_status_active, LiveDexState.registeredCount)
        } else {
            getString(R.string.pokedex_sync_status_inactive)
        }
    }

    private fun setDexMode(mode: DexMode) {
        if (mode == dexMode) return

        val currentSpeciesId = entries.getOrNull(selectedIndex)?.speciesId
        dexMode = mode
        entries = loadEntriesForCurrentMode()
        selectedIndex = entries.indexOfFirst { it.speciesId == currentSpeciesId }.coerceAtLeast(0)

        adapter.submit(entries, dexMode)
        adapter.setSelectedIndex(selectedIndex)
        recyclerView.scrollToPosition(selectedIndex)
        updateDexModeButtons()
        updatePreview(animate = false)
    }

    private fun updateDexModeButtons() {
        nationalButton.isSelected = dexMode == DexMode.NATIONAL
        regionalButton.isSelected = dexMode == DexMode.REGIONAL
        nationalButton.alpha = if (dexMode == DexMode.NATIONAL) 1f else 0.6f
        regionalButton.alpha = if (dexMode == DexMode.REGIONAL) 1f else 0.6f
    }

    private fun moveSelection(delta: Int) {
        if (entries.isEmpty()) return
        val newIndex = (selectedIndex + delta).coerceIn(0, entries.lastIndex)
        if (newIndex == selectedIndex) return
        selectedIndex = newIndex
        adapter.setSelectedIndex(selectedIndex)
        recyclerView.scrollToPosition(selectedIndex)
        updatePreview(animate = true)
    }

    private fun updatePreview(animate: Boolean) {
        val entry = entries.getOrNull(selectedIndex) ?: return
        previewSprite.loadSpecies(entry.assetFolder)
        if (animate) previewSprite.playAnimation()

        val number = if (dexMode == DexMode.NATIONAL) entry.nationalDexNumber else entry.regionalDexNumber
        previewName.text = entry.displayName
        previewNumber.text = getString(R.string.pokedex_number_format, number)
        previewType.text = entry.types.joinToString(" / ") { it.lowercase().replaceFirstChar(Char::uppercase) }
    }

    private fun openDetail() {
        val entry = entries.getOrNull(selectedIndex) ?: return
        val intent = Intent(this, PokedexDetailActivity::class.java)
            .putExtra(PokedexDetailActivity.EXTRA_SPECIES_ID, entry.speciesId)
        startActivity(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Base class handles the dialogue box (e.g. a background money-transfer notice)
        // first, when it's showing - only fall through to this screen's own key handling
        // once it says the key wasn't its concern.
        if (super.onKeyDown(keyCode, event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                setDexMode(DexMode.NATIONAL)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                setDexMode(DexMode.REGIONAL)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> {
                openDetail()
                return true
            }
        }
        return false
    }
}
