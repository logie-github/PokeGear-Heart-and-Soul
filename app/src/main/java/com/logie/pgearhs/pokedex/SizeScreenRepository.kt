package com.logie.pgearhs.pokedex

import android.content.Context
import org.json.JSONObject

/**
 * Real per-species affine scale/offset values for the SIZE screen, pulled directly out of
 * src/data/pokemon/pokedex_entries.h. GBA OAM affine scale is inverse (256 = native size,
 * a larger value renders smaller), matching Task_LoadSizeScreen's SetOamMatrix calls.
 */
data class SizeScreenScale(
    val pokemonScale: Int,
    val pokemonOffset: Int,
    val trainerScale: Int,
    val trainerOffset: Int
)

object SizeScreenRepository {

    private const val ASSET_FILE = "size_screen/pokedex_size_data.json"
    private const val TRAINER_SILHOUETTE_ASSET = "size_screen/trainer_silhouette.png"

    private var cached: Map<Int, SizeScreenScale>? = null

    fun get(context: Context, nationalDexNumber: Int): SizeScreenScale? =
        (cached ?: load(context).also { cached = it })[nationalDexNumber]

    fun trainerSilhouetteAssetPath(): String = TRAINER_SILHOUETTE_ASSET

    private fun load(context: Context): Map<Int, SizeScreenScale> {
        val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val result = mutableMapOf<Int, SizeScreenScale>()
        root.keys().forEach { key ->
            val obj = root.getJSONObject(key)
            result[key.toInt()] = SizeScreenScale(
                pokemonScale = obj.getInt("pokemonScale"),
                pokemonOffset = obj.getInt("pokemonOffset"),
                trainerScale = obj.getInt("trainerScale"),
                trainerOffset = obj.getInt("trainerOffset")
            )
        }
        return result
    }
}
