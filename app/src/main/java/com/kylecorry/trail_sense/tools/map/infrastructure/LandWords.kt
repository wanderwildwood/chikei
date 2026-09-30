package com.kylecorry.trail_sense.tools.map.infrastructure

import android.content.Context
import com.kylecorry.trail_sense.R

/**
 * Whose land a point is on, in words: a first line in bold and what else is known beneath it.
 *
 * The first line is the land's holder, the most particular answer the pack has: land held
 * outright (the smallest area, so a park inside a forest is the park), else a county lot's
 * owner, else the broadest protected area laid over it, else plainly that there is no record.
 * Beneath it go who holds or manages it, whether the public may enter, any easement, and the
 * designations laid over it, broadest first.
 */
class LandWords(private val context: Context) {

    class Description(val title: String?, val lines: List<String>)

    fun describe(land: PointInfo.Land): Description {
        return when (land) {
            is PointInfo.Land.Recorded -> recorded(land.areas)
            is PointInfo.Land.Public -> Description(
                land.name.ifBlank { context.getString(R.string.land_national_forest) }, emptyList()
            )
            is PointInfo.Land.Owned -> Description(land.owner, emptyList())
            PointInfo.Land.NoRecord -> Description(context.getString(R.string.land_no_record), emptyList())
            PointInfo.Land.Unknown -> Description(null, emptyList())
        }
    }

    private fun recorded(areas: List<LandIndex.Area>): Description {
        val held = areas.filter { it.kind == "fee" }.sortedBy { it.size }
        val lots = areas.filter { it.kind == "lot" }.sortedBy { it.size }
        val easements = areas.filter { it.kind == "easement" }
        val designations = areas.filter { it.kind == "designation" }.sortedByDescending { it.size }

        val lines = mutableListOf<String>()
        val title: String
        var shown: LandIndex.Area? = null
        val fee = held.firstOrNull()
        val lot = lots.firstOrNull()
        when {
            fee != null -> {
                shown = fee
                title = fee.name ?: fee.owner ?: fee.type ?: context.getString(R.string.land_no_record)
                addJoined(lines, fee.type?.takeUnless { saysSame(title, it) }, holderOf(fee, title))
            }

            lot != null -> {
                title = lot.owner ?: context.getString(R.string.land_owner_not_listed)
            }

            designations.isNotEmpty() -> {
                val broadest = designations.first()
                shown = broadest
                title = broadest.name ?: broadest.type ?: context.getString(R.string.land_no_record)
                addJoined(lines, broadest.type?.takeUnless { saysSame(title, it) }, broadest.manager)
                lines += context.getString(R.string.land_owner_not_recorded)
            }

            else -> title = context.getString(R.string.land_no_record)
        }

        accessOf(shown)?.let { lines += it }

        for (e in easements) {
            val what = e.type?.takeUnless { it.equals("Conservation Easement", ignoreCase = true) }
                ?: context.getString(R.string.land_easement)
            lines += listOfNotNull(what, e.holder ?: e.name).joinToString(": ")
        }

        for (d in designations) {
            if (d === shown) continue
            val name = d.name ?: continue
            if (name.equals(title, ignoreCase = true)) continue
            val type = d.type?.takeUnless { saysSame(name, it) }
            // the wilderness in a national forest is the Forest Service's: said once, above
            val said = listOfNotNull(name, shown?.owner, shown?.manager)
            val manager = d.manager?.takeUnless { m ->
                said.any { it.contains(m, ignoreCase = true) || m.contains(it, ignoreCase = true) }
            }
            lines += listOfNotNull(name, type, manager).joinToString(" · ")
            if (lines.size >= MAX_LINES) break
        }

        return Description(title, lines.distinct().take(MAX_LINES))
    }

    /**
     * Whether a name already says what kind of place it is: "Arches National Park" is a
     * National Park, "Linville Gorge Wilderness" a Wilderness Area.
     */
    private fun saysSame(name: String, type: String): Boolean {
        if (name.contains(type, ignoreCase = true)) {
            return true
        }
        val first = type.substringBefore(' ')
        return first.length >= 5 && name.contains(first, ignoreCase = true)
    }

    private fun addJoined(lines: MutableList<String>, vararg parts: String?) {
        val line = parts.filterNotNull().joinToString(" · ")
        if (line.isNotBlank()) {
            lines += line
        }
    }

    /** Who holds the land, and who manages it where that is someone else. */
    private fun holderOf(area: LandIndex.Area, title: String): String? {
        if (area.ownerType == "tribal" && area.owner == null) {
            return context.getString(R.string.land_tribal)
        }
        val owner = area.owner?.takeUnless { it.equals(title, ignoreCase = true) }
        // "The State of Utah School and Institutional Trust Lands Administration" managing land
        // owned by the School and Institutional Trust Lands Administration says nothing new
        val manager = area.manager?.takeUnless { m ->
            listOfNotNull(area.owner, title).any {
                m.contains(it, ignoreCase = true) || it.contains(m, ignoreCase = true)
            }
        }
        return when {
            owner != null && manager != null -> context.getString(R.string.land_owner_managed_by, owner, manager)
            else -> owner ?: manager
        }
    }

    private fun accessOf(area: LandIndex.Area?): String? {
        return when (area?.access) {
            "restricted" -> context.getString(R.string.land_access_restricted)
            "closed" -> context.getString(R.string.land_access_closed)
            else -> null
        }
    }

    companion object {
        private const val MAX_LINES = 5
    }
}
