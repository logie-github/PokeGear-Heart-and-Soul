package com.logie.pgearhs.pokedex

import android.content.Context
import org.json.JSONObject

/**
 * Real per-species area indicators for the Pokedex AREA screen, precomputed offline from
 * HSSOURCE (see build_area_markers.py): wild_encounters.json (species -> maps) joined with
 * every map.json's region_map_section, resolved against the real 15x28 MAPSEC layout grid used
 * by the game's own Dex_FindLayoutRect(). x/y (and rect* for glows) are pixel coordinates on the
 * bundled 128x128 johto_region_map.png, at a confirmed-exact native scale of 8px/tile with zero
 * offset (verified by an exact pixel-diff match and real city landmark positions - see the
 * JSON's own _meta notes and build_area_markers.py's docstring for the full verification).
 *
 * type mirrors the real game's own two indicator systems (src/pokedex_area_screen.c): "glow"
 * for towns/routes (a highlighted rectangle over the real MAPSEC bounding box, colored from the
 * real area_glow.png), "marker" for dungeons/special areas (a single dot, area_marker.png).
 */
data class AreaIndicator(
    val mapsec: String,
    val type: String,
    val x: Float,
    val y: Float,
    val rectX: Float = 0f,
    val rectY: Float = 0f,
    val rectW: Float = 0f,
    val rectH: Float = 0f
)

object AreaMarkerRepository {

    private const val ASSET_FILE = "pokedex_area_markers.json"

    private var cached: Map<String, List<AreaIndicator>>? = null

    fun get(context: Context, speciesConst: String): List<AreaIndicator> =
        (cached ?: load(context).also { cached = it })[speciesConst] ?: emptyList()

    private fun load(context: Context): Map<String, List<AreaIndicator>> {
        val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val markers = JSONObject(json).getJSONObject("markers")
        val result = mutableMapOf<String, List<AreaIndicator>>()
        markers.keys().forEach { species ->
            val array = markers.getJSONArray(species)
            result[species] = (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                AreaIndicator(
                    mapsec = obj.getString("mapsec"),
                    type = obj.getString("type"),
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat(),
                    rectX = obj.optDouble("rectX", 0.0).toFloat(),
                    rectY = obj.optDouble("rectY", 0.0).toFloat(),
                    rectW = obj.optDouble("rectW", 0.0).toFloat(),
                    rectH = obj.optDouble("rectH", 0.0).toFloat()
                )
            }
        }
        return result
    }
}
