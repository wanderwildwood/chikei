package com.kylecorry.trail_sense.tools.map.forage

/**
 * Which Overpass server Forage asks. The main one is overpass-api.de; when it is busy (429 or a
 * 5xx), too slow to answer, or cannot be reached, the same query goes to a public backup, run by
 * Private.coffee for anyone's use. A backup that answered is asked first for [STICKY_MILLIS],
 * then the main one gets its turn again.
 *
 * Each server has its own backoff, kept by the caller: one that said it was busy is skipped
 * ([Outcome.SKIPPED]) until its wait is over, and the other is tried.
 */
class OverpassServers(
    val main: String = MAIN,
    val backup: String = BACKUP,
    private val clock: () -> Long = System::currentTimeMillis
) {

    enum class Outcome {
        /** An answer. */
        OK,
        /** 429 or a 5xx: the server is busy. */
        BUSY,
        /** Connected, but no answer in time. */
        TIMEOUT,
        /** Could not connect at all. */
        OFFLINE,
        /** Not asked: the server's backoff is still running. */
        SKIPPED,
        /** Any other refusal, such as a 400 for a bad query; another server would say the same. */
        REJECTED
    }

    class Reply(val outcome: Outcome, val body: String? = null)

    @Volatile
    private var backupUntil = 0L

    /** The servers in the order they are tried now. */
    fun order(): List<String> =
        if (clock() < backupUntil) listOf(backup, main) else listOf(main, backup)

    /**
     * Asks [send] each server in turn until one answers. Returns the answer, or null when neither
     * did. A refusal of the query itself is not passed on to the other server.
     */
    fun ask(send: (String) -> Reply): String? {
        for (server in order()) {
            val reply = send(server)
            when (reply.outcome) {
                Outcome.OK -> {
                    // The backup's turn starts when it stands in for main, and is not stretched
                    // by its own answers, so main is always tried again in time
                    if (server == main) {
                        backupUntil = 0L
                    } else if (clock() >= backupUntil) {
                        backupUntil = clock() + STICKY_MILLIS
                    }
                    return reply.body
                }
                Outcome.REJECTED -> return null
                else -> continue
            }
        }
        return null
    }

    companion object {
        const val MAIN = "https://overpass-api.de/api/interpreter"
        const val BACKUP = "https://overpass.private.coffee/api/interpreter"

        /** How long a backup that answered is asked first. */
        const val STICKY_MILLIS = 30 * 60_000L

        /** How long the main server has to answer before the backup is asked. */
        const val MAIN_READ_TIMEOUT_MILLIS = 35_000

        const val BACKUP_READ_TIMEOUT_MILLIS = 45_000
    }
}
