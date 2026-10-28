package com.kylecorry.trail_sense.tools.map.streets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreetCoverageTest {

    // A straight street running east from a made-up point, about 500 m long
    private val lat = 40.0
    private val lng0 = -100.0
    private val perMetre = 1.0 / (111_195.0 * Math.cos(Math.toRadians(lat)))

    private fun street(metres: Double) = StreetWay(1, doubleArrayOf(lat, lng0, lat, lng0 + metres * perMetre))

    private fun walkEast(from: Double, to: Double, step: Double = 8.0): DoubleArray {
        val out = mutableListOf<Double>()
        var m = from
        while (m <= to) {
            out.add(lat + 0.00001) // about a metre off the street
            out.add(lng0 + m * perMetre)
            m += step
        }
        return out.toDoubleArray()
    }

    @Test
    fun anUnwalkedStreetIsOneThinRunOfTheWholeLength() {
        val pieces = StreetCoverage.pieces(street(500.0), WalkedIndex())
        assertEquals(1, pieces.size)
        assertFalse(pieces[0].walked)
        assertEquals(500.0, pieces[0].metres, 0.5)
    }

    @Test
    fun aFullyWalkedStreetIsOneThickRun() {
        val walked = WalkedIndex().apply { addPath(walkEast(0.0, 500.0)) }
        val pieces = StreetCoverage.pieces(street(500.0), walked)
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].walked)
    }

    @Test
    fun halfWalkedSplitsAtThePieceNearTheMiddle() {
        val walked = WalkedIndex().apply { addPath(walkEast(0.0, 250.0)) }
        val pieces = StreetCoverage.pieces(street(500.0), walked)
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].walked)
        assertFalse(pieces[1].walked)
        assertEquals(500.0, pieces.sumOf { it.metres }, 0.5)
        // The runs meet where one ends and the other begins
        val firstEnd = pieces[0].points.takeLast(2)
        val secondStart = pieces[1].points.take(2)
        assertEquals(firstEnd, secondStart)
        // The split is within a piece of the middle
        val splitMetres = (firstEnd[1] - lng0) / perMetre
        assertEquals(250.0, splitMetres, StreetCoverage.PIECE_METRES * 1.5)
    }

    @Test
    fun aStreetBesideThePathIsNotWalked() {
        // A track 60 m north of the street
        val far = doubleArrayOf(lat + 60 / 111_195.0, lng0, lat + 60 / 111_195.0, lng0 + 500 * perMetre)
        val walked = WalkedIndex().apply { addPath(far) }
        assertFalse(StreetCoverage.pieces(street(500.0), walked).any { it.walked })
    }

    @Test
    fun aPauseBetweenFixesWalksNothingInBetween() {
        val walked = WalkedIndex().apply {
            addPath(doubleArrayOf(lat, lng0, lat, lng0 + 300 * perMetre), longArrayOf(1000, 1000 + 600))
        }
        assertFalse(StreetCoverage.pieces(street(500.0), walked).any { it.walked && it.metres > 100 })
        assertEquals(2, walked.size)
    }

    @Test
    fun aSimplifiedStraightRunIsFilledIn() {
        // Two fixes 300 m apart a minute or two apart, or with no times at all (an imported file)
        listOf(longArrayOf(1000, 1100), null).forEach { times ->
            val walked = WalkedIndex().apply {
                addPath(doubleArrayOf(lat, lng0, lat, lng0 + 300 * perMetre), times)
            }
            assertTrue(walked.near(lat, lng0 + 150 * perMetre))
        }
    }

    @Test
    fun shortGapsAreFilledIn() {
        val walked = WalkedIndex().apply {
            addPath(doubleArrayOf(lat, lng0, lat, lng0 + 60 * perMetre))
        }
        assertTrue(walked.size > 4)
        assertTrue(walked.near(lat, lng0 + 30 * perMetre))
        assertFalse(walked.near(lat, lng0 + 120 * perMetre))
    }

    @Test
    fun aBentStreetKeepsItsCorner() {
        val corner = StreetWay(2, doubleArrayOf(lat, lng0, lat, lng0 + 100 * perMetre, lat + 100 / 111_195.0, lng0 + 100 * perMetre))
        val pieces = StreetCoverage.pieces(corner, WalkedIndex())
        val all = pieces.flatMap { it.points.toList() }
        assertTrue(all.zipWithNext().any { (a, b) -> a == lat && b == lng0 + 100 * perMetre })
        assertEquals(200.0, pieces.sumOf { it.metres }, 1.0)
    }

    @Test
    fun aStubIsOnePieceNotNone() {
        assertEquals(1, StreetCoverage.pieces(street(5.0), WalkedIndex()).size)
    }

    @Test
    fun aDegenerateWayHasNoPieces() {
        assertEquals(0, StreetCoverage.pieces(StreetWay(3, doubleArrayOf(lat, lng0, lat, lng0)), WalkedIndex()).size)
    }

    @Test
    fun theIndexWorksAtHighLatitudeAndAcrossTheMeridian() {
        val walked = WalkedIndex().apply { addPath(doubleArrayOf(64.0, -0.00005, 64.0, 0.00005)) }
        assertTrue(walked.near(64.0, 0.0001))
        assertFalse(walked.near(64.0, 0.001))
    }
}
