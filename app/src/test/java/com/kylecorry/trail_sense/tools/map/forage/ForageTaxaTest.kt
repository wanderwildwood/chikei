package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForageTaxaTest {

    private val taxa = ForageTaxa(
        listOf("Malus", "Prunus avium", "Morus", "Cherry plumus"),
        listOf("Apple", "Sweet cherry", "Mulberry", "Berry")
    )

    @Test
    fun matchesGenusAndSpecies() {
        assertEquals("Apple", taxa.match("Malus domestica")?.name)
        assertEquals("Apple", taxa.match("Malus")?.name)
        assertEquals("Sweet cherry", taxa.match("Prunus avium 'Stella'")?.name)
        assertEquals("Sweet cherry", taxa.match("prunus avium")?.name)
    }

    @Test
    fun genusAloneDoesNotMatchASpeciesEntry() {
        assertNull(taxa.match("Prunus"))
        assertNull(taxa.match("Prunus serrulata"))
        assertNull(taxa.match(null))
        assertNull(taxa.match("  "))
    }

    @Test
    fun matchesProduce() {
        assertEquals("Apple", taxa.matchProduce("apples")?.name)
        assertEquals("Mulberry", taxa.matchProduce("mulberries")?.name)
        assertEquals("Sweet cherry", taxa.matchProduce("hay;sweet_cherry")?.name)
        assertNull(taxa.matchProduce("timber"))
    }

    @Test
    fun fungiNeverMatch() {
        val withFungus = ForageTaxa(listOf("Morchella", "Malus"), listOf("Morel", "Apple"))
        assertNull(withFungus.match("Morchella esculenta"))
        assertNull(withFungus.matchProduce("morel"))
        assertTrue(Fungi.isFungus("Pleurotus ostreatus"))
        assertTrue(Fungi.isFungus("Basidiomycota"))
        assertFalse(Fungi.isFungus("Malus domestica"))
        assertTrue(Fungi.soundsLikeFungus("Field mushroom"))
        assertFalse(Fungi.soundsLikeFungus("Pear"))
    }

    @Test
    fun genusOfCleansCultivars() {
        assertEquals("Malus", ForageTaxa.genusOf("malus domestica 'Gala'"))
        assertEquals("Prunus avium", ForageTaxa.clean("Prunus  avium \"Stella\""))
    }
}
