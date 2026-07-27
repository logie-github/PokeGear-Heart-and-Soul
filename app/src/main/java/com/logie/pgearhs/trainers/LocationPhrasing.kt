package com.logie.pgearhs.trainers

/**
 * Turns a trainer's raw, map-folder-derived location string ("Violet City - Gym", "Mt Pyre
 * - 3F") into something that reads naturally spoken aloud ("the Violet City Gym", "Mt.
 * Pyre") for [RematchCall]'s flavor text.
 *
 * This is purely a call-time presentation step - [Trainer.location] itself stays as the raw
 * string everywhere else (list rows, the bundled JSON, etc.); only [naturalize] applies this
 * phrasing, and only where a spoken sentence is being assembled.
 *
 * The 140 distinct locations in trainer_roster.json were few enough to hand-curate rather
 * than rely on a generic heuristic for every case - collapsing floor/room-number variants of
 * the same place to one phrase and fixing possessives lost in the original map-folder-name
 * conversion (e.g. "Kogas Room" -> "Koga's Room"). [FALLBACK_ARTICLE_EXCEPTIONS] and the
 * default "the " prefix cover anything not in the table (new locations from a future roster
 * regeneration): bare names for routes/cities/towns/mountains/named roads, "the ___" for
 * everything else.
 */
object LocationPhrasing {

    private val NATURAL_PHRASING = mapOf(
        "Abandoned Ship - Corridors - 1F" to "the Abandoned Ship",
        "Abandoned Ship - Rooms 2 - 1F" to "the Abandoned Ship",
        "Aqua Hideout - B 1F" to "the Aqua Hideout",
        "Aqua Hideout - B 2F" to "the Aqua Hideout",
        "Azalea Town" to "Azalea Town",
        "Azalea Town - Gym" to "the Azalea Town Gym",
        "Burned Tower - 1F" to "the Burned Tower",
        "Celadon City - Gym" to "the Celadon City Gym",
        "Cianwood Gym" to "the Cianwood Gym",
        "Dewford Town" to "Dewford Town",
        "Dewford Town - Gym" to "the Dewford Town Gym",
        "Dragons Den - Cavern" to "the Dragon's Den",
        "Ecruteak City - Gym" to "the Ecruteak City Gym",
        "Ecruteak City - Theater" to "the Ecruteak City Theater",
        "Ever Grande City" to "Ever Grande City",
        "Ever Grande City - Champions Room" to "the Champion's Room",
        "Ever Grande City - Phoebes Room" to "Phoebe's Room",
        "Fortree City - Gym" to "the Fortree City Gym",
        "Fuchsia City - Gym" to "the Fuchsia City Gym",
        "Goldenrod City - Gym" to "the Goldenrod City Gym",
        "Goldenrod City - Radio Tower - 2F" to "the Goldenrod Radio Tower",
        "Goldenrod City - Radio Tower - 3F" to "the Goldenrod Radio Tower",
        "Goldenrod City - Radio Tower - 4F" to "the Goldenrod Radio Tower",
        "Goldenrod City - Radio Tower - 5F" to "the Goldenrod Radio Tower",
        "Goldenrod City - Underground Storage" to "the Goldenrod Underground",
        "Goldenrod City - Underground Switches" to "the Goldenrod Underground",
        "Goldenrod City - Underground Tunnel" to "the Goldenrod Underground",
        "Ilex Forest" to "the Ilex Forest",
        "Jagged Pass" to "Jagged Pass",
        "Lavaridge Town" to "Lavaridge Town",
        "Magma Hideout - 2F - 1R" to "the Magma Hideout",
        "Mahogany Town - Gym" to "the Mahogany Town Gym",
        "Mauville City - Gym" to "the Mauville City Gym",
        "Mossdeep City - Gym" to "the Mossdeep City Gym",
        "Mossdeep City - Space Center - 1F" to "the Mossdeep Space Center",
        "Mt Chimney" to "Mt. Chimney",
        "Mt Mortar - 2F" to "Mt. Mortar",
        "Mt Pyre - 2F" to "Mt. Pyre",
        "Mt Pyre - 3F" to "Mt. Pyre",
        "Mt Pyre - 4F" to "Mt. Pyre",
        "Mt Pyre - Summit" to "Mt. Pyre",
        "Mt Pyre 3f" to "Mt. Pyre",
        "Olivine City - Gym" to "the Olivine City Gym",
        "Olivine City - Lighthouse" to "the Olivine Lighthouse",
        "Petalburg City - Gym" to "the Petalburg City Gym",
        "Petalburg Woods" to "the Petalburg Woods",
        "Pokemon League - Kogas Room" to "Koga's Room",
        "Rocket Hideout - B 1F" to "the Rocket Hideout",
        "Rocket Hideout - B 2F" to "the Rocket Hideout",
        "Rocket Hideout - B 3F" to "the Rocket Hideout",
        "Route 109 - Seashore House" to "the Seashore House on Route 109",
        "Route 110 - Trick House Puzzle 2" to "the Trick House on Route 110",
        "Route 110 - Trick House Puzzle 3" to "the Trick House on Route 110",
        "Route 110 - Trick House Puzzle 4" to "the Trick House on Route 110",
        "Route 110 - Trick House Puzzle 8" to "the Trick House on Route 110",
        "Route 119 - Weather Institute - 1F" to "the Weather Institute on Route 119",
        "SSAqua - B 1F" to "the S.S. Aqua",
        "SSAqua - Room NE" to "the S.S. Aqua",
        "SSAqua - Room NNE" to "the S.S. Aqua",
        "SSAqua - Room NW" to "the S.S. Aqua",
        "SSAqua - Room SE" to "the S.S. Aqua",
        "SSAqua - Room SSW" to "the S.S. Aqua",
        "SSAqua - Room SW" to "the S.S. Aqua",
        "SSTidal Lower Deck" to "the S.S. Tidal",
        "SSTidal Rooms" to "the S.S. Tidal",
        "Saffron City - Fighting Dojo VIP" to "the Saffron Fighting Dojo",
        "Saffron City - Gym" to "the Saffron City Gym",
        "Seafloor Cavern - Room 1" to "the Seafloor Cavern",
        "Seafloor Cavern - Room 4" to "the Seafloor Cavern",
        "Seafloor Cavern - Room 9" to "the Seafloor Cavern",
        "Slateport City - Oceanic Museum - 2F" to "the Slateport Oceanic Museum",
        "Slowpoke Well - B 1F" to "the Slowpoke Well",
        "Sootopolis City - Gym - B 1F" to "the Sootopolis City Gym",
        "Sprout Tower - 2F" to "the Sprout Tower",
        "Union Cave - 1F" to "Union Cave",
        "Union Cave - B 2F" to "Union Cave",
        "Unplaced (no map/rematch reference found)" to "our usual spot",
        "Vermilion City - Gym" to "the Vermilion City Gym",
        "Victory Road - B 2F" to "Victory Road",
        "Violet City - Gym" to "the Violet City Gym",
    )

    private val ROUTE_PATTERN = Regex("^Route (\\d+)")

    fun naturalize(rawLocation: String): String {
        NATURAL_PHRASING[rawLocation]?.let { return it }

        ROUTE_PATTERN.find(rawLocation)?.let { return "Route ${it.groupValues[1]}" }

        // Unrecognized location (e.g. from a future roster regeneration) - strip any
        // trailing floor/room qualifier and default to "the ___", matching the pattern
        // most of the hand-curated entries above follow.
        val base = rawLocation.substringBefore(" - ")
        return if (base.endsWith("City") || base.endsWith("Town")) base else "the $base"
    }
}
