package com.logie.pgearhs.pokedex

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import com.logie.pgearhs.R
import com.logie.pgearhs.ui.BaseImmersiveActivity

class PokedexDetailActivity : BaseImmersiveActivity() {

    companion object {
        const val EXTRA_SPECIES_ID = "species_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pokedex_detail)

        val speciesId = intent.getIntExtra(EXTRA_SPECIES_ID, -1)
        val entry = PokedexRepository.find(this, speciesId) ?: run {
            finish()
            return
        }

        val spriteView = findViewById<ImageView>(R.id.detailSprite)
        val stream = assets.open("pokemon/${entry.assetFolder}/front.png")
        val bitmap = stream.use { BitmapFactory.decodeStream(it) }
        spriteView.setImageBitmap(bitmap)

        findViewById<TextView>(R.id.detailName).text = entry.displayName
        findViewById<TextView>(R.id.detailNumberAndType).text = buildString {
            append(getString(R.string.pokedex_number_format, entry.nationalDexNumber))
            append("  •  ")
            append(entry.types.joinToString(" / ") { it.lowercase().replaceFirstChar(Char::uppercase) })
        }
        findViewById<TextView>(R.id.detailCategory).text =
            entry.category.lowercase().replaceFirstChar(Char::uppercase) + " Pokémon"

        findViewById<TextView>(R.id.detailHeightWeight).text =
            getString(R.string.pokedex_height_weight_format, entry.heightM, entry.weightKg)

        findViewById<TextView>(R.id.detailAbilities).text = entry.abilities.joinToString(", ") {
            it.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        }

        // The source text renders the species' own name in ALL CAPS mid-sentence
        // (a leftover of the original game's text formatting); swap in the normal
        // title-cased name for readability without touching the rest of the text.
        findViewById<TextView>(R.id.detailPokedexEntry).text =
            entry.pokedexEntry.replace(entry.name, entry.displayName)

        findViewById<TextView>(R.id.detailBaseStats).text = buildString {
            val statOrder = listOf(
                "hp" to "HP",
                "attack" to "Attack",
                "defense" to "Defense",
                "spAttack" to "Sp. Atk",
                "spDefense" to "Sp. Def",
                "speed" to "Speed"
            )
            for ((key, label) in statOrder) {
                val value = entry.baseStats[key] ?: continue
                append(label.padEnd(8))
                append(value)
                append('\n')
            }
        }.trim()
    }
}
