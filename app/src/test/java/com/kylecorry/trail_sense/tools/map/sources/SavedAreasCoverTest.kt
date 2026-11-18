package com.kylecorry.trail_sense.tools.map.sources

import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedAreasCoverTest {

    private val area = CoordinateBounds(35.70, -82.40, 35.50, -82.70)

    @Test
    fun everyTileASaveKeepsIsLookedForInIt() {
        for (z in SavedAreas.MIN_MAP_ZOOM..SavedAreas.MAX_MAP_ZOOM) {
            for (tile in SavedAreas.tilesIn(area, z)) {
                assertTrue(SavedAreas.mayHold(area, tile), "$tile")
            }
        }
    }

    @Test
    fun aZoomedOutTileIsLookedForThoughItsCentreIsFarAway() {
        val tile = SavedAreas.tilesIn(area, SavedAreas.MIN_MAP_ZOOM).first()
        assertFalse(area.contains(tile.getCenter()))
        assertTrue(SavedAreas.mayHold(area, tile))
    }

    @Test
    fun aTileElsewhereIsNot() {
        val far = SavedAreas.tilesIn(CoordinateBounds(45.10, -110.40, 45.00, -110.60), 12).first()
        assertFalse(SavedAreas.mayHold(area, far))
    }
}
