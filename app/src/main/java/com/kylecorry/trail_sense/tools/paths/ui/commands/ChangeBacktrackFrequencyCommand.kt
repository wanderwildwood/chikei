package com.kylecorry.trail_sense.tools.paths.ui.commands

import android.content.Context
import com.kylecorry.andromeda.pickers.Pickers
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.commands.Command
import com.kylecorry.trail_sense.tools.paths.domain.TrackDetail
import com.kylecorry.trail_sense.tools.paths.infrastructure.BacktrackScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Picks the track detail: four levels, as CalTopo offers, rather than a free interval.
 */
class ChangeBacktrackFrequencyCommand(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChange: (Duration) -> Unit
) : Command {
    private val prefs by lazy { UserPreferences(context) }
    override fun execute() {
        val levels = TrackDetail.entries
        Pickers.item(
            context,
            context.getString(R.string.track_detail),
            levels.map { context.getString(it.label) },
            levels.indexOf(TrackDetail.of(prefs.paths.backtrackRecordFrequency))
        ) { index ->
            if (index != null) {
                val interval = levels[index].interval
                prefs.paths.backtrackRecordFrequency = interval
                onChange(interval)
                scope.launch {
                    BacktrackScheduler.restart(context)
                }
            }
        }
    }
}
