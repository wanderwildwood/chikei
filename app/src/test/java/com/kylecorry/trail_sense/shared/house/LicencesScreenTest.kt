package com.kylecorry.trail_sense.shared.house

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.kylecorry.trail_sense.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * The Licences screen drawn at about the Kompakt's size (360 x 600 dp at 4/3 density) and turned
 * page by page with swipes, no keys: the list's last page ends on whole rows, the app's own page reads the
 * whole GPL from the first paragraph to the last with no paragraph cut at the foot, skipped or shown
 * twice, and the link rows are 48 dp tall, inside their page and open their own address.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w360dp-h600dp-tvdpi")
class LicencesScreenTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var data: LicencesData

    @Before fun setUp() {
        // A browser on the test phone, so a link row has somewhere to go.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val browser = ComponentName("org.example.browser", "org.example.browser.Browse")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(browser)
            addIntentFilterForActivity(browser, IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
            })
        }
        rule.setContent {
            data = rememberLicencesData()
            HouseTheme {
                Box(Modifier.size(332.dp, 520.dp)) {
                    LicencesScreen(data, rememberLicencesLabels(), onBack = {}, modifier = Modifier.fillMaxSize())
                }
            }
        }
        rule.waitForIdle()
    }

    /** MMD's list: it takes no scroll actions (it turns pages itself), but it knows its items' keys. */
    private val theList = SemanticsMatcher.keyIsDefined(SemanticsProperties.IndexForKey)

    /** One swipe up on the list, the way a finger turns its page. */
    private fun turn() {
        rule.onNode(theList).performTouchInput { swipeUp() }
        rule.waitForIdle()
    }

    private fun listBounds(): Rect = rule.onNode(theList).fetchSemanticsNode().boundsInRoot

    /** Where the item with [key] sits in the list, or -1. */
    private fun indexOf(key: Any): Int =
        rule.onNode(theList).fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

    /** How many pages the open licence page has (each is one tall item and three empty ones). */
    private fun pageCount(): Int = generateSequence(0) { it + 1 }.takeWhile { indexOf("page-$it") >= 0 }.count()

    private fun textOf(n: SemanticsNode): String =
        n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    /** The texts the list shows now, top to bottom; fails if one is only partly inside the list. */
    private fun shownTexts(): List<String> {
        val list = listBounds()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.overlaps(list) }
            .sortedBy { it.boundsInRoot.top }
        nodes.forEach { n ->
            val b = n.boundsInRoot
            assertTrue("cut at the edge of the page: \"${textOf(n).take(60)}\" $b in $list", b.top >= list.top - 0.5f && b.bottom <= list.bottom + 0.5f)
        }
        return nodes.map(::textOf)
    }

    private fun flat(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** A link row: at least 48 dp tall, inside the page, and a tap opens [url]. */
    private fun checkLink(label: String, url: String) {
        val node = rule.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNode()
        val minPx = with(rule.density) { 48.dp.toPx() }
        assertTrue("$label row is ${node.boundsInRoot.height}px, under 48 dp", node.boundsInRoot.height >= minPx - 0.5f)
        val list = listBounds()
        assertTrue("$label row is not inside its page", node.boundsInRoot.top >= list.top && node.boundsInRoot.bottom <= list.bottom + 0.5f)
        rule.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        rule.waitForIdle()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val started = shadowOf(app).nextStartedActivity
        val opened = started?.data?.toString()
        // With no browser found the app says so, naming the address; either way it is this row's.
        val said = ShadowToast.getTextOfLatestToast()?.toString()
        assertTrue("tapping $label opened \"$opened\" / said \"$said\", not $url", opened == url || (said != null && url.removePrefix("https://") in said))
    }

    @Test fun theListsLastPageEndsOnWholeRows() {
        val rows = licenceRows(data)
        assertEquals(rows.size - 1, indexOf(rows.last().key))
        // Turn until the page stops changing: that is the last one.
        var shown = shownTexts()
        for (i in 0 until rows.size) {
            turn()
            val next = shownTexts()
            if (next == shown) break
            shown = next
        }
        // The last row, name and author, is on the page and whole.
        assertTrue(shown.contains(rows.last().title))
        assertEquals(rows.last().subtitle, shown.last())
    }

    @Test fun theAppPageReadsTheWholeGplPageByPageWithNothingCut() {
        rule.onNode(hasText(data.appName)).performClick()
        rule.waitUntil(10_000) { pageCount() > 5 && rule.onAllNodes(hasText("GNU GENERAL PUBLIC LICENSE", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        checkLink(ApplicationProvider.getApplicationContext<Application>().getString(R.string.licences_source_head), SOURCE_URL)

        val pages = pageCount()
        assertTrue("the GPL should take many pages, got $pages", pages > 5)
        val read = mutableListOf<String>()
        for (p in 0 until pages) {
            assertEquals(p * LICENCE_ROWS_PER_PAGE, indexOf("page-$p"))
            if (p > 0) turn()
            val shown = shownTexts()
            assertTrue("page ${p + 1} of $pages is empty", shown.isNotEmpty())
            read += shown
        }
        val gpl = ApplicationProvider.getApplicationContext<Application>().resources.openRawResource(R.raw.gpl_3_0)
            .bufferedReader().use { it.readText() }
        val paragraphs = licenceParagraphs(gpl)
        // Title, licence line and Source code come first; then every paragraph of the GPL in order,
        // each once, the last of them on the last page.
        assertEquals(data.appName, read[0])
        assertEquals(flat(paragraphs.joinToString(" ")), flat(read.drop(3).joinToString(" ")))
        assertEquals(flat(paragraphs.last()), flat(read.last()))
        // A further swipe stays on the last page.
        val lastPage = shownTexts()
        turn()
        assertEquals(lastPage, shownTexts())
    }

    @Test fun aLibraryPageLinkOpensItsWebsite() {
        val rows = licenceRows(data)
        val last = rows.last()
        val component = allComponents(data).last()
        assertEquals(last.title, component.name)
        var shown = shownTexts()
        while (last.title !in shown) {
            turn()
            val next = shownTexts()
            assertTrue("the list stopped turning before ${last.title}", next != shown)
            shown = next
        }
        rule.onNode(hasText(last.title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(component.url.removePrefix("https://"))).fetchSemanticsNodes().isNotEmpty() }
        checkLink(component.url.removePrefix("https://"), component.url)
    }
}
