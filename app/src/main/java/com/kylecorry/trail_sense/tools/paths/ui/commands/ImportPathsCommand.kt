package com.kylecorry.trail_sense.tools.paths.ui.commands

import com.kylecorry.trail_sense.shared.Notices
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.andromeda.alerts.CoroutineAlerts
import com.kylecorry.andromeda.core.coroutines.BackgroundMinimumState
import com.kylecorry.luna.concurrency.onDefault
import com.kylecorry.luna.concurrency.onIO
import com.kylecorry.luna.extensions.filterIndices
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.andromeda.gpx.GPXData
import com.kylecorry.andromeda.gpx.GPXWaypoint
import com.kylecorry.andromeda.pickers.CoroutinePickers
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.shared.io.ImportService
import com.kylecorry.trail_sense.tools.beacons.infrastructure.export.BeaconGpxImporter
import com.kylecorry.trail_sense.tools.paths.domain.FullPath
import com.kylecorry.trail_sense.tools.paths.domain.IPathService
import com.kylecorry.trail_sense.tools.paths.domain.Path
import com.kylecorry.trail_sense.tools.paths.domain.PathGroup
import com.kylecorry.trail_sense.tools.paths.domain.PathMetadata
import com.kylecorry.trail_sense.tools.paths.domain.PathPoint
import com.kylecorry.trail_sense.tools.paths.domain.PathSimplificationQuality
import com.kylecorry.trail_sense.tools.paths.infrastructure.persistence.IPathPreferences
import com.kylecorry.trail_sense.tools.paths.infrastructure.persistence.PathService

class ImportPathsCommand(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val gpxService: ImportService<GPXData>,
    private val pathService: IPathService = PathService.getInstance(context),
    private val prefs: IPathPreferences = UserPreferences(context).navigation
) {

    private val style = prefs.defaultPathStyle

    private companion object {
        /** More waypoints than this in one file are refused rather than flooding the beacon list. */
        const val MAX_BEACONS = 5000
    }

    fun execute(parentId: Long?) {
        lifecycleOwner.inBackground(BackgroundMinimumState.Created) {
            val gpx = gpxService.import() ?: return@inBackground
            val paths = mutableListOf<FullPath>()

            // Get the tracks and routes from the GPX
            paths.addAll(getTracks(gpx))
            paths.addAll(getRoutes(gpx))

            // A file of waypoints alone, such as one opened from Files, becomes beacons, once
            // the person agrees: another app can open a file here without their asking.
            if (paths.isEmpty()) {
                val waypoints = gpx.waypoints.size
                if (waypoints > MAX_BEACONS) {
                    Notices.toast(context, context.getString(R.string.gpx_too_many_waypoints, MAX_BEACONS))
                    return@inBackground
                }
                if (waypoints > 0) {
                    val cancelled = CoroutineAlerts.dialog(
                        context,
                        context.getString(R.string.import_btn),
                        context.resources.getQuantityString(R.plurals.import_beacons_message, waypoints, waypoints),
                        okText = context.getString(R.string.import_btn)
                    )
                    if (cancelled) return@inBackground
                }
                val count = if (waypoints == 0) 0 else onIO { BeaconGpxImporter(context).import(gpx) }
                if (count > 0) {
                    Notices.toast(context, context.resources.getQuantityString(R.plurals.beacons_imported, count, count))
                } else {
                    Notices.toast(context, context.getString(R.string.gpx_nothing_to_import))
                }
                return@inBackground
            }

            // Let the user select the paths to import
            val selectedPathIndices = CoroutinePickers.items(
                context,
                context.getString(R.string.import_btn),
                paths.map {
                    it.path.name ?: context.getString(android.R.string.untitled)
                },
                paths.indices.toList()
            ) ?: return@inBackground


            Alerts.withLoading(context, context.getString(R.string.importing)) {
                // Import the selected paths
                val selectedPaths = paths.filterIndices(selectedPathIndices)
                importPaths(selectedPaths, parentId)

                // Let the user know the import was successful
                Notices.toast(
                    context, context.resources.getQuantityString(
                        R.plurals.paths_imported,
                        selectedPathIndices.size,
                        selectedPathIndices.size
                    )
                )
            }
        }
    }

    private suspend fun getRoutes(gpx: GPXData): List<FullPath> = onDefault {
        // Groups are a Trail Sense concept, so routes don't have groups
        val paths = mutableListOf<FullPath>()
        for (route in gpx.routes) {
            val path = Path(0, route.name, style, PathMetadata.empty)
            paths.add(FullPath(path, route.points.toPathPoints()))
        }
        paths
    }

    private suspend fun getTracks(gpx: GPXData): List<FullPath> = onDefault {
        val paths = mutableListOf<FullPath>()
        for (track in gpx.tracks) {
            for ((points) in track.segments) {
                val path = Path(0, track.name, style, PathMetadata.empty)
                val parent = track.group?.let {
                    PathGroup(0, it)
                }
                paths.add(FullPath(path, points.toPathPoints(), parent))
            }
        }
        paths
    }

    private fun List<GPXWaypoint>.toPathPoints(): List<PathPoint> {
        return map {
            PathPoint(0, 0, it.coordinate, it.elevation, it.time)
        }
    }

    private suspend fun importPaths(
        paths: List<FullPath>, parentId: Long?
    ) = onIO {
        val shouldSimplify = prefs.simplifyPathOnImport

        val groupNames = paths.mapNotNull { it.parent?.name }.distinct()
        val groupIdMap = mutableMapOf<String, Long>()
        for (groupName in groupNames) {
            val id = pathService.addGroup(PathGroup(0, groupName, parentId))
            groupIdMap[groupName] = id
        }

        for (path in paths) {
            val parent = if (path.parent != null) {
                groupIdMap[path.parent.name]
            } else {
                parentId
            }
            // Create the path
            val pathToCreate = path.path.copy(parentId = parent)
            val pathId = pathService.addPath(pathToCreate)

            // Add the waypoints to the path
            pathService.addWaypointsToPath(path.points, pathId)

            // Simplify the path
            if (shouldSimplify) {
                pathService.simplifyPath(
                    pathId,
                    PathSimplificationQuality.High
                )
            }
        }
    }

}
