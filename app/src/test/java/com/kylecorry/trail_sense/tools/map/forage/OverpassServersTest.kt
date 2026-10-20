package com.kylecorry.trail_sense.tools.map.forage

import com.kylecorry.trail_sense.tools.map.forage.OverpassServers.Outcome
import com.kylecorry.trail_sense.tools.map.forage.OverpassServers.Reply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OverpassServersTest {

    private var now = 1_000_000L
    private val servers = OverpassServers("main", "backup") { now }

    /** A fake network: each server answers with the next outcome given for it. */
    private class Fake(vararg answers: Pair<String, Outcome>) {
        private val queue = answers.toMutableList()
        val asked = mutableListOf<String>()
        fun send(server: String): Reply {
            asked.add(server)
            val i = queue.indexOfFirst { it.first == server }
            val outcome = if (i < 0) Outcome.OFFLINE else queue.removeAt(i).second
            return Reply(outcome, if (outcome == Outcome.OK) "from $server" else null)
        }
    }

    @Test
    fun mainAnswersAndBackupIsNeverAsked() {
        val fake = Fake("main" to Outcome.OK)
        assertEquals("from main", servers.ask(fake::send))
        assertEquals(listOf("main"), fake.asked)
    }

    @Test
    fun busyTimeoutOrNoConnectionGoesToTheBackup() {
        for (failure in listOf(Outcome.BUSY, Outcome.TIMEOUT, Outcome.OFFLINE, Outcome.SKIPPED)) {
            val fresh = OverpassServers("main", "backup") { now }
            val fake = Fake("main" to failure, "backup" to Outcome.OK)
            assertEquals("from backup", fresh.ask(fake::send), failure.name)
            assertEquals(listOf("main", "backup"), fake.asked, failure.name)
        }
    }

    @Test
    fun aRejectedQueryIsNotSentAgainElsewhere() {
        val fake = Fake("main" to Outcome.REJECTED, "backup" to Outcome.OK)
        assertNull(servers.ask(fake::send))
        assertEquals(listOf("main"), fake.asked)
    }

    @Test
    fun bothFailing() {
        val fake = Fake("main" to Outcome.BUSY, "backup" to Outcome.BUSY)
        assertNull(servers.ask(fake::send))
        assertEquals(listOf("main", "backup"), fake.asked)
    }

    @Test
    fun theBackupIsRememberedForAWhileThenMainAgain() {
        servers.ask(Fake("main" to Outcome.BUSY, "backup" to Outcome.OK)::send)
        assertEquals(listOf("backup", "main"), servers.order())

        now += OverpassServers.STICKY_MILLIS - 1
        val during = Fake("backup" to Outcome.OK)
        assertEquals("from backup", servers.ask(during::send))
        assertEquals(listOf("backup"), during.asked)

        // An answer from the backup does not stretch its turn; once it is over, main is first
        now += 1
        assertEquals(listOf("main", "backup"), servers.order())
        val after = Fake("main" to Outcome.OK)
        assertEquals("from main", servers.ask(after::send))
        assertEquals(listOf("main"), after.asked)
    }

    @Test
    fun aFailingBackupFallsBackToMain() {
        servers.ask(Fake("main" to Outcome.TIMEOUT, "backup" to Outcome.OK)::send)
        val fake = Fake("backup" to Outcome.BUSY, "main" to Outcome.OK)
        assertEquals("from main", servers.ask(fake::send))
        assertEquals(listOf("backup", "main"), fake.asked)
        // Main answered, so it is first again
        assertEquals(listOf("main", "backup"), servers.order())
    }

    @Test
    fun theRealServers() {
        val real = OverpassServers()
        assertEquals("https://overpass-api.de/api/interpreter", real.order()[0])
        assertEquals("https://overpass.private.coffee/api/interpreter", real.order()[1])
    }
}
