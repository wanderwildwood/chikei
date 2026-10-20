package com.kylecorry.trail_sense.tools.map.forage

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.trail_sense.shared.ProguardIgnore

/**
 * Falling Fruit (fallingfruit.org), the open map of edible plants: which of its types Topo
 * shows, and what is made of its answers. Falling Fruit lists more than plants (shellfish,
 * water fountains, free fridges, dumpsters) and plenty of trees only bees want, and it counts
 * maples, oaks and pines as forager types too, which crowd a city's map. So a type is shown only
 * if Falling Fruit marks it for foragers and it is on Topo's list of fruit and nut plants
 * ([ForageTaxa], the same list OpenStreetMap is asked for), and never if it is a fungus, an
 * animal or a "freegan" find.
 */
object FallingFruitForage {

    /** Most spots one answer may carry; past this an area is asked for again square by square. */
    const val LIMIT = 1000

    /** A type as /types gives it. */
    class Type(
        val id: Int?,
        val parent_id: Int?,
        val taxonomic_rank: Int?,
        val scientific_names: List<String>?,
        val common_names: Map<String, List<String>>?,
        val categories: List<String>?
    ) : ProguardIgnore

    /** A type Topo shows, as kept on the phone. */
    class Kept(val id: Int, val name: String?, val scientific: String?) : ProguardIgnore

    /** [rule] is the [RULE] the list was made by; lists from 0.2.0 have none (0). */
    class KeptList(val fetched: Long, val types: List<Kept>, val rule: Int = 0) : ProguardIgnore

    /** Raised whenever [keep] changes what it lets through, so lists kept on phones are redone. */
    const val RULE = 1

    /** Whether a type kept by an older rule is still on [taxa]. */
    fun isAllowed(kept: Kept, taxa: ForageTaxa): Boolean =
        if (kept.scientific.isNullOrBlank()) taxa.matchProduce(kept.name) != null
        else taxa.match(kept.scientific) != null

    private const val RANK_KINGDOM = 1

    // Falling Fruit answers with bare arrays, which Gson can only type through a field
    private class Types(val items: List<Type>?) : ProguardIgnore
    private class Locations(val items: List<Location>?) : ProguardIgnore
    private class Clusters(val items: List<Cluster>?) : ProguardIgnore

    private inline fun <reified T> array(json: String): T? = try {
        JsonConvert.fromJson<T>("{\"items\":" + json.trim() + "}")
    } catch (_: Exception) {
        null
    }

    fun parseTypes(json: String): List<Type>? = array<Types>(json)?.items

    /** The types worth showing, by the rules above. */
    fun keep(types: List<Type>, taxa: ForageTaxa): List<Kept> {
        val byId = types.filter { it.id != null }.associateBy { it.id!! }

        fun ancestors(type: Type): Sequence<Type> = generateSequence(type) { t ->
            t.parent_id?.let { byId[it] }
        }.take(64)

        fun isKingdom(type: Type, name: String): Boolean =
            type.taxonomic_rank == RANK_KINGDOM &&
                type.scientific_names?.firstOrNull().equals(name, ignoreCase = true)

        fun under(type: Type, kingdom: String): Boolean = ancestors(type).any { isKingdom(it, kingdom) }

        // Genera Falling Fruit itself files under Fungi, so their unfiled kin are caught too
        val fungalGenera = types.filter { under(it, "Fungi") }
            .mapNotNull { ForageTaxa.genusOf(it.scientific_names?.firstOrNull())?.lowercase() }
            .toSet()

        return types.mapNotNull { type ->
            val id = type.id ?: return@mapNotNull null
            val categories = type.categories.orEmpty()
            if ("forager" !in categories || "freegan" in categories) return@mapNotNull null
            if (under(type, "Fungi") || under(type, "Animalia")) return@mapNotNull null
            if (ancestors(type).any { Fungi.isFungus(it.scientific_names?.firstOrNull()) }) {
                return@mapNotNull null
            }
            val names = type.scientific_names.orEmpty().filter { it.isNotBlank() }
            val name = type.common_names?.get("en")?.firstOrNull()
            if (Fungi.soundsLikeFungus(name)) return@mapNotNull null
            // On the list by any of its scientific names; by its common name only if it has none,
            // so a bare "Prunus" called "Plum" (flowering cherries and all) stays off
            val scientific = if (names.isEmpty()) {
                if (taxa.matchProduce(name) == null) return@mapNotNull null
                null
            } else {
                names.firstOrNull { taxa.match(it) != null } ?: return@mapNotNull null
            }
            val genus = ForageTaxa.genusOf(scientific)?.lowercase()
            if (genus != null && genus in fungalGenera) return@mapNotNull null
            Kept(id, name?.trim(), scientific?.trim())
        }
    }

