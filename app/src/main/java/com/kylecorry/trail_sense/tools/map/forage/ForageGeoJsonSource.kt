package com.kylecorry.trail_sense.tools.map.forage

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
import com.kylecorry.trail_sense.shared.extensions.point
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.MapLayerParams
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.geojson.sources.GeoJsonSource
import com.kylecorry.trail_sense.tools.beacons.domain.BeaconIcon

/**
 * The Forage layer on the map: a small black dot with a leaf for each edible plant when zoomed
 * in, Falling Fruit's counts in white rings when zoomed out, and a line asking to zoom in when
 * there is nothing to show at this zoom.
 */
class ForageGeoJsonSource : GeoJsonSource {

    private val labels = mutableMapOf<String, Bitmap>()

    override suspend fun load(
        context: Context,
        bounds: CoordinateBounds,
        zoom: Int,
        params: Bundle
    ): GeoJsonObject {
        val isWidget = params.getBoolean(MapLayerParams.PARAM_IS_WIDGET, false)
        val box = ForageBox(bounds.south, bounds.west, bounds.north, bounds.east)
        val view = Forage.view(context, box, zoom, fetch = !isWidget)
        val features = mutableListOf<GeoJsonFeature>()

        view.spots.forEach { spot ->
            features.add(
                GeoJsonFeature.point(
                    Coordinate(spot.lat, spot.lng),
                    spot.id,
                    spot.name,
                    color = Color.BLACK,
                    icon = BeaconIcon.Plant.id,
                    iconSize = 8f,
                    iconColor = Color.WHITE,
                    strokeColor = Color.WHITE,
                    strokeWeight = 1f,
                    size = 12f,
                    isClickable = true,
                    layerId = Forage.LAYER_ID,
                    additionalProperties = mapOf(PROPERTY_SPOT_ID to spot.id)
                )
            )
        }

        view.clusters.forEach { cluster ->
            val text = count(cluster.count)
            features.add(
                GeoJsonFeature.point(
                    Coordinate(cluster.lat, cluster.lng),
                    markerShape = null,
                    size = 22f,
                    iconSize = 22f,
                    bitmap = label(context, text, round = true),
                    layerId = Forage.LAYER_ID
                )
            )
        }

        if (view.zoomIn && !isWidget) {
            features.add(
                GeoJsonFeature.point(
                    bounds.center,
                    markerShape = null,
                    size = 24f,
                    iconSize = 24f,
                    bitmap = label(context, context.getString(R.string.forage_zoom_in), round = false),
                    layerId = Forage.LAYER_ID
                )
            )
        }

        return GeoJsonFeatureCollection(features)
    }

    override suspend fun cleanup() {
        synchronized(labels) { labels.clear() }
    }

    private fun count(value: Int): String = when {
        value >= 10_000 -> "${value / 1000}k"
        value >= 1000 -> String.format(java.util.Locale.US, "%.1fk", value / 1000f)
        else -> value.toString()
    }

    /** Black text in a white ring (a count) or a white box (a line), drawn at twice the size for a crisp edge. */
    private fun label(context: Context, text: String, round: Boolean): Bitmap {
        val key = "$round:$text"
        synchronized(labels) { labels[key] }?.let { return it }
        val density = context.resources.displayMetrics.density * 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (if (round) 11f else 14f) * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val stroke = 2f * density
        val height = ((if (round) 22f else 24f) * density).toInt()
        val textWidth = paint.measureText(text)
        val width = if (round) maxOf(height, (textWidth + 10 * density).toInt()) else (textWidth + 20 * density).toInt()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val rect = RectF(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        val radius = if (round) height / 2f else 8f * density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, rim)
        val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, width / 2f, baseline, paint)
        synchronized(labels) { labels[key] = bitmap }
        return bitmap
    }

    companion object {
        const val PROPERTY_SPOT_ID = "forageSpotId"
    }
}
