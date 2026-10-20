package com.kylecorry.trail_sense.tools.map.forage

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.trail_sense.shared.ProguardIgnore

/**
 * Edible trees and shrubs from OpenStreetMap, through the Overpass API: what Overpass is asked,
 * and what is made of its answer. Only trees and shrubs mapped one by one are asked for, never
 * orchards drawn as areas, which are mostly farms.
 */
object OverpassForage {

    /** Most points one answer may carry; a busy city square holds a few hundred. */
    const val LIMIT = 3000

    /** OSM tags kept with a spot, for its sheet. */
    private val KEPT_TAGS = setOf(
        "genus", "species", "taxon", "species:en", "taxon:en", "produce", "access",
        "description", "note", "denotation", "leaf_cycle"
    )

    fun query(box: ForageBox, genera: List<String>): String {
        // Genera are single Latin words; anything else would only break the expression
        val names = genera.filter { name -> name.isNotEmpty() && name.all { it.isLetter() } }
            .joinToString("|")
        return buildString {
            append("[out:json][timeout:25][bbox:").append(box.overpass()).append("];")
            append("(")
            append("node[\"natural\"~\"^(tree|shrub)$\"][~\"^(genus|species|taxon)$\"~\"^(")
            append(names)
            append(")( |$)\",i];")
            append("node[\"natural\"~\"^(tree|shrub)$\"][\"produce\"];")
            append(");out body ").append(LIMIT).append(";")
        }
    }

    private class Answer(val elements: List<Element>?) : ProguardIgnore
    private class Element(
        val type: String?,
        val id: Long?,
        val lat: Double?,
        val lon: Double?,
        val tags: Map<String, String>?
    ) : ProguardIgnore

    /** The edible spots in an Overpass answer; anything not on [taxa] is dropped. */
    fun parse(json: String, taxa: ForageTaxa): List<ForageSpot>? {
        val answer = try {
            JsonConvert.fromJson<Answer>(json)
        } catch (_: Exception) {
            null
        } ?: return null
        return answer.elements.orEmpty().mapNotNull { spot(it, taxa) }
    }

    private fun spot(element: Element, taxa: ForageTaxa): ForageSpot? {
        if (element.type != "node") return null
        val id = element.id ?: return null
        val lat = element.lat ?: return null
        val lon = element.lon ?: return null
        val tags = element.tags.orEmpty()
        val scientific = tags["species"] ?: tags["taxon"] ?: tags["genus"]
        if (Fungi.isFungus(scientific)) return null
        val entry = taxa.match(tags["species"]) ?: taxa.match(tags["taxon"])
            ?: taxa.match(tags["genus"]) ?: taxa.matchProduce(tags["produce"])
            ?: return null
        val name = tags["species:en"] ?: tags["taxon:en"] ?: entry.name
        if (Fungi.soundsLikeFungus(name)) return null
        return ForageSpot(
            id = "${ForageSources.OSM}:n$id",
            source = ForageSources.OSM,
            lat = lat,
            lng = lon,
            name = name,
            scientific = ForageTaxa.clean(scientific) ?: entry.scientific,
            tags = tags.filterKeys { it in KEPT_TAGS }
        )
    }
}
