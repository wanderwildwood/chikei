package com.kylecorry.trail_sense.tools.map.streets

import com.kylecorry.trail_sense.shared.ProguardIgnore

/**
 * One street, path or track from OpenStreetMap: its corner points as latitude, longitude,
 * latitude, longitude... in one flat list.
 */
class StreetWay(val id: Long, val points: DoubleArray) : ProguardIgnore {
    val size: Int get() = points.size / 2
    fun lat(i: Int): Double = points[i * 2]
    fun lng(i: Int): Double = points[i * 2 + 1]
}

/** What is kept for one square of the map: when it was fetched, and the streets that touch it. */
class StreetCellData(val fetched: Long, val ways: List<StreetWay>) : ProguardIgnore

/**
 * A street cut into short pieces, each walked or not. [walked] says whether the piece is covered,
 * [points] is its line (latitude, longitude pairs, as [StreetWay]'s), [metres] its length.
 */
class StreetPiece(val walked: Boolean, val points: DoubleArray, val metres: Double)
