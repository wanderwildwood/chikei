package com.kylecorry.trail_sense.tools.map.forage

import android.content.Context
import android.util.Log
import com.kylecorry.trail_sense.BuildConfig
import com.kylecorry.trail_sense.R
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The Forage layer's data: edible plants for the part of the map on screen, from OpenStreetMap
 * (through Overpass) and, when the app was built with a key, from Falling Fruit.
 *
 * The network is used lightly. Nothing is fetched until the map has been still for a moment;
 * then the squares of the grid on screen that are not already kept are asked for together, in
 * one request to each source, and kept for a month. A server that says it is busy is left alone
 * for a minute, then two, and so on up to half an hour. Only the corners of those squares,
 * rounded to about 100 m, leave the phone.
 */
object Forage {

    const val LAYER_ID = "forage"
    const val BROADCAST_UPDATED = "map-broadcast-forage-updated"

    private const val TAG = "Forage"
    private const val DEBOUNCE_MILLIS = 1500L

    /** Below this zoom only Falling Fruit's counts are shown; below [MIN_CLUSTER_ZOOM], nothing. */
    const val SPOT_ZOOM = ForageGrid.SPOT_ZOOM
    const val MIN_CLUSTER_ZOOM = 8
    private const val MAX_FF_ZOOM = 14
    private const val MAX_CELLS = 30
    private const val MAX_SPLIT_CELLS = 12
    private const val MAX_SAVED_CELLS = 64
    const val MAX_SHOWN = 500

    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    private const val FF_URL = "https://fallingfruit.org/api/0.3"

    private const val KIND_OSM = "osm"
    private const val KIND_FF = "ff"
    private const val KIND_FF_CLUSTERS = "ffc"

    /** Set when Falling Fruit refuses the key; it is then left out until the app restarts. */
    @Volatile
    private var fallingFruitRefused = false
    private var refusalShown = false

    /** Falling Fruit is on when the app was built with a key and the key still works. */
    val hasFallingFruit: Boolean
        get() = FallingFruitForage.isKey(BuildConfig.FALLING_FRUIT_KEY) && !fallingFruitRefused

    class View(
        val spots: List<ForageSpot>,
        val clusters: List<ForageCluster>,
        val zoomIn: Boolean
    )

