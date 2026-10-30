package com.kylecorry.trail_sense.shared.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.EditText
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.theme.MaterialComponentsViewInflater

/**
 * A text cursor that does not blink. The platform one is repainted twice a second, which on an
 * e-ink panel is a refresh for nothing, so the platform cursor is switched off and a steady
 * 2dp bar is drawn in its place while the field has focus and nothing is selected.
 */
internal class SteadyCaret(private val view: EditText) {
    private val paint = Paint().apply { color = android.graphics.Color.BLACK }
    private val bounds = Rect()
    private val width = 2f * view.resources.displayMetrics.density

    init {
        view.isCursorVisible = false
    }

    fun draw(canvas: Canvas) {
        val layout = view.layout ?: return
        if (!view.isFocused || !view.isEnabled) return
        val start = view.selectionStart
        if (start < 0 || start != view.selectionEnd) return
        val line = layout.getLineForOffset(start)
        view.getLineBounds(line, bounds)
        val x = (view.compoundPaddingLeft + layout.getPrimaryHorizontal(start)).coerceAtLeast(0f)
        canvas.drawRect(x, bounds.top.toFloat(), x + width, bounds.bottom.toFloat(), paint)
    }
}

class SteadyEditText(context: Context, attrs: AttributeSet?) : AppCompatEditText(context, attrs) {
    private val caret = SteadyCaret(this)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        caret.draw(canvas)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        invalidate()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }
}

class SteadyTextInputEditText(context: Context, attrs: AttributeSet?) :
    TextInputEditText(context, attrs) {
    private val caret = SteadyCaret(this)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        caret.draw(canvas)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        invalidate()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        invalidate()
    }
}

/** Every EditText and TextInputEditText inflated from a layout gets the steady cursor. */
class SteadyViewInflater : MaterialComponentsViewInflater() {
    override fun createEditText(context: Context, attrs: AttributeSet): AppCompatEditText =
        SteadyEditText(context, attrs)

    override fun createView(context: Context, name: String, attrs: AttributeSet): android.view.View? =
        if (name == "com.google.android.material.textfield.TextInputEditText") {
            SteadyTextInputEditText(context, attrs)
        } else {
            null
        }
}
