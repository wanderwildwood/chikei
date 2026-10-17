package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.trail_sense.main.persistence.AppDatabase
import com.kylecorry.trail_sense.shared.dem.DigitalElevationModelEntity
import com.kylecorry.trail_sense.shared.dem.DigitalElevationModelLoader
import com.kylecorry.trail_sense.shared.io.FileSubsystem
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Elevation for anywhere, from the Terrain Tiles on AWS's Registry of Open Data (Mapzen's
 * Terrarium tiles: 3DEP in the US, SRTM, GMTED and others elsewhere; see About for the credits).
 *
 * Each 256-pixel Terrarium tile at zoom [ZOOM] (about 30 m a pixel at mid latitudes) is turned
 * into a tile in the altimeter's own elevation format, so the elevation readout, long-press
 * point info, path profiles, hillshade and contour lines all read it like any imported model.
 * A region pack's own model wins wherever it has one (see DEM.getSources).
 *
 * Tiles fetched because the map was looked at live in [VIEWED] and the oldest go past
 * [MAX_VIEWED]; tiles saved with an area live in that area's folder until it is deleted.
 */
object TerrainTiles {

    const val ZOOM = 12
    const val VIEWED = "dem/terrain"
    const val VERSION = "terrain-tiles"
    private const val MAX_VIEWED = 150

    // Two bytes of elevation per pixel in fifths of a metre above -500 m: -500 m to 12,600 m.
    private const val A = 5.0
    private const val B = 500.0

    private const val URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png"

    private val lock = Mutex()

    /** z/x/y of every terrain tile held (viewed or saved), and which folder it is in. */
    @Volatile
    private var held: MutableMap<String, String>? = null

    /** Terrain tiles at [ZOOM] covering [tile]. */
    fun tilesFor(tile: Tile): List<Tile> {
        if (tile.z >= ZOOM) {
            var t = tile
            while (t.z > ZOOM) t = t.getParent() ?: break
            return listOf(Tile(t.x, t.y, ZOOM))
        }
        val scale = 1 shl (ZOOM - tile.z)
        val list = mutableListOf<Tile>()
        for (dx in 0 until scale) {
            for (dy in 0 until scale) {
                list.add(Tile(tile.x * scale + dx, tile.y * scale + dy, ZOOM))
            }
        }
        return list
    }

    fun url(tile: Tile): String = URL.format(tile.z, tile.x, tile.y)

    suspend fun has(tile: Tile): Boolean = lock.withLock { heldTiles().containsKey(key(tile)) }

    /**
     * Makes sure the viewed map has elevation under [tile]: fetches the terrain tiles not already
     * held, unless a region pack's own model already covers them. Returns true if any arrived.
     */
    suspend fun ensureViewed(context: Context, tile: Tile, allowNetwork: Boolean): Boolean {
        if (!allowNetwork) return false
        var added = false
        for (t in tilesFor(tile)) {
            val fresh = lock.withLock {
                if (heldTiles().containsKey(key(t)) || coveredByPack(t)) {
                    null
                } else {
                    t
                }
            } ?: continue
            val bytes = (WebTiles.fromNetwork(context, url(fresh), useCache = false)
                    as? WebTiles.Result.Found)?.bytes ?: continue
            store(fresh, bytes, VIEWED)
            added = true
        }
        if (added) trimViewed()
        return added
    }

    /** Fetches [tile] into a saved area's [folder] (moving it there if it was only viewed). Returns its size. */
    suspend fun save(context: Context, tile: Tile, folder: String): Long? {
        val existing = lock.withLock { heldTiles()[key(tile)] }
        if (existing == folder) {
            return withContext(Dispatchers.IO) { fileFor(folder, tile).length() }
        }
        if (existing == VIEWED) {
            return move(tile, VIEWED, folder)
        }
        val bytes = (WebTiles.fromNetwork(context, url(tile), useCache = false)
                as? WebTiles.Result.Found)?.bytes ?: return null
        return store(tile, bytes, folder)
    }

    /** A saved area's tiles are removed with it. Tiles another saved area also holds stay. */
    suspend fun forget(folder: String) {
        lock.withLock {
            heldTiles().entries.removeAll { it.value == folder }
        }
        DigitalElevationModelLoader().removeFolder(folder)
        // Another saved area may hold some of the same tiles: look again at what is held.
        invalidate()
    }

    private suspend fun store(tile: Tile, png: ByteArray, folder: String): Long? = withContext(Dispatchers.Default) {
        val bitmap = convert(png, tile) ?: return@withContext null
        val file = fileFor(folder, tile)
        try {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            bitmap.recycle()
        }
        DigitalElevationModelLoader().addTiles(folder, listOf(entity(tile, folder, bitmapSize = 256)))
        lock.withLock { heldTiles()[key(tile)] = folder }
        file.length()
    }

