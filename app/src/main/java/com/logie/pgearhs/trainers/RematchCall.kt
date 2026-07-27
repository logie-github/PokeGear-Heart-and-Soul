package com.logie.pgearhs.trainers

import kotlin.random.Random

/**
 * Assembles the flavor-text "conversation" for an outgoing rematch call, ported from
 * LazarusDex's `com.lazarusdex.data.RematchCall` (~/Documents/LazarusDex).
 *
 * Each list holds alternate versions of the same line. [assemble] picks one entry from
 * each list, in order, so the five chosen lines form one natural conversation.
 * Placeholders are filled from the caller context:
 *   PLAYER          -> the player's name
 *   POKEMONNAME     -> the trainer's lead Pokemon
 *   TRAINERLOCATION -> where the trainer battles
 */
object RematchCall {
    private val greeting = listOf(
        "Hi PLAYER!",
        "Hey PLAYER!",
        "Hello, PLAYER!",
        "Oh, hi PLAYER!",
        "Hey there, PLAYER!",
        "Good to hear from you, PLAYER!",
        "PLAYER! Hi!",
        "Well, hi PLAYER!",
        "Hello there, PLAYER!",
        "Hey! It's PLAYER!",
    )

    private val pokemonStatus = listOf(
        "My POKEMONNAME is doing great.",
        "POKEMONNAME has been doing really well.",
        "POKEMONNAME is in great shape.",
        "POKEMONNAME has been full of energy lately.",
        "POKEMONNAME couldn't be doing better.",
        "POKEMONNAME is as healthy as ever.",
        "POKEMONNAME's been doing fantastic.",
        "Things have been going great with POKEMONNAME.",
        "POKEMONNAME has been keeping me busy.",
        "POKEMONNAME is feeling stronger every day.",
    )

    private val battleResponse = listOf(
        "Oh, you'd like to battle again sometime?",
        "You want to have another battle sometime?",
        "So you're up for another battle?",
        "You'd like to battle again, huh?",
        "Sounds like you're ready for another battle.",
        "Another battle? I'd like that.",
        "You want a rematch sometime?",
        "Thinking about another battle already?",
        "You're asking for another battle?",
        "I'd be happy to battle you again.",
    )

    private val meetingLocation = listOf(
        "I'll be waiting at TRAINERLOCATION.",
        "Come find me at TRAINERLOCATION.",
        "Meet me over at TRAINERLOCATION.",
        "I'll head over to TRAINERLOCATION.",
        "Let's meet at TRAINERLOCATION.",
        "I'll be hanging around TRAINERLOCATION.",
        "You can find me waiting at TRAINERLOCATION.",
        "I'll see you at TRAINERLOCATION.",
        "I'll stay around TRAINERLOCATION until then.",
        "TRAINERLOCATION is where I'll be.",
    )

    private val goodbye = listOf(
        "See you soon!",
        "See you there!",
        "I'll be waiting!",
        "Don't keep me waiting!",
        "Catch you later!",
        "See you when you get here!",
        "Take care until then!",
        "Looking forward to it!",
        "See you around!",
        "Talk to you later!",
    )

    private const val DEFAULT_POKEMON = "Pokémon"
    private const val DEFAULT_LOCATION = "our usual spot"

    fun assemble(
        playerName: String,
        pokemonName: String?,
        location: String?,
        random: Random = Random.Default,
    ): List<String> {
        val pokemon = pokemonName?.takeIf { it.isNotBlank() } ?: DEFAULT_POKEMON
        val place = location?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCATION

        fun String.fill(): String =
            replace("PLAYER", playerName)
                .replace("POKEMONNAME", pokemon)
                .replace("TRAINERLOCATION", place)

        return listOf(
            greeting.random(random),
            pokemonStatus.random(random),
            battleResponse.random(random),
            meetingLocation.random(random),
            goodbye.random(random),
        ).map { it.fill() }
    }
}
