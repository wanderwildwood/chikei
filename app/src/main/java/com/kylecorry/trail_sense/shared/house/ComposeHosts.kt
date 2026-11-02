package com.kylecorry.trail_sense.shared.house

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.constraintlayout.widget.ConstraintLayout
import com.mudita.mmd.ThemeMMD
import androidx.compose.ui.platform.LocalCursorBlinkEnabled

/**
 * The app is made of Views; the shared About and Licences are Compose in Mudita's MMD. These
 * two small hosts are the only places the two meet.
 */
@Composable
fun HouseTheme(content: @Composable () -> Unit) {
    ThemeMMD(colorScheme = monochrome) {
        CompositionLocalProvider(LocalCursorBlinkEnabled provides false) { content() }
    }
}

/**
 * Shows the About dialog over [root]. The dialog is its own window, so the host view that carries
 * it has no size; it is taken out again when the dialog closes.
 */
fun showAboutDialog(context: Context, root: ConstraintLayout) {
    val host = ComposeView(context)
    host.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    host.setContent {
        HouseTheme { AboutDialog(onDismiss = { root.removeView(host) }) }
    }
    root.addView(host, ConstraintLayout.LayoutParams(0, 0))
}

/** A full-screen Licences list, for the Settings entry. */
fun licencesView(context: Context): ComposeView = ComposeView(context).apply {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        HouseTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                LicencesScreen(
                    rememberLicencesData(),
                    rememberLicencesLabels(),
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    showBackButton = false,
                )
            }
        }
    }
}
