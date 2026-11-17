package com.kylecorry.trail_sense.shared.dem

import android.net.Uri
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.andromeda.files.ZipUtils
import com.kylecorry.andromeda.files.ZipUtils.unzip
import com.kylecorry.andromeda.json.JsonConvert
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.trail_sense.main.persistence.AppDatabase
import com.kylecorry.trail_sense.shared.ProguardIgnore
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.io.FileSubsystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock

class DigitalElevationModelFile(
    val filename: String,
    val width: Int,
    val height: Int,
    val a: Double,
    val b: Double,
    val longitude_start: Double,
    val longitude_end: Double,
    val latitude_start: Double,
    val latitude_end: Double,
) : ProguardIgnore

class DigitalElevationModelIndex(
    val resolution_arc_seconds: Int,
    val compression_method: String,
    val version: String?,
    val files: List<DigitalElevationModelFile>,
    val has_water_mask: Boolean? = null
) : ProguardIgnore

class CompressedDigitalElevationModelIndex(
    val r: Int,
    val c: String,
    val v: String?,
    val w: Int,
    val h: Int,
    val f: List<List<Number>>,
    val wm: Boolean? = null
) : ProguardIgnore {
    fun toDigitalElevationModelIndex(): DigitalElevationModelIndex {
        return DigitalElevationModelIndex(
            r,
            c,
            v,
            f.map {
                DigitalElevationModelFile(
                    "${it[0].toInt()}.webp",
                    if (it.size > 7) it[7].toInt() else w,
                    if (it.size > 7) it[8].toInt() else h,
                    it[1].toDouble(),
                    it[2].toDouble(),
                    it[4].toDouble(),
                    it[6].toDouble(),
                    it[3].toDouble(),
                    it[5].toDouble()
                )
            },
            wm
        )
    }
}

class DigitalElevationModelLoader {

    /**
     * Topo: a model imported in Altimeter settings goes in a folder of its own beside the region
     * packs' and the saved areas' elevation, replacing only an earlier import. Upstream's import
     * cleared the whole dem folder and table, and with them every pack's and area's elevation.
     */
    fun load(source: Uri): Flow<Float> = flow {
        emit(0f)
        add(source, IMPORTED)
        emit(1f)
    }

    /**
     * Adds one region's model alongside whatever is already loaded, replacing only an earlier
     * copy of the same [key]. Region packs cover the country one quadrangle at a time, so
     * neighbouring cells have to accumulate rather than each replacing the last.
     */
    suspend fun add(source: Uri, key: String) = onIO {
        val files = DependencyRegistry.get<FileSubsystem>()
        val database = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao()
        val prefs = DependencyRegistry.get<UserPreferences>()
        val folder = "dem/$key"

        DEMRepo.lock.withLock {
            val dir = files.getDirectory(folder, create = true)
            dir.deleteRecursively()
            files.stream(source)?.use {
                ZipUtils.unzip(it, files.getDirectory(folder, create = true), MAX_ZIP_FILE_COUNT)
            } ?: throw IllegalArgumentException("Unable to read the elevation model")

            val indexFile = files.get("$folder/index.json")
            if (!indexFile.exists()) {
                files.getDirectory(folder).deleteRecursively()
                throw IllegalArgumentException("The provided zip file does not contain a valid DEM index.json file.")
            }
            val tiles = getTilesFromIndex(indexFile.readText(), directory = folder)
            database.getAll()
                .filter { it.filename.startsWith("$folder/") }
                .forEach { database.delete(it) }
            database.upsert(tiles)
            indexFile.delete()
            prefs.altimeter.isDigitalElevationModelLoaded = true
            recordVersion()
        }

        DEM.invalidateCache()
    }

