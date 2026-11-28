package org.honeycomb.launcher

import android.app.NotificationManager
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

/** Control Centre, Quiet and the all-apps views on the real activity. */
@RunWith(AndroidJUnit4::class)
class ControlCentreDeviceTest {
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
    private fun sh(cmd: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()

    @Before fun fresh() {
        sh("cmd notification allow_dnd ${ctx.packageName}")
        AppOrder.clear(ctx)
        // these tests start in List View, which a tall screen's first open would otherwise skip (Grid View is its default)
        AppOrder.saveGrid(ctx, false)
        rule.activityRule.scenario.recreate()
        appsLoaded()
    }

    /** From the face to all apps: through the dynamic grid, on every screen. */
    private fun openAllApps() {
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        waitFor("favourites")
        rule.allAppsAction()
    }

    /**
     * The app list's first load, a precondition rather than a timing check: on a freshly booted CI emulator both cores
     * are busy with first-boot work, and the first load has taken more than 20 s there.
     */
    private fun appsLoaded() = rule.waitUntil(60_000) { AppCache.apps.value?.isNotEmpty() == true }

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun gone(tag: String) = rule.waitUntil(5_000) { !shown(tag) }
    private fun waitFor(tag: String) = try { rule.waitUntil(5_000) { shown(tag) } } catch (e: Throwable) {
        throw AssertionError("no $tag in " + rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 6), e)
    }

