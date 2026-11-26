package org.honeycomb.launcher

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * The real bottom-edge swipe, injected as a finger, on Honeycomb running as the system's home (not a test-launched
 * copy). The system runs its recents transition first, which takes window focus from Honeycomb before the Home intent
 * lands, and the swipe's first move reaches the face before the system takes the pointer. Ten swipes on the bare face
 * each open the dynamic grid, and the next swipe returns to the face.
 */
class HomeSwipeOnFaceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun sh(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(c))
        .bufferedReader().use { it.readText() }

    @get:Rule(order = 0) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createEmptyComposeRule()

    /** Between activity transitions Compose briefly reports no resumed hierarchy; that is "not shown yet". */
    private fun shown(tag: String) =
        try { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }

    private fun swipeHome() {
        val (w, h) = sh("wm size").substringAfterLast(": ").trim().split("x").map { it.toInt() }
        sh("input swipe ${w / 2} ${h - 3} ${w / 2} ${h * 2 / 3} 120")
    }

    @Test fun theBottomEdgeSwipeOnTheBareFaceOpensTheDynamicGridAndAgainReturnsToTheFace() {
        sh("cmd overlay enable com.android.internal.systemui.navbar.gestural")
        sh("cmd package set-home-activity ${inst.targetContext.packageName}/.LauncherActivity")
        sh("input keyevent KEYCODE_HOME")
        repeat(10) { i ->
            rule.waitUntil(10_000) { shown("faces") && !shown("favourites") && !shown("smart-stack") }
            Thread.sleep(600)
            swipeHome()
            rule.waitUntil(5_000) { shown("favourites") }
            Thread.sleep(600)
            check(shown("favourites") && !shown("smart-stack")) { "swipe $i: the grid did not stay open" }
            swipeHome()
            rule.waitUntil(5_000) { shown("faces") && !shown("favourites") }
        }
    }
}
