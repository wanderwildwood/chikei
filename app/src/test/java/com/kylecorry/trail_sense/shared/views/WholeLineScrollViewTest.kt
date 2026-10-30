package com.kylecorry.trail_sense.shared.views

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WholeLineScrollViewTest {

    @Test
    fun everyLineFitting_cutsNothing() {
        assertEquals(-1, firstCutLine(intArrayOf(40, 80, 120), 120))
    }

    @Test
    fun theFirstLineThePageCutsIsFound() {
        // Lines 40 px apart, a foot at 130: the fourth (bottom 160) is cut, the third (120) is whole.
        assertEquals(3, firstCutLine(intArrayOf(40, 80, 120, 160, 200), 130))
    }

    @Test
    fun aFootAboveTheTextCutsNothingToClip() {
        assertEquals(-1, firstCutLine(intArrayOf(300, 340), 100))
    }

    @Test
    fun noLinesCutsNothing() {
        assertEquals(-1, firstCutLine(intArrayOf(), 100))
    }
}
