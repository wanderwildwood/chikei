package com.kylecorry.trail_sense.tools.paths.ui.commands

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import com.kylecorry.andromeda.core.coroutines.BackgroundMinimumState
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.andromeda.gpx.GPXParser
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.tools.paths.domain.FullPath
import com.kylecorry.trail_sense.tools.paths.domain.IPathService
import com.kylecorry.trail_sense.tools.paths.domain.Path
import com.kylecorry.trail_sense.tools.paths.domain.PathGPXConverter
import com.kylecorry.trail_sense.tools.paths.infrastructure.persistence.PathService
import com.kylecorry.trail_sense.tools.paths.ui.PathNameFactory
import java.io.File

/**
 * Hands a path to another app as a GPX file through the share sheet: CalTopo if it takes
 * GPX, otherwise Files, a messenger or email to carry it to the desk.
 */
class SharePathCommand(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val pathService: IPathService = PathService.getInstance(context)
) {

    fun execute(path: Path) {
        lifecycleOwner.inBackground(BackgroundMinimumState.Created) {
            val name = PathNameFactory(context).getName(path)
            val file = onIO {
                val waypoints = pathService.getWaypoints(listOf(path.id))[path.id] ?: emptyList()
                val parent = path.parentId?.let { pathService.getGroup(it) }
                val gpx = PathGPXConverter().toGPX(listOf(FullPath(path, waypoints, parent)))
                val dir = File(context.cacheDir, SHARE_DIR).apply {
                    deleteRecursively()
                    mkdirs()
                }
                File(dir, "${safeFilename(name)}.gpx").apply {
                    writeText(GPXParser.toGPX(gpx, context.getString(R.string.app_name)))
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onMain {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share_action_send))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
    }

    private fun safeFilename(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim()
        return cleaned.ifEmpty { "path" }.take(60)
    }

    companion object {
        // Must match the cache-path in res/xml/shared_files.xml
        const val SHARE_DIR = "shared"
    }
}
