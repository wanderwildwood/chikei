package com.kylecorry.trail_sense.tools.map.geocache

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.trail_sense.shared.ProguardIgnore
import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import java.net.URLEncoder

/**
 * The Opencaching sites, each with its own OKAPI and its own consumer key (asked for by form on
 * each site). The sites follow c:geo's ConnectorFactory (Apache-2.0).
 * [area] is roughly where each site's caches are, so a view is only asked of the sites that
 * could have caches in it.
 */
object Opencaching {

    class Site(
        val id: String,
        val host: String,
        val label: String,
        val area: List<ForageBox>
    ) {
        val source: String get() = GeocacheSources.OPENCACHING_PREFIX + id
        val api: String get() = "https://$host/okapi/"
    }

    val sites = listOf(
        Site("us", "www.opencaching.us", "Opencaching.us", listOf(ForageBox(17.0, -180.0, 72.0, -60.0))),
        Site("de", "www.opencaching.de", "Opencaching.de", listOf(ForageBox(34.0, -11.0, 61.0, 32.0))),
        Site("pl", "opencaching.pl", "Opencaching.pl", listOf(ForageBox(48.9, 14.0, 55.0, 24.3))),
        Site("nl", "www.opencaching.nl", "Opencaching.nl", listOf(ForageBox(49.4, 2.4, 53.7, 7.3))),
        Site("ro", "www.opencaching.ro", "Opencaching.ro", listOf(ForageBox(43.5, 20.1, 48.4, 30.0))),
        Site("uk", "opencache.uk", "Opencache.uk", listOf(ForageBox(49.8, -8.8, 61.0, 2.0)))
    )

    fun site(source: String): Site? = sites.firstOrNull { it.source == source }

    /**
     * The keys the app was built with, read from "us=key" lines ([text] is the file). A key is
     * letters and digits; anything else is left out.
     */
    fun keys(text: String?): Map<String, String> = text.orEmpty().lines()
        .mapNotNull { line ->
            val (id, key) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val site = id.trim().lowercase()
            val value = key.trim()
            if (sites.none { it.id == site } || !value.matches(Regex("[A-Za-z0-9]{8,64}"))) null else site to value
        }.toMap()

    const val FIELDS = "code|name|location|type|status|difficulty|terrain|size2|owner|hint2|short_description|description|url|date_hidden"
    const val LIMIT = 500

    /** The address that asks [site] for the caches in [box], with everything the map and the sheet need. */
    fun bboxUrl(site: Site, key: String, box: ForageBox, language: String): String {
        val bbox = listOf(box.south, box.west, box.north, box.east).joinToString("|") { ForageBox.format(it) }
        val search = "{\"bbox\":\"$bbox\",\"status\":\"Available|Temporarily unavailable\",\"limit\":\"$LIMIT\"}"
        val retrieve = "{\"fields\":\"$FIELDS\"}"
        val langs = if (language == "en") "en" else "$language|en"
        return site.api + "services/caches/shortcuts/search_and_retrieve" +
                "?search_method=" + enc("services/caches/search/bbox") +
                "&search_params=" + enc(search) +
                "&retr_method=" + enc("services/caches/geocaches") +
                "&retr_params=" + enc(retrieve) +
                "&wrap=true&langpref=" + enc(langs) +
                "&consumer_key=" + enc(key)
    }

    private class Owner(val username: String?) : ProguardIgnore

    private class Cache(
        val code: String?,
        val name: String?,
        val location: String?,
        val type: String?,
        val status: String?,
        val difficulty: Float?,
        val terrain: Float?,
        val size2: String?,
        val owner: Owner?,
        val hint2: String?,
        val short_description: String?,
        val description: String?,
        val url: String?,
        val date_hidden: String?
    ) : ProguardIgnore

    private class Reply(val results: Map<String, Cache>?, val more: Boolean?) : ProguardIgnore

    /** The caches in an OKAPI search_and_retrieve reply (wrapped), or null if it could not be read. */
    fun parse(site: Site, json: String): List<Geocache>? {
        val reply = try {
            JsonConvert.fromJson<Reply>(json)
        } catch (_: Exception) {
            null
        } ?: return null
        return reply.results.orEmpty().values.mapNotNull { cache ->
            val code = cache.code ?: return@mapNotNull null
            val (lat, lng) = cache.location?.split('|')?.mapNotNull { it.toDoubleOrNull() }
                ?.takeIf { it.size == 2 } ?: return@mapNotNull null
            Geocache(
                code = code.uppercase(),
                source = site.source,
                name = cache.name?.trim() ?: code,
                lat = lat,
                lng = lng,
                kind = GeocacheKinds.of(cache.type),
                typeName = cache.type,
                size = cache.size2?.takeIf { it != "none" },
                difficulty = cache.difficulty,
                terrain = cache.terrain,
                owner = cache.owner?.username,
                hint = cache.hint2?.trim()?.ifEmpty { null },
                summary = cache.short_description?.trim()?.ifEmpty { null },
                description = cache.description?.trim()?.ifEmpty { null },
                isHtml = true,
                url = cache.url,
                placed = cache.date_hidden?.take(10),
                available = cache.status == null || cache.status == "Available",
                archived = cache.status == "Archived"
            )
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
