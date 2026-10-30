package com.kylecorry.trail_sense.settings.licenses

import android.os.Bundle
import android.text.util.Linkify.WEB_URLS
import androidx.core.text.toSpannable
import androidx.core.text.util.LinkifyCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.kylecorry.andromeda.alerts.Alerts
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.text.TextUtils
import com.kylecorry.trail_sense.shared.views.guardLinks
import com.kylecorry.trail_sense.tools.tools.ui.ToolsFragment.Companion.SOURCE_URL


class LicenseFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.licenses, rootKey)

        val licenseSection =
            preferenceManager.findPreference<PreferenceCategory>(getString(R.string.pref_category_licenses))

        // Topo's own licence first: the full GPL text and the source address, as a link.
        licenseSection?.addPreference(Preference(requireContext()).also { pref ->
            pref.title = getString(R.string.licences_topo_title)
            pref.summary = SOURCE_URL
            pref.isIconSpaceReserved = false
            pref.isSingleLineTitle = false
            pref.setOnPreferenceClickListener {
                val text = getString(R.string.licences_topo_body, SOURCE_URL) + "\n\n" +
                    TextUtils.loadTextFromResources(requireContext(), R.raw.gpl_3_0)
                val content = text.toSpannable()
                LinkifyCompat.addLinks(content, WEB_URLS)
                Alerts.dialog(
                    requireContext(),
                    pref.title.toString(),
                    content.guardLinks(),
                    cancelText = null,
                    allowLinks = true
                )
                true
            }
        })

        fun extra(title: String, summary: String, text: () -> String) {
            licenseSection?.addPreference(Preference(requireContext()).also { pref ->
                pref.title = title
                pref.summary = summary
                pref.isIconSpaceReserved = false
                pref.isSingleLineTitle = false
                pref.setOnPreferenceClickListener {
                    val content = text().toSpannable()
                    LinkifyCompat.addLinks(content, WEB_URLS)
                    Alerts.dialog(requireContext(), title, content.guardLinks(), cancelText = null, allowLinks = true)
                    true
                }
            })
        }
        extra(getString(R.string.licences_map_title), getString(R.string.licences_map_summary)) {
            getString(R.string.about_bundled)
        }
        extra("Lato", "SIL Open Font License 1.1") {
            TextUtils.loadTextFromResources(requireContext(), R.raw.ofl_1_1)
        }

        for (library in Licenses.libraries) {
            val pref = Preference(requireContext())
            pref.title = library.name
            pref.summary = library.url
            pref.isIconSpaceReserved = false
            pref.isSingleLineTitle = false
            pref.setOnPreferenceClickListener {
                val content = (library.url + "\n\n" + library.license()).toSpannable()
                LinkifyCompat.addLinks(content, WEB_URLS)

                Alerts.dialog(
                    requireContext(),
                    library.name,
                    content.guardLinks(),
                    cancelText = null,
                    allowLinks = true
                )
                true
            }
            licenseSection?.addPreference(pref)
        }
    }


}
