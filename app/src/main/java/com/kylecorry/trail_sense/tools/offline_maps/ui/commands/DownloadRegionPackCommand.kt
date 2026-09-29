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
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.dem.DigitalElevationModelLoader
import com.kylecorry.trail_sense.shared.io.FileSubsystem
import com.kylecorry.trail_sense.tools.offline_maps.domain.CreateOfflineMapRequest
import com.kylecorry.trail_sense.tools.offline_maps.domain.OfflineMapService
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPack
import com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs.RegionPackClient
import java.io.File

/**
 * Offline Maps > + > Download region: pick a region from the pack server, download its map
 * and elevation, and import both. Returns true when something was imported.
 */
class DownloadRegionPackCommand(
    private val context: Context,
    private val mapService: OfflineMapService
) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val urlKey = context.getString(R.string.pref_region_pack_url)

    suspend fun execute(): Boolean {
        val url = serverUrl() ?: return false
        val client = RegionPackClient(url)

        val index = try {
            withLoading { client.index() }
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

        val formatter = FormatService.getInstance(context)
        val chosen = onMain {
            CoroutinePickers.item(
                context,
                context.getString(R.string.download_region),
                index.packs.map {
                    val size = formatter.formatFileSize(it.bytes)
                    if (it.updated != null) "${it.name}  ·  $size  ·  ${it.updated}" else "${it.name}  ·  $size"
                }
            )
        } ?: return false

        return download(client, index.packs[chosen])
    }

    private suspend fun download(client: RegionPackClient, pack: RegionPack): Boolean {
        val dir = File(context.cacheDir, "region_packs").apply { mkdirs() }
        val mapFile = File(dir, "${pack.id}.map")
        val demFile = File(dir, "${pack.id}-dem.zip")
        try {
            Alerts.withProgress(context, context.getString(R.string.downloading_region, pack.name)) { setProgressOnMain ->
                val main = Handler(Looper.getMainLooper())
                val setProgress = { p: Float -> main.post { setProgressOnMain(p) }; Unit }
                val total = pack.bytes.toFloat().coerceAtLeast(1f)
                client.download(pack.map, mapFile) {
                    setProgress(it * pack.map.bytes / total)
                }
                pack.elevation?.let { elevation ->
                    client.download(elevation, demFile) {
                        setProgress((pack.map.bytes + it * elevation.bytes) / total)
                    }
                }
            }

            withLoading {
                // The importer copies the file into the app's own storage.
                mapService.createMap(CreateOfflineMapRequest(Uri.fromFile(mapFile), pack.name))
                if (pack.elevation != null) {
                    DigitalElevationModelLoader().load(Uri.fromFile(demFile)).collect {}
                }
            }
            onMain { Alerts.toast(context, context.getString(R.string.region_pack_ready, pack.name)) }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            onMain {
                Alerts.dialog(
                    context,
                    context.getString(R.string.region_pack_failed),
                    e.message ?: "",
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

    private suspend inline fun <T> withLoading(crossinline block: suspend () -> T): T {
        return Alerts.withLoading(context, context.getString(R.string.loading)) { block() }
    }
}
