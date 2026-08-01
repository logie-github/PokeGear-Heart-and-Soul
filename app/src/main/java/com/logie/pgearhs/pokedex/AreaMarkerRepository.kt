package com.logie.pgearhs.pokedex

import android.content.Context
import org.json.JSONObject

/**
 * Real per-species area-marker points for the Pokedex AREA screen, precomputed offline from
 * HSSOURCE (see build_area_markers.py): wild_encounters.json (species -> maps) joined with
 * every map.json's region_map_section, resolved against the real 15x28 MAPSEC layout grid used
 * by the game's own Dex_FindLayoutRect(). x/y are pixel coordinates on the bundled 128x128
 * johto_region_map.png, proportionally scaled from that real grid (see the JSON's own _meta
 * notes for why this isn't a bit-exact native-GBA reproduction).
 */
data class AreaMarker(val mapsec: String, val x: Float, val y: Float)

object AreaMarkerRepository {

    private const val ASSET_FILE = "pokedex_area_markers.json"

    private var cached: Map<String, List<AreaMarker>>? = null

    fun get(context: Context, speciesConst: String): List<AreaMarker> =
        (cached ?: load(context).also { cached = it })[speciesConst] ?: emptyList()

    private fun load(context: Context): Map<String, List<AreaMarker>> {
        val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val markers = JSONObject(json).getJSONObject("markers")
        val result = mutableMapOf<String, List<AreaMarker>>()
        markers.keys().forEach { species ->
            val array = markers.getJSONArray(species)
            result[species] = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                AreaMarker(
                    mapsec = obj.getString("mapsec"),
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat()
                )
            }
        }
        return result
    }
}
