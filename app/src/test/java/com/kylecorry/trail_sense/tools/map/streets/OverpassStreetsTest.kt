package com.kylecorry.trail_sense.tools.map.streets

import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class OverpassStreetsTest {

    @Test
    fun theQueryAsksForWalkableWaysInTheBoxOnly() {
        val q = OverpassStreets.query(ForageBox(40.0, -100.0, 40.01, -99.99))
        assertTrue(q.contains("[bbox:40.000,-100.000,40.010,-99.990]"))
        assertTrue(q.contains("way[\"highway\"]"))
        assertTrue(q.contains("motorway"))
        assertTrue(q.contains("out geom"))
        // Nothing about the person goes in a query
        assertFalse(q.contains("around"))
    }

    @Test
    fun anAnswerBecomesWays() {
        val json = """{"version":0.6,"elements":[
            {"type":"way","id":11,"tags":{"highway":"residential"},
             "geometry":[{"lat":40.0,"lon":-100.0},{"lat":40.001,"lon":-100.0}]},
            {"type":"way","id":12,"geometry":[{"lat":40.0,"lon":-100.0}]},
            {"type":"node","id":13,"lat":40.0,"lon":-100.0},
            {"type":"way","id":14}
        ]}"""
        val ways = OverpassStreets.parse(json)!!
        assertEquals(1, ways.size)
        assertEquals(11L, ways[0].id)
        assertEquals(2, ways[0].size)
        assertEquals(40.001, ways[0].lat(1), 0.0)
        assertEquals(-100.0, ways[0].lng(1), 0.0)
    }

    @Test
    fun noiseIsNotAnAnswer() {
        assertNull(OverpassStreets.parse("<html>busy</html>"))
        assertNull(OverpassStreets.parse("{}"))
        assertEquals(0, OverpassStreets.parse("""{"elements":[]}""")!!.size)
    }

    @Test
    fun theCacheKeepsAndServesASquare() {
        val dir = File.createTempFile("streets", "").apply { delete(); mkdirs() }
        try {
            var now = 1_000L
            val cache = StreetCache(dir) { now }
            val cell = com.kylecorry.trail_sense.tools.map.forage.ForageCell(10, 20, 15)
            assertNull(cache.get(cell))
            cache.put(cell, StreetCellData(now, listOf(StreetWay(5, doubleArrayOf(1.0, 2.0, 3.0, 4.0)))))
            // A fresh cache object reads it back from disk
            val again = StreetCache(dir) { now }.get(cell)
            assertNotNull(again)
            assertEquals(5L, again!!.ways[0].id)
            assertTrue(cache.isFresh(again))
            now += StreetCache.TTL_MILLIS + 1
            assertFalse(cache.isFresh(again))
            // Stale still serves; asking again is the caller's choice
            assertNotNull(cache.get(cell))
        } finally {
            dir.deleteRecursively()
        }
    }
}
