package com.logie.pgearhs.pokedex

import android.content.Context
import org.json.JSONObject

object PokedexRepository {

    private const val ASSET_FILE = "pokemon_dex_data.json"

    private val SPECIAL_FOLDERS = mapOf(
        "NIDORAN♀" to "nidoran_f",
        "NIDORAN♂" to "nidoran_m",
        "DUDUNSPARC" to "dudunsparce"
    )

    private var cached: List<PokedexEntry>? = null

    fun loadAll(context: Context): List<PokedexEntry> {
        cached?.let { return it }

        val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val pokemonArray = root.getJSONArray("pokemon")

        val entries = mutableListOf<PokedexEntry>()
        for (i in 0 until pokemonArray.length()) {
            val obj = pokemonArray.getJSONObject(i)
            val name = obj.getString("name")
            if (name == "UNUSED") continue

            val typesArray = obj.getJSONArray("types")
            val types = (0 until typesArray.length()).map { typesArray.getString(it) }

            val abilitiesArray = obj.getJSONArray("abilities")
            val abilities = (0 until abilitiesArray.length()).map { abilitiesArray.getString(it) }

            val statsObj = obj.getJSONObject("baseStats")
            val baseStats = statsObj.keys().asSequence().associateWith { statsObj.getInt(it) }

            entries.add(
                PokedexEntry(
                    speciesId = obj.getInt("speciesId"),
                    name = name,
                    nationalDexNumber = obj.getInt("nationalDexNumber"),
                    regionalDexNumber = obj.getInt("regionalDexNumber"),
                    types = types,
                    category = obj.getString("category"),
                    heightM = obj.getDouble("height_m"),
                    weightKg = obj.getDouble("weight_kg"),
                    pokedexEntry = obj.getString("pokedexEntry"),
                    abilities = abilities,
                    baseStats = baseStats,
                    assetFolder = folderNameFor(name)
                )
            )
        }

        cached = entries
        return entries
    }

    fun byNationalDex(context: Context): List<PokedexEntry> =
        loadAll(context).sortedBy { it.nationalDexNumber }

    fun byRegionalDex(context: Context): List<PokedexEntry> =
        loadAll(context).sortedBy { it.regionalDexNumber }

    fun find(context: Context, speciesId: Int): PokedexEntry? =
        loadAll(context).firstOrNull { it.speciesId == speciesId }

    private fun folderNameFor(speciesName: String): String {
        SPECIAL_FOLDERS[speciesName]?.let { return it }
        return speciesName
            .lowercase()
            .replace(".", "")
            .replace("'", "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }
}
