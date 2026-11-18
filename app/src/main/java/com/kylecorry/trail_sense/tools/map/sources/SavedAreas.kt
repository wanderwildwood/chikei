package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.shared.ProguardIgnore
import com.kylecorry.trail_sense.shared.io.FileSubsystem
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import com.kylecorry.trail_sense.tools.map.forage.Forage
import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import com.kylecorry.trail_sense.tools.map.streets.Streets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

class SavedArea(
    val id: String,
    val name: String,
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
    val savedAt: Long,
    val bytes: Long,
    /** USGS topo tiles kept; zero outside the US. */
    val mapTiles: Int,
    val terrainTiles: Int
) : ProguardIgnore {
    val bounds: CoordinateBounds
        get() = CoordinateBounds(north, east, south, west)
}

private class SavedAreaList(val areas: List<SavedArea>) : ProguardIgnore

/**
 * Areas saved for use with no signal: the USGS topo tiles for the area at every zoom from
 * [MIN_MAP_ZOOM] to [MAX_MAP_ZOOM] (US only; public domain, so keeping them is allowed), and its
 * elevation from the Terrain Tiles (anywhere), from which contour lines are drawn. OpenTopoMap
 * is never saved: its policy forbids bulk and offline downloading.
 *
 * files/topo_areas/areas.json lists them; files/topo_areas/<id>/usgs/<z>/<x>/<y> holds the map
 * tiles; the elevation is in the altimeter's model under dem/area_<id>.
 */
object SavedAreas {

    const val MIN_MAP_ZOOM = 6
    const val MAX_MAP_ZOOM = 16

    /** A save bigger than this is refused, with the size, and the person asked for a smaller area. */
    const val MAX_BYTES = 200L * 1024 * 1024

    // Measured: USGS topo tiles are 15-35 kB as JPEG; converted elevation tiles about 110 kB.
    private const val MAP_TILE_BYTES = 26_000L
    private const val TERRAIN_TILE_BYTES = 110_000L

    private val lock = Mutex()

    /** Ids of the areas being saved right now, which have tiles but no entry in the list yet. */
    private val saving: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Whether this run of the app has looked for tiles no saved area names (see [sweep]). */
    @Volatile
    private var swept = false

    /** Set when areas.json was there but could not be read. */
    @Volatile
    private var unreadable = false

    @Volatile
    private var cached: List<SavedArea>? = null

    class Plan(
        val bounds: CoordinateBounds,
        val mapTiles: List<Tile>,
        val terrainTiles: List<Tile>,
        /** Set when the plan was cut short as too big: its size so far, over [MAX_BYTES]. */
        private val tooBig: Long? = null,
    ) {
        val estimatedBytes: Long
            get() = tooBig ?: (mapTiles.size * MAP_TILE_BYTES + terrainTiles.size * TERRAIN_TILE_BYTES)
    }

    /**
     * The tiles an area would hold. A view too big to save is found from the counts of its
     * tiles, or as soon as the list passes [MAX_BYTES], rather than after listing a country's
     * worth of tiles.
     */
    fun plan(context: Context, bounds: CoordinateBounds): Plan {
        val terrainBytes = countIn(bounds, TerrainTiles.ZOOM) * TERRAIN_TILE_BYTES
        if (terrainBytes > MAX_BYTES) return Plan(bounds, emptyList(), emptyList(), tooBig = terrainBytes)
        val mapTiles = mutableListOf<Tile>()
        if (UsCoverage.intersects(context, bounds)) {
            for (z in MIN_MAP_ZOOM..MAX_MAP_ZOOM) {
                for (tile in tilesIn(bounds, z)) {
                    if (UsCoverage.contains(context, tile.getCenter()) || touchesUs(context, tile)) {
                        mapTiles.add(tile)
                        val bytes = terrainBytes + mapTiles.size * MAP_TILE_BYTES
                        if (bytes > MAX_BYTES) return Plan(bounds, emptyList(), emptyList(), tooBig = bytes)
                    }
                }
            }
        }
        return Plan(bounds, mapTiles, tilesIn(bounds, TerrainTiles.ZOOM))
    }

