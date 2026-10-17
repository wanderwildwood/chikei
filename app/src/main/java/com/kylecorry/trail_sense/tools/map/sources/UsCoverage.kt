package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.sol.units.Coordinate

/**
 * Where The National Map has topo: the United States and its territories, as a coarse outline
 * (Natural Earth's 1:50m countries, public domain, widened by about 3 km and simplified; see
 * assets/us_coverage.txt). Close to a border either answer is fine: USGS draws a little past it,
 * and OpenTopoMap covers both sides.
 */
object UsCoverage {

    private class Ring(val lats: DoubleArray, val lons: DoubleArray, val bounds: CoordinateBounds)

    @Volatile
    private var rings: List<Ring>? = null

    fun contains(context: Context, location: Coordinate): Boolean {
        return load(context).any { ring ->
            ring.bounds.contains(location) && inside(ring, location.latitude, location.longitude)
        }
    }

    /** Whether any of [bounds] might be in the US: a quick test for whether a save can hold USGS tiles. */
    fun intersects(context: Context, bounds: CoordinateBounds): Boolean {
        return load(context).any { it.bounds.intersects(bounds) }
    }

    private fun inside(ring: Ring, lat: Double, lon: Double): Boolean {
        var inside = false
        var j = ring.lats.size - 1
        for (i in ring.lats.indices) {
            val yi = ring.lats[i]
            val yj = ring.lats[j]
            if ((yi > lat) != (yj > lat)) {
                val x = ring.lons[i] + (lat - yi) / (yj - yi) * (ring.lons[j] - ring.lons[i])
                if (lon < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun load(context: Context): List<Ring> {
        rings?.let { return it }
        synchronized(this) {
            rings?.let { return it }
            val loaded = context.assets.open("us_coverage.txt").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val points = line.trim().split(';').map { pair ->
                        val (lat, lon) = pair.split(',')
                        lat.toDouble() to lon.toDouble()
                    }
                    val lats = DoubleArray(points.size) { points[it].first }
                    val lons = DoubleArray(points.size) { points[it].second }
                    Ring(
                        lats,
                        lons,
                        CoordinateBounds(lats.max(), lons.max(), lats.min(), lons.min())
                    )
                }.toList()
            }
            rings = loaded
            return loaded
        }
    }
}
