package com.kylecorry.trail_sense.tools.offline_maps.ui.commands

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.pickers.CoroutinePickers
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.sol.units.Distance
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.DistanceUtils.toRelativeDistance
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.dem.DigitalElevationModelLoader
import com.kylecorry.trail_sense.tools.offline_maps.domain.CreateOfflineMapRequest
import com.kylecorry.trail_sense.tools.offline_maps.domain.OfflineMapService
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPack
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPackClient
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.persistence.MapRepo
import java.io.File

/**
 * Offline Maps > + > Download region. The server offers the country cut into USGS 7.5-minute
 * quadrangles; this lists them nearest first, marks the ones already here, and downloads and
 * imports whichever are picked: the map, and the elevation added alongside the rest.
 * Returns true when anything was imported.
 */
class DownloadRegionPackCommand(
    private val context: Context,
    private val mapService: OfflineMapService,
    private val location: Coordinate?
) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val urlKey = context.getString(R.string.pref_region_pack_url)
    private val formatter = FormatService.getInstance(context)
    private val units = UserPreferences(context).baseDistanceUnits

    suspend fun execute(): Boolean {
        val url = serverUrl() ?: return false
        val client = RegionPackClient(url)

        val index = try {
            Alerts.withLoading(context, context.getString(R.string.loading)) { client.index() }
        } catch (e: Exception) {
            e.printStackTrace()
            onMain {
                Alerts.dialog(
                    context,
                    context.getString(R.string.region_pack_unreachable),
                    context.getString(R.string.region_pack_unreachable_message, url),
                    cancelText = null
                )
            }
            return false
        }

        if (index.packs.isEmpty()) {
            onMain { Alerts.toast(context, context.getString(R.string.region_pack_none)) }
            return false
        }

        val packs = index.packs.sortedBy { distanceTo(it) ?: Float.MAX_VALUE }
        val chosen = onMain {
            CoroutinePickers.items(
                context,
                context.getString(R.string.download_region),
                packs.map { describe(it) },
                emptyList()
            )
        } ?: return false
        if (chosen.isEmpty()) {
            return false
        }

        var imported = 0
        for ((i, pack) in chosen.map { packs[it] }.withIndex()) {
            if (download(client, pack, i + 1, chosen.size)) {
                imported++
            } else {
                break
            }
        }
        if (imported > 0) {
            val message = if (imported == 1) {
                context.getString(R.string.region_pack_ready, packs[chosen.first()].name)
            } else {
                context.getString(R.string.region_packs_ready, imported)
            }
            onMain { Alerts.toast(context, message) }
        }
        return imported > 0
    }

    private fun describe(pack: RegionPack): String {
        val parts = mutableListOf(pack.name, formatter.formatFileSize(pack.bytes))
        when (prefs.getString(installedKey(pack), null)) {
            null -> {}
            pack.updated ?: "" -> parts.add(context.getString(R.string.region_pack_have))
            else -> parts.add(context.getString(R.string.region_pack_update))
        }
        if (contains(pack)) {
            parts.add(context.getString(R.string.region_pack_here))
        } else {
            distanceTo(pack)?.let {
                val d = Distance.meters(it).convertTo(units).toRelativeDistance()
                parts.add(formatter.formatDistance(d, 0, false))
            }
        }
        return parts.joinToString("  ·  ")
    }

    private suspend fun download(client: RegionPackClient, pack: RegionPack, number: Int, total: Int): Boolean {
        val dir = File(context.cacheDir, "region_packs").apply { mkdirs() }
        val mapFile = File(dir, "${pack.id}.map")
        val demFile = File(dir, "${pack.id}-dem.zip")
        val title = if (total == 1) {
            context.getString(R.string.downloading_region, pack.name)
        } else {
            context.getString(R.string.downloading_region_of, pack.name, number, total)
        }
        try {
            Alerts.withProgress(context, title) { setProgressOnMain ->
                val main = Handler(Looper.getMainLooper())
                val setProgress = { p: Float -> main.post { setProgressOnMain(p) }; Unit }
                val size = pack.bytes.toFloat().coerceAtLeast(1f)
                client.download(pack.map, mapFile) {
                    setProgress(it * pack.map.bytes / size)
                }
                pack.elevation?.let { elevation ->
                    client.download(elevation, demFile) {
                        setProgress((pack.map.bytes + it * elevation.bytes) / size)
                    }
                }
            }

            Alerts.withLoading(context, context.getString(R.string.loading)) {
                // An earlier copy of this region is replaced, not left alongside the new one.
                val old = MapRepo.getInstance(context).getTrailMaps().filter { it.name == pack.name }
                // The importer copies the file into the app's own storage.
                mapService.createMap(CreateOfflineMapRequest(Uri.fromFile(mapFile), pack.name))
                old.forEach { mapService.delete(it) }
                if (pack.elevation != null) {
                    DigitalElevationModelLoader().add(Uri.fromFile(demFile), pack.id)
                }
            }
            prefs.edit().putString(installedKey(pack), pack.updated ?: "").apply()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            onMain {
                Alerts.dialog(
                    context,
                    context.getString(R.string.region_pack_failed),
                    "${pack.name}: ${e.message ?: ""}",
                    cancelText = null
                )
            }
            return false
        } finally {
            onIO {
                mapFile.delete()
                demFile.delete()
            }
        }
    }

    private fun installedKey(pack: RegionPack) = "region_pack_installed_${pack.id}"

    private fun contains(pack: RegionPack): Boolean {
        val b = pack.bounds ?: return false
        val here = location ?: return false
        return here.longitude in b[0]..b[2] && here.latitude in b[1]..b[3]
    }

    private fun distanceTo(pack: RegionPack): Float? {
        val b = pack.bounds ?: return null
        val here = location ?: return null
        if (here == Coordinate.zero) return null
        val center = Coordinate((b[1] + b[3]) / 2, (b[0] + b[2]) / 2)
        return here.distanceTo(center)
    }

    private suspend fun serverUrl(): String? {
        val saved = prefs.getString(urlKey, null)?.takeIf { it.isNotBlank() }
        if (saved != null) {
            return saved
        }
        val entered = onMain {
            CoroutinePickers.text(
                context,
                context.getString(R.string.region_pack_server),
                context.getString(R.string.region_pack_server_description),
                hint = "https://"
            )
        }?.trim()?.takeIf { it.isNotBlank() } ?: return null
        prefs.edit().putString(urlKey, entered).apply()
        return entered
    }
}