    private class Wanted(val box: ForageBox, val zoom: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLock = Mutex()
    private var waiting: Job? = null
    private var latest: Wanted? = null

    @Volatile
    private var cache: ForageCache? = null
    @Volatile
    private var taxa: ForageTaxa? = null
    @Volatile
    private var ffTypes: Map<Int, FallingFruitForage.Kept>? = null

    /** What is on the map now, for the sheet a tapped marker opens. */
    @Volatile
    private var shown: Map<String, ForageSpot> = emptyMap()

    private class Backoff(val name: String) {
        var until = 0L
        var step = 0L
        fun ready(): Boolean = System.currentTimeMillis() >= until
        fun busy() {
            step = (if (step == 0L) 60_000L else step * 2).coerceAtMost(30 * 60_000L)
            until = System.currentTimeMillis() + step
            debug("$name busy; waiting ${step / 1000} s")
        }
        fun offline() {
            until = System.currentTimeMillis() + 20_000L
        }
        fun ok() {
            step = 0L
            until = 0L
        }
    }

    private val overpass = Backoff("Overpass")
    private val fallingFruit = Backoff("Falling Fruit")

    private fun cache(context: Context): ForageCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val made = ForageCache(File(context.applicationContext.filesDir, "forage"))
            scope.launch { made.prune() }
            cache = made
            return made
        }
    }

    fun taxa(context: Context): ForageTaxa {
        taxa?.let { return it }
        val made = ForageTaxa(
            context.resources.getStringArray(R.array.forage_taxa).toList(),
            context.resources.getStringArray(R.array.forage_taxa_names).toList()
        )
        taxa = made
        return made
    }

    fun spot(id: String): ForageSpot? = shown[id]

    /**
     * The spots (zoomed in) or counts (zoomed out) for [box] from what is kept, asking for what
     * is missing a moment later when [fetch] is true. The widget never fetches.
     */
    fun view(context: Context, box: ForageBox, zoom: Int, fetch: Boolean): View {
        val cache = cache(context)
        if (zoom >= SPOT_ZOOM) {
            val cells = ForageGrid.cells(box, SPOT_ZOOM, MAX_CELLS) ?: return View(emptyList(), emptyList(), true)
            val kinds = if (hasFallingFruit) listOf(KIND_OSM, KIND_FF) else listOf(KIND_OSM)
            var stale = false
            val osm = mutableListOf<ForageSpot>()
            val ff = mutableListOf<ForageSpot>()
            for (kind in kinds) {
                for (cell in cells) {
                    val data = cache.get(kind, cell)
                    if (!cache.isFresh(data)) stale = true
                    data?.spots?.let { if (kind == KIND_OSM) osm.addAll(it) else ff.addAll(it) }
                }
            }
            if (stale && fetch) request(context, Wanted(box, zoom))
            val spots = ForageMerge.merge(osm, ff)
                .filter { box.contains(it.lat, it.lng) }
                .take(MAX_SHOWN)
            shown = spots.associateBy { it.id }
            return View(spots, emptyList(), false)
        }

        shown = emptyMap()
        if (!hasFallingFruit || zoom < MIN_CLUSTER_ZOOM) {
            return View(emptyList(), emptyList(), true)
        }
        val cells = ForageGrid.cells(box, zoom - 1, MAX_CELLS) ?: return View(emptyList(), emptyList(), true)
        var stale = false
        val clusters = cells.flatMap { cell ->
            val data = cache.get(KIND_FF_CLUSTERS, cell)
            if (!cache.isFresh(data)) stale = true
            data?.clusters.orEmpty()
        }
        if (stale && fetch) request(context, Wanted(box, zoom))
        return View(emptyList(), clusters.filter { box.contains(it.lat, it.lng) }, false)
    }

    private fun request(context: Context, wanted: Wanted) {
        val app = context.applicationContext
        synchronized(this) {
            latest = wanted
            waiting?.cancel()
            waiting = scope.launch {
                delay(DEBOUNCE_MILLIS)
                fetchLock.withLock {
                    withContext(NonCancellable) {
                        val now = synchronized(this@Forage) { latest } ?: return@withContext
                        val changed = try {
                            fetch(app, now)
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

    /** Fetches whatever [wanted] still lacks. Returns whether anything new was kept. */
    private suspend fun fetch(context: Context, wanted: Wanted): Boolean {
        val cache = cache(context)
        var changed = false
        if (wanted.zoom >= SPOT_ZOOM) {
            val cells = ForageGrid.cells(wanted.box, SPOT_ZOOM, MAX_CELLS) ?: return false
            // Falling Fruit first and shown at once: it answers in a second, Overpass can take thirty
            if (hasFallingFruit) {
                val missingFf = cells.filter { !cache.isFresh(cache.get(KIND_FF, it)) }
                if (missingFf.isNotEmpty() && fetchFallingFruit(context, missingFf, kept = false)) {
                    Tools.broadcast(BROADCAST_UPDATED)
                }
            }
            val missingOsm = cells.filter { !cache.isFresh(cache.get(KIND_OSM, it)) }
            if (missingOsm.isNotEmpty()) changed = fetchOsm(context, missingOsm, kept = false)
        } else if (hasFallingFruit && wanted.zoom >= MIN_CLUSTER_ZOOM) {
            val cells = ForageGrid.cells(wanted.box, wanted.zoom - 1, MAX_CELLS) ?: return false
            val missing = cells.filter { !cache.isFresh(cache.get(KIND_FF_CLUSTERS, it)) }
            if (missing.isNotEmpty()) {
                changed = fetchClusters(context, missing, (wanted.zoom + 1).coerceAtMost(MAX_FF_ZOOM))
            }
        }
        // A refused key hides the source, which the map must hear about too (once)
        if (fallingFruitRefused && !refusalShown) {
            refusalShown = true
            return true
        }
        return changed
    }

    /**
     * With a saved area, keeps its spots too, if the Forage layer is in use and the area is not
     * too big to fetch in a request or two. Returns whether anything was kept.
     */
    suspend fun keepArea(context: Context, box: ForageBox): Boolean = withContext(Dispatchers.IO) {
        val cells = ForageGrid.cells(box, SPOT_ZOOM, MAX_SAVED_CELLS) ?: return@withContext false
        fetchLock.withLock {
            overpass.ok()
            fallingFruit.ok()
            var kept = fetchOsm(context, cells, kept = true)
            if (hasFallingFruit) kept = fetchFallingFruit(context, cells, kept = true) || kept
            kept
        }
    }

    private suspend fun fetchOsm(context: Context, cells: List<ForageCell>, kept: Boolean): Boolean {
        if (!overpass.ready()) return false
        val box = ForageGrid.requestBox(cells) ?: return false
        val query = OverpassForage.query(box, taxa(context).genera)
        debug("Overpass: ${cells.size} squares in one request")
        val reply = send(overpass, OVERPASS_URL, post = "data=" + URLEncoder.encode(query, "UTF-8")) ?: return false
        val spots = OverpassForage.parse(reply, taxa(context)) ?: return false
        store(context, KIND_OSM, cells, spots, kept)
        return true
    }

    private suspend fun fetchFallingFruit(context: Context, cells: List<ForageCell>, kept: Boolean): Boolean {
        val types = fallingFruitTypes(context) ?: return false
        val box = ForageGrid.requestBox(cells) ?: return false
        val reply = locations(box, types) ?: return false
        if (FallingFruitForage.countEntries(reply) >= FallingFruitForage.LIMIT && cells.size > 1) {
            // Too many for one answer: square by square, if there are not too many squares
            if (cells.size > MAX_SPLIT_CELLS) return false
            debug("Falling Fruit: answer full, asking ${cells.size} squares one by one")
            var any = false
            for (cell in cells) {
                val one = locations(cell.bounds().rounded(), types) ?: break
                val spots = FallingFruitForage.parseLocations(one, types) ?: continue
                store(context, KIND_FF, listOf(cell), spots, kept)
                any = true
            }
            return any
        }
        val spots = FallingFruitForage.parseLocations(reply, types) ?: return false
        store(context, KIND_FF, cells, spots, kept)
        return true
    }

    private fun locations(box: ForageBox, types: Map<Int, FallingFruitForage.Kept>): String? {
        debug("Falling Fruit: locations")
        return send(
            fallingFruit,
            "$FF_URL/locations?bounds=${enc(box.fallingFruit())}&types=${typeList(types)}" +
                "&limit=${FallingFruitForage.LIMIT}",
            key = true
        )
    }

    private suspend fun fetchClusters(context: Context, cells: List<ForageCell>, ffZoom: Int): Boolean {
        val types = fallingFruitTypes(context) ?: return false
        val box = ForageGrid.requestBox(cells) ?: return false
        debug("Falling Fruit: clusters for ${cells.size} squares in one request")
        val reply = send(
            fallingFruit,
            "$FF_URL/clusters?bounds=${enc(box.fallingFruit())}&zoom=$ffZoom&types=${typeList(types)}",
            key = true
        ) ?: return false
        val clusters = FallingFruitForage.parseClusters(reply) ?: return false
        val now = System.currentTimeMillis()
        val cache = cache(context)
        for (cell in cells) {
            val bounds = cell.bounds()
            cache.put(
                KIND_FF_CLUSTERS, cell,
                ForageCellData(now, clusters = clusters.filter { bounds.contains(it.lat, it.lng) })
            )
        }
        return true
    }

    /** Each spot goes in the one square that holds it, so squares never share a spot. */
    private fun store(context: Context, kind: String, cells: List<ForageCell>, spots: List<ForageSpot>, kept: Boolean) {
        val now = System.currentTimeMillis()
        val cache = cache(context)
        for (cell in cells) {
            val bounds = cell.bounds()
            cache.put(kind, cell, ForageCellData(now, spots = spots.filter { bounds.contains(it.lat, it.lng) }), kept)
        }
    }

    private fun typeList(types: Map<Int, FallingFruitForage.Kept>): String =
        types.keys.sorted().joinToString(",")

    /** Falling Fruit's types worth showing, kept on the phone and asked for again monthly. */
    private fun fallingFruitTypes(context: Context): Map<Int, FallingFruitForage.Kept>? {
        val file = File(context.filesDir, "forage/ff_types.json")
        val saved = ffTypes ?: try {
            if (file.isFile) {
                com.kylecorry.andromeda.json.JsonConvert.fromJson<FallingFruitForage.KeptList>(file.readText())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }?.let { list ->
            if (System.currentTimeMillis() - list.fetched < ForageCache.TTL_MILLIS) {
                list.types.associateBy { it.id }.also { ffTypes = it }
            } else {
                null
            }
        }
        if (saved != null) return saved
        debug("Falling Fruit: types")
        val reply = send(fallingFruit, "$FF_URL/types", key = true)
        val types = reply?.let { FallingFruitForage.parseTypes(it) }?.let { FallingFruitForage.keep(it) }
        if (types.isNullOrEmpty()) {
            // An old list still beats none
            return try {
                com.kylecorry.andromeda.json.JsonConvert.fromJson<FallingFruitForage.KeptList>(file.readText())
                    ?.types?.associateBy { it.id }
            } catch (_: Exception) {
                null
            }
        }
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(com.kylecorry.andromeda.json.JsonConvert.toJson(
            FallingFruitForage.KeptList(System.currentTimeMillis(), types)
        ))
        part.renameTo(file)
        return types.associateBy { it.id }.also { ffTypes = it }
    }

    /** The extra for a Falling Fruit spot: kept for a month, fetched when it is first opened. */
    suspend fun detail(context: Context, spot: ForageSpot): ForageDetail? = withContext(Dispatchers.IO) {
        if (spot.source != ForageSources.FALLING_FRUIT) return@withContext null
        val number = spot.id.substringAfter(':').toLongOrNull() ?: return@withContext null
        val file = File(context.filesDir, "forage/ff_detail/$number.json")
        val saved = try {
            if (file.isFile) com.kylecorry.andromeda.json.JsonConvert.fromJson<ForageDetail>(file.readText()) else null
        } catch (_: Exception) {
            null
        }
        if (saved != null && System.currentTimeMillis() - saved.fetched < ForageCache.TTL_MILLIS) {
            return@withContext saved
        }
        if (!hasFallingFruit) return@withContext saved
        debug("Falling Fruit: one spot")
        val reply = send(fallingFruit, "$FF_URL/locations/$number?embed=import", key = true)
            ?: return@withContext saved
        val detail = FallingFruitForage.parseDetail(reply, System.currentTimeMillis()) ?: return@withContext saved
        file.parentFile?.mkdirs()
        file.writeText(com.kylecorry.andromeda.json.JsonConvert.toJson(detail))
        detail
    }

    /**
     * One request. Returns the body, or null if it failed, after telling [backoff] how. A 401 or
     * 403 from Falling Fruit means the key no longer works, and the source is dropped.
     */
    private fun send(backoff: Backoff, url: String, post: String? = null, key: Boolean = false): String? {
        if (!backoff.ready()) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", WebTiles.USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            if (key) connection.setRequestProperty("x-api-key", BuildConfig.FALLING_FRUIT_KEY)
            if (post != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = connection.responseCode
            debug("${backoff.name}: HTTP $code")
            return when {
                code in 200..299 -> connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                    .also { backoff.ok() }
                key && (code == 401 || code == 403) -> {
                    fallingFruitRefused = true
                    null
                }
                code == 429 || code >= 500 -> {
                    backoff.busy()
                    null
                }
                else -> {
                    // A request the server will not take; asking again soon would not help
                    backoff.busy()
                    null
                }
            }
        } catch (_: IOException) {
            backoff.offline()
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
