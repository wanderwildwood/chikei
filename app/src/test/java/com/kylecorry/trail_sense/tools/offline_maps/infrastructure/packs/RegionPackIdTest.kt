package com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RegionPackIdTest {

    @Test
    fun onlyAPlainNameNamesAPack() {
        assertTrue(RegionPackClient.isPlainId("us-nc-millbrook-hollow"))
        assertTrue(RegionPackClient.isPlainId("pack_2"))
        assertFalse(RegionPackClient.isPlainId(".."))
        assertFalse(RegionPackClient.isPlainId("../../databases"))
        assertFalse(RegionPackClient.isPlainId("a/b"))
        assertFalse(RegionPackClient.isPlainId(""))
        assertFalse(RegionPackClient.isPlainId(null))
    }

    @Test
    fun aPackCannotNameAnotherElevationFolder() {
        assertFalse(RegionPackClient.isPlainId("terrain"))
        assertFalse(RegionPackClient.isPlainId("imported"))
        assertFalse(RegionPackClient.isPlainId("area_mf3k2a"))
        assertTrue(RegionPackClient.isPlainId("terrain-west"))
        assertTrue(RegionPackClient.isPlainId("us-area_1"))
    }
}
