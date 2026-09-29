package com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs

import android.content.Context
import androidx.preference.PreferenceManager
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.pickers.CoroutinePickers
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.trail_sense.R

/**
 * Where region packs come from (the map server set in Settings > Offline Maps, asked for on
 * first use) and which of them are already on the phone.
 */
class RegionPackSource(private val context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val urlKey = context.getString(R.string.pref_region_pack_url)

    /** The server's packs, or null if there is no server yet or it can't be reached (the user has been told). */
    suspend fun load(): Pair<RegionPackClient, List<RegionPack>>? {
        val url = serverUrl() ?: return null
        val client = RegionPackClient(url)
        return try {
            val index = Alerts.withLoading(context, context.getString(R.string.loading)) { client.index() }
            if (index.packs.isEmpty()) {
                onMain { Alerts.toast(context, context.getString(R.string.region_pack_none)) }
                null
            } else {
                client to index.packs
            }
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
            null
        }
    }

    fun isInstalled(pack: RegionPack): Boolean {
        return prefs.getString(installedKey(pack), null) != null
    }

    fun isOutdated(pack: RegionPack): Boolean {
        val installed = prefs.getString(installedKey(pack), null) ?: return false
        return installed != (pack.updated ?: "")
    }

    fun markInstalled(pack: RegionPack) {
        prefs.edit().putString(installedKey(pack), pack.updated ?: "").apply()
    }

    private fun installedKey(pack: RegionPack) = "region_pack_installed_${pack.id}"

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
