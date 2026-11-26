package org.honeycomb.launcher

import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test

/**
 * Android keeps the bottom-edge swipe for itself and hands it to the home app as a Home intent. With Honeycomb in
 * front it is the watch's Crown press: from the bare face it opens the dynamic grid, and the next one returns to the
 * face. A swipe up from the middle of the face raises the Smart Stack.
 */
class HomeOnFaceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Honeycomb holds HOME for these tests, as on the owner's phone, so a Home intent reaches this instance. */
    @get:Rule(order = 0) val beHome = object : ExternalResource() {
        override fun before() {
            val inst = InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                inst.uiAutomation.executeShellCommand("cmd package set-home-activity ${inst.targetContext.packageName}/.LauncherActivity")
            ).bufferedReader().use { it.readText() }
        }
    }
    @get:Rule(order = 1) val setup = SetupFinished()
    private fun homeIntent() = Intent(ctx, LauncherActivity::class.java).setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @get:Rule(order = 2) val rule = AndroidComposeTestRule(ActivityScenarioRule<LauncherActivity>(homeIntent())) { owner ->
        lateinit var activity: LauncherActivity
        owner.scenario.onActivity { activity = it }
        activity
    }

    private fun stackShown() = shown("smart-stack")

    /** The Home intent the system sends for the bottom-edge swipe, once Honeycomb's window has focus. */
    private fun home() {
        rule.waitUntil(10_000) { rule.activity.hasWindowFocus() }
        ctx.startActivity(homeIntent())
    }

    /**
     * The Home intent pauses and resumes the activity (onNewIntent), and a read that lands in between finds no resumed
     * Compose hierarchy and throws (CI run 45154740375). That moment is "not shown yet", so the wait polls again.
     */
    private fun shown(tag: String) =
        try { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }

    @Test fun homeOnTheBareFaceOpensTheDynamicGridAndHomeAgainReturnsToTheFace() {
        rule.waitUntil(10_000) { shown("faces") }
        check(!shown("favourites"))
        home()
        rule.waitUntil(5_000) { shown("favourites") }
        check(!stackShown())
        home()
        rule.waitUntil(5_000) { !shown("favourites") }
        rule.waitUntil(5_000) { shown("faces") }
        check(!stackShown())
    }

    /** A short swipe up (24 dp) from the middle of the face is enough to raise the stack. */
    @Test fun aShortSwipeUpFromTheMiddleOfTheFaceRaisesTheStack() {
        rule.waitUntil(10_000) { shown("faces") }
        val dp = ctx.resources.displayMetrics.density
        rule.onNodeWithTag("faces").performTouchInput { swipeUp(startY = centerY, endY = centerY - 24 * dp, durationMillis = 150) }
        rule.waitUntil(5_000) { stackShown() }
        rule.waitForIdle()
        check(stackShown())
    }

    /** A relaunch (a configuration change) re-reads the Home intent it started with; it is not a second Home. */
    @Test fun aRelaunchLeavesTheFaceBare() {
        rule.waitUntil(10_000) { rule.activity.hasWindowFocus() }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("faces").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        check(!stackShown())
    }
}
