package com.kylecorry.trail_sense.tools.map.streets

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.trail_sense.shared.ProguardIgnore
import com.kylecorry.trail_sense.tools.map.forage.ForageBox

/**
 * The streets Topo draws for the Streets layer, and what is made of Overpass's answer. Every way
 * a person can walk is asked for, leaving out motorways, roads closed to walkers, and ways that
 * are private or not built yet.
 */
object OverpassStreets {

    /** Most ways one answer may carry; a dense city square holds a couple of thousand. */
    const val LIMIT = 6000

    fun query(box: ForageBox): String = buildString {
        append("[out:json][timeout:25][bbox:").append(box.overpass()).append("];")
        append("way[\"highway\"]")
        append("[\"highway\"!~\"^(motorway|motorway_link|trunk|trunk_link|construction|proposed|raceway|")
        append("bus_guideway|platform|elevator|corridor|services|rest_area|abandoned|razed|escape)$\"]")
        append("[\"access\"!~\"^(private|no)$\"]")
        append("[\"foot\"!~\"^(no|private)$\"]")
        append("[\"area\"!=\"yes\"];")
        append("out geom ").append(LIMIT).append(";")
    }

    private class Answer(val elements: List<Element>?) : ProguardIgnore
    private class Element(val type: String?, val id: Long?, val geometry: List<Node?>?) : ProguardIgnore
    private class Node(val lat: Double?, val lon: Double?) : ProguardIgnore

    /** The ways in an Overpass answer, or null if it is not an answer at all. */
    fun parse(json: String): List<StreetWay>? {
        val answer = try {
            JsonConvert.fromJson<Answer>(json)
        } catch (_: Exception) {
            null
        } ?: return null
        val elements = answer.elements ?: return null
        return elements.mapNotNull { way(it) }
    }

    private fun way(element: Element): StreetWay? {
        if (element.type != "way") return null
        val id = element.id ?: return null
        val nodes = element.geometry?.filterNotNull()
            ?.filter { it.lat != null && it.lon != null } ?: return null
        if (nodes.size < 2) return null
        val points = DoubleArray(nodes.size * 2)
        nodes.forEachIndexed { i, node ->
            points[i * 2] = node.lat!!
            points[i * 2 + 1] = node.lon!!
        }
        return StreetWay(id, points)
    }
}
