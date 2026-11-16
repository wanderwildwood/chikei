package com.kylecorry.trail_sense.shared.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatCheckedTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import com.kylecorry.trail_sense.shared.house.DarkMode

/**
 * Dark mode for every window the app opens over its pages: dialogs, pickers, bottom sheets,
 * menus and popups.
 *
 * They are made in many places (and most of them inside the Andromeda library), but all of
 * them inflate their layouts through the activity's inflater, and so through this class (the
 * theme's `viewInflaterClass`). Each text, button or image made here tells [DarkMode] which
 * window it lands in the first time it is attached to one; a window already drawn through
 * the filter (the activity's own) is left as it is.
 */
class DarkWindowsViewInflater : SteadyViewInflater() {

    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView =
        mark(super.createTextView(context, attrs))

    override fun createButton(context: Context, attrs: AttributeSet): AppCompatButton =
        mark(super.createButton(context, attrs))

    override fun createImageView(context: Context, attrs: AttributeSet?): AppCompatImageView =
        mark(super.createImageView(context, attrs))

    override fun createImageButton(context: Context, attrs: AttributeSet?): AppCompatImageButton =
        mark(super.createImageButton(context, attrs))

    override fun createEditText(context: Context, attrs: AttributeSet): AppCompatEditText =
        mark(super.createEditText(context, attrs))

    override fun createCheckBox(context: Context, attrs: AttributeSet): AppCompatCheckBox =
        mark(super.createCheckBox(context, attrs))

    override fun createRadioButton(context: Context, attrs: AttributeSet): AppCompatRadioButton =
        mark(super.createRadioButton(context, attrs))

    override fun createCheckedTextView(context: Context, attrs: AttributeSet?): AppCompatCheckedTextView =
        mark(super.createCheckedTextView(context, attrs))

    private fun <T : View> mark(view: T): T {
        view.addOnAttachStateChangeListener(FirstWindow)
        return view
    }

    private object FirstWindow : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.removeOnAttachStateChangeListener(this)
            DarkMode.window(v)
        }

        override fun onViewDetachedFromWindow(v: View) {}
    }
}
