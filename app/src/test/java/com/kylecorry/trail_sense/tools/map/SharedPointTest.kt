package com.kylecorry.trail_sense.tools.map

import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.tools.map.infrastructure.SharedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SharedPointTest {

    @Test
    fun plainPoint() {
        assertEquals(
            SharedPoint(Coordinate(12.34567, -45.67891), null),
            SharedPoint.parse("geo:12.34567,-45.67891")
        )
    }

    @Test
    fun pointWithLabelInQuery() {
        assertEquals(
            SharedPoint(Coordinate(12.5, -45.25), "My car(old)"),
            SharedPoint.parse("geo:12.500000,-45.250000?q=12.500000,-45.250000(My%20car%28old%29)")
        )
    }

    @Test
    fun zeroPathTakesThePointFromTheQuery() {
        assertEquals(
            SharedPoint(Coordinate(51.47793, -0.00148), "Detour"),
            SharedPoint.parse("geo:0,0?q=51.47793,-0.00148(Detour)")
        )
    }

    @Test
    fun labelParameter() {
        assertEquals(
            SharedPoint(Coordinate(10.0, 20.0), "Ada Whitlock"),
            SharedPoint.parse("geo:10,20,300?label=Ada%20Whitlock")
        )
    }

    @Test
    fun addressesAndNonPointsAreLeftAlone() {
        assertNull(SharedPoint.parse("geo:0,0?q=1600%20Main%20Street"))
        assertNull(SharedPoint.parse("geo:0,0"))
        assertNull(SharedPoint.parse("geo:95,20"))
        assertNull(SharedPoint.parse("https://example.com"))
    }
}
