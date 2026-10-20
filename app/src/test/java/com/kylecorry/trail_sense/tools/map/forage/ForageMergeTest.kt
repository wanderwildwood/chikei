package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForageMergeTest {

    private fun osm(id: Int, lat: Double, lng: Double, scientific: String) =
        ForageSpot("osm:n$id", ForageSources.OSM, lat, lng, null, scientific)

    private fun ff(id: Int, lat: Double, lng: Double, scientific: String) =
        ForageSpot("ff:$id", ForageSources.FALLING_FRUIT, lat, lng, null, scientific)

    @Test
    fun sameTreeShowsOnceFromFallingFruit() {
        // About 3 m apart, both apples
        val merged = ForageMerge.merge(
            listOf(osm(1, 10.0, 20.0, "Malus domestica")),
            listOf(ff(1, 10.00002, 20.00002, "Malus"))
        )
        assertEquals(listOf("ff:1"), merged.map { it.id })
    }

    @Test
    fun differentGenusOrFartherStays() {
        val merged = ForageMerge.merge(
            listOf(
                osm(1, 10.0, 20.0, "Pyrus communis"),
                osm(2, 10.0001, 20.0, "Malus") // about 11 m away
            ),
            listOf(ff(1, 10.0, 20.0, "Malus"))
        )
        assertEquals(setOf("ff:1", "osm:n1", "osm:n2"), merged.map { it.id }.toSet())
    }

    @Test
    fun noFallingFruitKeepsOsm() {
        val merged = ForageMerge.merge(listOf(osm(1, 1.0, 1.0, "Malus"), osm(1, 1.0, 1.0, "Malus")), emptyList())
        assertEquals(1, merged.size)
    }
}
