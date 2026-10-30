package com.kylecorry.trail_sense.shared.views

import com.kylecorry.trail_sense.shared.dem.colors.ElevationColorStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegalFilesTest {
    private fun file(vararg names: String): File {
        // Unit tests run from the app module or from the repository root.
        val module = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        return File(module.parentFile.parentFile, names.joinToString("/"))
    }

    private fun repo(name: String): File {
        val root = if (File("src/main").exists()) File("..") else File(".")
        return File(root, name)
    }

    @Test
    fun theBundledPrivacyTextIsTheRepositoryOne() {
        assertEquals(repo("PRIVACY.md").readText(), file("src", "main", "res", "raw", "privacy.txt").readText())
    }

    @Test
    fun theBundledGplIsTheRepositoryLicence() {
        val bundled = file("src", "main", "res", "raw", "gpl_3_0.txt").readText()
        assertEquals(repo("LICENSE").readText(), bundled)
        assertTrue(bundled.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(bundled.contains("Version 3, 29 June 2007"))
    }

    @Test
    fun privacySaysNothingGoesToABackup() {
        val text = repo("PRIVACY.md").readText()
        assertTrue(text.contains("does not allow Android's backup"))
    }

    @Test
    fun noNonCommercialColourMapRemains() {
        assertFalse(ElevationColorStrategy.entries.any { it.name == "USGS" })
        assertFalse(file("src", "main", "java", "com", "kylecorry", "trail_sense", "shared", "dem", "colors", "USGSElevationColorMap.kt").exists())
    }
}
