package com.logie.pgearhs.trainers

import android.content.Context
import org.json.JSONObject

/**
 * Which slot [id] occupies in its native 5-member rematch chain (gRematchTable in
 * pokemonHnS-v121's src/battle_setup.c) - [tableId] is the chain's index, [position] is
 * where in that chain (1-4; position 0, the original encounter, isn't included here since
 * it has no "rematch" representation - see TrainerFlagsBridge.setRematchReady).
 */
data class RematchPosition(val tableId: Int, val position: Int)

object RematchPositionRepository {

    private const val ASSET_FILE = "trainer_rematch_positions.json"

    private var cached: Map<Int, RematchPosition>? = null

    fun loadAll(context: Context): Map<Int, RematchPosition> {
        cached?.let { return it }

        val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("rematches")

        val map = mutableMapOf<Int, RematchPosition>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            map[obj.getInt("id")] = RematchPosition(obj.getInt("tableId"), obj.getInt("position"))
        }

        cached = map
        return map
    }
}
