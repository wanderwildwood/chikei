package com.kylecorry.trail_sense.shared.house

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kylecorry.trail_sense.BuildConfig
import com.kylecorry.trail_sense.R

/**
 * This app's About: only the words and the address. The dialog, its drawers, its one Close button,
 * the Licenses drawer, the links and the llama are the shared HouseAbout.kt, the same in every app.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val data = AboutData(
        title = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
        sections = listOf(
            AboutSection(null, stringResource(R.string.about_what)),
            AboutSection(stringResource(R.string.about_privacy_title), stringResource(R.string.about_privacy)),
            AboutSection(stringResource(R.string.privacy_who_title), stringResource(R.string.privacy_who)),
            AboutSection(stringResource(R.string.privacy_contact_title), stringResource(R.string.privacy_contact)),
            AboutSection(stringResource(R.string.privacy_rights_title), stringResource(R.string.privacy_rights)),
            AboutSection(stringResource(R.string.about_terms_title), stringResource(R.string.disclaimer_message_content)),
            AboutSection(null, stringResource(R.string.about_licence)),
            AboutSection(null, stringResource(R.string.about_after)),
            AboutSection(null, stringResource(R.string.about_bundled)),
        ),
        sourceUrl = SOURCE_URL,
        links = listOf(AboutLinkData(stringResource(R.string.about_privacy_policy), PRIVACY_URL)),
    )
    val labels = AboutLabels(
        close = stringResource(R.string.about_close),
        licences = stringResource(R.string.licences_title),
        feedTheLlamas = stringResource(R.string.about_feed_the_llamas),
        noBrowser = { address -> context.getString(R.string.about_no_browser_address, address) },
        writeTo = { address -> context.getString(R.string.about_write_to, address) },
        noMailApp = { address -> context.getString(R.string.about_no_mail_app, address) },
        openDrawer = { heading -> context.getString(R.string.about_open_drawer, heading) },
        closeDrawer = { heading -> context.getString(R.string.about_close_drawer, heading) },
        contact = stringResource(R.string.about_contact_head),
        links = stringResource(R.string.about_links_head),
    )
    HouseAboutDialog(data, labels, R.drawable.llama, onDismiss)
}

/** The full privacy text, in the source repository (the short version is in the About itself). */
const val PRIVACY_URL = "https://github.com/wanderwildwood/chikei/blob/main/PRIVACY.md"
