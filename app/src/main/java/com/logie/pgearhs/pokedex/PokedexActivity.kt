package com.logie.pgearhs.pokedex

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.logie.pgearhs.R
import com.logie.pgearhs.ui.BaseImmersiveActivity

class PokedexActivity : BaseImmersiveActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PokedexAdapter
    private lateinit var previewSprite: PokemonPreviewSpriteView
    private lateinit var previewName: TextView
    private lateinit var previewNumber: TextView
    private lateinit var previewType: TextView
    private lateinit var nationalButton: Button
    private lateinit var regionalButton: Button

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
        nationalButton = findViewById(R.id.nationalDexButton)
        regionalButton = findViewById(R.id.regionalDexButton)

        recyclerView.layoutManager = LinearLayoutManager(this)

        entries = PokedexRepository.byNationalDex(this)
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
        updatePreview(animate = false)
    }

    private fun setDexMode(mode: DexMode) {
        if (mode == dexMode) return

        val currentSpeciesId = entries.getOrNull(selectedIndex)?.speciesId
        dexMode = mode
        entries = if (mode == DexMode.NATIONAL) {
            PokedexRepository.byNationalDex(this)
        } else {
            PokedexRepository.byRegionalDex(this)
        }
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
        return super.onKeyDown(keyCode, event)
    }
}