    /** How many tiles of zoom [z] cover [bounds], without listing them. */
    fun countIn(bounds: CoordinateBounds, z: Int): Long {
        val n = 1 shl z
        fun x(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        fun y(lat: Double) =
            floor((1 - asinh(tan(Math.toRadians(lat))) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        val across = (x(bounds.east) - x(bounds.west) + 1).coerceAtLeast(0)
        val down = (y(bounds.south) - y(bounds.north) + 1).coerceAtLeast(0)
        return across.toLong() * down
    }

    suspend fun all(context: Context): List<SavedArea> = areas(context)

    /** Whether the elevation under [bounds] was saved with an area (rather than only viewed). */
    suspend fun hasTerrain(context: Context, tile: Tile): Boolean {
        val center = tile.getCenter()
        return areas(context).any { it.terrainTiles > 0 && it.bounds.contains(center) }
    }

    /** A saved USGS tile for [tile], from whichever saved area holds one. */
    suspend fun mapTile(context: Context, tile: Tile): File? = withContext(Dispatchers.IO) {
        val areas = areas(context)
        for (area in areas) {
            if (area.mapTiles == 0 || !mayHold(area.bounds, tile)) continue
            val file = mapFile(context, area.id, tile)
            if (file.exists()) return@withContext file
        }
        null
    }

    /**
     * Whether an area saved over [bounds] may hold [tile]. A save keeps every tile that overlaps
     * the area, so at low zooms and along the edges a tile's centre is often far outside it.
     */
    internal fun mayHold(bounds: CoordinateBounds, tile: Tile): Boolean =
        bounds.intersects(tile.getBounds())

    /** A save's outcome: the area, and how many planned tiles could not be fetched. */
    class SaveResult(val area: SavedArea, val missing: Int)

    /**
     * Saves the planned tiles as a new area, or fills in the gaps of [into] (made from the same
     * plan), reporting progress 0..1. Returns null if nothing could be fetched (no signal).
     *
     * A tile that fails is asked for twice more after a pause. After [GIVE_UP_AFTER] tiles in a
     * row from one server fail every try, that server is taken as out of reach and the rest of
     * its tiles are not asked for; the other server's still are. What could
     * not be fetched is counted in [SaveResult.missing], so the person can be told and try again;
     * most of an area is still better than none.
     */
    suspend fun save(
        context: Context,
        name: String,
        plan: Plan,
        into: SavedArea? = null,
        onProgress: (Float) -> Unit
    ): SaveResult? = withContext(Dispatchers.IO) {
        WebTiles.forgetFailures()
        val id = into?.id ?: System.currentTimeMillis().toString(36)
        val total = (plan.mapTiles.size + plan.terrainTiles.size).coerceAtLeast(1)
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val mapKept = AtomicInteger(0)
        val terrainKept = AtomicInteger(0)
        val missing = AtomicInteger(0)
        // Counted for each server apart: the elevation server being down does not stop the map tiles.
        val terrainFailedInARow = AtomicInteger(0)
        val mapFailedInARow = AtomicInteger(0)
        val report = { onProgress(done.incrementAndGet().toFloat() / total) }

        saving.add(id)
        try {
            try {
                for (tile in plan.terrainTiles) {
                    // A tile this area already holds is not fetched again; its size comes back at once.
                    val kept = withRetries(terrainFailedInARow) {
                        TerrainTiles.save(context, tile, folder(id), ignoreOffline = true)
                    }
                    when (kept) {
                        is TerrainTiles.Saved.Kept -> {
                            bytes.addAndGet(kept.bytes)
                            terrainKept.incrementAndGet()
                        }
                        // The server has no such tile: nothing is missing
                        TerrainTiles.Saved.NoTile -> {}
                        null -> missing.incrementAndGet()
                    }
                    report()
                }

                // Three at a time, which WebTiles also enforces across the whole app.
                plan.mapTiles.chunked(3).forEach { group ->
                    coroutineScope {
                        group.map { tile ->
                            async {
                                val file = mapFile(context, id, tile)
                                if (file.exists()) {
                                    // Kept by an earlier try at this area
                                    bytes.addAndGet(file.length())
                                    mapKept.incrementAndGet()
                                } else {
                                    val result = withRetries(mapFailedInARow) {
                                        WebTiles.fromNetwork(
                                            context,
                                            TopoBaseTileSource.usgsUrl(tile),
                                            // Not through the viewing cache, which a save this size would empty.
                                            useCache = false,
                                            ignoreOffline = true
                                        ).takeIf { it !is WebTiles.Result.Unreachable }
                                    }
                                    when (result) {
                                        is WebTiles.Result.Found -> {
                                            file.parentFile?.mkdirs()
                                            file.writeBytes(result.bytes)
                                            bytes.addAndGet(result.bytes.size.toLong())
                                            mapKept.incrementAndGet()
                                        }
                                        // The server has no such tile: nothing is missing
                                        WebTiles.Result.Missing -> {}
                                        else -> missing.incrementAndGet()
                                    }
                                }
                                report()
                            }
                        }.awaitAll()
                    }
                }
            } catch (e: Throwable) {
                // Stopped part way (the page closed, the storage filled, anything else): a new
                // area's tiles so far would be in no list and could never be removed, so they go
                // now. An area being topped up keeps them. A save cut short by the app being closed
                // is swept up the next time the app starts (see [sweep]).
                if (into == null) {
                    withContext(NonCancellable) {
                        File(root(context), id).deleteRecursively()
                        TerrainTiles.forget(folder(id))
                    }
                }
                throw e
            }

            if (into == null && mapKept.get() == 0 && terrainKept.get() == 0) {
                File(root(context), id).deleteRecursively()
                TerrainTiles.forget(folder(id))
                return@withContext null
            }

            val b = plan.bounds
            val area = SavedArea(
                id, into?.name ?: name, b.north, b.south, b.east, b.west,
                System.currentTimeMillis(), bytes.get(), mapKept.get(), terrainKept.get()
            )
            lock.withLock {
                val existing = load(context)
                val list = if (existing.any { it.id == id }) {
                    existing.map { if (it.id == id) area else it }
                } else {
                    existing + area
                }
                write(context, list)
            }
            SaveResult(area, missing.get())
        } finally {
            saving.remove(id)
        }
    }

    /**
     * Runs [attempt] until it gives something, pausing between tries. [failedInARow] counts the
     * tiles in a row from one server that failed every try; once it reaches [GIVE_UP_AFTER] no
     * more are asked of that server.
     */
    internal suspend fun <T : Any> withRetries(
        failedInARow: AtomicInteger,
        pauses: LongArray = RETRY_PAUSES_MILLIS,
        attempt: suspend () -> T?
    ): T? {
        if (failedInARow.get() >= GIVE_UP_AFTER) return null
        for (pause in pauses) {
            attempt()?.let {
                failedInARow.set(0)
                return it
            }
            delay(pause)
        }
        attempt()?.let {
            failedInARow.set(0)
            return it
        }
        failedInARow.incrementAndGet()
        return null
    }

    private val RETRY_PAUSES_MILLIS = longArrayOf(2_000L, 5_000L)
    internal const val GIVE_UP_AFTER = 3

    /**
     * Removes [area], its map tiles and its elevation. Run to the end even if the page that asked
     * for it closes: once it is out of the list, nothing else could remove the rest.
     */
    suspend fun delete(context: Context, area: SavedArea) = withContext(Dispatchers.IO + NonCancellable) {
        val remaining = lock.withLock {
            load(context).filter { it.id != area.id }.also { write(context, it) }
        }
        File(root(context), area.id).deleteRecursively()
        TerrainTiles.forget(folder(area.id))
        // The fruit and nut trees and the streets kept with it, unless another area holds them
        val boxes = remaining.map { ForageBox(it.south, it.west, it.north, it.east) }
        Forage.releaseAreas(context, boxes)
        Streets.releaseAreas(context, boxes)
    }

    fun folder(id: String) = "dem/$FOLDER_PREFIX$id"

    private const val FOLDER_PREFIX = "area_"

    /** The list, after the first look of this run of the app for tiles it does not name. */
    private suspend fun areas(context: Context): List<SavedArea> {
        val list = lock.withLock { load(context) }
        if (!swept) sweep(context)
        return list
    }

    /**
     * A save stopped by the app being closed (swiped away, or ended by the system) leaves tiles
     * that no entry in the list names, and so no screen can remove. Once each run of the app,
     * the tile folders and the elevation of every such area go. An area being saved right now
     * has no entry yet and is left alone.
     */
    private suspend fun sweep(context: Context) = withContext(Dispatchers.IO + NonCancellable) {
        val orphans = lock.withLock {
            if (swept) return@withLock emptySet<String>()
            swept = true
            val listed = load(context)
            if (unreadable) return@withLock emptySet<String>()
            unclaimed(
                listed.map { it.id }.toSet(),
                found = {
                    val tileIds = root(context).listFiles()
                        ?.filter { it.isDirectory }
                        ?.map { it.name }
                        ?: emptyList()
                    val demDir = DependencyRegistry.get<FileSubsystem>().getDirectory("dem")
                    val demIds = demDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith(FOLDER_PREFIX) }
                        ?.map { it.name.removePrefix(FOLDER_PREFIX) }
                        ?: emptyList()
                    val rowIds = TerrainTiles.folders()
                        .filter { it.startsWith("dem/$FOLDER_PREFIX") }
                        .map { it.removePrefix("dem/$FOLDER_PREFIX") }
                    tileIds + demIds + rowIds
                },
                underWay = { synchronized(saving) { saving.toSet() } }
            )
        }
        for (id in orphans) {
            File(root(context), id).deleteRecursively()
            TerrainTiles.forget(folder(id))
        }
    }

    /**
     * The ids [found] lists that neither [listed] nor [underWay] names. The folders and rows are
     * listed before the saves under way are read: a save that starts after the listing is not
     * among what was found, and one that started before it is still marked as saving, since a
     * save only unmarks itself after its entry is in the list. Read the other way round, a save
     * starting in between would have its first tiles taken for orphans and deleted under it.
     */
    internal fun unclaimed(
        listed: Set<String>,
        found: () -> List<String>,
        underWay: () -> Set<String>
    ): Set<String> {
        val ids = found()
        return orphanIds(listed + underWay(), ids)
    }

    /** The ids among [found] that no saved or saving area has. */
    internal fun orphanIds(known: Set<String>, found: List<String>): Set<String> =
        found.filter { it.isNotEmpty() && it !in known }.toSet()

    private fun root(context: Context) = File(context.filesDir, "topo_areas")

    private fun mapFile(context: Context, id: String, tile: Tile) =
        File(root(context), "$id/usgs/${tile.z}/${tile.x}/${tile.y}")

    private fun load(context: Context): List<SavedArea> {
        cached?.let { return it }
        val file = File(root(context), "areas.json")
        val list = if (file.exists()) {
            val parsed = JsonConvert.fromJson<SavedAreaList>(file.readText())?.areas
            // A list that cannot be read names nothing: the sweep must not take that as
            // every area's tiles being unlisted.
            if (parsed == null) unreadable = true
            parsed ?: emptyList()
        } else {
            emptyList()
        }
        cached = list
        return list
    }

    private fun write(context: Context, list: List<SavedArea>) {
        val dir = root(context).apply { mkdirs() }
        val part = File(dir, "areas.json.part")
        part.writeText(JsonConvert.toJson(SavedAreaList(list)))
        part.renameTo(File(dir, "areas.json"))
        cached = list
    }

    private fun touchesUs(context: Context, tile: Tile): Boolean {
        val b = tile.getBounds()
        return listOf(
            Coordinate(b.north, b.west), Coordinate(b.north, b.east),
            Coordinate(b.south, b.west), Coordinate(b.south, b.east)
        ).any { UsCoverage.contains(context, it) }
    }

    fun tilesIn(bounds: CoordinateBounds, z: Int): List<Tile> {
        val n = 1 shl z
        fun x(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        fun y(lat: Double) =
            floor((1 - asinh(tan(Math.toRadians(lat))) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
        val tiles = mutableListOf<Tile>()
        for (tx in x(bounds.west)..x(bounds.east)) {
            for (ty in y(bounds.north)..y(bounds.south)) {
                tiles.add(Tile(tx, ty, z))
            }
        }
        return tiles
    }
}
