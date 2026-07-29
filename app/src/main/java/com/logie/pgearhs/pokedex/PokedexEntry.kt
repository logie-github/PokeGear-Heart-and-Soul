package com.logie.pgearhs.pokedex

data class Evolution(
    val species: String,
    val speciesId: Int,
    val method: String,
    val param: Int
)

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
    val assetFolder: String,
    val habitat: String?,
    val evolvesFrom: List<Evolution>,
    val evolvesTo: List<Evolution>
) {
    /** Display name, e.g. "Bulbasaur" instead of the raw "BULBASAUR". */
    val displayName: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
