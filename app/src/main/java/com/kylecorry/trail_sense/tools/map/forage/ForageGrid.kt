package com.kylecorry.trail_sense.tools.map.forage

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/**
 * A box of the map in degrees. Forage works in its own plain boxes, not the map's, so the
 * fetching and keeping can be checked without a phone.
 */
data class ForageBox(val south: Double, val west: Double, val north: Double, val east: Double) {

    fun contains(lat: Double, lng: Double): Boolean =
        lat >= south && lat < north && lng >= west && lng < east

    fun intersects(other: ForageBox): Boolean =
        south < other.north && other.south < north && west < other.east && other.west < east

    fun union(other: ForageBox): ForageBox = ForageBox(
        minOf(south, other.south), minOf(west, other.west),
        maxOf(north, other.north), maxOf(east, other.east)
    )

    /**
     * Grown out to whole thousandths of a degree (about 100 m), which is all a server is told:
     * the corners of a square of the grid, never the exact edges of the screen.
     */
    fun rounded(): ForageBox = ForageBox(
        floor(south * 1000 + EPSILON) / 1000,
        floor(west * 1000 + EPSILON) / 1000,
        ceil(north * 1000 - EPSILON) / 1000,
        ceil(east * 1000 - EPSILON) / 1000
    )

    /** South, west, north, east, as Overpass's bbox takes them. */
    fun overpass(): String = listOf(south, west, north, east).joinToString(",") { format(it) }

    /** South-west and north-east corners, as Falling Fruit's `bounds` takes them. */
    fun fallingFruit(): String = "${format(south)},${format(west)}|${format(north)},${format(east)}"

    companion object {
        private const val EPSILON = 1e-7
        fun format(value: Double): String = String.format(Locale.US, "%.3f", value)
    }
}

/** One square of the web map grid: the unit Forage fetches, keeps and draws from. */
data class ForageCell(val x: Int, val y: Int, val z: Int) {

    val key: String get() = "${z}_${x}_${y}"

    fun bounds(): ForageBox {
        val n = (1 shl z).toDouble()
        return ForageBox(
            south = latitude(y + 1, n),
            west = x / n * 360.0 - 180.0,
            north = latitude(y, n),
            east = (x + 1) / n * 360.0 - 180.0
        )
    }

    private fun latitude(row: Int, n: Double): Double =
        Math.toDegrees(atan(sinh(PI * (1 - 2 * row / n))))
}

object ForageGrid {

    /** Spots are fetched and kept in squares of this zoom: about 2.4 km across. */
    const val SPOT_ZOOM = 14

    fun cellAt(lat: Double, lng: Double, z: Int): ForageCell {
        val n = 1 shl z
        val x = floor((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val y = floor((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return ForageCell(x, y, z)
    }

    /**
     * The squares at [z] that [box] touches, or null if there are more than [max] (the view is
     * too wide to fetch at this zoom) or the box crosses the 180th meridian.
     */
    fun cells(box: ForageBox, z: Int, max: Int): List<ForageCell>? {
        if (box.west > box.east) return null
        val nw = cellAt(box.north, box.west, z)
        val se = cellAt(box.south, box.east, z)
        val count = (se.x - nw.x + 1).toLong() * (se.y - nw.y + 1)
        if (count > max) return null
        val cells = mutableListOf<ForageCell>()
        for (x in nw.x..se.x) {
            for (y in nw.y..se.y) {
                cells.add(ForageCell(x, y, z))
            }
        }
        return cells
    }

    /** The one box (rounded out) that covers every one of [cells]: one request for them all. */
    fun requestBox(cells: List<ForageCell>): ForageBox? =
        cells.map { it.bounds() }.reduceOrNull { a, b -> a.union(b) }?.rounded()

    /** Rough metres between two points; plenty for "is this the same tree". */
    fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return 6_371_000.0 * kotlin.math.sqrt(dLat * dLat + dLng * dLng)
    }
}
