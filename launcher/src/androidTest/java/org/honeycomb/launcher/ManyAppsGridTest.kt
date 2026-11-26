package org.honeycomb.launcher

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.emptyFlow
import org.honeycomb.launcher.design.ProvideScale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A phone with 146 launchable apps made the full grid stick at the bottom: every icon is a GPU texture, and
 * at 576 px each they overflowed HWUI's cache, so the grid uploaded icons again on every frame (97 ms frames on a
 * Pixel 9a-sized emulator). The grid is scrolled to its last row and back, and the icon textures it drew must still fit
 * the cache.
 */
class ManyAppsGridTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    private fun shell(cmd: String) =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    /** HWUI's numbers for this process, in bytes: the cache budget and the image textures it holds. */
    private fun gpu(): Pair<Double, Double> {
        val dump = shell("dumpsys gfxinfo ${ctx.packageName}")
        fun mb(pattern: String) = Regex(pattern).find(dump)?.groupValues?.get(1)?.toDouble()
            ?: error("no '$pattern' in gfxinfo:\n$dump")
        return mb("""Max resource usage: ([\d.]+)MB""") to mb("""Image:\s+Texture: ([\d.]+) MB""")
    }

    @Test fun hundredFortySixAppsScrollToTheLastRowAndBack() {
        val art = ctx.packageManager.getApplicationIcon(ctx.packageName)
        val count = 146
        // one bitmap per app, at the size the home draws every app's icon
        val icons = List(count) { circleIcon(art, iconPx(ctx)).asImageBitmap() }
        val labels = List(count) { "App %03d".format(it) }
        rule.setContent { ProvideScale { FrameSize {
            WatchGrid(icons, labels, emptyFlow(), onTap = { _, _ -> }, modifier = Modifier.testTag("grid"))
        } } }
        rule.waitForIdle()
        repeat(12) { rule.onNodeWithTag("grid").performTouchInput { swipeUp(bottom * 0.8f, top + height * 0.2f, 250) }; rule.waitForIdle() }
        // the grid ends in the List View button, below the last row
        rule.onNodeWithText("List View").assertExists()
        rule.onNodeWithContentDescription(labels.last()).assertExists()
        repeat(12) { rule.onNodeWithTag("grid").performTouchInput { swipeDown(top + height * 0.2f, bottom * 0.8f, 250) }; rule.waitForIdle() }
        rule.onNodeWithContentDescription(labels.first()).assertExists()
        val (budget, textures) = gpu()
        assertTrue("$count icons at ${iconPx(ctx)} px hold $textures MB of textures; HWUI's cache holds $budget MB",
            textures <= budget)
    }
}
