package com.kylecorry.trail_sense.tools.map.geocache

import com.kylecorry.trail_sense.tools.map.forage.ForageBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLDecoder

class OpencachingTest {

    private val us = Opencaching.sites.first { it.id == "us" }

    @Test
    fun keysFromTheFile() {
        val keys = Opencaching.keys("us=AbCd1234EfGh\nde = bad key!\nxx=AbCd1234EfGh\n\npl=Zz99Yy88Xx77")
        assertEquals(mapOf("us" to "AbCd1234EfGh", "pl" to "Zz99Yy88Xx77"), keys)
        assertTrue(Opencaching.keys(null).isEmpty())
    }

    @Test
    fun bboxRequest() {
        val url = Opencaching.bboxUrl(us, "KEY12345", ForageBox(40.0, -105.3, 40.1, -105.2), "en")
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(url.startsWith("https://www.opencaching.us/okapi/services/caches/shortcuts/search_and_retrieve?"))
        assertTrue(decoded.contains("\"bbox\":\"40.000|-105.300|40.100|-105.200\""))
        assertTrue(decoded.contains("consumer_key=KEY12345"))
        assertTrue(decoded.contains("wrap=true"))
    }

    @Test
    fun parsesAWrappedReply() {
        val json = """{"results":{
            "OU0A1B":{"code":"OU0A1B","name":"Boulder Bench","location":"40.015|-105.27","type":"Traditional",
              "status":"Available","difficulty":1.5,"terrain":2,"size2":"small","owner":{"uuid":"x","username":"Ada Whitlock"},
              "hint2":"Under the bench","short_description":"","description":"<p>Hi</p>","url":"https://www.opencaching.us/viewcache.php?wp=OU0A1B","date_hidden":"2020-06-01T00:00:00-06:00"},
            "OU0C2D":{"code":"OU0C2D","name":"Riddle","location":"40.02|-105.28","type":"Quiz","status":"Temporarily unavailable",
              "difficulty":3,"terrain":1,"size2":"none","owner":null,"hint2":"","short_description":null,"description":null,"url":null,"date_hidden":null},
            "OU0BAD":{"code":"OU0BAD","name":"No place","location":null}
          },"more":false}"""
        val caches = Opencaching.parse(us, json)!!.sortedBy { it.code }
        assertEquals(2, caches.size)
        val a = caches[0]
        assertEquals("OU0A1B", a.code)
        assertEquals("oc:us", a.source)
        assertEquals(40.015, a.lat, 1e-9)
        assertEquals(GeocacheKinds.TRADITIONAL, a.kind)
        assertEquals("small", a.size)
        assertEquals("Ada Whitlock", a.owner)
        assertEquals("Under the bench", a.hint)
        assertNull(a.summary)
        assertEquals("2020-06-01", a.placed)
        assertTrue(a.available)
        val b = caches[1]
        assertEquals(GeocacheKinds.MYSTERY, b.kind)
        assertNull(b.size)
        assertNull(b.hint)
        assertFalse(b.available)
    }

    @Test
    fun badReply() {
        assertNull(Opencaching.parse(us, "<html>busy</html>"))
    }
}
