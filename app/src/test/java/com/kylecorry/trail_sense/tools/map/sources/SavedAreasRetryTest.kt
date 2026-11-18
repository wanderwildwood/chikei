package com.kylecorry.trail_sense.tools.map.sources

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SavedAreasRetryTest {

    private val noPauses = longArrayOf(0L, 0L)

    @Test
    fun aTileThatFailsOnceIsAskedForAgain() = runBlocking {
        val streak = AtomicInteger(0)
        var tries = 0
        val got = SavedAreas.withRetries(streak, noPauses) {
            tries++
            if (tries < 2) null else "tile"
        }
        assertEquals("tile", got)
        assertEquals(2, tries)
        assertEquals(0, streak.get())
    }

    @Test
    fun aTileIsTriedThreeTimesBeforeItCountsAsMissing() = runBlocking {
        val streak = AtomicInteger(0)
        var tries = 0
        val got = SavedAreas.withRetries<String>(streak, noPauses) {
            tries++
            null
        }
        assertNull(got)
        assertEquals(3, tries)
        assertEquals(1, streak.get())
    }

    @Test
    fun aGoodTileClearsTheRunOfFailures() = runBlocking {
        val streak = AtomicInteger(SavedAreas.GIVE_UP_AFTER - 1)
        SavedAreas.withRetries(streak, noPauses) { "tile" }
        assertEquals(0, streak.get())
    }

    @Test
    fun afterSeveralTilesInARowFailNoMoreAreAskedFor() = runBlocking {
        val streak = AtomicInteger(0)
        repeat(SavedAreas.GIVE_UP_AFTER) {
            SavedAreas.withRetries<String>(streak, noPauses) { null }
        }
        var tries = 0
        val got = SavedAreas.withRetries(streak, noPauses) {
            tries++
            "tile"
        }
        assertNull(got)
        assertEquals(0, tries)
    }

    @Test
    fun oneServerGivingUpDoesNotStopTheOther() = runBlocking {
        val terrain = AtomicInteger(0)
        val map = AtomicInteger(0)
        repeat(SavedAreas.GIVE_UP_AFTER) {
            SavedAreas.withRetries<String>(terrain, noPauses) { null }
        }
        val got = SavedAreas.withRetries(map, noPauses) { "tile" }
        assertEquals("tile", got)
        assertNull(SavedAreas.withRetries(terrain, noPauses) { "tile" })
    }
}
