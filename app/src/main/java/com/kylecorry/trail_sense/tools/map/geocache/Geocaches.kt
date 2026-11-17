package com.kylecorry.trail_sense.tools.map.geocache

import android.content.Context
import android.util.Log
import com.kylecorry.trail_sense.BuildConfig
import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import com.kylecorry.trail_sense.tools.map.forage.ForageCell
import com.kylecorry.trail_sense.tools.map.forage.ForageGrid
import com.kylecorry.trail_sense.tools.map.sources.WebTiles
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The Geocaches layer: caches from the GPX files the user opened, and, when the app was built
 * with an Opencaching key, the caches of the Opencaching sites for what is on the screen,
 * fetched a moment after the map settles and kept a week (for good with a saved area).
 */
object Geocaches {

    const val LAYER_ID = "geocaches"
    const val BROADCAST_UPDATED = "map-broadcast-geocaches-updated"

    private const val TAG = "Geocaches"
    private const val DEBOUNCE_MILLIS = 1500L

    /** Opencaching is asked in squares of this zoom, from map zoom [OC_MIN_ZOOM] in. */
    private const val OC_CELL_ZOOM = 12
    const val OC_MIN_ZOOM = 11
    private const val MAX_CELLS = 24
    private const val MAX_SAVED_CELLS = 64
    const val MAX_SHOWN = 400

    /** The Opencaching keys the app was built with, by site. */
    val keys: Map<String, String> by lazy { Opencaching.keys(BuildConfig.OPENCACHING_KEYS.replace(';', '\n')) }

    @Volatile
    private var refused = emptySet<String>()

    val hasOpencaching: Boolean get() = keys.keys.any { it !in refused }

    private fun liveSites(box: ForageBox): List<Opencaching.Site> = Opencaching.sites.filter { site ->
        keys.containsKey(site.id) && site.id !in refused && site.area.any { it.intersects(box) }
    }

    class View(val caches: List<Geocache>, val zoomIn: Boolean, val empty: Boolean)

