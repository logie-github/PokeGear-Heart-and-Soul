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

    private const val POSITIONS_ASSET_FILE = "trainer_rematch_positions.json"
    private const val ANCHORS_ASSET_FILE = "trainer_rematch_anchors.json"

    private var cachedPositions: Map<Int, RematchPosition>? = null
    private var cachedAnchors: Map<Int, Int>? = null

    fun loadAll(context: Context): Map<Int, RematchPosition> {
        cachedPositions?.let { return it }

        val json = context.assets.open(POSITIONS_ASSET_FILE).bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("rematches")

        val map = mutableMapOf<Int, RematchPosition>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            map[obj.getInt("id")] = RematchPosition(obj.getInt("tableId"), obj.getInt("position"))
        }

        cachedPositions = map
        return map
    }

    /** tableId -> the trainerId that occupies chain position 0 (the original encounter). */
    fun loadAnchors(context: Context): Map<Int, Int> {
        cachedAnchors?.let { return it }

        val json = context.assets.open(ANCHORS_ASSET_FILE).bufferedReader().use { it.readText() }
        val array = JSONObject(json).getJSONArray("anchors")

        val map = mutableMapOf<Int, Int>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            map[obj.getInt("tableId")] = obj.getInt("id")
        }

        cachedAnchors = map
        return map
    }
}