    private class Location(val id: Long?, val lat: Double?, val lng: Double?, val type_ids: List<Int>?) :
        ProguardIgnore

    /** Spots from /locations, keeping only the types in [kept] (and the spots with any). */
    fun parseLocations(json: String, kept: Map<Int, Kept>): List<ForageSpot>? {
        val list = array<Locations>(json)?.items ?: return null
        return list.mapNotNull { location ->
            val id = location.id ?: return@mapNotNull null
            val lat = location.lat ?: return@mapNotNull null
            val lng = location.lng ?: return@mapNotNull null
            val types = location.type_ids.orEmpty().filter { it in kept }
            val first = types.firstOrNull()?.let { kept[it] } ?: return@mapNotNull null
            ForageSpot(
                id = "${ForageSources.FALLING_FRUIT}:$id",
                source = ForageSources.FALLING_FRUIT,
                lat = lat,
                lng = lng,
                name = types.mapNotNull { kept[it]?.name ?: kept[it]?.scientific }.distinct()
                    .joinToString(", ").ifBlank { null },
                scientific = first.scientific,
                typeIds = types
            )
        }
    }

    /** How many entries an answer held, kept or not, to tell whether it hit [LIMIT]. */
    fun countEntries(json: String): Int = array<Locations>(json)?.items?.size ?: 0

    private class Cluster(val lat: Double?, val lng: Double?, val count: Int?) : ProguardIgnore

    fun parseClusters(json: String): List<ForageCluster>? {
        val list = array<Clusters>(json)?.items ?: return null
        return list.mapNotNull {
            val count = it.count ?: return@mapNotNull null
            if (count <= 0) return@mapNotNull null
            ForageCluster(it.lat ?: return@mapNotNull null, it.lng ?: return@mapNotNull null, count)
        }
    }

    private class Import(val name: String?, val license: String?) : ProguardIgnore
    private class Detail(
        val description: String?,
        val season_start: Int?,
        val season_stop: Int?,
        val access: Int?,
        val unverified: Boolean?,
        val import: Import?
    ) : ProguardIgnore

    /** /locations/{id}?embed=import, as the sheet shows it. */
    fun parseDetail(json: String, now: Long): ForageDetail? {
        val detail = try {
            JsonConvert.fromJson<Detail>(json)
        } catch (_: Exception) {
            null
        } ?: return null
        return ForageDetail(
            description = detail.description?.trim()?.ifBlank { null },
            seasonStart = detail.season_start?.takeIf { it in 0..11 },
            seasonStop = detail.season_stop?.takeIf { it in 0..11 },
            access = detail.access?.takeIf { it in 0..4 },
            unverified = detail.unverified,
            datasetName = detail.import?.name?.trim()?.ifBlank { null },
            datasetLicence = detail.import?.license?.trim()?.ifBlank { null },
            fetched = now
        )
    }

    /**
     * Whether a key read at build time looks like one: six to sixteen letters and digits. The
     * example value from the setup notes does not count.
     */
    fun isKey(value: String?): Boolean =
        value != null && value.matches(Regex("[A-Za-z0-9]{6,16}")) && value != "ABCD1234"
}
