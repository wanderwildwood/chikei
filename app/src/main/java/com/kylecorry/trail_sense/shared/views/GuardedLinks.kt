package com.kylecorry.trail_sense.shared.views

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import com.kylecorry.trail_sense.R

/** A web link that opens in a real browser, or says there is none. Never the system's WebView shell. */
class GuardedUrlSpan(private val url: String) : ClickableSpan() {
    override fun onClick(widget: View) {
        openGuarded(widget.context, url)
    }
}

/** Opens [url] through the shared guarded opener, with the "no browser" message carrying the address. */
fun openGuarded(context: Context, url: String) {
    openLink(context, url, context.getString(R.string.about_no_browser_address, url))
}

/** Swaps every [URLSpan] (what Linkify and Markdown make) for a [GuardedUrlSpan]. */
fun CharSequence.guardLinks(): CharSequence {
    val out = SpannableStringBuilder(this)
    for (span in out.getSpans(0, out.length, URLSpan::class.java)) {
        val start = out.getSpanStart(span)
        val end = out.getSpanEnd(span)
        val flags = out.getSpanFlags(span)
        out.removeSpan(span)
        out.setSpan(GuardedUrlSpan(span.url), start, end, flags or Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return out
}

/** For a text view that shows links: guards them and lets them be tapped. */
fun TextView.guardLinks() {
    text = text.guardLinks()
    movementMethod = LinkMovementMethod.getInstance()
}
