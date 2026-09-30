package com.kylecorry.trail_sense.settings.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.kylecorry.andromeda.core.system.Resources
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.views.Toolbar
import com.kylecorry.trail_sense.tools.map.ui.MapSettingsFragment
import com.kylecorry.trail_sense.tools.navigation.ui.NavigationSettingsFragment
import com.kylecorry.trail_sense.tools.offline_maps.ui.photo_maps.PhotoMapSettingsFragment
import com.kylecorry.trail_sense.tools.pedometer.ui.PedometerSettingsFragment
import com.kylecorry.trail_sense.tools.tools.ui.ToolsSettingsFragment

/**
 * What every settings screen is given, from one place rather than thirty fragments: the house
 * top bar with the screen's name, rows with no space kept for icons (STYLE.md: no icons on
 * settings rows), and MMD's 2dp black divider between groups. Bottom sheets get their black
 * rim here too, for the same reason: one place, not one per sheet.
 */
object SettingsChrome {

    private val titles: Map<Class<out Fragment>, Int> = mapOf(
        SettingsFragment::class.java to R.string.settings,
        UnitSettingsFragment::class.java to R.string.units,
        SensorSettingsFragment::class.java to R.string.sensors,
        PrivacySettingsFragment::class.java to R.string.privacy,
        BackupSettingsFragment::class.java to R.string.backup_restore,
        ExperimentalSettingsFragment::class.java to R.string.experimental,
        ErrorSettingsFragment::class.java to R.string.errors,
        PluginsSettingsFragment::class.java to R.string.plugins,
        ToolsSettingsFragment::class.java to R.string.tools,
        MapSettingsFragment::class.java to R.string.map,
        PathsSettingsFragment::class.java to R.string.paths,
        BeaconSettingsFragment::class.java to R.string.beacons,
        NavigationSettingsFragment::class.java to R.string.navigation,
        PhotoMapSettingsFragment::class.java to R.string.photo_maps,
        PedometerSettingsFragment::class.java to R.string.pedometer,
        CalibrateCompassFragment::class.java to R.string.pref_compass_sensor_title,
        CalibrateGPSFragment::class.java to R.string.gps,
        CalibrateAltimeterFragment::class.java to R.string.pref_altimeter_calibration_title,
        CalibrateBarometerFragment::class.java to R.string.barometer,
        ThermometerSettingsFragment::class.java to R.string.tool_thermometer_title,
        CameraSettingsFragment::class.java to R.string.camera,
        CellSignalSettingsFragment::class.java to R.string.cell_signal,
        PowerSettingsFragment::class.java to R.string.tool_battery_title,
    )

    fun attach(activity: FragmentActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object :
            FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager,
                f: Fragment,
                v: View,
                savedInstanceState: Bundle?
            ) {
                if (f is PreferenceFragmentCompat) {
                    dress(f, v)
                }
                if (f is BottomSheetDialogFragment) {
                    // Material paints its own sheet background over the theme's; the house rim
                    // goes on once the sheet exists
                    v.post {
                        f.dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                            ?.background = AppCompatResources.getDrawable(v.context, R.drawable.eink_sheet_background)
                    }
                }
            }
        }, true)
    }

    private fun dress(fragment: PreferenceFragmentCompat, view: View) {
        view.findViewById<Toolbar>(R.id.settings_toolbar)?.let { bar ->
            val title = titles[fragment.javaClass]
            // A preference list drawn inside something else (a sheet) keeps that thing's own title
            bar.isVisible = title != null
            if (title != null) {
                bar.title.text = fragment.getString(title)
                // A first group named for the screen only says the title again, a line lower
                val screen = fragment.preferenceScreen
                val first = if ((screen?.preferenceCount ?: 0) > 0) screen?.getPreference(0) else null
                if (first is PreferenceCategory && first.title?.toString().equals(bar.title.text.toString(), ignoreCase = true)) {
                    first.title = null
                }
            }
        }
        fragment.preferenceScreen?.let { removeIconSpace(it) }
        fragment.setDivider(ColorDrawable(Color.BLACK))
        fragment.setDividerHeight(Resources.dp(fragment.requireContext(), 2f).toInt())
    }

    private fun removeIconSpace(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            // A colour swatch is the row's value, not decoration; everything else goes
            if (preference.icon != null && preference.key?.contains("color") != true) {
                preference.icon = null
            }
            preference.isIconSpaceReserved = preference.icon != null
            if (preference is PreferenceGroup) {
                removeIconSpace(preference)
            }
        }
    }
}
