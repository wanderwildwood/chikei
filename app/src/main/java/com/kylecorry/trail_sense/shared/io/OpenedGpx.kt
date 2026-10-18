package com.kylecorry.trail_sense.shared.io

import android.content.Context
import android.net.Uri
import com.kylecorry.andromeda.gpx.GPXData
import com.kylecorry.andromeda.gpx.GPXParser
import com.kylecorry.luna.concurrency.onIO

/**
 * A GPX file another app asked to open (Files, a mail attachment): read from the address it
 * gave, with no picker, for [com.kylecorry.trail_sense.tools.paths.ui.commands.ImportPathsCommand].
 */
class OpenedGpx(private val context: Context, private val uri: Uri) : ImportService<GPXData> {
    override suspend fun import(): GPXData? = onIO {
        try {
            context.contentResolver.openInputStream(uri)?.use { GPXParser.parse(it) }
        } catch (_: Exception) {
            null
        }
    }
}