    /** Whether [tag]'s content is taller than the screen, so a swipe up scrolls it before the sheet closes. */
    private fun overflows(tag: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasScrollAction()).fetchSemanticsNodes()
        .any { (it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) { null }?.maxValue?.invoke() ?: 0f) > 0f }

    /** Whether [tag]'s scrolling content is at its end (or does not scroll at all). */
    private fun atEnd(tag: String) = rule.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and hasScrollAction()).fetchSemanticsNodes()
        .all { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) { null }
            ?.let { it.value() >= it.maxValue() } ?: true }

    private val dp get() = rule.activity.resources.displayMetrics.density

    /**
     * A drag down from the window's top edge, starting [xFraction] of the way across, 120 dp long. It starts on the root,
     * since the screens sit inside the window margins and [tag]'s own top is below the edge band; [tag] must be showing.
     */
    private fun swipeDownFromTop(tag: String, xFraction: Float) = rule.onNodeWithTag(tag).assertExists().let { rule.onRoot().performTouchInput {
        down(Offset(width * xFraction, 4f))
        repeat(10) { moveBy(Offset(0f, 12f * dp), delayMillis = 16) }
        up()
    } }

    private fun drag(tag: String, fromY: Float, byDp: Float) = rule.onNodeWithTag(tag).performTouchInput {
        down(Offset(centerX, height * fromY))
        repeat(10) { moveBy(Offset(0f, byDp / 10f * dp), delayMillis = 16) }
        up()
    }

    @Test fun swipeDownFromTheTopRightThirdOpensControlCentreAndSwipeUpCloses() {
        swipeDownFromTop("faces", 0.85f)
        waitFor("control-centre")
        assertFalse(shown("notifications"))
        shot("control-centre-top")
        // the long two-column list: swipes up scroll it to its end and leave it open, as on the watch (SpaceTest covers the table)
        var swipes = 0
        while (!atEnd("control-centre") && swipes < 10) {
            drag("control-centre", 0.6f, -150f); rule.waitForIdle(); swipes++
            assertTrue("a swipe up scrolls tiles that overflow before it closes", shown("control-centre"))
        }
        assertTrue("the tiles never reached their end", atEnd("control-centre"))
        drag("control-centre", 0.6f, -150f) // and from the end (or at once, when every tile fits) the next one closes
        gone("control-centre")
        assertTrue(shown("faces"))
    }

    @Test fun swipeDownFromTheTopLeftOrCentreOpensNotificationCentre() {
        swipeDownFromTop("faces", 0.5f)
        waitFor("notifications")
        assertFalse(shown("control-centre"))
        shot("notification-centre")
        // a swipe up scrolls a long list to its end first, then closes the sheet
        repeat(4) { if (shown("notifications")) { drag("notifications", 0.5f, -150f); rule.waitForIdle() } }
        gone("notifications")
        swipeDownFromTop("faces", 0.1f)
        waitFor("notifications")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        gone("notifications")
    }

    @Test fun swipeUpOnTheFaceOpensTheSmartStackAndSwipeDownReturns() {
        drag("faces", 0.6f, -150f)
        waitFor("smart-stack")
        shot("smart-stack")
        drag("smart-stack", 0.4f, 150f)
        gone("smart-stack")
    }

    @Test fun aTapOnTheFaceOpensTheDynamicGridAndASwipeUpOnItTheFullGrid() {
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        waitFor("favourites")
        assertFalse(shown("app-list")); assertFalse(shown("app-grid"))
        drag("favourites", 0.6f, -150f)
        waitFor("app-list")
        gone("favourites")
    }

    @Test fun aSwipeStartingBelowTheTopEdgeScrollsTheListAndOpensNothing() {
        openAllApps()
        waitFor("app-list")
        drag("app-list", 0.1f, 200f)
        drag("app-list", 0.8f, -200f)
        rule.waitForIdle()
        assertFalse(shown("control-centre")); assertFalse(shown("notifications")); assertFalse(shown("smart-stack"))
        assertTrue(shown("app-list"))
        swipeDownFromTop("app-list", 0.9f)
        waitFor("control-centre")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        gone("control-centre")
        assertTrue(shown("app-list"))
    }

    @Test fun theQuietTileSetsDoNotDisturbAndHoldingItOpensQuiet() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Quiet.set(ctx, false)
        swipeDownFromTop("faces", 0.85f)
        waitFor("control-centre")
        // Quiet sits in the long list's fourth row, below the fold on a short screen: scroll it into view first
        rule.onNodeWithTag("tile-quiet").performScrollTo().performTouchInput { click(center) }
        rule.waitUntil(3_000) { nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY }
        val p = nm.notificationPolicy
        assertTrue(p.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0)
        assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, p.priorityCallSenders)
        // the tile says it is on
        rule.onNodeWithTag("tile-quiet").assertContentDescriptionEquals("Quiet, on")
        shot("control-centre-quiet-on")
        if (overflows("control-centre")) { // on a screen tall enough for every tile there is no end to scroll to
            drag("control-centre", 0.6f, -150f)
            shot("control-centre-end")
            drag("control-centre", 0.4f, 150f)
        }
        rule.onNodeWithTag("tile-quiet").performScrollTo().performTouchInput { longClick(center) }
        waitFor("quiet")
        val label = "Calls from contacts only"
        val row = rule.onNodeWithText(label)
        row.performScrollTo().assertIsDisplayed().assertIsOn()
        // Exercise the label side and then the switch side with real touches.
        row.performTouchInput { click(Offset(width * 0.25f, centerY)) }
        rule.waitUntil(3_000) { nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS == 0 }
        row.assertIsOff().performTouchInput { click(Offset(width * 0.85f, centerY)) }
        rule.waitUntil(3_000) { nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 }
        assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, nm.notificationPolicy.priorityCallSenders)
        row.assertIsOn()
        shot("quiet")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("control-centre")
        rule.onNodeWithTag("tile-quiet").performScrollTo().performTouchInput { click(center) }
        rule.waitUntil(3_000) { nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL }

        // Editing the exception while Quiet is off updates Android without turning Quiet on.
        rule.onNodeWithTag("tile-quiet").performScrollTo().performTouchInput { longClick(center) }
        waitFor("quiet")
        row.performScrollTo().performClick()
        rule.waitUntil(3_000) { nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS == 0 }
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        row.assertIsOff().performClick()
        rule.waitUntil(3_000) { nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0 }
        assertEquals(NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, nm.notificationPolicy.priorityCallSenders)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
    }

    @Test fun touchAndHoldTheFaceToChooseTheInfoFaceAndItSurvivesARestart() {
        ctx.getSharedPreferences("face", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        rule.activityRule.scenario.recreate()
        waitFor("faces")
        rule.onNodeWithTag("faces").assertContentDescriptionEquals("Stacked")
        // a sideways swipe never changes the face: a swipe left goes in to the dynamic grid, and Back returns to the same face
        rule.onNodeWithTag("faces").performTouchInput { swipeLeft() }
        waitFor("favourites")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("faces")
        rule.waitForIdle()
        assertTrue(rule.onRoot(useUnmergedTree = true).printToString(maxDepth = 8),
            rule.onAllNodesWithContentDescription("Stacked").fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("faces").performTouchInput { longClick(center) }
        waitFor("face-picker")
        shot("face-picker")
        // A viewport-wide drag can cross several cards on a tall screen. Swipe one card,
        // then verify Info is centered rather than tapping a sliver of a neighboring page.
        rule.onNodeWithTag("pick-0").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("edit-1").assertHasClickAction()
        rule.onNodeWithTag("pick-1").performTouchInput { click(center) }
        waitFor("faces")
        rule.onNodeWithTag("faces").assertContentDescriptionEquals("Info")
        rule.activityRule.scenario.recreate()
        waitFor("faces")
        rule.onNodeWithTag("faces").assertContentDescriptionEquals("Info")
        ctx.getSharedPreferences("face", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    // a swipe left on the face goes in to the dynamic grid, never starts the camera
    @Test fun aSwipeLeftOnTheFaceDoesNotStartTheCamera() {
        val i = InstrumentationRegistry.getInstrumentation()
        val monitor = i.addMonitor(android.content.IntentFilter(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), null, true)
        try {
            rule.onNodeWithTag("faces").performTouchInput { down(Offset(width * 0.7f, centerY)); repeat(10) { moveBy(Offset(-15f * dp, 0f), delayMillis = 16) }; up() }
            waitFor("favourites")
            assertEquals(0, monitor.hits)
        } finally { i.removeMonitor(monitor) }
    }

    @Test fun theChosenAllAppsViewSurvivesARestart() {
        openAllApps()
        waitFor("app-list")
        shot("apps-list-top")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("grid-view"))
        shot("apps-list-end")
        rule.onNodeWithTag("grid-view").performClick()
        waitFor("app-grid")
        shot("apps-grid")
        rule.activityRule.scenario.recreate()
        appsLoaded()
        openAllApps()
        waitFor("app-grid")
        assertFalse(shown("app-list"))
        repeat(4) { rule.onNodeWithTag("app-grid").performTouchInput { swipeUp() } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("List View").performTouchInput { click(center) }
        waitFor("app-list")
        assertFalse(AppOrder.savedGrid(ctx))
    }

    @Test fun holdingAGridIconOpensQuickActionsAndMovesToTop() {
        AppOrder.saveGrid(ctx, true)
        rule.activityRule.scenario.recreate()
        appsLoaded()
        openAllApps()
        waitFor("app-grid")
        val second = rule.onNodeWithTag("grid-1").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first()
        rule.onNodeWithTag("grid-1").performTouchInput { longClick(center) }
        rule.waitUntil(5_000) { shown("menu-move-top") }
        rule.onNodeWithTag("menu-move-top").performClick()
        rule.waitForIdle()
        val saved = AppOrder.savedList(ctx)
        val labels = AppCache.apps.value!!.associate { it.component.flattenToString() to it.label }
        assertTrue("Expected non-empty saved order", saved.isNotEmpty())
        assertEquals(second, labels[saved[0]])
    }
}
