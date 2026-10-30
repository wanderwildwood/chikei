package com.kylecorry.trail_sense.shared.map_layers.preferences.ui

import com.kylecorry.trail_sense.shared.views.guardLinks
import android.content.Context
import android.graphics.Color
import android.widget.TextView
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class SummaryClickablePreference(context: Context) : Preference(context) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val summaryView = holder.findViewById(android.R.id.summary) as? TextView
        summaryView?.apply {
            guardLinks()
            linksClickable = true
            highlightColor = Color.TRANSPARENT
        }
    }
}
