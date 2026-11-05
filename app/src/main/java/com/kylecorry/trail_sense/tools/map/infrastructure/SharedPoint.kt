package com.kylecorry.trail_sense.tools.map.infrastructure

import com.kylecorry.sol.units.Coordinate
import java.net.URLDecoder

/**
 * A point another app sent as a `geo:` link to show on the map: `geo:lat,lon`, with an optional
 * `?q=lat,lon(Label)` or `?label=`. `geo:0,0?q=lat,lon(Label)` takes the point from the query.
 * Anything else, such as `geo:0,0?q=<address>`, is not a point and is left to the Beacons tool.
 */
data class SharedPoint(val coordinate: Coordinate, val label: String?) {
    companion object {
        private val QUERY_POINT =
            Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

        fun parse(text: String): SharedPoint? {
            if (!text.startsWith("geo:", ignoreCase = true)) return null
            val body = text.substring(4)
            val params = body.substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { it.substringBefore('=').lowercase() to decode(it.substringAfter('=', "")) }

            val queryMatch = params["q"]?.let { QUERY_POINT.matchEntire(it) }
            val pathPoint = coordinate(body.substringBefore('?').substringBefore(';').split(','))
                ?.takeIf { it != Coordinate.zero }
            val point = pathPoint
                ?: queryMatch?.let { coordinate(it.groupValues.subList(1, 3)) }
                ?: return null

            val label = queryMatch?.groupValues?.get(3)?.trim()?.ifBlank { null }
                ?: params["label"]?.trim()?.ifBlank { null }
            return SharedPoint(point, label)
        }

        private fun coordinate(parts: List<String>): Coordinate? {
            if (parts.size < 2) return null
            val latitude = parts[0].trim().toDoubleOrNull() ?: return null
            val longitude = parts[1].trim().toDoubleOrNull() ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return Coordinate(latitude, longitude)
        }

        private fun decode(value: String): String {
            // A plus is kept as itself; senders write spaces as %20
            return try {
                URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
            } catch (e: IllegalArgumentException) {
                value
            }
        }
    }
}
