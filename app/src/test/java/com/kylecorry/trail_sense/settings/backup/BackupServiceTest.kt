package com.kylecorry.trail_sense.settings.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BackupServiceTest {

    @Test
    fun databaseAndSettingsComeBeforeTheFilesFolder() {
        val version = File("files/app-version-1.txt")
        val database = File("databases")
        val prefs = File("shared_prefs")
        val files = File("files")
        val order = BackupService.backupOrder(version, database, prefs, files)
        assertEquals(listOf(version, database, prefs, files), order)
    }

    @Test
    fun aMissingDatabaseFolderIsLeftOut() {
        val order = BackupService.backupOrder(File("v"), null, File("p"), File("f"))
        assertEquals(listOf(File("v"), File("p"), File("f")), order)
    }

    @Test
    fun mapTilesAndLayerCachesAreLeftOut() {
        // Each holds a file per tile or square; together they passed the restore's file limit
        for (name in listOf("topo_areas", "forage", "streets")) {
            assertTrue(name in BackupService.REFETCHABLE_DIRECTORIES, name)
        }
    }
}
