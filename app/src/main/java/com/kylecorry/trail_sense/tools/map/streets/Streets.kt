package com.kylecorry.trail_sense.tools.map.streets

import android.content.Context
import android.util.Log
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.trail_sense.BuildConfig
import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import com.kylecorry.trail_sense.tools.map.forage.ForageCell
import com.kylecorry.trail_sense.tools.map.forage.ForageGrid
import com.kylecorry.trail_sense.tools.map.forage.OverpassServers
import com.kylecorry.trail_sense.tools.map.sources.WebTiles
import com.kylecorry.trail_sense.tools.paths.infrastructure.persistence.PathService
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
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * The Streets layer's data: the streets and paths of OpenStreetMap for the part of the map on
 * screen, each cut into pieces of about 25 m and drawn thick where a recorded or imported path
 * has been along it, thin where not.
 *
 * The network is used once per place. Nothing is fetched until the map has been still for a
 * moment; then the squares on screen that are not already kept are asked for together, in one
 * request, and kept on the phone for good (they are only asked for again after six months). With
 * no signal the kept streets are drawn. The walked part is worked out on the phone from the
 * Paths tool, so no track ever leaves it. Only the corners of the squares, rounded to about
 * 100 m, are sent.
 */
object Streets {

    const val LAYER_ID = "streets"
    const val BROADCAST_UPDATED = "map-broadcast-streets-updated"

    private const val TAG = "Streets"
    private const val DEBOUNCE_MILLIS = 1500L

    /** Squares are fetched and kept at this zoom: about 1.2 km across. */
    const val CELL_ZOOM = 15

    /** Below this map zoom the layer asks to zoom in instead of drawing. */
    const val MIN_ZOOM = 14
    private const val MAX_CELLS = 16
    private const val MAX_SAVED_CELLS = 64
    const val MAX_WAYS = 4000

    class View(val pieces: List<StreetPiece>, val zoomIn: Boolean) {
        val walkedMetres: Double get() = pieces.filter { it.walked }.sumOf { it.metres }
        val totalMetres: Double get() = pieces.sumOf { it.metres }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchLock = Mutex()
    private var waiting: Job? = null
    private var latest: ForageBox? = null

    @Volatile
    private var cache: StreetCache? = null

    @Volatile
    private var walked: WalkedIndex? = null
    private var walkedKey = -1

    private val servers = OverpassServers()
    private val backoffs = mapOf(servers.main to Backoff(), servers.backup to Backoff())

    private class Backoff {
        var until = 0L
        var step = 0L
        fun ready(): Boolean = System.currentTimeMillis() >= until
        fun busy() {
            step = (if (step == 0L) 60_000L else step * 2).coerceAtMost(30 * 60_000L)
            until = System.currentTimeMillis() + step
        }
        fun offline() {
            until = System.currentTimeMillis() + 20_000L
        }
        fun ok() {
            step = 0L
            until = 0L
        }
    }

