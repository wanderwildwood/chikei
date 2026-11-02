package com.kylecorry.trail_sense.settings.licenses

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.kylecorry.trail_sense.shared.house.licencesView

/** Settings > Licenses: the same Licences list the About dialog opens (app first, a row per library). */
class LicenseFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        licencesView(requireContext())
}
