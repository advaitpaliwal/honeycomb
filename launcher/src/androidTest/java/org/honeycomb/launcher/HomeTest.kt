package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The home on the real activity: seven favourites by use, and press-and-hold reorder that survives a restart. */
@RunWith(AndroidJUnit4::class)
class HomeTest {
    /** Notification-policy access before the activity starts, so its one-time ask does not cover the home screen. */
    @get:Rule(order = -2) val setup = SetupFinished()
    @get:Rule(order = -1) val dnd = org.junit.rules.TestRule { base, _ -> object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            val i = InstrumentationRegistry.getInstrumentation()
            i.uiAutomation.executeShellCommand("cmd notification allow_dnd ${i.targetContext.packageName}").close()
            Thread.sleep(300)
            base.evaluate()
        }
    } }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun fresh() {
        AppOrder.clear(ctx)
        AppOrder.saveGrid(ctx, false)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A tap on the face opens the dynamic grid, on every screen (Space.kt). */
    private fun favourites() {
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("favourites").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun allApps() {
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("favourites").fetchSemanticsNodes().isNotEmpty() }
        rule.allAppsAction()
    }

    private fun dragOnto(from: SemanticsNodeInteraction, to: SemanticsNodeInteraction) {
        val delta = to.fetchSemanticsNode().boundsInRoot.center - from.fetchSemanticsNode().boundsInRoot.center
        holdAndDrag(from, delta)
    }

    private fun holdAndDrag(node: SemanticsNodeInteraction, by: Offset) = node.performTouchInput {
        down(center)
        advanceEventTime(900) // past the long-press timeout: edit mode
        repeat(12) { moveBy(by / 12f, delayMillis = 16) }
        up()
    }

    @Test fun favouritesKeepTheWatchClusterCentredInTheWindow() {
        favourites()
        rule.waitForIdle()
        shot("favourites-insets")
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val left = rule.onNodeWithTag("pin-2").fetchSemanticsNode().boundsInRoot.left
        val right = rule.onNodeWithTag("pin-3").fetchSemanticsNode().boundsInRoot.right
        val density = ctx.resources.displayMetrics.density
        val width = minOf(screen.width, 600f * density)
        assertEquals(width * (422f - 28f - 30.3f) / 422f, right - left, 2f)
        assertEquals(left - screen.left, screen.right - right, 1f)
        val phone = rule.onNodeWithTag("pin-0").fetchSemanticsNode().boundsInRoot.center
        assertEquals(screen.center.x, phone.x, 1f)
        val top = rule.onNodeWithTag("pin-1").fetchSemanticsNode().boundsInRoot.center.y
        val bottom = rule.onNodeWithTag("pin-6").fetchSemanticsNode().boundsInRoot.center
        assertTrue("the centre app between the top and bottom apps", top < phone.y && phone.y < bottom.y)
        assertEquals(screen.center.x, bottom.x, 1f)
    }

    /** The dynamic grid's apps by use, as the home ranks them (usedPackages), with nothing pinned. */
    private fun byUse(): List<String> {
        val apps = AppCache.apps.value!!
        val label = apps.associate { it.component.flattenToString() to it.label }
        return AppOrder.favourites(emptyList(), apps.map { it.component.flattenToString() }, usedPackages(ctx), { label[it].orEmpty() })
    }

    @Test fun theDynamicGridHoldsSevenAppsByUseTheMostUsedInTheCentreAndNoAllAppsCircle() {
        favourites()
        val pins = byUse()
        assertEquals(7, pins.size)
        val label = { k: String -> AppCache.apps.value!!.first { it.component.flattenToString() == k }.label }
        (0 until 7).forEach { rule.onNodeWithTag("pin-$it").assertIsDisplayed().assertContentDescriptionEquals(label(pins[it])) }
        assertTrue(rule.onAllNodesWithTag("pin-7").fetchSemanticsNodes().isEmpty())
        // no all-apps circle: nothing on screen is called All apps, the full grid is a swipe left or up away
        assertTrue(rule.onAllNodesWithTag("all-apps", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithContentDescription("All apps", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shot("home-favourites")
    }

    @Test fun theAllAppsAccessibilityActionOpensTheFullGrid() {
        favourites()
        assertEquals(1, rule.allAppsActions().size)
        rule.allAppsAction()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("app-list").fetchSemanticsNodes().isNotEmpty() || rule.onAllNodesWithTag("app-grid").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun draggingAFavouriteOntoAnotherSwapsThemAndSurvivesARestart() {
        favourites()
        val before = byUse()
        dragOnto(rule.onNodeWithTag("pin-2"), rule.onNodeWithTag("pin-4"))
        rule.waitForIdle()
        val swapped = AppOrder.swap(before, 2, 4)
        // only the two slots moved are pinned; the rest stay free to follow use
        assertEquals(AppOrder.pinned(emptyList(), before, swapped), AppOrder.savedPins(ctx))
        rule.activityRule.scenario.recreate()
        favourites()
        val label = { k: String -> AppCache.apps.value!!.first { it.component.flattenToString() == k }.label }
        rule.onNodeWithTag("pin-4").assertContentDescriptionEquals(label(swapped[4]))
        rule.onNodeWithTag("pin-2").assertContentDescriptionEquals(label(swapped[2]))
    }

    @Test fun listViewIsAlphabeticalWithFastScrubber() {
        allApps()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("app-list").fetchSemanticsNodes().isNotEmpty() }
        val sorted = alphabeticalApps(AppCache.apps.value!!)
        if (sorted.size >= 3) {
            val tops = (0..2).map { rule.onNodeWithTag("row-${sorted[it].component.flattenToString()}").fetchSemanticsNode().positionInRoot.y }
            assertTrue("rows appear in strict alphabetical order: $tops", tops[0] < tops[1] && tops[1] < tops[2])
        }
        if (sorted.size >= 6) {
            rule.onNodeWithTag("app-list-scrubber").assertIsDisplayed()
        }
        shot("home-list-alphabetical")
    }
}