    private suspend fun move(tile: Tile, from: String, to: String): Long? {
        val source = fileFor(from, tile)
        val dest = fileFor(to, tile)
        val moved = withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            source.exists() && (source.renameTo(dest) || source.copyTo(dest, true).exists())
        }
        if (!moved) return null
        val loader = DigitalElevationModelLoader()
        loader.removeFiles(listOf(relativePath(from, tile)))
        loader.addTiles(to, listOf(entity(tile, to, bitmapSize = 256)))
        lock.withLock { heldTiles()[key(tile)] = to }
        return dest.length()
    }

    private suspend fun trimViewed() {
        val files = DependencyRegistry.get<FileSubsystem>()
        val dir = files.getDirectory(VIEWED)
        val all = withContext(Dispatchers.IO) { dir.listFiles()?.toList() ?: emptyList() }
        if (all.size <= MAX_VIEWED) return
        val oldest = all.sortedBy { it.lastModified() }.take(all.size - MAX_VIEWED)
        DigitalElevationModelLoader().removeFiles(oldest.map { "$VIEWED/${it.name}" })
        lock.withLock {
            val names = oldest.map { it.name.removeSuffix(".png").replace('_', '/') }.toSet()
            heldTiles().entries.removeAll { it.value == VIEWED && it.key in names }
        }
    }

    /** Terrarium: metres = R * 256 + G + B / 256 - 32768, in Web Mercator rows. */
    private fun convert(png: ByteArray, tile: Tile): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
        }
        val src = BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: return null
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        src.recycle()
        val metres = FloatArray(w * h) {
            val p = pixels[it]
            ((p shr 16 and 0xFF) * 256 + (p shr 8 and 0xFF) + (p and 0xFF) / 256f) - 32768f
        }

        // Re-sample into even steps of latitude, row 0 at the north edge, as the elevation
        // reader expects. Within one tile the two differ by well under a pixel, but it is cheap.
        val bounds = tile.getBounds()
        val n = (1 shl tile.z).toDouble()
        val out = IntArray(w * h)
        for (j in 0 until h) {
            val lat = bounds.north - (j + 0.5) / h * (bounds.north - bounds.south)
            val global = (1 - asinh(tan(Math.toRadians(lat))) / PI) / 2 * n
            val sy = ((global - tile.y) * h - 0.5).coerceIn(0.0, (h - 1).toDouble())
            val y0 = floor(sy).toInt()
            val y1 = (y0 + 1).coerceAtMost(h - 1)
            val f = (sy - y0).toFloat()
            for (i in 0 until w) {
                val e = metres[y0 * w + i] * (1 - f) + metres[y1 * w + i] * f
                val v = ((e.coerceIn(-500f, 12_600f) + B) * A).roundToInt().coerceIn(0, 65_535)
                // The reader takes (G << 8 | R) / A - B.
                out[j * w + i] = (0xFF shl 24) or ((v and 0xFF) shl 16) or ((v shr 8 and 0xFF) shl 8)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun entity(tile: Tile, folder: String, bitmapSize: Int): DigitalElevationModelEntity {
        val b = tile.getBounds()
        return DigitalElevationModelEntity(
            1,
            "16-bit",
            VERSION,
            relativePath(folder, tile),
            bitmapSize,
            bitmapSize,
            A,
            B,
            b.north,
            b.south,
            b.east,
            b.west
        )
    }

    private fun coveredByPack(tile: Tile): Boolean {
        val b = tile.getBounds()
        val corners = listOf(
            b.north to b.west, b.north to b.east, b.south to b.west, b.south to b.east
        )
        val packs = packBounds ?: return false
        return corners.all { (lat, lon) ->
            packs.any { lat <= it[0] && lat >= it[1] && lon <= it[2] && lon >= it[3] }
        }
    }

    /** north, south, east, west of every elevation tile that came from a region pack. */
    @Volatile
    private var packBounds: List<DoubleArray>? = null

    private suspend fun heldTiles(): MutableMap<String, String> {
        held?.let { return it }
        val rows = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao().getAll()
        val map = mutableMapOf<String, String>()
        val packs = mutableListOf<DoubleArray>()
        for (row in rows) {
            if (row.version == VERSION) {
                val folder = row.filename.substringBeforeLast('/')
                val name = row.filename.substringAfterLast('/').removeSuffix(".png")
                map[name.replace('_', '/')] = folder
            } else {
                packs.add(doubleArrayOf(row.north, row.south, row.east, row.west))
            }
        }
        packBounds = packs
        held = map
        return map
    }

    /** Region packs were added or removed: look again at what covers what. */
    fun invalidate() {
        held = null
        packBounds = null
    }

    private fun key(tile: Tile) = "${tile.z}/${tile.x}/${tile.y}"

    private fun relativePath(folder: String, tile: Tile) = "$folder/${tile.z}_${tile.x}_${tile.y}.png"

    private fun fileFor(folder: String, tile: Tile): File {
        return DependencyRegistry.get<FileSubsystem>().get(relativePath(folder, tile))
    }
}
