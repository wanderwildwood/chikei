package com.kylecorry.trail_sense.tools.paths.domain

import androidx.annotation.StringRes
import com.kylecorry.trail_sense.R
import java.time.Duration

/**
 * How closely a recorded track follows the ground: how often a point is taken. Finer detail
 * keeps the GPS awake more of the time.
 */
enum class TrackDetail(val interval: Duration, @StringRes val label: Int) {
    Low(Duration.ofSeconds(60), R.string.track_detail_low),
    Medium(Duration.ofSeconds(30), R.string.track_detail_medium),
    High(Duration.ofSeconds(10), R.string.track_detail_high),
    Highest(Duration.ofSeconds(5), R.string.track_detail_highest);

    companion object {
        val DEFAULT = High

        /** The level recording at this interval, or null for one set by hand. */
        fun of(interval: Duration): TrackDetail? = entries.firstOrNull { it.interval == interval }
    }
}
