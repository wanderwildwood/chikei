package com.kylecorry.trail_sense.tools.map.geocache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Made-up caches near Boulder, Colorado, in the shapes Pocket Queries and OpenSAK write. */
class GeocacheGpxTest {

    private val pocketQuery = """<?xml version="1.0" encoding="utf-8"?>
<gpx xmlns="http://www.topografix.com/GPX/1/0" xmlns:groundspeak="http://www.groundspeak.com/cache/1/0/1" version="1.0">
  <wpt lat="40.015" lon="-105.270">
    <time>2019-05-04T00:00:00Z</time>
    <name>GC1ABCD</name>
    <desc>Creekside Ammo Can by Ada Whitlock, Traditional Cache (1.5/2)</desc>
    <url>https://coord.info/GC1ABCD</url>
    <sym>Geocache</sym>
    <type>Geocache|Traditional Cache</type>
    <groundspeak:cache id="1" available="True" archived="False">
      <groundspeak:name>Creekside Ammo Can</groundspeak:name>
      <groundspeak:placed_by>Ada Whitlock</groundspeak:placed_by>
      <groundspeak:type>Traditional Cache</groundspeak:type>
      <groundspeak:container>Regular</groundspeak:container>
      <groundspeak:difficulty>1.5</groundspeak:difficulty>
      <groundspeak:terrain>2</groundspeak:terrain>
      <groundspeak:short_description html="False">A short walk.</groundspeak:short_description>
      <groundspeak:long_description html="True">&lt;p&gt;Under the &lt;b&gt;big&lt;/b&gt; rock.&lt;/p&gt;</groundspeak:long_description>
      <groundspeak:encoded_hints>Look low</groundspeak:encoded_hints>
    </groundspeak:cache>
  </wpt>
  <wpt lat="40.020" lon="-105.280">
    <name>GC2EFGH</name>
    <sym>Geocache Found</sym>
    <type>Geocache|Unknown Cache</type>
    <groundspeak:cache id="2" available="False" archived="False">
      <groundspeak:name>Tomas's Riddle</groundspeak:name>
      <groundspeak:placed_by>Tomas Reyes</groundspeak:placed_by>
      <groundspeak:type>Unknown Cache</groundspeak:type>
      <groundspeak:container>Micro</groundspeak:container>
      <groundspeak:difficulty>4</groundspeak:difficulty>
      <groundspeak:terrain>1</groundspeak:terrain>
      <groundspeak:encoded_hints></groundspeak:encoded_hints>
    </groundspeak:cache>
  </wpt>
</gpx>"""

    private val waypoints = """<?xml version="1.0" encoding="utf-8"?>
<gpx xmlns="http://www.topografix.com/GPX/1/0" version="1.0">
  <wpt lat="40.010" lon="-105.265">
    <name>PK1ABCD</name>
    <desc>Parking</desc>
    <type>Waypoint|Parking Area</type>
  </wpt>
  <wpt lat="40.0" lon="-105.0">
    <name>PKZZZZZ</name>
    <type>Waypoint|Parking Area</type>
  </wpt>
</gpx>"""

    @Test
    fun readsAPocketQuery() {
        val caches = GeocacheGpx.read(pocketQuery.byteInputStream())
        assertEquals(2, caches.size)
        val first = caches[0]
        assertEquals("GC1ABCD", first.code)
        assertEquals("Creekside Ammo Can", first.name)
        assertEquals(GeocacheKinds.TRADITIONAL, first.kind)
        assertEquals("Regular", first.size)
        assertEquals(1.5f, first.difficulty)
        assertEquals(2f, first.terrain)
        assertEquals("Ada Whitlock", first.owner)
        assertEquals("Look low", first.hint)
        assertEquals("A short walk.", first.summary)
        assertTrue(first.isHtml)
        assertTrue(first.description!!.contains("<b>big</b>"))
        assertEquals("https://coord.info/GC1ABCD", first.url)
        assertEquals("2019-05-04", first.placed)
        assertTrue(first.available)
        assertFalse(first.foundOnSite)

        val second = caches[1]
        assertEquals(GeocacheKinds.MYSTERY, second.kind)
        assertFalse(second.available)
        assertTrue(second.foundOnSite)
        assertNull(second.hint)
    }

    @Test
    fun readsAPocketQueryZipAndJoinsItsWaypoints() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            // The waypoints file first, as a zip may hold it
            out.putNextEntry(ZipEntry("12345-wpts.gpx")); out.write(waypoints.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("12345.gpx")); out.write(pocketQuery.toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("readme.txt")); out.write("x".toByteArray()); out.closeEntry()
        }
        val caches = GeocacheGpx.read(zip.toByteArray().inputStream())
        assertEquals(2, caches.size)
        val wpts = caches.first { it.code == "GC1ABCD" }.waypoints!!
        assertEquals(1, wpts.size)
        assertEquals("PK1ABCD", wpts[0].code)
        assertEquals("Parking Area", wpts[0].typeName)
        assertNull(caches.first { it.code == "GC2EFGH" }.waypoints)
    }

    @Test
    fun tellsCachesFromTracks() {
        assertTrue(GeocacheGpx.looksLikeCaches(pocketQuery.take(600)))
        assertFalse(GeocacheGpx.looksLikeCaches("<gpx><trk><name>Walk</name><trkseg></trkseg></trk></gpx>"))
    }

    @Test
    fun refusesEntities() {
        val evil = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
<gpx><wpt lat="1" lon="2"><name>GC1</name><type>Geocache|Traditional Cache</type><desc>&x;</desc></wpt></gpx>"""
        val result = runCatching { GeocacheGpx.read(evil.byteInputStream()) }
        assertTrue(result.isFailure || result.getOrNull()!!.none { (it.name).contains("root") })
    }

    @Test
    fun kinds() {
        assertEquals(GeocacheKinds.MULTI, GeocacheKinds.of("Multi-cache"))
        assertEquals(GeocacheKinds.MYSTERY, GeocacheKinds.of("Quiz"))
        assertEquals(GeocacheKinds.EVENT, GeocacheKinds.of("Cache In Trash Out Event"))
        assertEquals(GeocacheKinds.EARTH, GeocacheKinds.of("Geocache|Earthcache"))
        assertEquals(GeocacheKinds.OTHER, GeocacheKinds.of(null))
    }
}
