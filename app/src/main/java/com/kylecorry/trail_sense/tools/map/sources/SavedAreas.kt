package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.shared.ProguardIgnore
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    @Volatile
    private var cached: List<SavedArea>? = null

    class Plan(
        val bounds: CoordinateBounds,
        val mapTiles: List<Tile>,
        val terrainTiles: List<Tile>
    ) {
        val estimatedBytes: Long
            get() = mapTiles.size * MAP_TILE_BYTES + terrainTiles.size * TERRAIN_TILE_BYTES
    }

    fun plan(context: Context, bounds: CoordinateBounds): Plan {
        val mapTiles = mutableListOf<Tile>()
        if (UsCoverage.intersects(context, bounds)) {
            for (z in MIN_MAP_ZOOM..MAX_MAP_ZOOM) {
                for (tile in tilesIn(bounds, z)) {
                    if (UsCoverage.contains(context, tile.getCenter()) || touchesUs(context, tile)) {
                        mapTiles.add(tile)
                    }
                }
            }
        }
        return Plan(bounds, mapTiles, tilesIn(bounds, TerrainTiles.ZOOM))
    }

    suspend fun all(context: Context): List<SavedArea> = lock.withLock { load(context) }

    /** Whether the elevation under [bounds] was saved with an area (rather than only viewed). */
    suspend fun hasTerrain(context: Context, tile: Tile): Boolean {
        val center = tile.getCenter()
        return lock.withLock { load(context) }.any { it.terrainTiles > 0 && it.bounds.contains(center) }
    }

    /** A saved USGS tile for [tile], from whichever saved area holds one. */
    suspend fun mapTile(context: Context, tile: Tile): File? = withContext(Dispatchers.IO) {
        val center = tile.getCenter()
        val areas = lock.withLock { load(context) }
        for (area in areas) {
            if (area.mapTiles == 0 || !area.bounds.grow(0.05f).contains(center)) continue
            val file = mapFile(context, area.id, tile)
            if (file.exists()) return@withContext file
        }
        null
    }

    /**
     * Saves the planned tiles as a new area, reporting progress 0..1. Returns the area, or null
     * if nothing could be fetched (no signal). Tiles that fail are left out; most of an area is
     * better than none, and the size shown afterwards is what was kept.
     */
    suspend fun save(
        context: Context,
        name: String,
        plan: Plan,
        onProgress: (Float) -> Unit
    ): SavedArea? = withContext(Dispatchers.IO) {
        WebTiles.forgetFailures()
        val id = System.currentTimeMillis().toString(36)
        val total = (plan.mapTiles.size + plan.terrainTiles.size).coerceAtLeast(1)
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val mapKept = AtomicInteger(0)
        val terrainKept = AtomicInteger(0)
        val report = { onProgress(done.incrementAndGet().toFloat() / total) }

        for (tile in plan.terrainTiles) {
            TerrainTiles.save(context, tile, folder(id))?.let {
                bytes.addAndGet(it)
                terrainKept.incrementAndGet()
            }
            report()
        }

        // Three at a time, which WebTiles also enforces across the whole app.
        plan.mapTiles.chunked(3).forEach { group ->
            coroutineScope {
                group.map { tile ->
                    async {
                        val result = WebTiles.fromNetwork(
                            context,
                            TopoBaseTileSource.usgsUrl(tile),
                            // Not through the viewing cache, which a save this size would empty.
                            useCache = false
                        )
                        if (result is WebTiles.Result.Found) {
                            val file = mapFile(context, id, tile)
                            file.parentFile?.mkdirs()
                            file.writeBytes(result.bytes)
                            bytes.addAndGet(result.bytes.size.toLong())
                            mapKept.incrementAndGet()
                        }
                        report()
                    }
                }.awaitAll()
            }
        }

        if (mapKept.get() == 0 && terrainKept.get() == 0) {
            File(root(context), id).deleteRecursively()
            TerrainTiles.forget(folder(id))
            return@withContext null
        }

        val b = plan.bounds
        val area = SavedArea(
            id, name, b.north, b.south, b.east, b.west,
            System.currentTimeMillis(), bytes.get(), mapKept.get(), terrainKept.get()
        )
        lock.withLock {
            val list = load(context) + area
            write(context, list)
        }
        area
    }

    suspend fun delete(context: Context, area: SavedArea) = withContext(Dispatchers.IO) {
        lock.withLock {
            write(context, load(context).filter { it.id != area.id })
        }
        File(root(context), area.id).deleteRecursively()
        TerrainTiles.forget(folder(area.id))
    }

    fun folder(id: String) = "dem/area_$id"

    private fun root(context: Context) = File(context.filesDir, "topo_areas")

    private fun mapFile(context: Context, id: String, tile: Tile) =
        File(root(context), "$id/usgs/${tile.z}/${tile.x}/${tile.y}")

    private fun load(context: Context): List<SavedArea> {
        cached?.let { return it }
        val file = File(root(context), "areas.json")
        val list = if (file.exists()) {
            JsonConvert.fromJson<SavedAreaList>(file.readText())?.areas ?: emptyList()
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
