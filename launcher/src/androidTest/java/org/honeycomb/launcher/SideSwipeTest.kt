package org.honeycomb.launcher

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The sideways swipe, injected as a real finger on Honeycomb running as the system's home under gesture navigation, so
 * Android's own Back gesture competes for the side edges as it does on a phone. A swipe left goes in a level (face,
 * dynamic grid, full grid) and a swipe right comes back out; the middle of the right edge is kept from Back on the face
 * and the dynamic grid only, so Back works from the whole left edge (including on the dynamic grid), the rest of the
 * right edge, and all of the right edge on the full grid.
 */
class SideSwipeTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun sh(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(c))
        .bufferedReader().use { it.readText() }

    @get:Rule(order = 0) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createEmptyComposeRule()

    private fun shown(tag: String) =
        try { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }

    private val face get() = shown("faces") && !shown("favourites") && !shown("smart-stack")
    private val dynamic get() = shown("favourites")
    private val full get() = (shown("app-grid") || shown("app-list")) && !shown("favourites")

    private var w = 0
    private var h = 0

    /** The kept band's middle and a point on the edge well below it, in px (real dp, as the system measures). */
    private val middle get() = h / 2
    private val belowBand get() = (h / 2 + (SideSwipe.BAND_DP * inst.targetContext.resources.displayMetrics.density) / 2 + h / 10).toInt()

    private fun swipe(x0: Int, y0: Int, x1: Int, y1: Int, ms: Int = 250) { sh("input swipe $x0 $y0 $x1 $y1 $ms") }
    private fun left(y: Int = middle, from: Int = w * 4 / 5) = swipe(from, y, w / 5, y)
    private fun right(y: Int = middle) = swipe(w / 5, y, w * 4 / 5, y)
    private fun fromRightEdge(y: Int) = swipe(w - 2, y, w * 2 / 5, y)
    private fun fromLeftEdge(y: Int) = swipe(1, y, w * 3 / 5, y)

    private fun settle(what: String, check: () -> Boolean) {
        rule.waitUntil(5_000) { check() }
        Thread.sleep(400)
        check(check()) { "$what did not stay" }
    }

    @Before fun beHomeWithGestureNavigation() {
        sh("cmd overlay enable com.android.internal.systemui.navbar.gestural")
        sh("cmd package set-home-activity ${inst.targetContext.packageName}/.LauncherActivity")
        val (sw, shh) = sh("wm size").lines().last { it.contains(":") }.substringAfterLast(": ").trim().split("x").map { it.toInt() }
        w = sw; h = shh
        sh("input keyevent KEYCODE_HOME")
        rule.waitUntil(10_000) { face }
        Thread.sleep(600)
    }

    @Test fun aSwipeLeftGoesInALevelAtATimeAndASwipeRightComesBackOutOneAtATime() {
        left(); settle("the dynamic grid") { dynamic }
        left(); settle("the full grid") { full }
        right(); settle("the dynamic grid, back from the full grid") { dynamic }
        right(); settle("the face, back from the dynamic grid") { face }
    }

    @Test fun aSwipeLeftFromTheRightEdgeInItsMiddleGoesInInsteadOfBack() {
        fromRightEdge(middle); settle("the dynamic grid from the right edge") { dynamic }
        // on the dynamic grid Back would go out to the face; the kept right-edge band goes in to the full grid instead
        fromRightEdge(middle); settle("the full grid from the right edge") { full }
    }

    @Test fun backStillWorksFromTheWholeLeftEdgeTheRestOfTheRightEdgeAndAllOfItOnTheFullGrid() {
        // left-edge Back works cleanly everywhere, stepping all the way out without ping-ponging back in
        left(); settle("the dynamic grid") { dynamic }
        left(); settle("the full grid") { full }
        fromLeftEdge(middle); settle("the dynamic grid, by left-edge Back on the full grid") { dynamic }
        fromLeftEdge(middle); settle("the face, by left-edge Back on the dynamic grid") { face }

        // right-edge Back still works outside the kept middle band, and across the whole edge on the full grid
        left(); settle("the dynamic grid") { dynamic }
        fromRightEdge(belowBand); settle("the face, by Back from below the right-edge band") { face }
        left(); settle("the dynamic grid") { dynamic }
        left(); settle("the full grid") { full }
        fromRightEdge(middle); settle("the dynamic grid, by right-edge Back on the full grid") { dynamic }
    }

    @Test fun aSwipeUpThatDriftsSidewaysStillRaisesTheStackAndAShortSideNudgeDoesNothing() {
        swipe(w / 2, h * 7 / 10, w / 2 - w / 12, h * 3 / 10, 300)
        settle("the Smart Stack") { shown("smart-stack") && !shown("favourites") }
        sh("input keyevent KEYCODE_BACK")
        rule.waitUntil(5_000) { face }
        Thread.sleep(600)
        // a sideways move shorter than the swipe's distance, let go without a flick, springs back to the face
        val nudge = (EdgeSwipe.FLICK_DISTANCE_DP * inst.targetContext.resources.displayMetrics.density).toInt()
        swipe(w / 2, middle, w / 2 - nudge, middle, 200)
        settle("the face, sprung back from a short nudge") { face }
    }
}
