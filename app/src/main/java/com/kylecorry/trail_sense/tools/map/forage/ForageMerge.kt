package com.kylecorry.trail_sense.tools.map.forage

import kotlin.math.floor

/**
 * The two sources often hold the same tree, Falling Fruit having imported it from OSM or a
 * city's list that OSM imported too. A tree from OSM within [SAME_METRES] of a Falling Fruit spot
 * of the same genus is taken to be that spot and left out, since Falling Fruit says more about
 * it (season, access, notes).
 */
object ForageMerge {

    const val SAME_METRES = 5.0

    fun merge(osm: List<ForageSpot>, fallingFruit: List<ForageSpot>): List<ForageSpot> {
        if (fallingFruit.isEmpty()) return osm.distinctBy { it.id }
        // Buckets of about 11 m, so each OSM tree is compared only with its near neighbours
        val buckets = fallingFruit.groupBy { bucket(it.lat) to bucket(it.lng) }
        val kept = osm.filter { tree ->
            val genus = tree.genus ?: return@filter true
            val bLat = bucket(tree.lat)
            val bLng = bucket(tree.lng)
            for (dLat in -1..1) {
                for (dLng in -1..1) {
                    val near = buckets[(bLat + dLat) to (bLng + dLng)] ?: continue
                    if (near.any { spot ->
                            spotHasGenus(spot, genus) &&
                                ForageGrid.metres(tree.lat, tree.lng, spot.lat, spot.lng) <= SAME_METRES
                        }) {
                        return@filter false
                    }
                }
            }
            true
        }
        return (fallingFruit + kept).distinctBy { it.id }
    }

    private fun spotHasGenus(spot: ForageSpot, genus: String): Boolean =
        spot.genus.equals(genus, ignoreCase = true)

    private fun bucket(value: Double): Long = floor(value * 10_000).toLong()
}
