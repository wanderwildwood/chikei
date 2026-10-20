package com.kylecorry.trail_sense.tools.map.forage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForageGridTest {

    @Test
    fun roundsOutwardToThousandths() {
        val box = ForageBox(10.12345, -20.98761, 10.20001, -20.90049).rounded()
        assertEquals(10.123, box.south, 1e-9)
        assertEquals(-20.988, box.west, 1e-9)
        assertEquals(10.201, box.north, 1e-9)
        assertEquals(-20.900, box.east, 1e-9)
    }

    @Test
    fun roundingKeepsWholeValues() {
        val box = ForageBox(1.5, 2.25, 1.75, 2.5).rounded()
        assertEquals("1.500,2.250,1.750,2.500", box.overpass())
        assertEquals("1.500,2.250|1.750,2.500", box.fallingFruit())
    }

    @Test
    fun cellHoldsItsPoint() {
        val cell = ForageGrid.cellAt(12.3456, 45.6789, 14)
        assertTrue(cell.bounds().contains(12.3456, 45.6789))
        assertEquals("14_${cell.x}_${cell.y}", cell.key)
    }

    @Test
    fun cellsCoverABox() {
        val a = ForageGrid.cellAt(30.0, 10.0, 14)
        val box = ForageBox(29.99, 9.99, 30.03, 10.05)
        val cells = ForageGrid.cells(box, 14, 30)
        assertNotNull(cells)
        assertTrue(cells!!.contains(a))
        cells.forEach { assertTrue(it.bounds().intersects(box)) }
    }

    @Test
    fun tooManyCellsIsNull() {
        assertNull(ForageGrid.cells(ForageBox(0.0, 0.0, 1.0, 1.0), 14, 30))
    }

    @Test
    fun requestBoxIsRoundedAndCoversEveryCell() {
        val cells = ForageGrid.cells(ForageBox(40.001, -100.002, 40.03, -99.97), 14, 30)!!
        val box = ForageGrid.requestBox(cells)!!
        cells.forEach {
            val b = it.bounds()
            assertTrue(box.south <= b.south && box.west <= b.west && box.north >= b.north && box.east >= b.east)
        }
        assertEquals(box, box.rounded())
    }

    @Test
    fun metresIsRoughlyRight() {
        // A thousandth of a degree of latitude is about 111 m
        assertEquals(111.2, ForageGrid.metres(0.0, 0.0, 0.001, 0.0), 0.5)
    }
}
