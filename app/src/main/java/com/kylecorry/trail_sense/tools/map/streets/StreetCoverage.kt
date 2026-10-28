package com.kylecorry.trail_sense.tools.map.streets

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the person has been: every point of every recorded or imported path, with the gaps
 * between close points filled in, kept in a grid of small squares so that "was anyone near
 * here" is a handful of lookups.
 */
class WalkedIndex {

    private val cells = HashMap<Long, MutableList<DoubleArray>>()

    var size = 0
        private set

    /**
     * Adds one path's points in order, as latitude, longitude pairs. [seconds], when given, is the
     * time of each point (or -1 when unknown): a step with a long pause in it is not filled in.
     */
    fun addPath(points: DoubleArray, seconds: LongArray? = null) {
        val n = points.size / 2
        for (i in 0 until n) {
            val lat = points[i * 2]
            val lng = points[i * 2 + 1]
            add(lat, lng)
            if (i == 0) continue
            val gap = StreetCoverage.metres(points[i * 2 - 2], points[i * 2 - 1], lat, lng)
            // Imported and simplified tracks run straight between corners, so a long step is
            // filled in too; a step with a long pause in it (the recording stopped, or fixes
            // minutes apart) says nothing about the streets in between and is not
            val pause = if (seconds != null && seconds[i] >= 0 && seconds[i - 1] >= 0) {
                seconds[i] - seconds[i - 1]
            } else {
                0L
            }
            if (gap in FILL_MIN..FILL_MAX && pause <= MAX_PAUSE_SECONDS) {
                val steps = (gap / FILL_MIN).toInt()
                for (s in 1 until steps) {
                    val t = s.toDouble() / steps
                    add(
                        points[i * 2 - 2] + (lat - points[i * 2 - 2]) * t,
                        points[i * 2 - 1] + (lng - points[i * 2 - 1]) * t
                    )
                }
            }
        }
    }

    private fun add(lat: Double, lng: Double) {
        cells.getOrPut(key(row(lat), column(lat, lng))) { mutableListOf() }.add(doubleArrayOf(lat, lng))
        size++
    }

    /** Whether any walked point is within [RADIUS] metres of the spot. */
    fun near(lat: Double, lng: Double): Boolean {
        val r = row(lat)
        for (dr in -1..1) {
            val rowLat = (r + dr + 0.5) * CELL_DEG
            val c = column(rowLat, lng)
            for (dc in -1..1) {
                val list = cells[key(r + dr, c + dc)] ?: continue
                for (p in list) {
                    if (StreetCoverage.metres(lat, lng, p[0], p[1]) <= RADIUS) return true
                }
            }
        }
        return false
    }

    private fun row(lat: Double): Int = floor(lat / CELL_DEG).toInt()

    /** Columns are about as wide as rows are tall, whatever the latitude of their row. */
    private fun column(lat: Double, lng: Double): Int {
        val rowCentre = (row(lat) + 0.5) * CELL_DEG
        val step = CELL_DEG / max(0.05, cos(Math.toRadians(rowCentre)))
        return floor(lng / step).toInt()
    }

    private fun key(row: Int, column: Int): Long = (row.toLong() shl 32) xor (column.toLong() and 0xffffffffL)

    companion object {
        /** A street piece counts as walked when a point of a path is this close to its middle. */
        const val RADIUS = 18.0

        /** Steps between fixes are filled in at about this spacing, when no longer than [FILL_MAX]. */
        const val FILL_MIN = 10.0
        const val FILL_MAX = 500.0

        /** A step between two fixes longer than this in time is a gap in the recording. */
        const val MAX_PAUSE_SECONDS = 120L

        private const val CELL_DEG = 0.0002 // about 22 m
    }
}

/** Cuts streets into pieces and says which have been walked. */
object StreetCoverage {

    /** About how long each piece is; the real length is a little more or less so no piece is a stub. */
    const val PIECE_METRES = 25.0

    fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return 6_371_000.0 * sqrt(dLat * dLat + dLng * dLng)
    }

    /** [way] cut into pieces of about [PIECE_METRES], each checked against [walked], then joined into runs. */
    fun pieces(way: StreetWay, walked: WalkedIndex): List<StreetPiece> {
        val n = way.size
        if (n < 2) return emptyList()
        val cumulative = DoubleArray(n)
        for (i in 1 until n) {
            cumulative[i] = cumulative[i - 1] + metres(way.lat(i - 1), way.lng(i - 1), way.lat(i), way.lng(i))
        }
        val total = cumulative[n - 1]
        if (total <= 0.0) return emptyList()
        val count = max(1, (total / PIECE_METRES).roundToInt())
        val length = total / count

        val result = mutableListOf<StreetPiece>()
        var run = ArrayList<Double>()
        var runWalked = false
        var runMetres = 0.0

        fun at(distance: Double): DoubleArray {
            var segment = 0
            while (segment < n - 2 && cumulative[segment + 1] < distance) segment++
            val span = cumulative[segment + 1] - cumulative[segment]
            val t = if (span <= 0.0) 0.0 else ((distance - cumulative[segment]) / span).coerceIn(0.0, 1.0)
            return doubleArrayOf(
                way.lat(segment) + (way.lat(segment + 1) - way.lat(segment)) * t,
                way.lng(segment) + (way.lng(segment + 1) - way.lng(segment)) * t
            )
        }

        fun flush() {
            if (run.isNotEmpty()) result.add(StreetPiece(runWalked, run.toDoubleArray(), runMetres))
            run = ArrayList()
            runMetres = 0.0
        }

        for (k in 0 until count) {
            val from = k * length
            val to = if (k == count - 1) total else (k + 1) * length
            val middle = at((from + to) / 2)
            val isWalked = walked.near(middle[0], middle[1])
            if (run.isNotEmpty() && isWalked != runWalked) flush()
            if (run.isEmpty()) {
                runWalked = isWalked
                // The run starts where the last one ended, so the lines meet
                val start = at(from)
                run.add(start[0]); run.add(start[1])
            }
            // The corners of the street inside this piece keep its shape
            for (v in 1 until n - 1) {
                if (cumulative[v] > from && cumulative[v] < to) {
                    run.add(way.lat(v)); run.add(way.lng(v))
                }
            }
            val end = at(to)
            run.add(end[0]); run.add(end[1])
            runMetres += to - from
        }
        flush()
        return result
    }
}
