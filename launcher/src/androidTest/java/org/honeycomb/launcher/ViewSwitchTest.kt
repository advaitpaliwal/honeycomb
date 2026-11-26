package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The watch ends each all-apps view with one button naming the other view (support.apple.com/guide/watch/apd3cd8641c2:
 * "Scroll to the bottom, then choose an option: List view / Grid view"). Both ends must show the same control: the same
 * size, at the same distance from the screen's bottom edge, only its label and glyph flipped.
 */
class ViewSwitchTest {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 0) val rule = createAndroidComposeRule<LauncherActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun grid() {
        AppOrder.clear(ctx)
        AppOrder.saveGrid(ctx, true)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(60_000) { AppCache.apps.value?.isNotEmpty() == true }
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(1_000); rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence/view-switch").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun button(name: String): Rect = rule.onNode(hasContentDescription(name) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    @Test fun bothViewsEndInTheSameButton() {
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        rule.waitUntil(5_000) { shown("favourites") }; rule.allAppsAction()
        rule.waitUntil(5_000) { shown("app-grid") }
        repeat(6) { rule.onNodeWithTag("app-grid").performTouchInput { swipeUp() } }
        shot("grid-bottom")
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val grid = button("List View")
        rule.onNode(hasContentDescription("List View") and hasClickAction()).performTouchInput { click(center) }
        rule.waitUntil(5_000) { shown("app-list") }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("grid-view"))
        // to the list's real end, as a thumb would take it, not just until the button first shows
        repeat(3) { rule.onNodeWithTag("app-list").performTouchInput { swipeUp() } }
        shot("list-bottom")
        val list = button("Grid View")
        assertEquals("width", grid.width, list.width, 1f)
        assertEquals("height", grid.height, list.height, 1f)
        assertEquals("centre from the left edge", grid.center.x, list.center.x, 1f)
        assert(grid.bottom <= root.bottom && list.bottom <= root.bottom) {
            "View switch button clipped past root bottom: grid=${grid.bottom} list=${list.bottom} root=${root.bottom}"
        }
    }
}
