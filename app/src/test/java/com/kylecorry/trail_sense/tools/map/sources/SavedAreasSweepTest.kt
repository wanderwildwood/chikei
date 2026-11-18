package com.kylecorry.trail_sense.tools.map.sources

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SavedAreasSweepTest {

    @Test
    fun onlyTilesNoAreaNamesAreSweptUp() {
        val known = setOf("mf3k2a", "mf9x0q")
        val found = listOf("mf3k2a", "mf3k2a", "mg01zz", "mf9x0q", "mg01zz", "")
        assertEquals(setOf("mg01zz"), SavedAreas.orphanIds(known, found))
    }

    @Test
    fun nothingIsSweptWhenEveryFolderIsNamed() {
        assertEquals(emptySet<String>(), SavedAreas.orphanIds(setOf("a1"), listOf("a1")))
    }

    @Test
    fun anAreaFolderIsNamedAfterItsId() {
        assertEquals("dem/area_mf3k2a", SavedAreas.folder("mf3k2a"))
    }
}
