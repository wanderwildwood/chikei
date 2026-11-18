package com.kylecorry.trail_sense.shared.dem

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigitalElevationModelLoaderTest {

    @Test
    fun anEarlierImportIsFoundDirectlyInTheDemFolder() {
        assertTrue(DigitalElevationModelLoader.isRootFile("dem/12.webp"))
        assertTrue(DigitalElevationModelLoader.isRootFile("dem/n40_w075.webp"))
    }

    @Test
    fun foldersUnderDemAreNotAnEarlierImport() {
        assertFalse(DigitalElevationModelLoader.isRootFile("dem/imported/12.webp"))
        assertFalse(DigitalElevationModelLoader.isRootFile("dem/terrain/12_1205_1539.png"))
        assertFalse(DigitalElevationModelLoader.isRootFile("dem/area_abc/12_1205_1539.png"))
        assertFalse(DigitalElevationModelLoader.isRootFile("dem/pack-01/3.webp"))
        assertFalse(DigitalElevationModelLoader.isRootFile("other/12.webp"))
    }
}
