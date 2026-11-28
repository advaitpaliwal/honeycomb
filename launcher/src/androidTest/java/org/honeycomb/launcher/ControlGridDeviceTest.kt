package org.honeycomb.launcher

import androidx.compose.ui.graphics.toArgb
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Control Centre's watch grid: every control is a WatchButton, with no slider and no now-playing module. */
@RunWith(AndroidJUnit4::class)
class ControlGridDeviceTest {
    @get:Rule(order = -2) val setup = SetupFinished()
    @get:Rule(order = -1) val access = org.junit.rules.TestRule { base, _ -> object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
            sh("cmd notification allow_dnd $pkg")
            sh("cmd notification allow_listener $pkg/org.honeycomb.launcher.MediaListener")
            sh("appops set $pkg WRITE_SETTINGS allow")
            Thread.sleep(500)
            base.evaluate()
        }
    } }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val audio get() = ctx.getSystemService(AudioManager::class.java)

    private fun sh(cmd: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = rule.waitUntil(10_000) { shown(tag) }
    private val dp get() = rule.activity.resources.displayMetrics.density

    private fun shot(name: String) {
        rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before fun openControlCentre() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        rule.waitUntil(60_000) { AppCache.apps.value?.isNotEmpty() == true }
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.85f, 4f))
            repeat(10) { moveBy(Offset(0f, 12f * dp), delayMillis = 16) }
            up()
        }
        waitFor("control-centre")
    }

    @After fun quiet() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    @Test fun everyControlIsAWatchButtonAndNoSliderOrNowPlayingRemains() {
        for (id in ControlGrid.order) assertTrue(id, shown("tile-$id"))
        for (tag in listOf("cc-media", "cc-connectivity", "slider-brightness", "slider-volume", "status")) assertFalse(tag, shown(tag))
        shot("cc-watch-layout")
    }

    @Test fun inactiveBatteryTileIsTheSchemesTonalTile() {
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val tile = rule.onNodeWithTag("tile-battery").fetchSemanticsNode().boundsInRoot
        fun matches(name: String, expected: Int, actual: Int) {
            val a = DoubleArray(3); val b = DoubleArray(3)
            ColorUtils.colorToLAB(expected, a); ColorUtils.colorToLAB(actual, b)
            val delta = ColorUtils.distanceEuclidean(a, b)
            assertTrue("$name: ΔE76=$delta", delta <= 3.0)
        }
        // Native (docs/design-language.md): the sheet is the phone scheme's surface, a tile at rest surfaceContainerHighest,
        // flat to its top edge (no watch rim)
        val y = tile.center.y.toInt()
        val body = org.honeycomb.launcher.design.HoneycombColors.button.toArgb()
        matches("ground", org.honeycomb.launcher.design.HoneycombColors.sheet.toArgb(), image.getPixel(1, y))
        matches("body", body, image.getPixel((tile.left + tile.width * .14f).toInt(), y))
        matches("top edge", body, image.getPixel(tile.center.x.toInt(), tile.top.toInt() + 2))
    }

    @Test fun scrollReachesWindowEdgeAndLastControlClearsNavigationBar() {
        val viewport = rule.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("control-centre")))
        val rootBottom = rule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val viewportBottom = viewport.fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("scroll viewport must reach the bottom edge", rootBottom, viewportBottom, 1f)
        val last = rule.onNodeWithTag("tile-${ControlGrid.order.last()}")
        last.performScrollTo()
        viewport.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, rootBottom) }
        rule.waitForIdle()
        val node = last.fetchSemanticsNode()
        val inset = rule.activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        assertEquals("last control must be fully visible", node.size.height.toFloat(), node.boundsInRoot.height, 1f)
        assertTrue("last control overlaps the gesture area", node.boundsInRoot.bottom <= rootBottom - inset + 1f)
    }

    @Test fun theBatteryButtonReadsTheBatterysPercentage() {
        val pct = ctx.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // the watch's battery button shows "100%" and says "Battery, 100 percent"
        rule.onNodeWithTag("tile-battery").assertContentDescriptionEquals("Battery, $pct percent")
    }
}
