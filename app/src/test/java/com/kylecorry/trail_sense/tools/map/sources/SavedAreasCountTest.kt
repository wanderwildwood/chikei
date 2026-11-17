package com.kylecorry.trail_sense.tools.map.sources

import com.kylecorry.sol.science.geology.CoordinateBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SavedAreasCountTest {

    @Test
    fun countingTilesAgreesWithListingThem() {
        val bounds = CoordinateBounds(35.70, -82.40, 35.50, -82.70)
        for (z in 6..14) {
            assertEquals(SavedAreas.tilesIn(bounds, z).size.toLong(), SavedAreas.countIn(bounds, z))
        }
    }
}
