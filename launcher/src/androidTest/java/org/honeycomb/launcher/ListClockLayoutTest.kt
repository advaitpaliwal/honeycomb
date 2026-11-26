package org.honeycomb.launcher

import android.content.ComponentName
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import org.honeycomb.launcher.design.ProvideScale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * List View: at rest the time sits in its own band and the first row starts below it; scrolled, no row shows through
 * the time's band. Both at text size 1x and 2x, on a compact phone, a tall phone and a landscape tablet.
 */
class ListClockLayoutTest {
    @get:Rule val rule = createComposeRule()

    // solid red discs (they stand out on a light or a dark ground) and names in the label colour, so anything left of
    // a row inside the band shows up in the pixels
    private val red = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(android.graphics.Color.rgb(229, 57, 53)) }.asImageBitmap()
    private val apps = ('A'..'P').map { App("Ap$it", ComponentName("test.$it", "$it.Main"), red) }
    private val first = "row-${apps[0].component.flattenToString()}"

    private val sizes = mapOf(
        "compact" to DpSize(360.dp, 640.dp),
        "tall" to DpSize(360.dp, 800.dp),
        "tablet landscape" to DpSize(1280.dp, 800.dp),
    )

    @OptIn(ExperimentalTestApi::class)
    private fun show(size: androidx.compose.runtime.State<DpSize>, scale: androidx.compose.runtime.State<Float>) = rule.setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value) then DeviceConfigurationOverride.FontScale(scale.value)) {
            // ProvideScale follows the font size, so the time really grows at 2x: the band has to follow it
            ProvideScale {
                FrameSize {
                    AppListScreen(apps, apps.map { it.component.flattenToString() }, emptyFlow(), false, {}, {}, null, {}, { _, _ -> })
                }
            }
        }
    }

    @Test fun firstRowRestsBelowTheSearchBarWithNoDuplicateClock() {
        val size = mutableStateOf(sizes.values.first()); val scale = mutableFloatStateOf(1f)
        show(size, scale)
        for ((name, s) in sizes) for (k in listOf(1f, 2f)) {
            rule.runOnIdle { size.value = s; scale.floatValue = k }
            assertTrue("$name at ${k}x: list-time should not exist", rule.onAllNodesWithTag("list-time").fetchSemanticsNodes().isEmpty())
            val search = rule.onNodeWithTag("app-search").fetchSemanticsNode()
            val row = rule.onNodeWithTag(first).fetchSemanticsNode()
            val searchBottom = search.positionInRoot.y + search.size.height
            val rowTop = row.positionInRoot.y
            assertTrue("$name at ${k}x: search bottom $searchBottom px, first row top $rowTop px", searchBottom <= rowTop)
        }
    }

    @Test fun searchBarScrollsAwayWithListWithoutForehead() {
        val size = mutableStateOf(sizes.values.first()); val scale = mutableFloatStateOf(1f)
        show(size, scale)
        for ((name, s) in sizes) for (k in listOf(1f, 2f)) {
            rule.runOnIdle { size.value = s; scale.floatValue = k }
            rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
            val startY = rule.onNodeWithTag(first).fetchSemanticsNode().positionInRoot.y
            rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, startY) }
            rule.waitForIdle()
            val movedY = rule.onNodeWithTag(first).fetchSemanticsNode().positionInRoot.y
            assertTrue("$name at ${k}x: first row should scroll near the top edge (was $startY, now $movedY)", movedY < startY / 2f)
        }
    }
}