    private class Wanted(val box: ForageBox, val zoom: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLock = Mutex()
    private var waiting: Job? = null
    private var latest: Wanted? = null

    @Volatile
    private var store: GeocacheStore? = null

    @Volatile
    private var shown: Map<String, Geocache> = emptyMap()

    private val backoffUntil = mutableMapOf<String, Long>()
    private val backoffStep = mutableMapOf<String, Long>()

    fun store(context: Context): GeocacheStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val made = GeocacheStore(File(context.applicationContext.filesDir, "geocaches"))
            scope.launch { made.prune() }
            store = made
            return made
        }
    }

    fun cache(code: String): Geocache? = shown[code]

    /** The caches in [box], from what is kept, asking Opencaching for what is missing when [fetch] is true. */
    fun view(context: Context, box: ForageBox, zoom: Int, fetch: Boolean): View {
        val store = store(context)
        val byCode = LinkedHashMap<String, Geocache>()
        store.imported().filter { box.contains(it.lat, it.lng) }.forEach { byCode[it.code] = it }

        val sites = liveSites(box)
        var zoomIn = false
        if (sites.isNotEmpty()) {
            val cells = if (zoom >= OC_MIN_ZOOM) ForageGrid.cells(box, OC_CELL_ZOOM, MAX_CELLS) else null
            if (cells == null) {
                zoomIn = true
            } else {
                var stale = false
                for (site in sites) for (cell in cells) {
                    val data = store.cell(site.id, cell)
                    if (!store.isFresh(data)) stale = true
                    data?.caches?.forEach { cache ->
                        // A cache opened from a file wins: it may carry the user's own notes
                        if (box.contains(cache.lat, cache.lng) && cache.code !in byCode) byCode[cache.code] = cache
                    }
                }
                if (stale && fetch) request(context, Wanted(box, zoom))
            }
        }

        val all = byCode.values.filter { !it.archived }
        val caches = if (all.size > MAX_SHOWN) {
            // Too many to draw: the ones nearest the middle of the screen
            val lat = (box.south + box.north) / 2
            val lng = (box.west + box.east) / 2
            all.sortedBy { ForageGrid.metres(lat, lng, it.lat, it.lng) }.take(MAX_SHOWN)
        } else {
            all
        }
        shown = caches.associateBy { it.code }
        return View(caches, zoomIn && caches.isEmpty(), store.imported().isEmpty() && !hasOpencaching)
    }

    /** Reads a GPX file or Pocket Query zip and keeps its caches. Returns how many were read, or null if none could be. */
    suspend fun import(context: Context, input: InputStream): Int? = withContext(Dispatchers.IO) {
        val caches = try {
            GeocacheGpx.read(input)
        } catch (e: Exception) {
            debug("import failed: ${e.javaClass.simpleName}")
            null
        }
        if (caches.isNullOrEmpty()) return@withContext null
        store(context).addImported(caches)
        Tools.broadcast(BROADCAST_UPDATED)
        caches.size
    }

    fun changed() = Tools.broadcast(BROADCAST_UPDATED)

    private fun request(context: Context, wanted: Wanted) {
        val app = context.applicationContext
        synchronized(this) {
            latest = wanted
            waiting?.cancel()
            waiting = scope.launch {
                delay(DEBOUNCE_MILLIS)
                fetchLock.withLock {
                    withContext(NonCancellable) {
                        val now = synchronized(this@Geocaches) { latest } ?: return@withContext
                        val cells = ForageGrid.cells(now.box, OC_CELL_ZOOM, MAX_CELLS) ?: return@withContext
                        val changed = try {
                            fetch(app, cells, kept = false, onlyMissing = true)
                        } catch (e: Exception) {
                            debug("fetch failed: ${e.javaClass.simpleName}")
                            false
                        }
                        if (changed) Tools.broadcast(BROADCAST_UPDATED)
                    }
                }
            }
        }
    }

    /** With a saved area, keeps its Opencaching caches too. Returns whether anything was kept. */
    suspend fun keepArea(context: Context, box: ForageBox): Boolean = withContext(Dispatchers.IO) {
        if (liveSites(box).isEmpty()) return@withContext false
        val cells = ForageGrid.cells(box, OC_CELL_ZOOM, MAX_SAVED_CELLS) ?: return@withContext false
        fetchLock.withLock {
            synchronized(backoffUntil) { backoffUntil.clear(); backoffStep.clear() }
            fetch(context, cells, kept = true, onlyMissing = false)
        }
    }

    private fun fetch(context: Context, cells: List<ForageCell>, kept: Boolean, onlyMissing: Boolean): Boolean {
        val store = store(context)
        val language = Locale.getDefault().language.ifEmpty { "en" }
        var changed = false
        for (cell in cells) {
            val box = cell.bounds()
            for (site in liveSites(box)) {
                if (onlyMissing && store.isFresh(store.cell(site.id, cell))) continue
                if (!ready(site.id)) continue
                val key = keys[site.id] ?: continue
                val body = get(site, Opencaching.bboxUrl(site, key, box, language)) ?: continue
                val caches = Opencaching.parse(site, body) ?: continue
                store.putCell(site.id, cell, CacheCellData(System.currentTimeMillis(), caches), kept)
                changed = true
            }
        }
        return changed
    }

    private fun ready(site: String): Boolean =
        synchronized(backoffUntil) { System.currentTimeMillis() >= (backoffUntil[site] ?: 0L) }

    private fun backOff(site: String, millis: Long? = null) = synchronized(backoffUntil) {
        val step = millis ?: ((backoffStep[site] ?: 0L).let { if (it == 0L) 60_000L else it * 2 }).coerceAtMost(30 * 60_000L)
        if (millis == null) backoffStep[site] = step
        backoffUntil[site] = System.currentTimeMillis() + step
    }

    private fun get(site: Opencaching.Site, url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", WebTiles.USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            debug("${site.label}: HTTP $code")
            return when {
                code in 200..299 -> {
                    synchronized(backoffUntil) { backoffStep.remove(site.id) }
                    connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                }
                code == 400 || code == 401 || code == 403 -> {
                    // OKAPI answers a key it does not know with a 400 or 401; the site is left out until restart
                    val error = connection.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
                    if (code != 400 || "consumer" in error) {
                        refused = refused + site.id
                        Tools.broadcast(BROADCAST_UPDATED)
                    } else {
                        backOff(site.id)
                    }
                    null
                }
                else -> {
                    backOff(site.id)
                    null
                }
            }
        } catch (e: IOException) {
            backOff(site.id, 20_000L)
            debug("${site.label}: ${e.javaClass.simpleName}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
