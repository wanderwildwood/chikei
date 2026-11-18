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
 *     <base>/<id>/<id>-land.json.gz whose land each part of it is (optional; see land_pack.py)
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
    val elevation: RegionPackFile?,
    val land: RegionPackFile? = null
) : ProguardIgnore {
    val bytes: Long
        get() = map.bytes + (elevation?.bytes ?: 0) + (land?.bytes ?: 0)
}

class RegionPackFile(
    val path: String,
    val bytes: Long,
    val sha256: String
) : ProguardIgnore

class RegionPackClient(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')

    suspend fun index(): RegionPackIndex = onIO {
        // An index is a few kilobytes; a reply far bigger is not one.
        val bytes = open(url("packs.json")).use { it.inputStream.use { input -> readAtMost(input, MAX_INDEX_BYTES + 1) } }
        if (bytes.size > MAX_INDEX_BYTES) throw IOException("packs.json is too big")
        val index = JsonConvert.fromJson<RegionPackIndex>(bytes.decodeToString())
            ?: throw IOException("packs.json is not a pack index")
        // A pack's id names its files and its elevation folder, so only a plain name will do:
        // one like "../databases" would point a delete outside them.
        RegionPackIndex(index.packs.filter { isPlainId(it.id) })
    }

    /**
     * Downloads [file] into [dest], reporting progress 0..1, and fails unless the bytes hash to
     * the index's sha256. A partial file never takes the destination's name. Progress is
     * reported from the download thread, once per whole percent.
     */
    suspend fun download(file: RegionPackFile, dest: File, onProgress: (Float) -> Unit) = onIO {
        val part = File(dest.parentFile, dest.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open(url(file.path)).use { connection ->
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            // More than the index said (with room for a rounding): not this file.
                            if (file.bytes > 0 && total > file.bytes + file.bytes / 100 + 64 * 1024) {
                                throw IOException("${file.path}: bigger than the index says")
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
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
        } catch (e: Exception) {
            part.delete()
            throw e
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

    companion object {
        private const val MAX_INDEX_BYTES = 4 * 1024 * 1024
        private val PLAIN_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * Whether [id] can name a pack's files: letters, digits, - and _ only, and not one of
         * the elevation folders beside the packs' own under dem/ (the viewed terrain, the model
         * imported in Altimeter settings, a saved area's), which installing the pack would replace.
         */
        fun isPlainId(id: String?): Boolean =
            id != null && PLAIN_ID.matches(id) && id !in RESERVED_IDS && !id.startsWith("area_")

        private val RESERVED_IDS = setOf("terrain", "imported")

        private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
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
        // Packs are kept as maps once imported; the map tiles' HTTP cache is no place for them.
        connection.useCaches = false
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
