package org.honeycomb.launcher

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** App info's "Additional settings in the app" opens exactly one Honeycomb screen, and it reaches both setup and Feeds. */
@RunWith(AndroidJUnit4::class)
class SettingsEntryTest {
    @get:Rule val rule = createAndroidComposeRule<HoneycombSettingsActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun appInfoFindsOneSettingsEntry() {
        val ctx = inst.targetContext
        val found = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(ctx.packageName), 0,
        ).map { it.activityInfo.name }
        assertEquals(listOf(HoneycombSettingsActivity::class.java.name), found)
    }

    @Test fun runSetupAgainOpensSetup() = opens("settings-setup", SetupActivity::class.java.name)

    @Test fun feedsOpensFeeds() = opens("settings-feeds", FeedsActivity::class.java.name)

    private fun opens(tag: String, activity: String) {
        val monitor = inst.addMonitor(activity, null, false)
        try {
            rule.onNodeWithTag(tag).performClick()
            assertNotNull("$tag did not open $activity", inst.waitForMonitorWithTimeout(monitor, 5_000))
        } finally {
            inst.removeMonitor(monitor)
        }
    }
}
