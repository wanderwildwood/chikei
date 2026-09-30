package com.kylecorry.trail_sense.tools.map.infrastructure

import com.kylecorry.andromeda.core.tryOrDefault
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.sol.units.Bearing
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.sol.units.Distance
import com.kylecorry.trail_sense.main.getAppService
import com.kylecorry.trail_sense.shared.dem.DEM
import com.kylecorry.trail_sense.tools.offline_maps.domain.OfflineMapService
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.trail_maps.mapsforge.MapsforgeAdapter
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.core.model.Tile
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * What the ground is at a point, from what is already on the phone: whose land it is (from the
 * region packs' Forest Service outlines) and the lie of it (from the elevation model).
 */
object PointInfo {

    sealed interface Land {
        /** Inside a Forest Service outline; [name] is the forest's, e.g. Pisgah National Forest. */
        data class Public(val name: String) : Land

        /** Covered by a region pack but outside every Forest Service outline. */
        data object NotForest : Land

        /** No region pack covers the point, so there is nothing to say. */
        data object Unknown : Land
    }

    /** [slope] in degrees; [aspect] is the way the ground faces (downhill), or null on the flat. */
    data class Ground(val slope: Float, val aspect: Bearing?)

    private const val SAMPLE_METERS = 20f
    private const val FLAT_DEGREES = 2f
    private const val LOOKUP_ZOOM: Byte = 14
    // mapdata/build_cells.py writes its sources into each pack's header comment
    private const val PACK_SOURCE = "USDA Forest Service"

    suspend fun land(location: Coordinate): Land = onIO {
        val maps = getAppService<OfflineMapService>().getRenderableTrailMaps(null)
            .filter { it.bounds?.contains(location) == true }
        val point = LatLong(location.latitude, location.longitude)
        var covered = false
        for (map in maps) {
            val file = MapsforgeAdapter.open(map.mapFile.path) ?: continue
            try {
                // Only a region pack carries the Forest Service's outlines; a plain OSM map
                // outside them says nothing about whose land it is
                if (file.mapFileInfo.comment?.contains(PACK_SOURCE) != true) {
                    continue
                }
                covered = true
                val name = tryOrDefault(null) { publicLandAt(file, point) }
                if (name != null) {
                    return@onIO Land.Public(name)
                }
            } finally {
                file.close()
            }
        }
        if (covered) Land.NotForest else Land.Unknown
    }

    suspend fun ground(location: Coordinate): Ground? {
        val north = DEM.getElevation(location.plus(Distance.meters(SAMPLE_METERS), Bearing.from(0f)))
        val south = DEM.getElevation(location.plus(Distance.meters(SAMPLE_METERS), Bearing.from(180f)))
        val east = DEM.getElevation(location.plus(Distance.meters(SAMPLE_METERS), Bearing.from(90f)))
        val west = DEM.getElevation(location.plus(Distance.meters(SAMPLE_METERS), Bearing.from(270f)))
        val samples = listOf(north, south, east, west).map { it.elevation }
        // Off the elevation model every sample reads 0; that is no ground, not a flat one
        if (samples.all { it == 0f }) {
            return null
        }
        val dzEast = (east.elevation - west.elevation) / (2 * SAMPLE_METERS)
        val dzNorth = (north.elevation - south.elevation) / (2 * SAMPLE_METERS)
        val slope = Math.toDegrees(atan(hypot(dzEast, dzNorth)).toDouble()).toFloat()
        if (slope < FLAT_DEGREES) {
            return Ground(slope, null)
        }
        // The ground faces the way it falls: against the rise in each axis
        val facing = Math.toDegrees(atan2(-dzEast, -dzNorth).toDouble()).toFloat()
        return Ground(slope, Bearing.from(facing))
    }

    private fun publicLandAt(file: org.mapsforge.map.reader.MapFile, point: LatLong): String? {
        val x = MercatorProjection.longitudeToTileX(point.longitude, LOOKUP_ZOOM)
        val y = MercatorProjection.latitudeToTileY(point.latitude, LOOKUP_ZOOM)
        val result = file.readMapData(Tile(x, y, LOOKUP_ZOOM, 256)) ?: return null
        for (way in result.ways) {
            if (way.tags.none { it.key == "ownership" && it.value == "usfs" }) {
                continue
            }
            // Outer and inner rings together, even-odd: a hole of private land stays private
            var inside = false
            for (ring in way.latLongs) {
                if (contains(ring, point)) {
                    inside = !inside
                }
            }
            if (inside) {
                return way.tags.firstOrNull { it.key == "name" }?.value ?: ""
            }
        }
        return null
    }

    private fun contains(ring: Array<LatLong>, point: LatLong): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) /
                (b.latitude - a.latitude) + a.longitude
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}
