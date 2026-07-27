package com.logie.pgearhs.trainers

import android.content.Context
import org.json.JSONObject

object TrainerRepository {

    private const val ASSET_FILE = "trainer_roster.json"

    private var cached: List<Trainer>? = null

    fun loadAll(context: Context): List<Trainer> {
        cached?.let { return it }

        val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        val trainersArray = JSONObject(json).getJSONArray("trainers")

        val trainers = mutableListOf<Trainer>()
        for (i in 0 until trainersArray.length()) {
            val obj = trainersArray.getJSONObject(i)
            trainers.add(
                Trainer(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    trainerClass = obj.getString("class"),
                    location = obj.getString("location"),
                    firstPokemon = obj.optString("firstPokemon").takeIf { it.isNotBlank() }
                )
            )
        }

        cached = trainers
        return trainers
    }
}
