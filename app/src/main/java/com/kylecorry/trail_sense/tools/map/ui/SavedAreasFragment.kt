package com.kylecorry.trail_sense.tools.map.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import com.google.android.material.R as MaterialR
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.luna.concurrency.onMain
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.databinding.FragmentSavedAreasBinding
import com.kylecorry.trail_sense.shared.FormatService
import com.kylecorry.trail_sense.tools.map.sources.SavedArea
import com.kylecorry.trail_sense.tools.map.sources.SavedAreas
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The areas saved for use with no signal, in plain words: what each holds, its size, and when it
 * was saved. A row deletes on a second tap within four seconds.
 */
class SavedAreasFragment : BoundFragment<FragmentSavedAreasBinding>() {

    private val formatter by lazy { FormatService.getInstance(requireContext()) }
    private val handler = Handler(Looper.getMainLooper())
    private var armed: String? = null
    private var areas: List<SavedArea> = emptyList()

    override fun generateBinding(layoutInflater: LayoutInflater, container: ViewGroup?): FragmentSavedAreasBinding {
        return FragmentSavedAreasBinding.inflate(layoutInflater, container, false)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        armed = null
    }

    private fun load() {
        inBackground {
            val list = SavedAreas.all(requireContext()).sortedByDescending { it.savedAt }
            onMain {
                areas = list
                draw()
            }
        }
    }

    private fun draw() {
        val rows = binding.rows
        rows.removeAllViews()
        binding.empty.isVisible = areas.isEmpty()
        areas.forEachIndexed { i, area ->
            if (i > 0) rows.addView(rule())
            rows.addView(row(area))
        }
    }

    private fun row(area: SavedArea): View {
        val context = requireContext()
        val pad = dp(16)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(12), pad, dp(12))
            isClickable = true
            isFocusable = true
        }
        val title = AppCompatTextView(context).apply {
            setTextAppearance(themeStyle(MaterialR.attr.textAppearanceBodyLarge))
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            text = if (armed == area.id) {
                getString(R.string.saved_area_delete_armed, area.name)
            } else {
                area.name
            }
        }
        val holds = getString(
            if (area.mapTiles > 0) R.string.saved_area_holds_map else R.string.saved_area_holds_contours
        )
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(area.savedAt).atZone(ZoneId.systemDefault()))
        val detail = AppCompatTextView(context).apply {
            setTextAppearance(themeStyle(MaterialR.attr.textAppearanceBodyMedium))
            setTextColor(Color.BLACK)
            text = getString(R.string.saved_area_detail, holds, formatter.formatFileSize(area.bytes), date)
        }
        row.addView(title)
        row.addView(detail)
        row.setOnClickListener { tapped(area) }
        return row
    }

    private fun tapped(area: SavedArea) {
        handler.removeCallbacksAndMessages(null)
        if (armed != area.id) {
            armed = area.id
            draw()
            // An armed row disarms itself, so a stray tap never leaves a live delete behind.
            handler.postDelayed({
                armed = null
                if (isBound) draw()
            }, 4_000)
            return
        }
        armed = null
        inBackground {
            SavedAreas.delete(requireContext(), area)
            load()
        }
    }

    private fun rule(): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
            setBackgroundColor(Color.BLACK)
        }
    }

    private fun themeStyle(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return value.resourceId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
