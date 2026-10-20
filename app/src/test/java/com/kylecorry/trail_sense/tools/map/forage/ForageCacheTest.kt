package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ForageCacheTest {

    @TempDir
    lateinit var dir: File

    private val cell = ForageCell(100, 200, 14)
    private val spot = ForageSpot("osm:n1", ForageSources.OSM, 1.0, 2.0, "Apple", "Malus", tags = mapOf("access" to "yes"))

    @Test
    fun keepsWhatWasFetched() {
        var now = 1_000L
        ForageCache(dir) { now }.put("osm", cell, ForageCellData(now, spots = listOf(spot)))
        // A fresh cache reads it back from disk
        val read = ForageCache(dir) { now }.get("osm", cell)
        assertNotNull(read)
        assertEquals("Apple", read!!.spots!![0].name)
        assertEquals("yes", read.spots!![0].tags!!["access"])
        assertNull(ForageCache(dir) { now }.get("ff", cell))
    }

    @Test
    fun freshForAMonth() {
        var now = 0L
        val cache = ForageCache(dir) { now }
        val data = ForageCellData(0L, spots = emptyList())
        assertTrue(cache.isFresh(data))
        now = ForageCache.TTL_MILLIS - 1
        assertTrue(cache.isFresh(data))
        now = ForageCache.TTL_MILLIS
        assertFalse(cache.isFresh(data))
        assertFalse(cache.isFresh(null))
    }

    @Test
    fun staleIsStillShown() {
        val cache = ForageCache(dir) { ForageCache.TTL_MILLIS * 3 }
        cache.put("osm", cell, ForageCellData(0L, spots = listOf(spot)))
        val data = cache.get("osm", cell)
        assertFalse(cache.isFresh(data))
        assertEquals(1, data!!.spots!!.size)
    }

    @Test
    fun pruneSparesKeptSquares() {
        val now = ForageCache.PRUNE_MILLIS * 2
        val cache = ForageCache(dir) { now }
        cache.put("osm", cell, ForageCellData(0L, spots = listOf(spot)))
        val other = ForageCell(101, 200, 14)
        cache.put("osm", other, ForageCellData(0L, spots = listOf(spot)), kept = true)
        dir.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(1_000L) }
        cache.prune()
        assertNull(cache.get("osm", cell))
        assertNotNull(cache.get("osm", other))
    }
}
