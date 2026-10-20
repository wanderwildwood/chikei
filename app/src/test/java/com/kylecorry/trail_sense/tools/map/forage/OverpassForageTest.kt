package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverpassForageTest {

    private val taxa = ForageTaxa(listOf("Malus", "Prunus avium"), listOf("Apple", "Sweet cherry"))

    // Made-up answer: an apple, a flowering cherry (not edible enough), a sweet cherry by
    // produce only, a tree tagged as a fungal genus, an orchard way, and a node with no tags
    private val answer = """
        {"elements": [
          {"type": "node", "id": 1, "lat": 10.0001, "lon": 20.0001,
           "tags": {"natural": "tree", "genus": "Malus", "species": "Malus domestica", "access": "yes", "name": "Someone's tree"}},
          {"type": "node", "id": 2, "lat": 10.0002, "lon": 20.0002,
           "tags": {"natural": "tree", "genus": "Prunus", "species": "Prunus serrulata"}},
          {"type": "node", "id": 3, "lat": 10.0003, "lon": 20.0003,
           "tags": {"natural": "tree", "produce": "sweet cherries"}},
          {"type": "node", "id": 4, "lat": 10.0004, "lon": 20.0004,
           "tags": {"natural": "tree", "genus": "Pleurotus"}},
          {"type": "way", "id": 5, "tags": {"landuse": "orchard", "produce": "apple"}},
          {"type": "node", "id": 6, "lat": 10.0006, "lon": 20.0006}
        ]}
    """.trimIndent()

    @Test
    fun keepsOnlyEdiblePlants() {
        val spots = OverpassForage.parse(answer, taxa)!!
        assertEquals(listOf("osm:n1", "osm:n3"), spots.map { it.id })
        val apple = spots[0]
        assertEquals("Apple", apple.name)
        assertEquals("Malus domestica", apple.scientific)
        assertEquals("yes", apple.tags?.get("access"))
        // Names of people's trees and other stray tags are not kept
        assertNull(apple.tags?.get("name"))
        assertEquals("Sweet cherry", spots[1].name)
        assertEquals("Prunus avium", spots[1].scientific)
    }

    @Test
    fun badAnswerIsNull() {
        assertNull(OverpassForage.parse("<html>busy</html>", taxa))
    }

    @Test
    fun queryAsksForTheRoundedBoxAndGenera() {
        val query = OverpassForage.query(ForageBox(1.0, 2.0, 1.5, 2.5), listOf("Malus", "Prunus", "bad\"name"))
        assertTrue(query.contains("[bbox:1.000,2.000,1.500,2.500]"))
        assertTrue(query.contains("^(Malus|Prunus)( |$)"))
        assertFalse(query.contains("bad"))
        assertTrue(query.contains("out body ${OverpassForage.LIMIT}"))
    }
}
