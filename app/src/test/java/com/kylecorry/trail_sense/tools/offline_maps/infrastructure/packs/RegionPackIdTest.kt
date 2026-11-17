package com.kylecorry.trail_sense.tools.offline_maps.infrastructure.packs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RegionPackIdTest {

    @Test
    fun onlyAPlainNameNamesAPack() {
        assertTrue(RegionPackClient.isPlainId("us-nc-asheville"))
        assertTrue(RegionPackClient.isPlainId("pack_2"))
        assertFalse(RegionPackClient.isPlainId(".."))
        assertFalse(RegionPackClient.isPlainId("../../databases"))
        assertFalse(RegionPackClient.isPlainId("a/b"))
        assertFalse(RegionPackClient.isPlainId(""))
        assertFalse(RegionPackClient.isPlainId(null))
    }
}
