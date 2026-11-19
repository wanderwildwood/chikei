package com.kylecorry.trail_sense.tools.map.geocache

import com.kylecorry.trail_sense.shared.ProguardIgnore

/** Where a cache came from: a GPX file the user opened, or an Opencaching site ("oc:us" and so on). */
object GeocacheSources {
    const val GPX = "gpx"
    const val OPENCACHING_PREFIX = "oc:"
}

/** The kinds of cache, each drawn with its own letter. Anything unknown is [OTHER]. */
object GeocacheKinds {
    const val TRADITIONAL = "traditional"
    const val MULTI = "multi"
    const val MYSTERY = "mystery"
    const val LETTERBOX = "letterbox"
    const val WHERIGO = "wherigo"
    const val EARTH = "earth"
    const val VIRTUAL = "virtual"
    const val WEBCAM = "webcam"
    const val EVENT = "event"
    const val OTHER = "other"

    /** A kind from Geocaching.com's or Opencaching's own words for it ("Traditional Cache", "Quiz", "Unknown Cache"...). */
    fun of(text: String?): String {
        val t = text?.lowercase()?.substringAfter("geocache|")?.trim() ?: return OTHER
        return when {
            t.startsWith("traditional") || t == "drive-in" -> TRADITIONAL
            t.startsWith("multi") -> MULTI
            t.startsWith("unknown") || t.startsWith("mystery") || t == "quiz" || t.startsWith("puzzle") -> MYSTERY
            t.startsWith("letterbox") -> LETTERBOX
            t.startsWith("wherigo") -> WHERIGO
            t.startsWith("earth") -> EARTH
            t.startsWith("virtual") -> VIRTUAL
            t.startsWith("webcam") -> WEBCAM
            "event" in t || t == "cito" || t.startsWith("cache in trash out") -> EVENT
            else -> OTHER
        }
    }
}

/**
 * One geocache. [code] is the site's own (GC12345, OU0123), and is the cache's id here too.
 * [description] may hold HTML ([isHtml]); [hint] is plain text, shown only when asked.
 * [foundOnSite] is the site's word that the user already found it (from a "My Finds" file).
 */
class Geocache(
    val code: String,
    val source: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val kind: String = GeocacheKinds.OTHER,
    val typeName: String? = null,
    val size: String? = null,
    val difficulty: Float? = null,
    val terrain: Float? = null,
    val owner: String? = null,
    val hint: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val isHtml: Boolean = false,
    val url: String? = null,
    val placed: String? = null,
    val available: Boolean = true,
    val archived: Boolean = false,
    val foundOnSite: Boolean = false,
    val waypoints: List<CacheWaypoint>? = null
) : ProguardIgnore

/** A cache's extra point: parking, a stage, a trailhead. */
class CacheWaypoint(
    val code: String,
    val name: String?,
    val lat: Double,
    val lng: Double,
    val typeName: String? = null,
    val note: String? = null
) : ProguardIgnore

/** What the user did at a cache, kept on the phone: found it, did not find it, or a note. */
object VisitKinds {
    const val FOUND = "found"
    const val DNF = "dnf"
    const val NOTE = "note"
}

/** One visit. [exported] is set once it has gone into a field notes file. */
class CacheVisit(
    val code: String,
    val kind: String,
    val time: Long,
    val note: String? = null,
    val name: String? = null,
    val exported: Boolean = false
) : ProguardIgnore

/** The caches opened from GPX files, kept in one file. */
class ImportedCaches(val caches: List<Geocache> = emptyList()) : ProguardIgnore

/** The user's visits, newest last. */
class CacheVisits(val visits: List<CacheVisit> = emptyList()) : ProguardIgnore

/** One Opencaching square: when it was fetched, and the caches in it. */
class CacheCellData(val fetched: Long, val caches: List<Geocache> = emptyList()) : ProguardIgnore
