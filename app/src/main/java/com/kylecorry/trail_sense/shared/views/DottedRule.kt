package com.kylecorry.trail_sense.shared.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors

/**
 * The rule between the rows of an ordinary list (house rule, 3 Nov 2026): a thin dotted line
 * inset to the text on both sides, as the Kompakt's own contacts list draws it, in place of the
 * solid separator the upstream lists used. The dashes are the house ones (Feeds' DottedRule.kt):
 * about 2.3 dp of ink, 1.5 dp of gap, 1 dp thick and never more than 1.5 px.
 *
 * Drawn over the foot of each row rather than between rows, so no row changes height and a page
 * still ends where it did.
 */
/** Added to a list with `addItemDecoration(DottedRule())`. */
class DottedRule(private val insetDp: Float = 16f) : RecyclerView.ItemDecoration() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val density = parent.resources.displayMetrics.density
        paint.color = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        paint.strokeWidth = (1f * density).coerceAtMost(1.5f)
        paint.pathEffect = DashPathEffect(floatArrayOf(2.3f * density, 1.5f * density), 0f)
        val inset = insetDp * density
        val left = parent.paddingLeft + inset
        val right = parent.width - parent.paddingRight - inset
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val y = child.bottom + child.translationY - paint.strokeWidth / 2
            c.drawLine(left, y, right, y, paint)
        }
    }
}
