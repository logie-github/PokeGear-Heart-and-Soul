package com.logie.pgearhs.pokedex

data class PokedexEntry(
    val speciesId: Int,
    val name: String,
    val nationalDexNumber: Int,
    val regionalDexNumber: Int,
    val types: List<String>,
    val category: String,
    val heightM: Double,
    val weightKg: Double,
    val pokedexEntry: String,
    val abilities: List<String>,
    val baseStats: Map<String, Int>,
    val assetFolder: String
) {
    /** Display name, e.g. "Bulbasaur" instead of the raw "BULBASAUR". */
    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
