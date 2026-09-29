package com.kylecorry.trail_sense.shared.tiles

import com.kylecorry.andromeda.core.tryOrLog

import android.content.Context
import com.kylecorry.andromeda.core.system.Package
import com.kylecorry.trail_sense.tools.tools.infrastructure.Tools

class TileManager {

    fun setTilesEnabled(context: Context, enabled: Boolean) {
        val tools = Tools.getTools(context, availableOnly = false)
        tools.filter { it.tiles.any() }.forEach {
            val isAvailable = it.isAvailable(context)
            it.tiles.forEach { tile ->
                // Topo leaves some registered tools' tiles out of the manifest (the pedometer's,
                // say); asking for a component that isn't there throws, so skip it.
                tryOrLog {
                    Package.setComponentEnabled(context, tile, enabled && isAvailable)
                }
            }
        }
    }

}
