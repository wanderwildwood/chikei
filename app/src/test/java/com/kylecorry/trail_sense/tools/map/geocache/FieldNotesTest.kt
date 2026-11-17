package com.kylecorry.trail_sense.tools.map.geocache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldNotesTest {

    // 2026-11-17 15:04:00 UTC
    private val time = 1_794_927_840_000L

    @Test
    fun linesInTheSitesFormat() {
        assertEquals(
            "GC1ABCD,2026-11-17T15:04:00Z,Found it,\"TFTC, said \'hi\'\"",
            FieldNotes.line(CacheVisit("GC1ABCD", VisitKinds.FOUND, time, "TFTC, said \"hi\""))
        )
        assertEquals(
            "OU0123,2026-11-17T15:04:00Z,Didn't find it,\"\"",
            FieldNotes.line(CacheVisit("OU0123", VisitKinds.DNF, time))
        )
        assertEquals(
            "GC1ABCD,2026-11-17T15:04:00Z,Write note,\"two lines\"",
            FieldNotes.line(CacheVisit("GC1ABCD", VisitKinds.NOTE, time, "two\n lines"))
        )
    }

    @Test
    fun utf16LittleEndian() {
        val bytes = FieldNotes.bytes(listOf(CacheVisit("GC1", VisitKinds.FOUND, time)))
        assertEquals('G'.code.toByte(), bytes[0])
        assertEquals(0.toByte(), bytes[1])
        assertTrue(String(bytes, Charsets.UTF_16LE).endsWith("\"\"\n"))
    }

    @Test
    fun sites() {
        assertTrue(FieldNotes.isGeocachingCom("gc1abcd"))
        assertFalse(FieldNotes.isGeocachingCom("OU0123"))
    }
}
