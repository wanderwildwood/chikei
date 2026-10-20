package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
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
}
