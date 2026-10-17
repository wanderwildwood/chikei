package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.kylecorry.trail_sense.R
import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which map Topo draws under the person's own region packs (Settings > Map > Map). */
enum class TopoMapSource(val id: String) {
    /** USGS topo in the US, OpenTopoMap elsewhere, contour lines wherever neither can be had. */
    Automatic("automatic"),
    Usgs("usgs"),
    OpenTopoMap("opentopomap"),

    /** Nothing from the network at all: only the packs from the person's own server. */
    PacksOnly("packs");

    companion object {
        fun get(context: Context): TopoMapSource {
            val key = context.getString(R.string.pref_topo_map_source)
            val id = PreferenceManager.getDefaultSharedPreferences(context).getString(key, null)
            return entries.firstOrNull { it.id == id } ?: Automatic
        }
    }
}

/**
 * What the map has drawn, tile by tile, so its corner credits what is on the screen: USGS,
 * OpenTopoMap or the Terrain Tiles change as the map moves over a border or out of signal.
 */
object TopoCredits {

    enum class Provider { Usgs, OpenTopoMap, Terrain }

    private const val REMEMBERED = 300
    private val recent = object : LinkedHashMap<Tile, Provider>(REMEMBERED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Tile, Provider>?): Boolean {
            return size > REMEMBERED
        }
    }

    private val _changes = MutableStateFlow(0L)

    /** Ticks a moment after tiles stop arriving, so the credit line is redrawn once, not per tile. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Where the map is looking, set by the map screen before it asks for its credits. */
    @Volatile
    var viewBounds: CoordinateBounds? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { _changes.value = _changes.value + 1 }

    fun drew(tile: Tile, provider: Provider) {
        synchronized(recent) { recent[tile] = provider }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 800)
    }

    fun clear() {
        synchronized(recent) { recent.clear() }
    }

    /** Providers of the tiles on screen: those in view at the closest zoom drawn there. */
    fun shown(): Set<Provider> {
        val bounds = viewBounds
        val tiles = synchronized(recent) { recent.entries.map { it.key to it.value } }
        val inView = if (bounds == null) tiles else tiles.filter { it.first.getBounds().intersects(bounds) }
        val zoom = inView.maxOfOrNull { it.first.z } ?: return emptySet()
        return inView.filter { it.first.z == zoom }.map { it.second }.toSet()
    }

    /** The corner line's text; OpenTopoMap first, since its licence asks that it always be seen. */
    fun text(context: Context, providers: Set<Provider>): String? {
        val parts = listOfNotNull(
            if (Provider.OpenTopoMap in providers) context.getString(R.string.credit_opentopomap) else null,
            if (Provider.Usgs in providers) context.getString(R.string.credit_usgs) else null,
            if (Provider.Terrain in providers) context.getString(R.string.credit_terrain) else null
        )
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }
}
