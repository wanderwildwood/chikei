package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ForageStringsTest {

    private fun array(name: String): List<String> {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map { File(it) }.first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val arrays = doc.getElementsByTagName("string-array")
        for (i in 0 until arrays.length) {
            val node = arrays.item(i)
            if (node.attributes.getNamedItem("name").nodeValue == name) {
                val items = node.childNodes
                return (0 until items.length).map { items.item(it) }
                    .filter { it.nodeName == "item" }.map { it.textContent.trim() }
            }
        }
        return emptyList()
    }

    @Test
    fun everyPlantHasAName() {
        val taxa = array("forage_taxa")
        val names = array("forage_taxa_names")
        assertTrue(taxa.isNotEmpty())
        assertEquals(taxa.size, names.size)
        // No fungus on the list, and every entry a genus or a species
        taxa.forEach {
            assertTrue(!Fungi.isFungus(it), it)
            assertTrue(it.split(' ').size in 1..2, it)
        }
    }

    private fun shipped() = ForageTaxa(array("forage_taxa"), array("forage_taxa_names"))

    @Test
    fun theShippedListIsFruitAndNutsOnly() {
        val taxa = shipped()
        listOf(
            "Malus domestica", "Pyrus communis", "Prunus avium", "Prunus domestica", "Prunus persica",
            "Morus alba", "Ficus carica", "Diospyros virginiana", "Asimina triloba", "Juglans nigra",
            "Juglans cinerea", "Castanea dentata", "Corylus avellana", "Carya illinoinensis",
            "Prunus dulcis", "Rubus idaeus", "Vaccinium corymbosum", "Ribes uva-crispa",
            "Vitis riparia", "Amelanchier alnifolia", "Sambucus nigra", "Olea europaea",
            "Cydonia oblonga", "Mespilus germanica", "Citrus × limon", "Pinus edulis",
            "Pinus pinea", "Crataegus pinnatifida"
        ).forEach { assertNotNull(taxa.match(it), it) }
        listOf(
            "Acer saccharinum", "Acer platanoides", "Quercus alba", "Quercus", "Pinus strobus",
            "Pinus ponderosa", "Pinus", "Ginkgo biloba", "Tilia cordata", "Celtis occidentalis",
            "Crataegus monogyna", "Cornus kousa", "Prunus serrulata", "Prunus", "Gleditsia triacanthos",
            "Taraxacum officinale", "Urtica dioica"
        ).forEach { assertNull(taxa.match(it), it) }
    }

    @Test
    fun theOverpassQueryAsksOnlyForListedGenera() {
        val genera = shipped().genera
        listOf("Malus", "Juglans", "Corylus", "Pinus").forEach { assertTrue(it in genera, it) }
        listOf("Acer", "Quercus", "Tilia", "Ginkgo", "Celtis").forEach { assertFalse(it in genera, it) }
        // Pines are asked for, but only the pine-nut pines are kept from the answer
        val answer = """{"elements": [
            {"type": "node", "id": 1, "lat": 1.0, "lon": 2.0, "tags": {"natural": "tree", "species": "Pinus strobus"}},
            {"type": "node", "id": 2, "lat": 1.0, "lon": 2.0, "tags": {"natural": "tree", "species": "Pinus edulis"}},
            {"type": "node", "id": 3, "lat": 1.0, "lon": 2.0, "tags": {"natural": "tree", "genus": "Acer", "produce": "maple_syrup"}}
        ]}"""
        assertEquals(listOf("osm:n2"), OverpassForage.parse(answer, shipped())!!.map { it.id })
    }
}
