package com.kylecorry.trail_sense.tools.map.geocache

import com.kylecorry.trail_sense.tools.map.forage.ForageCell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GeocacheStoreTest {

    @TempDir
    lateinit var dir: File

    private var now = 1_000_000L
    private fun store() = GeocacheStore(dir) { now }
    private fun cache(code: String, found: Boolean = false) =
        Geocache(code, GeocacheSources.GPX, "Cache $code", 40.0, -105.0, foundOnSite = found)

    @Test
    fun importedCachesReplaceByCodeAndSurviveARestart() {
        store().addImported(listOf(cache("GC1"), cache("GC2")))
        store().addImported(listOf(Geocache("GC2", GeocacheSources.GPX, "Renamed", 40.0, -105.0)))
        val again = store().imported()
        assertEquals(listOf("GC1", "GC2"), again.map { it.code })
        assertEquals("Renamed", again[1].name)
    }

    @Test
    fun findsFromTheSiteBecomeExportedVisits() {
        val store = store()
        store.addImported(listOf(cache("GC1", found = true), cache("GC2")))
        assertEquals(VisitKinds.FOUND, store.state("GC1")?.kind)
        assertTrue(store.state("GC1")!!.exported)
        assertNull(store.state("GC2"))
    }

    @Test
    fun visitsUndoAndExport() {
        val store = store()
        store.addVisit(CacheVisit("GC1", VisitKinds.DNF, 1))
        store.addVisit(CacheVisit("GC1", VisitKinds.NOTE, 2, "muggles"))
        store.addVisit(CacheVisit("GC1", VisitKinds.FOUND, 3))
        assertEquals(VisitKinds.FOUND, store.state("GC1")?.kind)
        store.undoState("GC1")
        assertEquals(VisitKinds.DNF, store.state("GC1")?.kind)
        assertEquals(2, store().visits().size)
        store.markExported(store.visits().take(1))
        val reread = store().visits()
        assertTrue(reread[0].exported)
        assertFalse(reread[1].exported)
    }

    @Test
    fun cellsKeepAWeek() {
        val store = store()
        val cell = ForageCell(1, 2, 12)
        store.putCell("us", cell, CacheCellData(now, listOf(cache("OU1"))), kept = false)
        val read = store().cell("us", cell)!!
        assertEquals("OU1", read.caches[0].code)
        assertTrue(store.isFresh(read))
        now += GeocacheStore.TTL_MILLIS
        assertFalse(store.isFresh(read))
    }
}
