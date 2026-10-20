package com.kylecorry.trail_sense.tools.map.forage

import com.kylecorry.trail_sense.shared.ProguardIgnore

/** Where a spot came from. The id of a spot starts with its source, so ids never collide. */
object ForageSources {
    const val OSM = "osm"
    const val FALLING_FRUIT = "ff"
}

/**
 * One edible plant on the map. [name] is the common name and [scientific] the scientific one,
 * either of which may be missing. [typeIds] are Falling Fruit's; [tags] the few OpenStreetMap
 * tags worth showing.
 */
class ForageSpot(
    val id: String,
    val source: String,
    val lat: Double,
    val lng: Double,
    val name: String?,
    val scientific: String?,
    val typeIds: List<Int>? = null,
    val tags: Map<String, String>? = null
) : ProguardIgnore {
    val genus: String? get() = ForageTaxa.genusOf(scientific)
}

/** Falling Fruit's count of spots around a point, for a map zoomed out. */
class ForageCluster(val lat: Double, val lng: Double, val count: Int) : ProguardIgnore

/** What is kept for one square of one source: when it was fetched, and what was in it. */
class ForageCellData(
    val fetched: Long,
    val spots: List<ForageSpot>? = null,
    val clusters: List<ForageCluster>? = null
) : ProguardIgnore

/** The extra a Falling Fruit spot shows when it is opened. */
class ForageDetail(
    val description: String? = null,
    val seasonStart: Int? = null,
    val seasonStop: Int? = null,
    val access: Int? = null,
    val unverified: Boolean? = null,
    val datasetName: String? = null,
    val datasetLicence: String? = null,
    val fetched: Long = 0
) : ProguardIgnore
