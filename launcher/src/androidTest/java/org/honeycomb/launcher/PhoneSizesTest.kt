package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Every home screen on whatever phone runs the test: each one is saved to the app's evidence/phone-sizes folder, then
 * checked against the window. The faces reach the side edges as on the watch but stay clear of the system bars and the
 * camera cutout, the other screens sit inside Material's window margins, and the face and favourites sit at the middle
 * of the screen, not in its top half.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSizesTest {
    /** Modes access before the activity starts, so its one-time ask does not open Settings over the home mid-walk. */
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
    private val dp get() = rule.activity.resources.displayMetrics.density
    private val problems = mutableListOf<String>()

    @Before fun fresh() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd notification allow_dnd ${ctx.packageName}").close()
        AppOrder.clear(ctx)
        FaceChoice.save(ctx, 0)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
    }

    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(700); rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence/phone-sizes").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(tag: String) = rule.waitUntil(5_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun bounds(tag: String): Rect = rule.onAllNodesWithTag(tag)[0].fetchSemanticsNode().boundsInRoot
    private val root: Rect get() = rule.onRoot().fetchSemanticsNode().boundsInRoot
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    private fun check(ok: Boolean, what: String) { if (!ok) problems += what }

    /** Inside the screen, at least Material's 16 dp window margin clear at each side. */
    private fun inside(tag: String) {
        val b = bounds(tag); val r = root
        val side = 16f * dp - 1f
        check(b.left >= r.left + side && b.right <= r.right - side && b.top >= r.top - 1 && b.bottom <= r.bottom + 1,
            "$tag at $b is not inside $r with $side px sides")
    }

    /**
     * The insets nothing on a face may run under: the system bars (even while hidden: the status bar and the gesture
     * handle) and the camera cutout. The faces reach the side edges, as on the watch (wp is the window's width / 211).
     */
    private fun barsAndCutout(): android.graphics.Insets {
        val ins = rule.activity.window.decorView.rootWindowInsets
        return android.graphics.Insets.max(
            ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()),
            ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout()),
        )
    }

    /** A face's content: inside the window less the system bars and the cutout, at every side. */
    private fun faceInside(tag: String) {
        val b = bounds(tag); val r = root; val i = barsAndCutout()
        check(b.left >= r.left + i.left - 1 && b.right <= r.right - i.right + 1 && b.top >= r.top + i.top - 1 && b.bottom <= r.bottom - i.bottom + 1,
            "$tag at $b is not inside $r less the system bars and cutout $i")
    }

    /**
     * A face's centre within 6% of the screen's height of the middle of the screen left beside the system bars and the
     * camera cutout: the faces keep clear of both (Faces.kt safe()), so they move the faces' middle by half their difference.
     */
    private fun faceCentred(tag: String) {
        val b = bounds(tag); val r = root; val i = barsAndCutout()
        val top = i.top.toFloat(); val bottom = i.bottom.toFloat()
        val middle = r.top + top + (r.height - top - bottom) / 2f
        check(kotlin.math.abs(b.center.y - middle) <= r.height * 0.06f, "$tag centre ${b.center.y} is not near the middle $middle beside the bars and cutout ($top top, $bottom bottom)")
    }

    /** The centre of [tag] within 6% of the screen's height of the screen's middle. */
    private fun centred(tag: String) {
        val b = bounds(tag); val r = root
        check(kotlin.math.abs(b.center.y - r.center.y) <= r.height * 0.06f, "$tag centre ${b.center.y} is not near the middle ${r.center.y}")
    }

    /** Every control on screen takes a tap across at least 48 dp each way (Material's minimum touch target). */
    private fun touchTargets(screen: String) {
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { n ->
            val t = n.touchBoundsInRoot
            if (t.isEmpty) return@forEach // scrolled out of view: nothing to tap
            // a control the scroll edge cuts through is clipped, not small: it is judged at its full size
            val w = maxOf(t.width, n.size.width.toFloat()); val h = maxOf(t.height, n.size.height.toFloat())
            check(w >= 48f * dp - 1f && h >= 48f * dp - 1f, "$screen: a control's touch area $t (laid out ${n.size}) is under 48 dp")
        }
    }

    private fun swipeDownFromTop(xFraction: Float) = rule.onRoot().performTouchInput {
        down(Offset(width * xFraction, 4f))
        repeat(10) { moveBy(Offset(0f, 12f * dp), delayMillis = 16) }
        up()
    }

    @Test fun aTapOnTheFaceOpensTheDynamicGrid() {
        waitFor("faces")
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        waitFor(firstIn())
    }

    /** What a tap on the face opens on every screen: the dynamic grid (Space.kt). */
    private fun firstIn() = "pin-0"

    @Test fun aTapOnTheFaceAfterTheSmartStackOpensFavourites() {
        waitFor("faces")
        rule.onNodeWithTag("faces").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
        waitFor("stack-edit") // Edit closes every stack, so it is there even with no cards
        back(); rule.waitUntil(5_000) { rule.onAllNodesWithTag("smart-stack").fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        waitFor(firstIn())
    }

    @Test fun everyScreenFitsThisPhone() {
        waitFor("faces"); shot("face"); faceInside("face"); faceCentred("face")

        rule.onNodeWithTag("faces").performTouchInput { longClick(center) }
        waitFor("pick-0"); shot("face-picker"); inside("pick-0")
        rule.onNodeWithTag("pick-1").performClick()
        waitFor("face-info"); shot("info-face"); faceInside("face")

        rule.onNodeWithTag("faces").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
        // a phone with no weather, media, event or timer has an empty stack
        waitFor("smart-stack"); shot("smart-stack")
        for (t in listOf("widget-0", "widget-stack")) if (rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty()) inside(t)
        back(); rule.waitUntil(5_000) { rule.onAllNodesWithTag("smart-stack").fetchSemanticsNodes().isEmpty() }

        // the face settles after the stack lowers; a tap during that is taken by the motion, so tap until favourites show
        repeat(5) {
            if (rule.onAllNodesWithTag(firstIn()).fetchSemanticsNodes().isEmpty()) {
                Thread.sleep(800); rule.waitForIdle()
                // the Info face is showing: tap between the time and the complications at the bottom, which take their own taps
                rule.onNodeWithTag("faces").performTouchInput { click(Offset(centerX, height * 0.36f)) }; rule.waitForIdle()
            }
        }
        run {
            waitFor("pin-0"); shot("favourites"); touchTargets("favourites"); inside("pin-0"); inside("pin-6"); centred("pin-0")

            rule.allAppsAction()
            // All apps opens in Grid View on a screen taller than 3:2 until List View is chosen
            val first = if (tallDevice(ctx)) "app-grid" else "app-list"
            waitFor(first); shot("all-apps-${first.removePrefix("app-")}"); touchTargets("all-apps-${first.removePrefix("app-")}")
            back(); back()

            AppOrder.saveGrid(ctx, true)
            rule.activityRule.scenario.recreate()
            waitFor("faces")
            rule.onNodeWithTag("faces").performTouchInput { click(Offset(centerX, height * 0.36f)) }
            waitFor("pin-0"); rule.allAppsAction()
            waitFor("app-grid"); shot("all-apps-grid"); touchTargets("all-apps-grid")
            back(); back()
        }

        waitFor("faces"); swipeDownFromTop(0.85f)
        waitFor("control-centre"); shot("control-centre"); touchTargets("control-centre")
        rule.onAllNodesWithTag("status").fetchSemanticsNodes().firstOrNull()?.let { inside("status") }
        back()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag("control-centre").fetchSemanticsNodes().isEmpty() }
        swipeDownFromTop(0.15f)
        waitFor("notifications"); shot("notification-centre"); touchTargets("notification-centre")
        back()

        FaceChoice.save(ctx, 0)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
