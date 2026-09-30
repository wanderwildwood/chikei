package com.kylecorry.trail_sense.tools.map.infrastructure

import android.util.JsonReader
import android.util.JsonToken
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.LandFiles
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos

/**
 * The areas in a region pack's land file (written by mapdata/land_pack.py), and which of them
 * hold a point. Nothing here knows one country's agencies from another's: an area is whatever
 * its file says it is.
 */
class LandIndex {

    class Area(
        val kind: String,
        val name: String?,
        val owner: String?,
        val ownerType: String?,
        val manager: String?,
        val type: String?,
        val access: String?,
        val holder: String?,
        /** Each polygon's rings, outer first, as flat [lon, lat, lon, lat, ...] arrays */
        val polygons: List<List<DoubleArray>>,
    ) {
        private val west = polygons.minOf { p -> p[0].everyOther(0).min() }
        private val east = polygons.maxOf { p -> p[0].everyOther(0).max() }
        private val south = polygons.minOf { p -> p[0].everyOther(1).min() }
        private val north = polygons.maxOf { p -> p[0].everyOther(1).max() }

        /** Square metres, roughly: enough to tell a park from the lot inside it */
        val size: Double = polygons.sumOf { p ->
            p.withIndex().sumOf { (i, ring) -> if (i == 0) ringArea(ring) else -ringArea(ring) }
        }

        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < south || lat > north || lon < west || lon > east) {
                return false
            }
            // Every ring together, even-odd: a hole stays outside the area
            var inside = false
            for (polygon in polygons) {
                for (ring in polygon) {
                    if (ringContains(ring, lat, lon)) {
                        inside = !inside
                    }
                }
            }
            return inside
        }
    }

    /** The areas holding the point, from the given regions' land files. */
    fun at(lat: Double, lon: Double, entries: List<LandFiles.Entry>): List<Area> {
        return entries.flatMap { entry -> load(entry.file).filter { it.contains(lat, lon) } }
    }

    private fun load(file: File): List<Area> {
        val key = file.path + ":" + file.lastModified()
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val areas = parse(file)
        synchronized(cache) {
            cache[key] = areas
            while (cache.size > CACHE_SIZE) {
                cache.remove(cache.keys.first())
            }
        }
        return areas
    }

    private fun parse(file: File): List<Area> {
        val areas = mutableListOf<Area>()
        JsonReader(GZIPInputStream(file.inputStream()).bufferedReader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "areas") {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        readArea(reader)?.let { areas.add(it) }
                    }
                    reader.endArray()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
        }
        return areas
    }

    private fun readArea(reader: JsonReader): Area? {
        val fields = mutableMapOf<String, String>()
        var polygons: List<List<DoubleArray>> = emptyList()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when {
                name == "polygons" -> polygons = readPolygons(reader)
                reader.peek() == JsonToken.STRING -> fields[name] = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (polygons.isEmpty() || polygons.any { it.isEmpty() || it[0].size < 6 }) {
            return null
        }
        return Area(
            fields["kind"] ?: return null,
            fields["name"], fields["owner"], fields["owner_type"], fields["manager"],
            fields["type"], fields["access"], fields["holder"], polygons
        )
    }

    private fun readPolygons(reader: JsonReader): List<List<DoubleArray>> {
        val polygons = mutableListOf<List<DoubleArray>>()
        reader.beginArray()
        while (reader.hasNext()) {
            val rings = mutableListOf<DoubleArray>()
            reader.beginArray()
            while (reader.hasNext()) {
                val coords = ArrayList<Double>()
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginArray()
                    coords.add(reader.nextDouble())
                    coords.add(reader.nextDouble())
                    reader.endArray()
                }
                reader.endArray()
                rings.add(coords.toDoubleArray())
            }
            reader.endArray()
            polygons.add(rings)
        }
        reader.endArray()
        return polygons
    }

    companion object {
        private const val CACHE_SIZE = 4
        private val cache = LinkedHashMap<String, List<Area>>()

        private fun DoubleArray.everyOther(start: Int): List<Double> =
            (start until size step 2).map { this[it] }

        private fun ringContains(ring: DoubleArray, lat: Double, lon: Double): Boolean {
            var inside = false
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i]
                val yi = ring[2 * i + 1]
                val xj = ring[2 * j]
                val yj = ring[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        private fun ringArea(ring: DoubleArray): Double {
            val n = ring.size / 2
            if (n < 3) return 0.0
            val midLat = Math.toRadians(ring[1])
            val mx = 111_320.0 * cos(midLat)
            val my = 110_540.0
            var sum = 0.0
            var j = n - 1
            for (i in 0 until n) {
                sum += (ring[2 * j] * mx) * (ring[2 * i + 1] * my) - (ring[2 * i] * mx) * (ring[2 * j + 1] * my)
                j = i
            }
            return abs(sum) / 2
        }
    }
}
