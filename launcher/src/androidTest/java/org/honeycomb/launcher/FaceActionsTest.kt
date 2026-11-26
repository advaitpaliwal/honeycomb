package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*

class FaceActionsTest {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule val rule = createAndroidComposeRule<LauncherActivity>()
    private val prefs get() = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("face", 0)
    @Before fun reset() {
        prefs.edit().clear().commit()
        rule.activityRule.scenario.recreate()
    }
    @After fun clean() { prefs.edit().clear().commit() }
    private fun picker() = rule.onNodeWithTag("faces").performTouchInput { longClick(center) }
    private fun opening(name: String, action: () -> Unit) {
        val dir = File(rule.activity.getExternalFilesDir(null), "face-actions").apply { mkdirs() }
        fun frame(ms: Int) = File(dir, "$name-$ms.jpg").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            frame(0); action()
            var elapsed = 0
            for (ms in listOf(32, 96, 192, 320, 480)) {
                rule.mainClock.advanceTimeBy((ms - elapsed).toLong()); frame(ms); elapsed = ms
            }
        } finally { rule.mainClock.autoAdvance = true }
    }
    @Test fun editSavesColourAcrossRestart() {
        picker()
        opening("edit") { rule.onNodeWithTag("edit-0").performClick() }
        rule.onNodeWithContentDescription("Blue").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("pick-0").performTouchInput { click(center) }
        rule.activityRule.scenario.recreate()
        picker()
        rule.onNodeWithTag("edit-0").performClick()
        rule.onNodeWithContentDescription("Blue").assertIsSelected()
    }
    @Test fun galleryAddsAndSelectsPlain() {
        picker()
        rule.onNodeWithTag("face-picker").performScrollToIndex(6)
        opening("add") { rule.onNodeWithContentDescription("Add").performClick() }
        rule.onNodeWithTag("face-gallery").performScrollToNode(hasText("Plain"))
        rule.onNodeWithContentDescription("Get Plain").performClick()
        rule.onNodeWithText("Add").performClick()
        rule.onNodeWithTag("faces").assertContentDescriptionEquals("Plain")
        picker()
        rule.onNodeWithTag("pick-6").assertIsDisplayed()
    }
}
