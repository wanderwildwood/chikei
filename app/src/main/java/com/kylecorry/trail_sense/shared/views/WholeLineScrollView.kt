package com.kylecorry.trail_sense.shared.views

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView
import android.widget.TextView

/**
 * A scroll view that never shows half a line of the text in it: the line the foot cuts is not
 * drawn until a scroll brings it up whole. The introduction pages read as pages of whole lines,
 * with the button below always in reach.
 *
 * It looks for the page's text by [textId]; with no such view, it scrolls as any ScrollView does.
 */
class WholeLineScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle
) : ScrollView(context, attrs, defStyleAttr) {

    var textId: Int = View.NO_ID

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateClip()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateClip()
        // Text set after this layout (a new page) is laid out in the pass after it.
        post { updateClip() }
    }

    private fun updateClip() {
        val text = if (textId == View.NO_ID) null else findViewById<TextView>(textId)
        val layout = text?.layout
        if (text == null || layout == null || height == 0) {
            clipBounds = null
            return
        }
        var top = 0
        var v: View = text
        while (v !== this) {
            top += v.top
            v = v.parent as? View ?: run { clipBounds = null; return }
        }
        val bottoms = IntArray(layout.lineCount) { top + layout.getLineBottom(it) }
        val usable = scrollY + height - paddingBottom
        val cut = firstCutLine(bottoms, usable)
        val wanted = if (cut < 0) null else Rect(0, 0, width, top + layout.getLineTop(cut) - scrollY)
        if (clipBounds != wanted) clipBounds = wanted
    }
}

/**
 * The first line whose bottom lies below [usableBottom] (the lines' bottoms, in the scroll
 * view's own coordinates), or -1 when every line fits. A line is never the first cut if it is
 * the first line of the text: the clip then would be above the text itself.
 */
internal fun firstCutLine(lineBottoms: IntArray, usableBottom: Int): Int {
    val i = lineBottoms.indexOfFirst { it > usableBottom }
    return if (i <= 0) -1 else i
}
