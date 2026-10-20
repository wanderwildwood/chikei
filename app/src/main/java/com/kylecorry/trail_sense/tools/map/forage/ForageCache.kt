package com.kylecorry.trail_sense.tools.map.forage

import com.kylecorry.andromeda.json.JsonConvert
import java.io.File

/**
 * What Forage fetched, kept per square and source under [root], so the layer still shows with no
 * signal wherever it has been looked at. A square is asked for again once it is [TTL_MILLIS]
 * old, if there is signal; until then, and whenever there is none, the kept copy is shown.
 *
 * Squares kept with a saved area go in `kept/` and are never thrown out; the rest are, once
 * nobody has looked at them for [PRUNE_MILLIS].
 */
class ForageCache(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val memory = object : LinkedHashMap<String, ForageCellData>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ForageCellData>?): Boolean =
            size > 512
    }

    private fun file(kind: String, cell: ForageCell, kept: Boolean): File =
        File(if (kept) File(root, "kept") else root, "$kind/${cell.key}.json")

    fun get(kind: String, cell: ForageCell): ForageCellData? {
        val name = "$kind/${cell.key}"
        synchronized(memory) { memory[name] }?.let { return it }
        val data = listOf(false, true).mapNotNull { kept ->
            val file = file(kind, cell, kept)
            if (!file.isFile) return@mapNotNull null
            try {
                JsonConvert.fromJson<ForageCellData>(file.readText())
            } catch (_: Exception) {
                null
            }
        }.maxByOrNull { it.fetched } ?: return null
        synchronized(memory) { memory[name] = data }
        return data
    }

    fun put(kind: String, cell: ForageCell, data: ForageCellData, kept: Boolean = false) {
        synchronized(memory) { memory["$kind/${cell.key}"] = data }
        val file = file(kind, cell, kept)
        file.parentFile?.mkdirs()
        // Written aside and moved in, so a square is never left half written
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(JsonConvert.toJson(data))
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
    }

    fun isFresh(data: ForageCellData?): Boolean =
        data != null && clock() - data.fetched in 0 until TTL_MILLIS

    /** Throws out squares not kept with an area and not fetched for a long while. */
    fun prune() {
        val cutoff = clock() - PRUNE_MILLIS
        root.listFiles()?.filter { it.isDirectory && it.name != "kept" }?.forEach { dir ->
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
        synchronized(memory) { memory.clear() }
    }

    companion object {
        const val TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val PRUNE_MILLIS = 120L * 24 * 60 * 60 * 1000
    }
}
