package com.logie.pgearhs.trainers

data class Trainer(
    val id: Int,
    val name: String,
    val trainerClass: String,
    val location: String,
    val firstPokemon: String?
) {
    /** e.g. "Youngster Joey" - matches how the games themselves refer to a trainer. */
    val displayName: String
        get() = "$trainerClass $name"
}
