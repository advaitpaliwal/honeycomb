package org.honeycomb.launcher

import android.app.NotificationManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Smart Stack in its three states, shot from Honeycomb's window (UiAutomation may be held by agent-device): nothing live (only Edit, centred, no card and no header),
 * one Live Update (a running countdown with progress), and a Live Update with something playing. A fresh stack opens
 * with no widget, so no "Create widget and allow access?" prompt can cover it.
 */
@RunWith(AndroidJUnit4::class)
class LiveStackShots {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 0) val rule = createAndroidComposeRule<LauncherActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(1200); rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence/live-stack").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @SdkSuppress(minSdkVersion = 36)
    @Test fun threeStates() {
        nm.cancel(LiveUpdateTest.ID)
        Widgets.ids(ctx).forEach { Widgets.discard(ctx, it) }
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
        // the instrumentation restarts the process; ask the system to bind the notification listener again
        if (MediaListener.Defaults.live == null)
            android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(ctx, MediaListener::class.java))
        rule.waitUntil(20_000) { MediaListener.Defaults.live != null }
        rule.onNodeWithTag("faces").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
        rule.waitUntil(5_000) { count("smart-stack") > 0 }

        // nothing live: date-and-clock header, empty prompt, and Edit — no live/playing/widget cards
        shot("live-stack-empty")
        assertEquals(0, count("now-playing")); assertEquals(0, count("live-icon")); assertEquals(0, count("widget-0"))
        assertEquals(1, count("stack-time")); assertTrue(count("stack-edit") > 0)

        // one Live Update: a countdown with its progress
        LiveUpdateTest.post(nm, ctx)
        rule.waitUntil(5_000) { count("live-value") > 0 }
        assertTrue(count("live-progress") > 0)
        shot("live-stack-countdown")

        // a Live Update and something playing
        val session = MediaSession(ctx, "live-stack-shots").apply {
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Pink + White")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Frank Ocean").build())
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        try {
            rule.waitUntil(8_000) { count("now-playing") > 0 }
            shot("live-stack-countdown-playing")
        } finally { session.release(); nm.cancel(LiveUpdateTest.ID) }
    }
}
