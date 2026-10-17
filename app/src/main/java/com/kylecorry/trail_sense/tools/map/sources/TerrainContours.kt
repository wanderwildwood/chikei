package com.kylecorry.trail_sense.tools.map.sources

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.sol.math.trigonometry.Trigonometry
import com.kylecorry.sol.units.Distance
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.dem.DEM
import com.kylecorry.trail_sense.shared.dem.getCellSizeX
import com.kylecorry.trail_sense.shared.dem.getCellSizeY
import com.kylecorry.trail_sense.shared.dem.getSlopeAngle
import com.kylecorry.trail_sense.shared.dem.getSlopeAspect
import com.kylecorry.trail_sense.shared.dem.getSlopeVector
import com.kylecorry.trail_sense.shared.map_layers.tiles.Tile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A map drawn on the phone from elevation alone: contour lines over a faint hillshade, for
 * wherever no topo map can be had (no signal outside a saved area's map, or no map for that
 * zoom). It reads the same elevation as everything else, so it is a region pack's 3DEP where
 * there is one and the Terrain Tiles everywhere else.
 */
object TerrainContours {

    const val MIN_ZOOM = 11

    // Grid steps line up across tiles (multiples of the step), so a line meets itself at a seam.
    private val resolutionForZoom = mapOf(
        11 to 1 / 900.0,
        12 to 1 / 1800.0
    )
    private const val FINEST = 1 / 3600.0

    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.rgb(96, 96, 96)
        strokeWidth = 1f
        isAntiAlias = true
    }
    private val indexPaint = Paint(linePaint).apply {
        color = Color.BLACK
        strokeWidth = 1.8f
    }
    private val shadePaint = Paint().apply {
        alpha = 80
        isFilterBitmap = true
    }

    suspend fun render(tile: Tile): Bitmap? {
        if (tile.z < MIN_ZOOM) return null
        val bounds = tile.getBounds()
        if (!DEM.covers(bounds)) return null

        val metric = DependencyRegistry.get<UserPreferences>().baseDistanceUnits.isMetric
        val interval = interval(tile.z, metric)
        val resolution = resolutionForZoom[tile.z] ?: FINEST
        val size = tile.size.width
        val bitmap = Bitmap.createBitmap(size, tile.size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        hillshade(tile, resolution)?.let { shade ->
            canvas.drawBitmap(
                shade,
                null,
                android.graphics.Rect(0, 0, bitmap.width, bitmap.height),
                shadePaint
            )
            shade.recycle()
        }

        val n = (1 shl tile.z).toDouble()
        val contours = DEM.getContourLines(bounds, interval, resolution)
        for (contour in contours) {
            // Below sea level is almost always the sea, whose floor is no use on a walk.
            if (contour.elevation < 0f) continue
            val steps = contour.elevation / interval
            val isIndex = abs(steps / 5f - Math.round(steps / 5f)) < 0.01f
            val paint = if (isIndex) indexPaint else linePaint
            for (line in contour.lines) {
                if (line.size < 2) continue
                val path = Path()
                line.forEachIndexed { i, point ->
                    val x = ((point.longitude + 180.0) / 360.0 * n - tile.x) * size
                    val y = ((1 - asinh(tan(Math.toRadians(point.latitude))) / PI) / 2 * n - tile.y) * size
                    if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    /** Contour interval for [zoom], with every fifth line an index line. */
    fun interval(zoom: Int, metric: Boolean): Float {
        return if (metric) {
            when {
                zoom <= 11 -> 100f
                zoom == 12 -> 50f
                zoom == 13 -> 25f
                zoom == 14 -> 20f
                else -> 10f
            }
        } else {
            val feet = when {
                zoom <= 11 -> 400f
                zoom == 12 -> 200f
                zoom == 13 -> 100f
                zoom == 14 -> 80f
                else -> 40f
            }
            Distance.feet(feet).meters().value
        }
    }

    private suspend fun hillshade(tile: Tile, resolution: Double): Bitmap? {
        val bounds = tile.getBounds()
        val cellSizeX = getCellSizeX(resolution, bounds)
        val cellSizeY = getCellSizeY(resolution)
        // Light from the north-west, 45 degrees up: the usual relief that reads as hills.
        val zenith = Math.toRadians(90.0 - 45.0)
        val azimuth = Math.toRadians(Trigonometry.remapUnitAngle(315f, 90f, true).toDouble())
        return try {
            DEM.getElevationImage(
                bounds,
                resolution,
                tile.size,
                config = Bitmap.Config.ARGB_8888,
                padding = 2
            ) { x, y, getElevation ->
                val vector = getSlopeVector(cellSizeX, cellSizeY, x, y, getElevation)
                val slope = getSlopeAngle(vector, 2f).toDouble()
                val aspect = getSlopeAspect(vector).toDouble()
                val shade = 255 * (cos(zenith) * cos(slope) + sin(zenith) * sin(slope) * cos(azimuth - aspect))
                val grey = shade.toInt().coerceIn(0, 255)
                Color.rgb(grey, grey, grey)
            }
        } catch (_: Exception) {
            null
        }
    }
}