    /**
     * Adds tiles already written under [folder] (the Terrain Tiles Topo fetches or saves),
     * replacing any rows for the same files.
     */
    suspend fun addTiles(folder: String, tiles: List<DigitalElevationModelEntity>) = onIO {
        val database = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao()
        val prefs = DependencyRegistry.get<UserPreferences>()
        DEMRepo.lock.withLock {
            val paths = tiles.map { it.filename }.toSet()
            database.getAll()
                .filter { it.filename.startsWith("$folder/") && it.filename in paths }
                .forEach { database.delete(it) }
            database.upsert(tiles)
            prefs.altimeter.isDigitalElevationModelLoaded = true
            recordVersion()
        }
        DEM.invalidateCache()
    }

    /** Removes these tiles (paths relative to the app's files) and their files. */
    suspend fun removeFiles(paths: List<String>) = onIO {
        val files = DependencyRegistry.get<FileSubsystem>()
        val database = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao()
        DEMRepo.lock.withLock {
            val set = paths.toSet()
            database.getAll().filter { it.filename in set }.forEach { database.delete(it) }
            paths.forEach { files.get(it).delete() }
            recordVersion()
        }
        DEM.invalidateCache()
    }

    /** Removes every tile under [folder], and the folder. */
    suspend fun removeFolder(folder: String) = onIO {
        val files = DependencyRegistry.get<FileSubsystem>()
        val database = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao()
        DEMRepo.lock.withLock {
            database.getAll().filter { it.filename.startsWith("$folder/") }.forEach { database.delete(it) }
            files.getDirectory(folder).deleteRecursively()
            recordVersion()
        }
        DEM.invalidateCache()
    }

    /**
     * DEMRepo.clean() wipes every model when dem/version.txt disagrees with the table, which is
     * upstream's guard against a restored backup. Models added piece by piece (region packs,
     * Terrain Tiles) carry different versions, so the file is kept equal to what the table
     * reports after every change; without this, a pack's elevation was wiped on next use.
     * Call while holding DEMRepo.lock.
     */
    private suspend fun recordVersion() {
        val files = DependencyRegistry.get<FileSubsystem>()
        val database = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao()
        val prefs = DependencyRegistry.get<UserPreferences>()
        val version = database.getVersion()
        if (version == null) {
            files.get("dem/version.txt").delete()
            prefs.altimeter.isDigitalElevationModelLoaded = false
        } else {
            files.get("dem/version.txt", create = true).writeText(version)
        }
    }

    /** Removes the imported model only; the packs' and saved areas' elevation stays. */
    suspend fun clear() = onIO {
        val prefs = DependencyRegistry.get<UserPreferences>()
        val database = DependencyRegistry.get<AppDatabase>().digitalElevationModelDao()
        removeFolder("dem/$IMPORTED")
        prefs.altimeter.isDigitalElevationModelLoaded = database.getAll().isNotEmpty()
        DEM.invalidateCache()
    }

    companion object {
        /** The folder under dem/ that a model imported in Altimeter settings goes in. */
        private const val IMPORTED = "imported"
        private const val MAX_ZIP_FILE_COUNT = 1000


        fun getTilesFromIndex(
            indexText: String,
            isCompressed: Boolean = false,
            directory: String = "dem"
        ): List<DigitalElevationModelEntity> {
            val parsed = if (isCompressed) {
                JsonConvert.fromJson<CompressedDigitalElevationModelIndex>(indexText)
                    ?.toDigitalElevationModelIndex()
                    ?: throw IllegalArgumentException("The provided zip file does not contain a valid compressed DEM index.json file.")
            } else {
                JsonConvert.fromJson<DigitalElevationModelIndex>(indexText)
                    ?: throw IllegalArgumentException("The provided zip file does not contain a valid DEM index.json file.")
            }
            return parsed.files.map {
                DigitalElevationModelEntity(
                    parsed.resolution_arc_seconds,
                    parsed.compression_method,
                    parsed.version ?: "",
                    "$directory/${it.filename}",
                    it.width,
                    it.height,
                    it.a,
                    it.b,
                    it.latitude_start,
                    it.latitude_end,
                    it.longitude_end,
                    it.longitude_start,
                    parsed.has_water_mask ?: false
                )
            }
        }

    }

}
