package com.kylecorry.trail_sense.tools.map.streets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import androidx.core.graphics.createBitmap
import com.kylecorry.andromeda.geojson.GeoJsonFeature
import com.kylecorry.andromeda.geojson.GeoJsonFeatureCollection
import com.kylecorry.andromeda.geojson.GeoJsonObject
import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.extensions.lineString
import com.kylecorry.trail_sense.shared.extensions.point
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.MapLayerParams
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.geojson.sources.GeoJsonSource
import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import kotlin.math.roundToInt

/**
 * The Streets layer on the map: a thin black line for every street piece not yet walked, a thick
 * one for each piece a path has been along, and a line just below the middle of the screen with the share
 * of the streets on screen walked. Zoomed out too far, a line asks to zoom in.
 */
class StreetsGeoJsonSource : GeoJsonSource {

    private val labels = mutableMapOf<String, Bitmap>()

    override suspend fun load(
        context: Context,
        bounds: CoordinateBounds,
        zoom: Int,
        params: Bundle
    ): GeoJsonObject {
        val isWidget = params.getBoolean(MapLayerParams.PARAM_IS_WIDGET, false)
        val box = ForageBox(bounds.south, bounds.west, bounds.north, bounds.east)
        val view = Streets.view(context, box, zoom, fetch = !isWidget)
        val features = mutableListOf<GeoJsonFeature>()

        view.pieces.forEach { piece ->
            val points = (0 until piece.points.size / 2).map {
                Coordinate(piece.points[it * 2], piece.points[it * 2 + 1])
            }
            features.add(
                GeoJsonFeature.lineString(
                    points,
                    color = Color.BLACK,
                    thicknessScale = if (piece.walked) WALKED_SCALE else UNWALKED_SCALE
                )
            )
        }

        if (!isWidget) {
            val text = when {
                view.zoomIn -> context.getString(R.string.streets_zoom_in)
                view.totalMetres > 0 -> context.getString(
                    R.string.streets_walked_share,
                    (view.walkedMetres / view.totalMetres * 100).roundToInt()
                )
                else -> context.getString(R.string.streets_none)
            }
            val foot = bounds.center.latitude - (bounds.north - bounds.south) * 0.05
            val image = label(context, text)
            // A wide picture is drawn as wide as the size given, so that is its width in dp
            val width = image.width / (context.resources.displayMetrics.density * 2)
            features.add(
                GeoJsonFeature.point(
                    Coordinate(foot, bounds.center.longitude),
                    markerShape = null,
                    size = width,
                    iconSize = width,
                    bitmap = image,
                    layerId = Streets.LAYER_ID
                )
            )
        }

        return GeoJsonFeatureCollection(features)
    }

    override suspend fun cleanup() {
        synchronized(labels) { labels.clear() }
    }

    /** Black text in a white box with a black rim, drawn at twice the size for a crisp edge. */
    private fun label(context: Context, text: String): Bitmap {
        synchronized(labels) { labels[text] }?.let { return it }
        val density = context.resources.displayMetrics.density * 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val stroke = 2f * density
        val height = (24f * density).toInt()
        val width = (paint.measureText(text) + 20 * density).toInt()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val rect = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        val radius = 8f * density
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = stroke
        })
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, width / 2f, baseline, paint)
        synchronized(labels) { labels[text] = bitmap }
        return bitmap
    }

    companion object {
        /** Line thickness against the map's default: walked pieces are plainly heavier. */
        const val UNWALKED_SCALE = 0.45f
        const val WALKED_SCALE = 2.6f
    }
}
