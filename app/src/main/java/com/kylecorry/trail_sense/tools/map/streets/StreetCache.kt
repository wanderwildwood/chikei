package com.kylecorry.trail_sense.tools.map.streets

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.trail_sense.tools.map.forage.ForageCell
import java.io.File

/**
 * The streets fetched, kept per square of the map under [root], so the layer draws with no
 * signal wherever it has been looked at. Streets change slowly: a square is asked for again
 * once it is [TTL_MILLIS] old, if there is signal; until then, and whenever there is none, the
 * kept copy is shown. Squares kept with a saved area are never thrown out.
 */
class StreetCache(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val memory = object : LinkedHashMap<String, StreetCellData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreetCellData>?): Boolean =
            size > 24
    }

    private fun file(cell: ForageCell, kept: Boolean): File =
        File(if (kept) File(root, "kept") else root, "${cell.key}.json")

    fun get(cell: ForageCell): StreetCellData? {
        synchronized(memory) { memory[cell.key] }?.let { return it }
        val data = listOf(false, true).mapNotNull { kept ->
            val file = file(cell, kept)
            if (!file.isFile) return@mapNotNull null
            try {
                JsonConvert.fromJson<StreetCellData>(file.readText())
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.fetched } ?: return null
        synchronized(memory) { memory[cell.key] = data }
        return data
    }

    fun put(cell: ForageCell, data: StreetCellData, kept: Boolean = false) {
        synchronized(memory) { memory[cell.key] = data }
        val file = file(cell, kept)
        file.parentFile?.mkdirs()
        // Written aside and moved in, so a square is never left half written
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(JsonConvert.toJson(data))
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
    }

    fun isFresh(data: StreetCellData?): Boolean =
        data != null && clock() - data.fetched in 0 until TTL_MILLIS

    /** Throws out squares not kept with an area and not fetched for a long while. */
    fun prune() {
        val cutoff = clock() - PRUNE_MILLIS
        root.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        synchronized(memory) { memory.clear() }
    }

    companion object {
        const val TTL_MILLIS = 180L * 24 * 60 * 60 * 1000
        const val PRUNE_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
