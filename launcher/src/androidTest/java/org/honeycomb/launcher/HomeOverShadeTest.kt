package org.honeycomb.launcher

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * The real bottom-edge swipe while the system's notification shade or Quick Settings is open over the face. As on the
 * watch, where the Crown closes Notification or Control Centre before anything else, the swipe only closes the shade:
 * the face stays and the dynamic grid does not open. The next swipe, on the bare face, opens the grid as usual.
 */
class HomeOverShadeTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private fun sh(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(c))
        .bufferedReader().use { it.readText() }

    @get:Rule(order = 0) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createEmptyComposeRule()

    /** Between activity transitions Compose briefly reports no resumed hierarchy; that is "not shown yet". */
    private fun shown(tag: String) =
        try { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } catch (_: IllegalStateException) { false }

    private fun focus() = sh("dumpsys window displays").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty()
    private fun shadeOpen() = "NotificationShade" in focus()
    private fun faceInFront() = "${inst.targetContext.packageName}/" in focus()

    /**
     * The bottom-edge swipe, to [to] of the screen height. Fully open Quick Settings ignores one that stops at two thirds
     * of the height under the stock Pixel launcher too, and closes on one that reaches the top third.
     */
    private fun swipeHome(to: Float = 2 / 3f) {
        val (w, h) = sh("wm size").lineSequence().last { it.isNotBlank() }.substringAfterLast(": ").trim().split("x").map { it.toInt() }
        sh("input swipe ${w / 2} ${h - 3} ${w / 2} ${(h * to).toInt()} 120")
    }

    private fun bareFace() {
        sh("cmd overlay enable com.android.internal.systemui.navbar.gestural")
        sh("cmd package set-home-activity ${inst.targetContext.packageName}/.LauncherActivity")
        sh("input keyevent KEYCODE_HOME")
        rule.waitUntil(10_000) { faceInFront() && shown("faces") && !shown("favourites") && !shown("smart-stack") }
        Thread.sleep(600)
    }

    private fun homeClosesTheShadeAndKeepsTheFace(open: String) {
        bareFace()
        repeat(3) { i ->
            sh("cmd statusbar $open")
            rule.waitUntil(5_000) { shadeOpen() }
            Thread.sleep(600)
            swipeHome(to = 1 / 3f)
            rule.waitUntil(5_000) { !shadeOpen() && faceInFront() }
            Thread.sleep(1_000)
            check(!shadeOpen()) { "$open, swipe $i: the shade stayed open" }
            check(shown("faces") && !shown("favourites") && !shown("smart-stack")) { "$open, swipe $i: the face did not stay" }
        }
        swipeHome()
        rule.waitUntil(5_000) { shown("favourites") }
        swipeHome()
        rule.waitUntil(5_000) { shown("faces") && !shown("favourites") }
    }

    @Test fun homeWithTheNotificationShadeOpenClosesItAndTheFaceStays() = homeClosesTheShadeAndKeepsTheFace("expand-notifications")

    @Test fun homeWithQuickSettingsFullyOpenClosesItAndTheFaceStays() = homeClosesTheShadeAndKeepsTheFace("expand-settings")
}
