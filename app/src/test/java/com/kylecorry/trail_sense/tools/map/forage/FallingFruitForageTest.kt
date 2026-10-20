package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FallingFruitForageTest {

    // A made-up type list in Falling Fruit's shape: three kingdoms, an apple under plants, two
    // fungi filed under Fungi and two not filed at all, an animal, a freegan find, a tree only
    // bees want, a type with only a common name, and the forager trees Topo leaves off: a maple,
    // an oak, a white pine, and a bare "Prunus" called plum, beside a pinyon and a pawpaw known
    // only by its common name.
    private val typesJson = """
        [
          {"id": 1, "parent_id": null, "taxonomic_rank": 1, "scientific_names": ["Plantae"], "common_names": {"en": ["Plant"]}, "categories": ["forager"]},
          {"id": 2, "parent_id": null, "taxonomic_rank": 1, "scientific_names": ["Fungi"], "common_names": {"en": ["Mushroom"]}, "categories": ["forager"]},
          {"id": 3, "parent_id": null, "taxonomic_rank": 1, "scientific_names": ["Animalia"], "common_names": {"en": ["Animal"]}, "categories": ["forager"]},
          {"id": 10, "parent_id": 1, "taxonomic_rank": 6, "scientific_names": ["Malus"], "common_names": {"en": ["Apple"]}, "categories": ["forager", "grafter"]},
          {"id": 11, "parent_id": 10, "taxonomic_rank": 8, "scientific_names": ["Malus domestica"], "common_names": {"en": ["Orchard apple"]}, "categories": ["forager"]},
          {"id": 20, "parent_id": 2, "taxonomic_rank": 8, "scientific_names": ["Fakeus edulis"], "common_names": {"en": ["Fake cap"]}, "categories": ["forager"]},
          {"id": 21, "parent_id": null, "taxonomic_rank": 8, "scientific_names": ["Fakeus other"], "common_names": {"en": ["Other cap"]}, "categories": ["forager"]},
          {"id": 22, "parent_id": null, "taxonomic_rank": 6, "scientific_names": ["Morchella"], "common_names": {"en": ["Morel"]}, "categories": ["forager"]},
          {"id": 23, "parent_id": null, "taxonomic_rank": null, "scientific_names": [], "common_names": {"en": ["Field mushroom"]}, "categories": ["forager"]},
          {"id": 30, "parent_id": 3, "taxonomic_rank": 8, "scientific_names": ["Fakea clam"], "common_names": {"en": ["Clam"]}, "categories": ["forager"]},
          {"id": 40, "parent_id": null, "taxonomic_rank": null, "scientific_names": [], "common_names": {"en": ["Free fridge"]}, "categories": ["forager", "freegan"]},
          {"id": 50, "parent_id": null, "taxonomic_rank": 6, "scientific_names": ["Salix"], "common_names": {"en": ["Willow"]}, "categories": ["honeybee"]},
          {"id": 60, "parent_id": null, "taxonomic_rank": null, "scientific_names": [], "common_names": {"en": ["Monkey fruit"]}, "categories": ["forager"]},
          {"id": 70, "parent_id": 1, "taxonomic_rank": 8, "scientific_names": ["Acer saccharum"], "common_names": {"en": ["Sugar maple"]}, "categories": ["forager"]},
          {"id": 71, "parent_id": 1, "taxonomic_rank": 6, "scientific_names": ["Quercus"], "common_names": {"en": ["Oak"]}, "categories": ["forager"]},
          {"id": 72, "parent_id": 1, "taxonomic_rank": 8, "scientific_names": ["Pinus strobus"], "common_names": {"en": ["White pine"]}, "categories": ["forager"]},
          {"id": 73, "parent_id": 1, "taxonomic_rank": 8, "scientific_names": ["Pinus edulis"], "common_names": {"en": ["Pinyon"]}, "categories": ["forager"]},
          {"id": 74, "parent_id": 1, "taxonomic_rank": 6, "scientific_names": ["Prunus"], "common_names": {"en": ["Plum"]}, "categories": ["forager"]},
          {"id": 75, "parent_id": 1, "taxonomic_rank": 8, "scientific_names": ["Prunus serrulata", "Prunus domestica"], "common_names": {"en": ["Odd plum"]}, "categories": ["forager"]},
          {"id": 76, "parent_id": null, "taxonomic_rank": null, "scientific_names": [], "common_names": {"en": ["Pawpaw"]}, "categories": ["forager"]}
        ]
    """.trimIndent()

    private val taxa = ForageTaxa(
        listOf("Malus", "Pinus edulis", "Prunus domestica", "Asimina triloba", "Fakeus", "Morchella"),
        listOf("Apple", "Pinyon pine", "Plum", "Pawpaw", "Fake cap", "Morel")
    )

    private fun kept(): Map<Int, FallingFruitForage.Kept> =
        FallingFruitForage.keep(FallingFruitForage.parseTypes(typesJson)!!, taxa).associateBy { it.id }

    @Test
    fun keepsFruitAndNutForagersOnly() {
        assertEquals(setOf(10, 11, 73, 75, 76), kept().keys)
        assertEquals("Orchard apple", kept()[11]?.name)
        assertEquals("Malus domestica", kept()[11]?.scientific)
    }

    @Test
    fun mapleOakAndPineAreDropped() {
        val ids = kept().keys
        assertFalse(70 in ids) // a maple
        assertFalse(71 in ids) // the oaks
        assertFalse(72 in ids) // a pine not grown for its nuts
        assertTrue(73 in ids) // a pinyon is
        assertFalse(74 in ids) // a bare genus with flowering cherries in it
        assertFalse(1 in ids) // the plant kingdom itself
        assertFalse(60 in ids) // a common name not on the list
    }

    @Test
    fun typesMapToTheNameOnTheList() {
        // The scientific name kept is the one on the list, not merely the first
        assertEquals("Prunus domestica", kept()[75]?.scientific)
        assertEquals("Pinus edulis", kept()[73]?.scientific)
        // A type with no scientific name is kept by its common name, and shown by it
        assertNull(kept()[76]?.scientific)
        assertEquals("Pawpaw", kept()[76]?.name)
    }

    @Test
    fun olderListsAreSortedAgain() {
        assertTrue(FallingFruitForage.isAllowed(FallingFruitForage.Kept(11, "Apple", "Malus domestica"), taxa))
        assertFalse(FallingFruitForage.isAllowed(FallingFruitForage.Kept(70, "Sugar maple", "Acer saccharum"), taxa))
        assertTrue(FallingFruitForage.isAllowed(FallingFruitForage.Kept(76, "Pawpaw", null), taxa))
        assertFalse(FallingFruitForage.isAllowed(FallingFruitForage.Kept(60, "Monkey fruit", null), taxa))
    }

    @Test
    fun keptSpotsAreFilteredByTheList() {
        fun ff(scientific: String?, name: String?) =
            ForageSpot("ff:1", ForageSources.FALLING_FRUIT, 0.0, 0.0, name, scientific)
        assertTrue(taxa.allows(ff("Malus domestica", "Apple")))
        assertFalse(taxa.allows(ff("Acer platanoides", "Norway maple")))
        assertFalse(taxa.allows(ff("Quercus rubra", "Red oak")))
        assertTrue(taxa.allows(ff(null, "Pawpaw")))
        val maple = ForageSpot("osm:n1", ForageSources.OSM, 0.0, 0.0, "Maple", "Acer",
            tags = mapOf("genus" to "Acer"))
        val plum = ForageSpot("osm:n2", ForageSources.OSM, 0.0, 0.0, "Plum", "Prunus",
            tags = mapOf("genus" to "Prunus", "produce" to "plums"))
        assertFalse(taxa.allows(maple))
        assertTrue(taxa.allows(plum))
    }

    @Test
    fun fungiAreLeftOutEveryWay() {
        val ids = kept().keys
        assertFalse(20 in ids) // filed under Fungi
        assertFalse(21 in ids) // unfiled, but its genus is filed under Fungi elsewhere
        assertFalse(22 in ids) // unfiled, a known fungal genus
        assertFalse(23 in ids) // no scientific name, but plainly a mushroom
        assertFalse(2 in ids)
    }

    @Test
    fun parsesLocationsKeepingShownTypes() {
        val json = """[
            {"id": 100, "lat": 1.0, "lng": 2.0, "type_ids": [11, 20]},
            {"id": 101, "lat": 1.1, "lng": 2.1, "type_ids": [20]},
            {"id": 102, "lat": 1.2, "lng": 2.2, "type_ids": [50, 10]}
        ]"""
        val spots = FallingFruitForage.parseLocations(json, kept())!!
        assertEquals(listOf("ff:100", "ff:102"), spots.map { it.id })
        assertEquals(listOf(11), spots[0].typeIds)
        assertEquals("Orchard apple", spots[0].name)
        assertEquals("Malus", spots[1].genus)
        assertEquals(3, FallingFruitForage.countEntries(json))
    }

    @Test
    fun parsesClusters() {
        val clusters = FallingFruitForage.parseClusters("""[{"lat": 1.0, "lng": 2.0, "count": 12}, {"lat": 1.0, "lng": 2.0, "count": 0}]""")!!
        assertEquals(1, clusters.size)
        assertEquals(12, clusters[0].count)
    }

    @Test
    fun parsesDetail() {
        val json = """{"id": 100, "lat": 1.0, "lng": 2.0, "type_ids": [11],
            "description": " Two trees by the path. ", "season_start": 7, "season_stop": 9,
            "access": 2, "unverified": true, "author": "A. Person",
            "import": {"name": "Example City Trees", "license": "ODbL"}}"""
        val detail = FallingFruitForage.parseDetail(json, 5L)!!
        assertEquals("Two trees by the path.", detail.description)
        assertEquals(7, detail.seasonStart)
        assertEquals(9, detail.seasonStop)
        assertEquals(2, detail.access)
        assertEquals(true, detail.unverified)
        assertEquals("Example City Trees", detail.datasetName)
        assertEquals("ODbL", detail.datasetLicence)
        assertEquals(5L, detail.fetched)
    }

    @Test
    fun detailWithoutImportOrSeason() {
        val detail = FallingFruitForage.parseDetail("""{"description": null, "season_start": 14, "access": null}""", 0L)!!
        assertNull(detail.description)
        assertNull(detail.seasonStart)
        assertNull(detail.datasetName)
    }

    @Test
    fun keyShape() {
        assertFalse(FallingFruitForage.isKey(null))
        assertFalse(FallingFruitForage.isKey(""))
        assertFalse(FallingFruitForage.isKey("KEY"))
        assertFalse(FallingFruitForage.isKey("ABCD1234"))
        assertFalse(FallingFruitForage.isKey("has space1"))
        assertFalse(FallingFruitForage.isKey("abcdefghijklmnopq"))
        assertTrue(FallingFruitForage.isKey("zz99yy88"))
    }
}
