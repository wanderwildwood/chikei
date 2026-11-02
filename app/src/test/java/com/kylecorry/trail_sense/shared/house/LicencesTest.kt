package com.kylecorry.trail_sense.shared.house

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LicencesTest {
    private val data = LicencesData(
        "chikei", SOURCE_URL, "GPL-3.0-only", LICENCE_COMPONENTS,
        LICENCE_RAW.map { LicenceText(it.first, it.first, it.second) }, emptyList(),
    )

    @Test fun theDataHasNoProblems() {
        assertEquals(emptyList<String>(), licencesProblems(data))
        assertTrue(LICENCE_COMPONENTS.isNotEmpty())
    }

    @Test fun theSourceLinkIsTheRepo() {
        assertEquals("https://github.com/wanderwildwood/chikei", SOURCE_URL)
    }

    @Test fun gplIsFirstAndEveryTextIsBundled() {
        assertEquals("gpl", orderedTexts(data.texts).first().id)
        val raw = File("src/main/res/raw")
        assertTrue(raw.listFiles().orEmpty().count { it.extension == "txt" } >= LICENCE_RAW.size)
        assertTrue(licenceParagraphs(File(raw, raw.list().orEmpty().first { it.contains("gpl") }).readText()).size > 10)
    }
}
