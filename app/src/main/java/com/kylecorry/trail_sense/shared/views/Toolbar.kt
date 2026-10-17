package com.kylecorry.trail_sense.shared.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.extensions.flatten

class Toolbar(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    val leftButton: MaterialButton
    val rightButton: MaterialButton
    val title: TextView
    val subtitle: TextView
    private val textContainer: LinearLayout

    init {
        inflate(context, R.layout.view_toolbar, this)
        leftButton = findViewById(R.id.toolbar_left_button)
        rightButton = findViewById(R.id.toolbar_right_button)
        title = findViewById(R.id.toolbar_title)
        subtitle = findViewById(R.id.toolbar_subtitle)
        textContainer = findViewById(R.id.toolbar_text_container)

        val a = context.theme.obtainStyledAttributes(attrs, R.styleable.Toolbar, 0, 0)
        title.text = a.getString(R.styleable.Toolbar_title) ?: ""
        subtitle.text = a.getString(R.styleable.Toolbar_subtitle) ?: ""

        subtitle.isVisible = a.getBoolean(R.styleable.Toolbar_showSubtitle, true)

        val leftIcon = a.getResourceId(R.styleable.Toolbar_leftButtonIcon, -1)
        val rightIcon = a.getResourceId(R.styleable.Toolbar_rightButtonIcon, -1)

        if (leftIcon != -1) {
            leftButton.isVisible = true
            leftButton.setIconResource(leftIcon)
        }

        if (rightIcon != -1) {
            rightButton.isVisible = true
            rightButton.setIconResource(rightIcon)
        }

        val flattenQuickActions =
            a.getBoolean(R.styleable.Toolbar_flattenButtons, false)
        if (flattenQuickActions) {
            rightButton.flatten()
            leftButton.flatten()
        }

        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // The title starts where TopAppBarMMD starts it: after the way back when there is one,
        // otherwise 16dp in (12dp here plus the bar's own 4dp), and stops short of the action.
        val start = getActionSpace(leftButton).takeIf { it > 0 } ?: titleInset
        val end = getActionSpace(rightButton)
        if (textContainer.paddingStart != start || textContainer.paddingEnd != end) {
            textContainer.setPaddingRelative(
                start,
                textContainer.paddingTop,
                end,
                textContainer.paddingBottom
            )
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private val titleInset = (12 * resources.displayMetrics.density).toInt()

    private fun getActionSpace(button: View): Int {
        if (!button.isVisible) {
            return 0
        }

        val margins = button.layoutParams as? MarginLayoutParams
        return button.measuredWidth + (margins?.marginStart ?: 0) +
                (margins?.marginEnd ?: 0)
    }

}
