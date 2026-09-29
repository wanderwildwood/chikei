package com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.dem.DigitalElevationModelLoader
import com.kylecorry.trail_sense.tools.offline_maps.domain.CreateOfflineMapRequest
import com.kylecorry.trail_sense.tools.offline_maps.domain.OfflineMapService
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.persistence.MapRepo
import java.io.File

/**
 * Downloads region packs one after another and imports each: the map (replacing an earlier
 * copy of the same region) and its elevation, added alongside the other regions'.
 */
class RegionPackDownloader(
    private val context: Context,
    private val mapService: OfflineMapService,
    private val source: RegionPackSource = RegionPackSource(context)
) {

    /** Returns how many were imported; stops at the first failure, which the user is shown. */
    suspend fun download(client: RegionPackClient, packs: List<RegionPack>): Int {
        var imported = 0
        for ((i, pack) in packs.withIndex()) {
            if (!download(client, pack, i + 1, packs.size)) {
                break
            }
            imported++
        }
        if (imported > 0) {
            val message = if (imported == 1) {
                context.getString(R.string.region_pack_ready, packs.first().name)
            } else {
                context.getString(R.string.region_packs_ready, imported)
            }
            onMain { Alerts.toast(context, message) }
        }
        return imported
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
            source.markInstalled(pack)
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
}
