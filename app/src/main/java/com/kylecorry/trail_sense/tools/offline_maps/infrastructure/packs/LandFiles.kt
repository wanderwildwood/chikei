package com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs

import android.content.Context
import java.io.File

/**
 * Where each region pack's land file is kept: `land/<id>.json.gz` in the app's own storage,
 * beside `land/<id>.meta` holding the region's name (the name its map was imported under) and
 * bounds, so a lookup can pass over the regions a point is not in without opening them.
 */
class LandFiles(context: Context) {

    private val dir = File(context.filesDir, "land").apply { mkdirs() }

    fun install(pack: RegionPack, downloaded: File) {
        val bounds = pack.bounds ?: return
        val dest = File(dir, "${pack.id}.json.gz")
        downloaded.copyTo(dest, overwrite = true)
        File(dir, "${pack.id}.meta").writeText(pack.name + "\n" + bounds.joinToString(","))
    }

    fun all(): List<Entry> {
        return dir.listFiles { f -> f.name.endsWith(".meta") }.orEmpty().mapNotNull { meta ->
            val lines = meta.readLines()
            val bounds = lines.getOrNull(1)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
            val data = File(dir, meta.name.removeSuffix(".meta") + ".json.gz")
            if (lines.isEmpty() || bounds?.size != 4 || !data.exists()) {
                null
            } else {
                Entry(lines[0], bounds[0], bounds[1], bounds[2], bounds[3], data)
            }
        }
    }

    class Entry(
        val name: String,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
        val file: File
    ) {
        fun contains(latitude: Double, longitude: Double): Boolean {
            return latitude in south..north && longitude in west..east
        }
    }
}
