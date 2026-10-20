package com.kylecorry.trail_sense.tools.map.forage

import android.content.Context
import android.os.Bundle
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.kylecorry.andromeda.core.cache.DependencyRegistry
import com.kylecorry.andromeda.core.system.GeoUri
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.sol.units.Coordinate
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.shared.navigateWithAnimation
import com.kylecorry.trail_sense.shared.sharing.ActionItem
import com.kylecorry.trail_sense.shared.sharing.Share
import com.kylecorry.trail_sense.tools.beacons.domain.BeaconOwner
import com.kylecorry.trail_sense.tools.navigation.infrastructure.Navigator
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** The sheet a tapped Forage spot opens, with Navigate and Beacon beneath. */
object ForageSheet {

    fun show(fragment: Fragment, spotId: String) {
        val spot = Forage.spot(spotId) ?: return
        val context = fragment.requireContext()
        fragment.inBackground {
            val detail = Forage.detail(context, spot)
            onMain {
                if (!fragment.isAdded) return@onMain
                present(fragment, spot, detail)
            }
        }
    }

    private fun present(fragment: Fragment, spot: ForageSpot, detail: ForageDetail?) {
        val context = fragment.requireContext()
        val location = Coordinate(spot.lat, spot.lng)
        val title = spot.name?.replaceFirstChar { it.uppercase() }
            ?: spot.scientific ?: context.getString(R.string.forage)
        val view = fragment.layoutInflater.inflate(R.layout.view_forage_detail, null)
        val isFallingFruit = spot.source == ForageSources.FALLING_FRUIT

        fun line(id: Int, text: String?) {
            view.findViewById<TextView>(id).apply {
                this.text = text
                isVisible = !text.isNullOrBlank()
            }
        }

        line(R.id.forage_scientific, spot.scientific?.takeIf { it != title })
        line(R.id.forage_season, season(context, detail))
        line(
            R.id.forage_access,
            if (isFallingFruit) fallingFruitAccess(context, detail?.access) else osmAccess(context, spot.tags?.get("access"))
        )
        view.findViewById<TextView>(R.id.forage_unverified).isVisible = detail?.unverified == true
        line(
            R.id.forage_description,
            if (isFallingFruit) {
                detail?.description ?: if (detail == null) context.getString(R.string.forage_details_offline) else null
            } else {
                spot.tags?.get("description") ?: spot.tags?.get("note")
            }
        )
        line(R.id.forage_source, source(context, spot, detail))
        line(
            R.id.forage_credit,
            context.getString(if (isFallingFruit) R.string.forage_credit_falling_fruit else R.string.forage_credit_osm)
        )

        val formatter = FormatService.getInstance(context)
        Share.actions(
            fragment,
            title,
            listOf(
                ActionItem(context.getString(R.string.navigate), R.drawable.ic_beacon) {
                    DependencyRegistry.get<Navigator>().navigateTo(location, title, BeaconOwner.Maps)
                },
                ActionItem(context.getString(R.string.beacon), R.drawable.ic_location) {
                    val bundle = Bundle().apply { putParcelable("initial_location", GeoUri(location)) }
                    fragment.findNavController().navigateWithAnimation(R.id.placeBeaconFragment, bundle)
                }
            ),
            subtitle = formatter.formatLocation(location),
            customView = view
        ) {
            // Nothing chosen: the sheet just closes
        }
    }

    private fun season(context: Context, detail: ForageDetail?): String? {
        val start = detail?.seasonStart ?: return null
        val stop = detail.seasonStop ?: start
        val locale = Locale.getDefault()
        val from = Month.of(start + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)
        val to = Month.of(stop + 1).getDisplayName(TextStyle.FULL_STANDALONE, locale)
        return if (start == stop) {
            context.getString(R.string.forage_season_one, from)
        } else {
            context.getString(R.string.forage_season, from, to)
        }
    }

    private fun fallingFruitAccess(context: Context, access: Int?): String? = when (access) {
        0 -> context.getString(R.string.forage_access_owner)
        1 -> context.getString(R.string.forage_access_permission)
        2 -> context.getString(R.string.forage_access_public)
        3 -> context.getString(R.string.forage_access_overhanging)
        4 -> context.getString(R.string.forage_access_private)
        else -> null
    }

    private fun osmAccess(context: Context, access: String?): String? = when (access?.lowercase()) {
        null, "" -> null
        "yes", "public" -> context.getString(R.string.forage_access_public)
        "permissive" -> context.getString(R.string.forage_access_permissive)
        "private", "no" -> context.getString(R.string.forage_access_private)
        "customers" -> context.getString(R.string.forage_access_customers)
        else -> context.getString(R.string.forage_access_other, access)
    }

    private fun source(context: Context, spot: ForageSpot, detail: ForageDetail?): String? {
        if (spot.source == ForageSources.OSM) {
            val number = spot.id.substringAfter(":n")
            return context.getString(R.string.forage_source_osm, number)
        }
        val dataset = detail?.datasetName ?: return if (detail != null) {
            context.getString(R.string.forage_source_falling_fruit_users)
        } else {
            null
        }
        val licence = detail.datasetLicence
        return if (licence != null) {
            context.getString(R.string.forage_source_dataset, dataset, licence)
        } else {
            context.getString(R.string.forage_source_dataset_no_licence, dataset)
        }
    }
}
