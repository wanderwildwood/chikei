package com.kylecorry.trail_sense.shared.house

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * DuraSpeed, MediaTek's service on the Mudita Kompakt, closes installed apps a few minutes after
 * the screen goes dark, which can stop a track recorded with the screen off. It has no way in
 * from the phone's Settings and will not open its own screen for another app, but its App info
 * page opens, and from there its Open button: where Topo is switched on.
 */
object DuraSpeed {
    const val PACKAGE = "com.mediatek.duraspeed"

    /** Whether this phone has DuraSpeed at all (the Kompakt does; KompaktOS and most phones do not). */
    fun installed(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION") // the flags-object overload is Android 13; the Kompakt is 12
        context.packageManager.getApplicationInfo(PACKAGE, 0)
    }.isSuccess

    /** Opens DuraSpeed's App info page; false when the phone would not. */
    fun open(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
