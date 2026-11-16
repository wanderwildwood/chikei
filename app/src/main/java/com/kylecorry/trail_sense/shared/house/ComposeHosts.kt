package com.kylecorry.trail_sense.shared.house

import android.content.Context
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.mudita.mmd.ThemeMMD
import androidx.compose.ui.platform.LocalCursorBlinkEnabled

/**
 * The app is made of Views; the shared About and Licences are Compose in Mudita's MMD. These
 * two small hosts are the only places the two meet.
 */
@Composable
fun HouseTheme(content: @Composable () -> Unit) {
    ThemeMMD(colorScheme = monochrome, typography = houseTypography) { DarkSelection {
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false) { content() }
    } }
}

/**
 * Shows the About dialog over [root]. The dialog is its own window, so the host view that carries
 * it has no size; it is taken out again when the dialog closes. Taking the host out alone would
 * leave the dialog up (its composition outlives the view), so Close ends the composition first.
 */
fun showAboutDialog(context: Context, root: ViewGroup) {
    val host = ComposeView(context)
    host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    host.setContent {
        HouseTheme { AboutDialog(onDismiss = { host.disposeComposition(); root.removeView(host) }) }
    }
    root.addView(host, ViewGroup.LayoutParams(0, 0))
}
