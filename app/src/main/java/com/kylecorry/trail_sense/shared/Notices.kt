package com.kylecorry.trail_sense.shared

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.kylecorry.trail_sense.R
import java.lang.ref.WeakReference

/**
 * A notice stays until the next press. On this panel one on a timer (a toast, a short
 * snackbar) could be gone before it had been read; one that waits is read first and goes on
 * the way to whatever the next press does.
 *
 * MainActivity hands its touches and Back here, and says when it is in front, so a notice from
 * work that ends as a page closes, or from the background, still has somewhere to be said.
 */
object Notices {
    private var shown = WeakReference<Snackbar>(null)
    private var front = WeakReference<Activity>(null)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Says [text] at the foot of the page in front, above the tool bar, until the next press.
     * From any thread. With no page of this app in front, the phone's own toast is all there is.
     */
    fun say(context: Context, text: CharSequence) {
        val app = context.applicationContext
        main.post {
            val activity = front.get()
            val root = activity?.findViewById<View>(android.R.id.content)
            if (activity == null || root == null) {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                return@post
            }
            val bar = Snackbar.make(root, text, Snackbar.LENGTH_INDEFINITE)
            activity.findViewById<View>(R.id.bottom_navigation)
                ?.takeIf { it.isShown }
                ?.let { bar.anchorView = it }
            stay(bar)
        }
    }

    /** In place of the library's toast: the same words, said until the next press. */
    fun toast(context: Context, text: String, @Suppress("UNUSED_PARAMETER") short: Boolean = true) =
        say(context, text)

    /** Shows [snackbar] until the next press, in place of whatever was said last. */
    fun stay(snackbar: Snackbar) {
        val before = shown.get()
        if (before != null && before !== snackbar) before.dismiss()
        snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.maxLines = 6
        snackbar.duration = Snackbar.LENGTH_INDEFINITE
        shown = WeakReference(snackbar)
        snackbar.show()
    }

    /** Called by the activity as it comes to the front (with itself) and goes (with null). */
    fun front(activity: Activity?) {
        front = WeakReference(activity)
    }

    /**
     * A finger lifting anywhere takes the notice away, except on its own button (Undo and the
     * like), which then does what it says and takes it away itself.
     */
    fun pressed(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_UP) return
        val snackbar = shown.get() ?: return
        if (!snackbar.isShown) return
        val action = snackbar.view.findViewById<View>(com.google.android.material.R.id.snackbar_action)
        if (action != null && action.visibility == View.VISIBLE && within(action, event)) return
        snackbar.dismiss()
    }

    /** So does Back, or any other button. */
    fun pressed(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) shown.get()?.dismiss()
    }

    private fun within(view: View, event: MotionEvent): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return event.rawX >= at[0] && event.rawX < at[0] + view.width &&
            event.rawY >= at[1] && event.rawY < at[1] + view.height
    }
}

/** In place of the library's toast on a page: said along its foot until the next press. */
fun Fragment.toast(message: String, @Suppress("UNUSED_PARAMETER") short: Boolean = true) {
    val context = context ?: return
    Notices.say(context, message)
}
