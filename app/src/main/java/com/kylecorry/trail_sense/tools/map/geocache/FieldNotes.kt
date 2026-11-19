package com.kylecorry.trail_sense.tools.map.geocache

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Field notes: the plain file Geocaching.com (Drafts, "Upload field notes") and Opencaching sites
 * take to log visits made in the field. One line per visit:
 *
 *     GC12345,2026-11-17T15:04:00Z,Found it,"note"
 *
 * written in UTF-16LE, as c:geo does (Apache-2.0, export/FieldNotes.java).
 */
object FieldNotes {

    const val FILE_NAME = "geocache_visits.txt"
    const val MIME = "text/plain"

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun line(visit: CacheVisit): String {
        val type = when (visit.kind) {
            VisitKinds.FOUND -> "Found it"
            VisitKinds.DNF -> "Didn't find it"
            else -> "Write note"
        }
        // The note is quoted; a quote inside it becomes an apostrophe and a line break a space
        val note = visit.note.orEmpty().replace('"', '\'').replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim()
        return "${visit.code},${time.format(Instant.ofEpochMilli(visit.time))},$type,\"$note\""
    }

    fun text(visits: List<CacheVisit>): String = visits.joinToString("") { line(it) + "\n" }

    fun bytes(visits: List<CacheVisit>): ByteArray = text(visits).toByteArray(Charsets.UTF_16LE)

    /** Geocaching.com codes start GC; everything else here is an Opencaching code. */
    fun isGeocachingCom(code: String): Boolean = code.uppercase().startsWith("GC")
}
