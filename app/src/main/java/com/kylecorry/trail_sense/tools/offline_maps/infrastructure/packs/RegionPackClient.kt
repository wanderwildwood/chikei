package com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.trail_sense.shared.ProguardIgnore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A region pack server is a plain directory of static files, built by mapdata/publish.py:
 *
 *     <base>/packs.json
 *     <base>/<id>/<id>.map          Mapsforge map: OSM, public land, forest roads, contours
 *     <base>/<id>/<id>-dem.zip      elevation, in the altimeter's DEM import format
 */
class RegionPackIndex(
    val packs: List<RegionPack>
) : ProguardIgnore

class RegionPack(
    val id: String,
    val name: String,
    val updated: String?,
    /** [west, south, east, north]; a quadrangle's corners */
    val bounds: List<Double>?,
    val map: RegionPackFile,
    val elevation: RegionPackFile?
) : ProguardIgnore {
    val bytes: Long
        get() = map.bytes + (elevation?.bytes ?: 0)
}

class RegionPackFile(
    val path: String,
    val bytes: Long,
    val sha256: String
) : ProguardIgnore

class RegionPackClient(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')

    suspend fun index(): RegionPackIndex = onIO {
        val text = open(url("packs.json")).use { it.inputStream.bufferedReader().readText() }
        JsonConvert.fromJson<RegionPackIndex>(text) ?: throw IOException("packs.json is not a pack index")
    }

    /**
     * Downloads [file] into [dest], reporting progress 0..1, and fails unless the bytes hash to
     * the index's sha256. A partial file never takes the destination's name. Progress is
     * reported from the download thread, once per whole percent.
     */
    suspend fun download(file: RegionPackFile, dest: File, onProgress: (Float) -> Unit) = onIO {
        val part = File(dest.parentFile, dest.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        open(url(file.path)).use { connection ->
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                        val fraction = (total.toFloat() / file.bytes.coerceAtLeast(1)).coerceAtMost(1f)
                        val percent = (fraction * 100).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(fraction)
                        }
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${file.path}: checksum mismatch")
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("${file.path}: could not save")
        }
    }

    private fun url(path: String): URL {
        return URL("$base/${path.trimStart('/')}")
    }

    private fun open(url: URL): AutoCloseableConnection {
        val connection = url.openConnection() as HttpURLConnection
        // Finite timeouts: a stalled download on a trailhead's last bar of signal must fail
        // rather than hold the loading dialog open forever.
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("$url: HTTP $code")
        }
        return AutoCloseableConnection(connection)
    }

    private class AutoCloseableConnection(private val connection: HttpURLConnection) : AutoCloseable {
        val inputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }
}
