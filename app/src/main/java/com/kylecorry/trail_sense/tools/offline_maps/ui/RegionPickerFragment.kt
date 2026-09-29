package com.kylecorry.trail_sense.tools.offline_maps.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.navigation.fragment.findNavController
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.sol.science.geology.CoordinateBounds
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.FragmentRegionPickerBinding
import com.kylecorry.trail_sense.main.getAppService
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.extensions.point
import com.kylecorry.trail_sense.shared.extensions.polygon
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.LayerFactory
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.setLayers
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.start
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.stop
import com.kylecorry.trail_sense.shared.map_layers.ui.layers.geojson.ConfigurableGeoJsonLayer
import com.kylecorry.trail_sense.shared.extensions.GEO_JSON_PROPERTY_MARKER_SHAPE_NONE
import com.kylecorry.andromeda.geojson.GeoJsonFeature
import com.kylecorry.andromeda.geojson.GeoJsonFeatureCollection
import com.kylecorry.trail_sense.shared.sensors.SensorSubsystem
import com.kylecorry.trail_sense.tools.map.map_layers.MyLocationGeoJsonSource
import com.kylecorry.trail_sense.tools.offline_maps.domain.OfflineMapService
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPack
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPackClient
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPackDownloader
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPackSource
import com.kylecorry.trail_sense.tools.offline_maps.map_layers.TrailMapsTileSource
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools

/**
 * Pick region packs on a map, the way CalTopo picks download squares: every quadrangle the
 * server offers is drawn as a square with its name; tap squares to select them, then download.
 * Squares already on the phone show their map and a check mark.
 */
class RegionPickerFragment : BoundFragment<FragmentRegionPickerBinding>() {

    private val source by lazy { RegionPackSource(requireContext()) }
    private val mapService by lazy { getAppService<OfflineMapService>() }
    private val sensors by lazy { getAppService<SensorSubsystem>() }
    private val formatter by lazy { FormatService.getInstance(requireContext()) }

    private val gridLayer = ConfigurableGeoJsonLayer()
    private var client: RegionPackClient? = null
    private var packs: List<RegionPack> = emptyList()
    private val selected = mutableSetOf<String>()

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentRegionPickerBinding {
        return FragmentRegionPickerBinding.inflate(layoutInflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val map = binding.map
        map.backgroundColorOverride = Color.WHITE
        gridLayer.renderer.configurePointRenderer(shouldRenderLabels = true)
        map.userLocation = sensors.lastKnownLocation

        val factory = LayerFactory()
        val layers = listOfNotNull(
            Tools.getMapLayerDefinition(requireContext(), TrailMapsTileSource.SOURCE_ID)?.let { factory.createLayer(it) },
            gridLayer,
            Tools.getMapLayerDefinition(requireContext(), MyLocationGeoJsonSource.SOURCE_ID)?.let { factory.createLayer(it) }
        )
        map.setLayers(layers)

        binding.zoomInBtn.setOnClickListener { map.zoom(2f) }
        binding.zoomOutBtn.setOnClickListener { map.zoom(0.5f) }
        map.setOnSingleTapListener { toggleAt(it) }
        binding.downloadBtn.setOnClickListener { download() }

        inBackground {
            val loaded = source.load()
            if (loaded == null) {
                onMain { findNavController().navigateUp() }
                return@inBackground
            }
            client = loaded.first
            packs = loaded.second
            onMain {
                redraw()
                val here = sensors.lastKnownLocation
                val nearby = packs.mapNotNull { boundsOf(it) }
                    .sortedBy { here.distanceTo(it.center) }
                    .take(9)
                if (nearby.isNotEmpty()) {
                    map.doOnLayout { map.fitIntoView(CoordinateBounds.from(nearby.flatMap { it.corners }), 1.05f) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.start()
    }

    override fun onPause() {
        super.onPause()
        binding.map.stop()
    }

    private fun toggleAt(location: Coordinate) {
        val pack = packs.firstOrNull { p ->
            boundsOf(p)?.contains(location) == true
        } ?: return
        if (!selected.remove(pack.id)) {
            selected.add(pack.id)
        }
        redraw()
    }

    private fun redraw() {
        val features = mutableListOf<GeoJsonFeature>()
        var id = 1L
        for (pack in packs) {
            val bounds = boundsOf(pack) ?: continue
            val isSelected = pack.id in selected
            val isInstalled = source.isInstalled(pack)
            val ring = listOf(bounds.corners + bounds.corners.first())
            if (isSelected) {
                // A light wash on selected squares, drawn apart from the outline because the
                // renderer's opacity applies to both; the map under the wash stays readable.
                features.add(GeoJsonFeature.polygon(ring, id++, color = Color.BLACK, opacity = 40))
            }
            features.add(
                GeoJsonFeature.polygon(
                    ring,
                    id++,
                    strokeColor = Color.BLACK,
                    strokeWeight = if (isSelected) 4f else 1f,
                    opacity = 255
                )
            )
            val mark = when {
                isInstalled && source.isOutdated(pack) -> " ↻"
                isInstalled -> " ✓"
                else -> ""
            }
            features.add(
                GeoJsonFeature.point(
                    bounds.center,
                    id++,
                    name = pack.name + mark,
                    // The renderer writes the name at 3/4 of the point's size, in whichever of
                    // black or white contrasts with the point's colour: so white, and 16.
                    color = Color.WHITE,
                    markerShape = GEO_JSON_PROPERTY_MARKER_SHAPE_NONE,
                    size = 16f
                )
            )
        }
        gridLayer.setData(GeoJsonFeatureCollection(features))

        val chosen = packs.filter { it.id in selected }
        binding.downloadBtn.isEnabled = chosen.isNotEmpty()
        binding.downloadBtn.text = if (chosen.isEmpty()) {
            getString(R.string.download)
        } else {
            getString(
                R.string.download_regions_button,
                chosen.size,
                formatter.formatFileSize(chosen.sumOf { it.bytes })
            )
        }
        binding.title.subtitle.text = getString(R.string.region_picker_hint)
    }

    private fun download() {
        val client = client ?: return
        val chosen = packs.filter { it.id in selected }
        if (chosen.isEmpty()) return
        inBackground {
            val imported = RegionPackDownloader(requireContext(), mapService, source).download(client, chosen)
            if (imported > 0) {
                onMain {
                    selected.removeAll(chosen.take(imported).map { it.id }.toSet())
                    redraw()
                }
            }
        }
    }

    private fun boundsOf(pack: RegionPack): CoordinateBounds? {
        val b = pack.bounds ?: return null
        if (b.size != 4) return null
        return CoordinateBounds(b[3], b[2], b[1], b[0])
    }

    private val CoordinateBounds.corners: List<Coordinate>
        get() = listOf(
            Coordinate(north, west),
            Coordinate(north, east),
            Coordinate(south, east),
            Coordinate(south, west)
        )
}