    private fun cache(context: Context): StreetCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val made = StreetCache(File(context.applicationContext.filesDir, "streets"))
            scope.launch { made.prune() }
            cache = made
            return made
        }
    }

    /** Every walked point, rebuilt whenever the Paths tool's paths change. */
    private suspend fun walked(): WalkedIndex {
        val service = DependencyRegistry.get<PathService>()
        val key = service.changeKey
        walked?.let { if (key == walkedKey) return it }
        val index = WalkedIndex()
        service.getWaypoints(null).values.forEach { points ->
            val flat = DoubleArray(points.size * 2)
            val seconds = LongArray(points.size)
            points.forEachIndexed { i, point ->
                flat[i * 2] = point.coordinate.latitude
                flat[i * 2 + 1] = point.coordinate.longitude
                seconds[i] = point.time?.epochSecond ?: -1L
            }
            index.addPath(flat, seconds)
        }
        walked = index
        walkedKey = key
        return index
    }

    /**
     * The street pieces in [box] from what is kept, asking for what is missing a moment later
     * when [fetch] is true. The widget never fetches.
     */
    suspend fun view(context: Context, box: ForageBox, zoom: Int, fetch: Boolean): View {
        if (zoom < MIN_ZOOM) return View(emptyList(), true)
        val cells = ForageGrid.cells(box, CELL_ZOOM, MAX_CELLS) ?: return View(emptyList(), true)
        val cache = cache(context)
        var stale = false
        val ways = LinkedHashMap<Long, StreetWay>()
        for (cell in cells) {
            val data = cache.get(cell)
            if (!cache.isFresh(data)) stale = true
            data?.ways?.forEach { ways.putIfAbsent(it.id, it) }
        }
        if (stale && fetch) request(context, box)
        val index = walked()
        val pieces = ways.values.asSequence()
            .filter { touches(it, box) }
            .take(MAX_WAYS)
            .flatMap { StreetCoverage.pieces(it, index).asSequence() }
            .toList()
        return View(pieces, false)
    }

    private fun touches(way: StreetWay, box: ForageBox): Boolean {
        var south = Double.MAX_VALUE; var north = -Double.MAX_VALUE
        var west = Double.MAX_VALUE; var east = -Double.MAX_VALUE
        for (i in 0 until way.size) {
            south = minOf(south, way.lat(i)); north = maxOf(north, way.lat(i))
            west = minOf(west, way.lng(i)); east = maxOf(east, way.lng(i))
        }
        return box.intersects(ForageBox(south, west, north, east))
    }

    private fun request(context: Context, box: ForageBox) {
        val app = context.applicationContext
        synchronized(this) {
            latest = box
            waiting?.cancel()
            waiting = scope.launch {
                delay(DEBOUNCE_MILLIS)
                fetchLock.withLock {
                    withContext(NonCancellable) {
                        val now = synchronized(this@Streets) { latest } ?: return@withContext
                        val changed = try {
                            fetchMissing(app, now)
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

    private fun fetchMissing(context: Context, box: ForageBox): Boolean {
        val cache = cache(context)
        val cells = ForageGrid.cells(box, CELL_ZOOM, MAX_CELLS) ?: return false
        val missing = cells.filter { !cache.isFresh(cache.get(it)) }
        if (missing.isEmpty()) return false
        return fetchCells(context, missing, kept = false)
    }

    /** With a saved area, keeps its streets too, if the layer is in use. Returns whether any were kept. */
    suspend fun keepArea(context: Context, box: ForageBox): Boolean = withContext(Dispatchers.IO) {
        val cells = ForageGrid.cells(box, CELL_ZOOM, MAX_SAVED_CELLS) ?: return@withContext false
        fetchLock.withLock {
            backoffs.values.forEach { it.ok() }
            fetchCells(context, cells, kept = true)
        }
    }

    private fun fetchCells(context: Context, cells: List<ForageCell>, kept: Boolean): Boolean {
        if (backoffs.values.none { it.ready() }) return false
        val box = ForageGrid.requestBox(cells) ?: return false
        debug("Overpass: ${cells.size} squares in one request")
        val post = "data=" + URLEncoder.encode(OverpassStreets.query(box), "UTF-8")
        val reply = servers.ask { server ->
            val timeout = if (server == servers.main) {
                OverpassServers.MAIN_READ_TIMEOUT_MILLIS
            } else {
                OverpassServers.BACKUP_READ_TIMEOUT_MILLIS
            }
            exchange(backoffs.getValue(server), server, post, timeout)
        } ?: return false
        val ways = OverpassStreets.parse(reply) ?: return false
        store(context, cells, ways, kept)
        return true
    }

    /** Each square keeps the streets that touch it, so a street across two squares is in both. */
    private fun store(context: Context, cells: List<ForageCell>, ways: List<StreetWay>, kept: Boolean) {
        val now = System.currentTimeMillis()
        val cache = cache(context)
        for (cell in cells) {
            val bounds = cell.bounds()
            cache.put(cell, StreetCellData(now, ways.filter { touches(it, bounds) }), kept)
        }
    }

    private fun exchange(backoff: Backoff, url: String, post: String, readTimeout: Int): OverpassServers.Reply {
        if (!backoff.ready()) return OverpassServers.Reply(OverpassServers.Outcome.SKIPPED)
        val connection = URL(url).openConnection() as HttpURLConnection
        var connected = false
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeout
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", WebTiles.USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(post.toByteArray()) }
            connection.connect()
            connected = true
            val code = connection.responseCode
            debug("HTTP $code")
            return when {
                code in 200..299 -> OverpassServers.Reply(
                    OverpassServers.Outcome.OK,
                    connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                ).also { backoff.ok() }
                code == 429 || code >= 500 -> {
                    backoff.busy()
                    OverpassServers.Reply(OverpassServers.Outcome.BUSY)
                }
                else -> {
                    backoff.busy()
                    OverpassServers.Reply(OverpassServers.Outcome.REJECTED)
                }
            }
        } catch (e: IOException) {
            backoff.offline()
            return OverpassServers.Reply(
                if (connected && e is SocketTimeoutException) OverpassServers.Outcome.TIMEOUT
                else OverpassServers.Outcome.OFFLINE
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
