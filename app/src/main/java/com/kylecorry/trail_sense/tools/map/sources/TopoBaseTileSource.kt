package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.MapLayerParams
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.tiles.TileSource
import com.kylecorry.trail_sense.tools.map.sources.TopoCredits.Provider

/**
 * The map under the person's own region packs, so a new phone shows a map with no setup.
 *
 * With signal: USGS topo (The National Map) in the US, OpenTopoMap elsewhere, as chosen in
 * Settings > Map. Where neither has a tile, contour lines drawn here from the Terrain Tiles.
 *
 * With none, in this order: a saved area's USGS tiles; contour lines where an area's elevation
 * was saved; whatever tiles the HTTP cache still holds from looking before; contour lines from
 * any elevation the phone has. The packs, a layer above this one, cover all of it where they are.
 *
 * Tiles are fetched only for what the map is showing (the map asks tile by tile for its
 * viewport) and never for a home-screen widget, which must not reach the network by itself.
 */
class TopoBaseTileSource : TileSource {

    override suspend fun loadTile(context: Context, tile: Tile, params: Bundle): Bitmap? {
        val mode = TopoMapSource.get(context)
        if (mode == TopoMapSource.PacksOnly) return null
        val allowNetwork = !params.getBoolean(MapLayerParams.PARAM_IS_WIDGET, false)

        // Elevation under the map that is being looked at, for the readout, profiles and
        // contours. A pack's own model covers its area, so nothing is fetched there.
        if (tile.z >= TerrainTiles.ZOOM) {
            TerrainTiles.ensureViewed(context, tile, allowNetwork)
        }

        val provider = when (mode) {
            TopoMapSource.Usgs -> Provider.Usgs
            TopoMapSource.OpenTopoMap -> Provider.OpenTopoMap
            else -> if (UsCoverage.contains(context, tile.getCenter())) Provider.Usgs else Provider.OpenTopoMap
        }

        // 1. A saved area's map, which needs no signal and is the same map as online.
        if (provider == Provider.Usgs) {
            savedUsgs(context, tile)?.let { return drew(tile, Provider.Usgs, it) }
        }

        // 2. The server.
        if (allowNetwork) {
            when (val result = fetch(context, provider, tile)) {
                is Fetched.Map -> return drew(tile, provider, result.bitmap)
                Fetched.None -> {
                    // USGS has no tile here (past its edge, or no map at this zoom): in
                    // Automatic, OpenTopoMap may, and contour lines will do otherwise.
                    if (mode == TopoMapSource.Automatic && provider == Provider.Usgs) {
                        val other = fetch(context, Provider.OpenTopoMap, tile)
                        if (other is Fetched.Map) return drew(tile, Provider.OpenTopoMap, other.bitmap)
                    }
                    return contours(tile)
                }

                Fetched.Unreachable -> Unit
            }
        }

        // 3. No signal: saved elevation, then what was viewed before, then any elevation.
        if (SavedAreas.hasTerrain(context, tile)) {
            contours(tile)?.let { return it }
        }
        cached(context, provider, tile)?.let { return drew(tile, provider, it) }
        if (mode == TopoMapSource.Automatic && provider == Provider.Usgs) {
            cached(context, Provider.OpenTopoMap, tile)?.let { return drew(tile, Provider.OpenTopoMap, it) }
        }
        return contours(tile)
    }

    private sealed class Fetched {
        class Map(val bitmap: Bitmap) : Fetched()
        data object None : Fetched()
        data object Unreachable : Fetched()
    }

    private suspend fun fetch(context: Context, provider: Provider, tile: Tile): Fetched {
        val (source, url) = sourceTile(provider, tile)
        return when (val result = WebTiles.fromNetwork(context, url)) {
            is WebTiles.Result.Found -> decode(result.bytes, source, tile)?.let { Fetched.Map(it) } ?: Fetched.None
            WebTiles.Result.Missing -> Fetched.None
            WebTiles.Result.Unreachable -> Fetched.Unreachable
        }
    }

    private suspend fun cached(context: Context, provider: Provider, tile: Tile): Bitmap? {
        val (source, url) = sourceTile(provider, tile)
        val result = WebTiles.fromCache(context, url) as? WebTiles.Result.Found ?: return null
        return decode(result.bytes, source, tile)
    }

    private suspend fun savedUsgs(context: Context, tile: Tile): Bitmap? {
        val source = ancestorAt(tile, USGS_MAX_ZOOM)
        val file = SavedAreas.mapTile(context, source) ?: return null
        return decode(file.readBytes(), source, tile)
    }

    private suspend fun contours(tile: Tile): Bitmap? {
        val bitmap = TerrainContours.render(tile) ?: return null
        TopoCredits.drew(tile, Provider.Terrain)
        return bitmap
    }

    private fun drew(tile: Tile, provider: Provider, bitmap: Bitmap): Bitmap {
        TopoCredits.drew(tile, provider)
        return bitmap
    }

    /** The provider's tile to fetch for [tile]: itself, or past the provider's last zoom, the ancestor to enlarge. */
    private fun sourceTile(provider: Provider, tile: Tile): Pair<Tile, String> {
        return when (provider) {
            Provider.OpenTopoMap -> {
                val source = ancestorAt(tile, OPENTOPOMAP_MAX_ZOOM)
                source to openTopoMapUrl(source)
            }

            else -> {
                val source = ancestorAt(tile, USGS_MAX_ZOOM)
                source to usgsUrl(source)
            }
        }
    }

    private fun ancestorAt(tile: Tile, maxZoom: Int): Tile {
        var t = tile
        while (t.z > maxZoom) t = t.getParent() ?: break
        return t
    }

    /**
     * Decodes a map tile in greys (what the e-ink panel shows anyway, so the picture on a
     * screenshot is the picture on the phone), enlarging the part [tile] covers if [source] is
     * an ancestor.
     */
    private fun decode(bytes: ByteArray, source: Tile, tile: Tile): Bitmap? {
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val size = tile.size.width
        val out = Bitmap.createBitmap(size, tile.size.height, Bitmap.Config.RGB_565)
        val dz = tile.z - source.z
        val src = if (dz <= 0) {
            Rect(0, 0, image.width, image.height)
        } else {
            val parts = 1 shl dz
            val w = image.width / parts
            val h = image.height / parts
            val ox = (tile.x - source.x * parts) * w
            val oy = (tile.y - source.y * parts) * h
            Rect(ox, oy, ox + w, oy + h)
        }
        Canvas(out).drawBitmap(image, src, Rect(0, 0, out.width, out.height), greyPaint)
        image.recycle()
        return out
    }

    companion object {
        const val SOURCE_ID = "topo_base"

        // The National Map's USGSTopo cache ends at 16 (1:9,000); past that a tile is enlarged.
        const val USGS_MAX_ZOOM = 16
        private const val OPENTOPOMAP_MAX_ZOOM = 17

        fun usgsUrl(tile: Tile): String =
            "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/${tile.z}/${tile.y}/${tile.x}"

        fun openTopoMapUrl(tile: Tile): String =
            "https://tile.opentopomap.org/${tile.z}/${tile.x}/${tile.y}.png"

        // Greys, with everything short of white pulled a third darker: brown contours and green
        // woodland are pale once their colour is gone, and the panel has only sixteen greys.
        private const val CONTRAST = 1.35f
        private val greyPaint = Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            CONTRAST, 0f, 0f, 0f, 255f * (1 - CONTRAST),
                            0f, CONTRAST, 0f, 0f, 255f * (1 - CONTRAST),
                            0f, 0f, CONTRAST, 0f, 255f * (1 - CONTRAST),
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            })
        }
    }
}
