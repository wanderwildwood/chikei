package com.kylecorry.trail_sense.tools.map.infrastructure

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.kylecorry.sol.units.Coordinate

/**
 * Field Kit's "Calling for help" page, opened from Here with the phone's position: the position to read
 * out or send, and the buttons that dial. Field Kit answers
 * `com.wanderwildwood.zatsuno.action.CALL_FOR_HELP` with the point as two doubles;
 * its README has the names. Offered only when Field Kit is on the phone.
 */
object FieldKit {
    private const val PACKAGE = "com.wanderwildwood.zatsuno"
    private const val ACTION_CALL_FOR_HELP = "$PACKAGE.action.CALL_FOR_HELP"
    private const val EXTRA_LATITUDE = "$PACKAGE.extra.LATITUDE"
    private const val EXTRA_LONGITUDE = "$PACKAGE.extra.LONGITUDE"

    private fun callForHelp(location: Coordinate?): Intent =
        Intent(ACTION_CALL_FOR_HELP).setPackage(PACKAGE).apply {
            if (location != null) {
                putExtra(EXTRA_LATITUDE, location.latitude)
                putExtra(EXTRA_LONGITUDE, location.longitude)
            }
        }

    /** Whether Field Kit is installed and opens its help page; asked each time a menu opens. */
    @Suppress("DEPRECATION") // the flags-object overload is Android 13; the Kompakt is 12
    fun installed(context: Context): Boolean =
        context.packageManager.resolveActivity(callForHelp(null), 0) != null

    /**
     * Opens the help page with [location] on it, as a bare position. A point that is not a real
     * position yet (no fix, so 0, 0) is left out and Field Kit shows the phone's own.
     */
    fun open(context: Context, location: Coordinate): Boolean {
        val real = location != Coordinate.zero
        return try {
            context.startActivity(callForHelp(location.takeIf { real }))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
