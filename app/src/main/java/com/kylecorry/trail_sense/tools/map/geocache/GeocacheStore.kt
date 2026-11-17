package com.kylecorry.trail_sense.tools.map.geocache

import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.trail_sense.tools.map.forage.ForageCell
import java.io.File

/**
 * What the Geocaches layer keeps under [root]: the caches opened from GPX files and the user's
 * visits (both small, both go into backups), and Opencaching's squares under `oc/` (fetched
 * again on use; `oc/kept/` holds the squares of saved areas, never thrown out).
 */
class GeocacheStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val importedFile = File(root, "imported.json")
    private val visitsFile = File(root, "visits.json")
    private val ocRoot = File(root, "oc")

    @Volatile
    private var imported: List<Geocache>? = null
    @Volatile
    private var visits: List<CacheVisit>? = null

    private val cells = object : LinkedHashMap<String, CacheCellData>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheCellData>?): Boolean = size > 256
    }

    @Synchronized
    fun imported(): List<Geocache> {
        imported?.let { return it }
        val read = read<ImportedCaches>(importedFile)?.caches.orEmpty()
        imported = read
        return read
    }

    /** Adds [caches], replacing any with the same code. Returns how many there are now. */
    @Synchronized
    fun addImported(caches: List<Geocache>): Int {
        val byCode = LinkedHashMap<String, Geocache>()
        imported().forEach { byCode[it.code] = it }
        caches.forEach { byCode[it.code] = it }
        val all = byCode.values.toList()
        write(importedFile, ImportedCaches(all))
        imported = all
        // A "My Finds" file brings the finds the site already knows; they are kept as visits too
        val known = visits().map { it.code }.toSet()
        val finds = caches.filter { it.foundOnSite && it.code !in known }
            .map { CacheVisit(it.code, VisitKinds.FOUND, clock(), name = it.name, exported = true) }
        if (finds.isNotEmpty()) saveVisits(visits() + finds)
        return all.size
    }

    @Synchronized
    fun clearImported() {
        importedFile.delete()
        imported = emptyList()
    }

    @Synchronized
    fun visits(): List<CacheVisit> {
        visits?.let { return it }
        val read = read<CacheVisits>(visitsFile)?.visits.orEmpty()
        visits = read
        return read
    }

    /** The latest found or not found at [code], which is what the map shows. */
    fun state(code: String): CacheVisit? = visits().lastOrNull { it.code == code && it.kind != VisitKinds.NOTE }

    @Synchronized
    fun addVisit(visit: CacheVisit) = saveVisits(visits() + visit)

    /** Takes back the latest found or not found at [code] (a wrong tap), keeping notes. */
    @Synchronized
    fun undoState(code: String) {
        val all = visits()
        val index = all.indexOfLast { it.code == code && it.kind != VisitKinds.NOTE }
        if (index >= 0) saveVisits(all.filterIndexed { i, _ -> i != index })
    }

    @Synchronized
    fun markExported(exported: List<CacheVisit>) {
        val set = exported.map { it.code to it.time }.toSet()
        saveVisits(visits().map {
            if (it.code to it.time in set) CacheVisit(it.code, it.kind, it.time, it.note, it.name, true) else it
        })
    }

    private fun saveVisits(all: List<CacheVisit>) {
        write(visitsFile, CacheVisits(all))
        visits = all
    }

    private fun cellFile(site: String, cell: ForageCell, kept: Boolean): File =
        File(if (kept) File(ocRoot, "kept") else ocRoot, "$site/${cell.key}.json")

    fun cell(site: String, cell: ForageCell): CacheCellData? {
        val name = "$site/${cell.key}"
        synchronized(cells) { cells[name] }?.let { return it }
        val data = listOf(false, true).mapNotNull { read<CacheCellData>(cellFile(site, cell, it)) }
            .maxByOrNull { it.fetched } ?: return null
        synchronized(cells) { cells[name] = data }
        return data
    }

    fun putCell(site: String, cell: ForageCell, data: CacheCellData, kept: Boolean) {
        synchronized(cells) { cells["$site/${cell.key}"] = data }
        write(cellFile(site, cell, kept), data)
    }

    fun isFresh(data: CacheCellData?): Boolean = data != null && clock() - data.fetched in 0 until TTL_MILLIS

    /** Throws out squares not kept with an area and not fetched for a long while. */
    fun prune() {
        val cutoff = clock() - PRUNE_MILLIS
        ocRoot.listFiles()?.filter { it.isDirectory && it.name != "kept" }?.forEach { dir ->
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
        synchronized(cells) { cells.clear() }
    }

    private inline fun <reified T> read(file: File): T? {
        if (!file.isFile) return null
        return try {
            JsonConvert.fromJson<T>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Written aside and moved in, so a file is never left half written. */
    private fun write(file: File, value: Any) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(JsonConvert.toJson(value))
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
    }

    companion object {
        /** Caches change little, but are archived now and then: a square is asked for again after a week. */
        const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val PRUNE_MILLIS = 120L * 24 * 60 * 60 * 1000
    }
}
