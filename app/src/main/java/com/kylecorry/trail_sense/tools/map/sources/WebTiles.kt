package com.kylecorry.trail_sense.tools.map.sources

import android.content.Context
import android.net.http.HttpResponseCache
import com.kylecorry.trail_sense.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The only way the map reaches the network: one map tile or one elevation tile at a time, at
 * most three at once across every source, each request naming the app.
 *
 * Viewed tiles go through Android's HTTP cache, which keeps them as long as each server's
 * Cache-Control says and asks again with If-None-Match once they are stale. With no signal a
 * tile still in that cache is shown however old it is, which is how a place looked at before
 * stays on the map. Nothing here fetches a tile that was not asked for.
 */
object WebTiles {

    /** OpenTopoMap and The National Map ask that an app say who it is; this is who. */
    val USER_AGENT = "Topo/${BuildConfig.VERSION_NAME} (Android; https://github.com/wanderwildwood/chikei)"

    private const val CACHE_BYTES = 100L * 1024 * 1024
    private val permits = Semaphore(3)

    @Volatile
    private var cacheInstalled = false

    /** When the network last failed. For a short while after, the map goes straight to what is kept. */
    @Volatile
    private var lastFailure = 0L
    private const val RETRY_AFTER_MILLIS = 20_000L

    sealed class Result {
        class Found(val bytes: ByteArray) : Result()

        /** The server answered that there is no such tile (outside its area, or past its zoom). */
        data object Missing : Result()

        /** No network and nothing kept: nothing to say about this tile yet. */
        data object Unreachable : Result()
    }

    fun install(context: Context) {
        if (cacheInstalled) return
        synchronized(this) {
            if (cacheInstalled) return
            if (HttpResponseCache.getInstalled() == null) {
                try {
                    HttpResponseCache.install(File(context.cacheDir, "web_tiles"), CACHE_BYTES)
                } catch (_: IOException) {
                    // Without a cache every view asks the server again; the map still works.
                }
            }
            cacheInstalled = true
        }
    }

    /** Whether the network failed in the last few seconds (so a save would fail too). */
    fun isOffline(): Boolean = System.currentTimeMillis() - lastFailure < RETRY_AFTER_MILLIS

    /** A save starts by trying the network afresh, whatever the map found a moment ago. */
    fun forgetFailures() {
        lastFailure = 0L
    }

    /**
     * Gets [url] from the server (or from the HTTP cache while the server says it is still
     * fresh). [useCache] false is for saving and for elevation, which are kept elsewhere.
     * Returns [Result.Unreachable] at once while the network is known to be down.
     */
    suspend fun fromNetwork(context: Context, url: String, useCache: Boolean = true): Result =
        withContext(Dispatchers.IO) {
            install(context)
            if (isOffline()) {
                return@withContext Result.Unreachable
            }
            try {
                permits.withPermit { request(url, onlyIfCached = false, useCache = useCache) }
                    ?: Result.Unreachable
            } catch (_: IOException) {
                lastFailure = System.currentTimeMillis()
                Result.Unreachable
            }
        }

    /** Gets [url] only if the HTTP cache holds it, however old; never touches the network. */
    suspend fun fromCache(context: Context, url: String): Result = withContext(Dispatchers.IO) {
        install(context)
        try {
            when (val result = request(url, onlyIfCached = true, useCache = true)) {
                is Result.Found -> result
                else -> Result.Unreachable
            }
        } catch (_: IOException) {
            Result.Unreachable
        }
    }

    private fun request(url: String, onlyIfCached: Boolean, useCache: Boolean): Result? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.useCaches = useCache
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (onlyIfCached) {
                // Any age will do: with no signal an old tile beats a blank square.
                connection.addRequestProperty("Cache-Control", "only-if-cached, max-stale=2147483647")
            }
            val code = connection.responseCode
            return when {
                code in 200..299 -> Result.Found(connection.inputStream.use { it.readBytes() })
                code == 404 || code == 410 -> Result.Missing
                // 504 is the cache saying it has nothing; anything else is a server's bad moment.
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    }
}
