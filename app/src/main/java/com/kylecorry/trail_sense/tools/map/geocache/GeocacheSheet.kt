package com.kylecorry.trail_sense.tools.map.geocache

import android.content.Context
import android.text.Html
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.andromeda.core.system.Intents
import com.kylecorry.andromeda.core.coroutines.BackgroundMinimumState
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.andromeda.pickers.CoroutinePickers
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.sharing.ActionItem
import com.kylecorry.trail_sense.shared.sharing.Share
import com.kylecorry.trail_sense.tools.beacons.domain.BeaconOwner
import com.kylecorry.trail_sense.tools.navigation.infrastructure.Navigator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The sheet a tapped cache opens: its code, kind, size, difficulty and terrain, the hint behind a
 * tap, a few lines of its description, and what the user did there. Beneath: Navigate, Found it,
 * Didn't find it, Note, the whole description and the cache's page.
 */
object GeocacheSheet {

    fun show(fragment: Fragment, code: String) {
        val cache = Geocaches.cache(code) ?: return
        present(fragment, cache)
    }

    private fun present(fragment: Fragment, cache: Geocache) {
        val context = fragment.requireContext()
        val store = Geocaches.store(context)
        val view = fragment.layoutInflater.inflate(R.layout.view_geocache_detail, null)
        val state = store.state(cache.code)
        val notes = store.visits().filter { it.code == cache.code && it.kind == VisitKinds.NOTE }

        fun line(id: Int, text: CharSequence?) {
            view.findViewById<TextView>(id).apply {
                this.text = text
                isVisible = !text.isNullOrBlank()
            }
        }

        line(R.id.geocache_kind, listOfNotNull(cache.code, cache.typeName, size(context, cache.size)).joinToString(" · "))
        line(R.id.geocache_rating, rating(context, cache))
        line(R.id.geocache_owner, cache.owner?.let { context.getString(R.string.geocache_owner, it) })
        line(R.id.geocache_unavailable, if (!cache.available) context.getString(R.string.geocache_unavailable) else null)
        line(R.id.geocache_visit, visitLine(context, state))
        line(R.id.geocache_notes, notes.lastOrNull()?.note?.let { context.getString(R.string.geocache_your_note, it) })
        line(R.id.geocache_summary, plain(cache.summary, cache.isHtml) ?: plain(cache.description, cache.isHtml))
        line(
            R.id.geocache_waypoints,
            cache.waypoints?.size?.takeIf { it > 0 }?.let {
                context.resources.getQuantityString(R.plurals.geocache_waypoints, it, it)
            }
        )
        line(R.id.geocache_source, source(context, cache))

        // The hint stays hidden until asked for, as on the sites
        val hint = view.findViewById<TextView>(R.id.geocache_hint)
        hint.isVisible = cache.hint != null
        hint.setOnClickListener {
            hint.text = context.getString(R.string.geocache_hint_shown, cache.hint)
            hint.setOnClickListener(null)
        }

        val location = Coordinate(cache.lat, cache.lng)
        val formatter = FormatService.getInstance(context)
        val actions = listOfNotNull(
            ActionItem(context.getString(R.string.navigate), R.drawable.ic_beacon) {
                DependencyRegistry.get<Navigator>().navigateTo(location, cache.name, BeaconOwner.Maps)
            },
            ActionItem(context.getString(R.string.geocache_found), R.drawable.ic_check) {
                visit(fragment, cache, VisitKinds.FOUND)
            },
            ActionItem(context.getString(R.string.geocache_dnf), R.drawable.ic_cancel) {
                visit(fragment, cache, VisitKinds.DNF)
            },
            ActionItem(context.getString(R.string.geocache_add_note), R.drawable.ic_edit) {
                visit(fragment, cache, VisitKinds.NOTE)
            },
            if (state != null) ActionItem(context.getString(R.string.geocache_undo), R.drawable.ic_undo) {
                fragment.inBackground(BackgroundMinimumState.Created) {
                    onIO { store.undoState(cache.code) }
                    Geocaches.changed()
                }
            } else null,
            if (cache.description != null) ActionItem(context.getString(R.string.geocache_description), R.drawable.ic_info) {
                Alerts.dialog(context, cache.name, plain(cache.description, cache.isHtml, max = null), cancelText = null)
            } else null,
            cache.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                ActionItem(context.getString(R.string.geocache_open_page), R.drawable.ic_link) {
                    Intents.openChooser(context, Intents.url(url), cache.name)
                }
            }
        )
        Share.actions(
            fragment,
            cache.name,
            actions,
            subtitle = formatter.formatLocation(location),
            customView = view
        ) {
            // Nothing chosen: the sheet just closes
        }
    }

    /** Asks for a note (optional for a find or a did-not-find) and keeps the visit. */
    private fun visit(fragment: Fragment, cache: Geocache, kind: String) {
        val context = fragment.requireContext()
        fragment.inBackground(BackgroundMinimumState.Created) {
            val note = CoroutinePickers.text(
                context,
                context.getString(
                    when (kind) {
                        VisitKinds.FOUND -> R.string.geocache_found
                        VisitKinds.DNF -> R.string.geocache_dnf
                        else -> R.string.geocache_add_note
                    }
                ),
                hint = context.getString(R.string.geocache_note_hint)
            ) ?: return@inBackground
            if (kind == VisitKinds.NOTE && note.isBlank()) return@inBackground
            onIO {
                Geocaches.store(context).addVisit(
                    CacheVisit(cache.code, kind, System.currentTimeMillis(), note.trim().ifEmpty { null }, cache.name)
                )
            }
            onMain { Geocaches.changed() }
        }
    }

    private fun size(context: Context, size: String?): String? {
        val s = size?.trim()?.lowercase() ?: return null
        if (s in setOf("not chosen", "unknown", "other", "virtual", "none", "")) return null
        return context.getString(R.string.geocache_size, s)
    }

    private fun rating(context: Context, cache: Geocache): String? {
        val d = cache.difficulty ?: return null
        val t = cache.terrain ?: return null
        return context.getString(R.string.geocache_rating, half(d), half(t))
    }

    private fun half(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(java.util.Locale.getDefault(), "%.1f", value)

    private fun visitLine(context: Context, visit: CacheVisit?): String? {
        visit ?: return null
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(visit.time).atZone(ZoneId.systemDefault()))
        return context.getString(
            if (visit.kind == VisitKinds.FOUND) R.string.geocache_found_on else R.string.geocache_dnf_on,
            date
        )
    }

    private fun source(context: Context, cache: Geocache): String {
        val site = Opencaching.site(cache.source)
        return if (site != null) context.getString(R.string.geocache_source_opencaching, site.label)
        else context.getString(R.string.geocache_source_gpx)
    }

    /** Description text with its HTML taken out, cut to [max] characters. */
    private fun plain(text: String?, isHtml: Boolean, max: Int? = 400): String? {
        val raw = text?.takeIf { it.isNotBlank() } ?: return null
        val plain = if (isHtml || raw.contains('<')) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()
                .replace('￼', ' ')
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        } else raw.trim()
        if (plain.isEmpty()) return null
        return if (max != null && plain.length > max) plain.take(max).trimEnd() + "…" else plain
    }
}
