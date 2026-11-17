package com.kylecorry.trail_sense.tools.map.geocache

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.fragment.app.Fragment
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.andromeda.core.system.IntentResultRetriever
import com.kylecorry.andromeda.core.coroutines.BackgroundMinimumState
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.andromeda.pickers.Pickers
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.luna.result.getOrNull
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.Notices
import com.kylecorry.trail_sense.shared.io.IntentUriPicker
import com.kylecorry.trail_sense.shared.map_layers.preferences.repo.MapLayerPreferenceRepo
import com.kylecorry.trail_sense.tools.map.MapToolRegistration

/**
 * Getting caches in and visits out: opening a geocache GPX file or Pocket Query zip (from the Map
 * menu, or from another app), exporting field notes, and removing the opened caches.
 */
object GeocacheIntake {

    /** The address of a geocache file another app opened, handed to the map. */
    const val ARG_OPENED = "opened_geocaches"

    /** Whether the GPX at [uri] holds geocaches, from its first few kilobytes. */
    fun isCacheFile(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(16 * 1024)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            GeocacheGpx.looksLikeCaches(String(head, 0, read, Charsets.UTF_8))
        } ?: false
    } catch (_: Exception) {
        false
    }

    /** The Map menu's Geocaches entry: open a file, export field notes, remove the opened caches. */
    fun menu(fragment: Fragment, anchor: View) {
        val context = fragment.requireContext()
        val store = Geocaches.store(context)
        val opened = store.imported().size
        val items = listOfNotNull(
            context.getString(R.string.geocaches_open_file) to { pick(fragment) },
            context.getString(R.string.geocaches_export_field_notes) to { exportFieldNotes(fragment) },
            if (opened > 0) context.getString(R.string.geocaches_remove_opened) to { removeOpened(fragment, opened) } else null
        )
        Pickers.menu(anchor, items.map { it.first }) { index ->
            items[index].second()
            true
        }
    }

    private fun pick(fragment: Fragment) {
        val retriever = fragment as? IntentResultRetriever ?: return
        val context = fragment.requireContext()
        fragment.inBackground(BackgroundMinimumState.Created) {
            val uri = IntentUriPicker(retriever, context).open(listOf("*/*")).getOrNull() ?: return@inBackground
            import(fragment, uri)
        }
    }

    /** Reads the file at [uri], says how it went, and turns the Geocaches layer on if it was off. */
    fun import(fragment: Fragment, uri: Uri) {
        val context = fragment.requireContext().applicationContext
        fragment.inBackground(BackgroundMinimumState.Created) {
            val count = onIO {
                context.contentResolver.openInputStream(uri)?.use { Geocaches.import(context, it) }
            }
            if (count != null) onIO { turnLayerOn() }
            onMain {
                if (count == null) {
                    Notices.toast(context, context.getString(R.string.geocaches_import_failed))
                } else {
                    val total = Geocaches.store(context).imported().size
                    Notices.toast(context, context.getString(R.string.geocaches_imported, count, total))
                    Geocaches.changed()
                }
            }
        }
    }

    private fun turnLayerOn() {
        val repo = DependencyRegistry.get<MapLayerPreferenceRepo>()
        val active = repo.getActiveLayerIds(MapToolRegistration.MAP_ID)
        if (Geocaches.LAYER_ID !in active) {
            repo.setActiveLayerIds(MapToolRegistration.MAP_ID, active + Geocaches.LAYER_ID)
        }
    }

    private fun removeOpened(fragment: Fragment, count: Int) {
        val context = fragment.requireContext()
        Alerts.dialog(
            context,
            context.getString(R.string.geocaches_remove_opened),
            context.getString(R.string.geocaches_remove_opened_confirm, count)
        ) { cancelled ->
            if (cancelled) return@dialog
            fragment.inBackground(BackgroundMinimumState.Created) {
                onIO { Geocaches.store(context).clearImported() }
                Geocaches.changed()
            }
        }
    }

    /**
     * Field notes for one site at a time, since each site takes only its own codes: the new
     * visits for Geocaching.com or for Opencaching, or every visit again.
     */
    private fun exportFieldNotes(fragment: Fragment) {
        val context = fragment.requireContext()
        val store = Geocaches.store(context)
        val visits = store.visits()
        if (visits.isEmpty()) {
            Alerts.dialog(context, context.getString(R.string.geocaches_export_field_notes), context.getString(R.string.field_notes_none), cancelText = null)
            return
        }
        val fresh = visits.filter { !it.exported }
        val gc = fresh.filter { FieldNotes.isGeocachingCom(it.code) }
        val oc = fresh.filter { !FieldNotes.isGeocachingCom(it.code) }
        val choices = listOfNotNull(
            if (gc.isNotEmpty()) context.getString(R.string.field_notes_site_gc, gc.size) to gc else null,
            if (oc.isNotEmpty()) context.getString(R.string.field_notes_site_oc, oc.size) to oc else null,
            context.getString(R.string.field_notes_all_again, visits.size) to visits
        )
        Pickers.item(context, context.getString(R.string.geocaches_export_field_notes), choices.map { it.first }, -1) { index ->
            if (index != null) save(fragment, choices[index].second)
        }
    }

    private fun save(fragment: Fragment, visits: List<CacheVisit>) {
        val retriever = fragment as? IntentResultRetriever ?: return
        val context = fragment.requireContext()
        fragment.inBackground(BackgroundMinimumState.Created) {
            val uri = IntentUriPicker(retriever, context).create(FieldNotes.FILE_NAME, FieldNotes.MIME) ?: return@inBackground
            val ok = onIO {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(FieldNotes.bytes(visits)) } != null
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) onIO { Geocaches.store(context).markExported(visits) }
            onMain {
                Notices.toast(
                    context,
                    if (ok) context.getString(R.string.field_notes_saved, visits.size)
                    else context.getString(R.string.field_notes_not_saved)
                )
            }
        }
    }
}
