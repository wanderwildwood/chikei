package com.kylecorry.trail_sense.tools.map.geocache

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
import com.kylecorry.trail_sense.tools.map.forage.ForageBox

/**
 * The Geocaches layer on the map. Each cache is a small square with a letter for its kind
 * (T traditional, M multi, ? mystery...): black while not yet found, white with a tick once found,
 * white with a cross after a did-not-find, grey while the cache is unavailable. Squares, not
 * circles, so caches never pass for Forage's dots.
 */
class GeocacheGeoJsonSource : GeoJsonSource {

    private val images = mutableMapOf<String, Bitmap>()

    override suspend fun load(
        context: Context,
        bounds: CoordinateBounds,
        zoom: Int,
        params: Bundle
    ): GeoJsonObject {
        val isWidget = params.getBoolean(MapLayerParams.PARAM_IS_WIDGET, false)
        val box = ForageBox(bounds.south, bounds.west, bounds.north, bounds.east)
        // "My region packs only" keeps every layer off the network; what is kept still draws.
        val offline = com.kylecorry.trail_sense.tools.map.sources.TopoMapSource.get(context) ==
            com.kylecorry.trail_sense.tools.map.sources.TopoMapSource.PacksOnly
        val view = Geocaches.view(context, box, zoom, fetch = !isWidget && !offline)
        val store = Geocaches.store(context)
        val features = mutableListOf<GeoJsonFeature>()

        view.caches.forEach { cache ->
            val state = store.state(cache.code)?.kind
            val image = marker(context, cache, state)
            features.add(
                GeoJsonFeature.point(
                    Coordinate(cache.lat, cache.lng),
                    cache.code,
                    cache.name,
                    markerShape = null,
                    size = MARKER_DP,
                    iconSize = MARKER_DP,
                    bitmap = image,
                    isClickable = true,
                    layerId = Geocaches.LAYER_ID,
                    additionalProperties = mapOf(PROPERTY_CODE to cache.code)
                )
            )
        }

        val line = when {
            isWidget -> null
            view.zoomIn -> context.getString(R.string.geocaches_zoom_in)
            view.empty -> context.getString(R.string.geocaches_none_yet)
            else -> null
        }
        if (line != null) {
            val image = label(context, line)
            val width = image.width / (context.resources.displayMetrics.density * 2)
            features.add(
                GeoJsonFeature.point(
                    bounds.center,
                    markerShape = null,
                    size = width,
                    iconSize = width,
                    bitmap = image,
                    layerId = Geocaches.LAYER_ID
                )
            )
        }

        return GeoJsonFeatureCollection(features)
    }

    override suspend fun cleanup() {
        synchronized(images) { images.clear() }
    }

    private fun letter(kind: String): String = when (kind) {
        GeocacheKinds.TRADITIONAL -> "T"
        GeocacheKinds.MULTI -> "M"
        GeocacheKinds.MYSTERY -> "?"
        GeocacheKinds.LETTERBOX -> "L"
        GeocacheKinds.WHERIGO -> "W"
        GeocacheKinds.EARTH -> "E"
        GeocacheKinds.VIRTUAL -> "V"
        GeocacheKinds.WEBCAM -> "C"
        GeocacheKinds.EVENT -> "!"
        else -> "•"
    }

    /** A square marker, drawn at twice the density for a crisp edge. */
    private fun marker(context: Context, cache: Geocache, state: String?): Bitmap {
        val text = when (state) {
            VisitKinds.FOUND -> "✓"
            VisitKinds.DNF -> "✕"
            else -> letter(cache.kind)
        }
        val filled = state == null
        val grey = !cache.available && state == null
        val key = "$text:$filled:$grey"
        synchronized(images) { images[key] }?.let { return it }
        val density = context.resources.displayMetrics.density * 2
        val side = (MARKER_DP * density).toInt()
        val stroke = 1.5f * density
        val bitmap = createBitmap(side, side)
        val canvas = Canvas(bitmap)
        val rect = RectF(stroke / 2, stroke / 2, side - stroke / 2, side - stroke / 2)
        val radius = 2f * density
        val fillColor = when {
            grey -> Color.rgb(0x80, 0x80, 0x80)
            filled -> Color.BLACK
            else -> Color.WHITE
        }
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (filled) Color.WHITE else Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = stroke
        })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (filled) Color.WHITE else Color.BLACK
            // Map marker, drawn on the map: exempt from the 16 sp floor (like Forage's counts).
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val baseline = side / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, side / 2f, baseline, paint)
        synchronized(images) { images[key] = bitmap }
        return bitmap
    }

    /** Black text in a white box, for the line asking to zoom in. */
    private fun label(context: Context, text: String): Bitmap {
        val key = "label:$text"
        synchronized(images) { images[key] }?.let { return it }
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
        synchronized(images) { images[key] = bitmap }
        return bitmap
    }

    companion object {
        const val PROPERTY_CODE = "geocacheCode"
        private const val MARKER_DP = 16f
    }
}
