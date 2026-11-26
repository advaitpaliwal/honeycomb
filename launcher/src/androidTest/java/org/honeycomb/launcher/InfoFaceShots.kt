package org.honeycomb.launcher

import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Info face in its three states, shot from Honeycomb's window in the phone's current light or dark mode: nothing live
 * (the date and time alone, centred), a real media session playing (the note circle), and a real Live Update with the
 * media (its progress ring, then the note). Each state checks the face draws no card and squeezes no glyph.
 */
@RunWith(AndroidJUnit4::class)
class InfoFaceShots {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 0) val rule = createAndroidComposeRule<LauncherActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private val mode get() = if (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"

    private fun shot(name: String) {
        rule.waitForIdle(); Thread.sleep(1200); rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence/info-face").apply { mkdirs() }
        File(dir, "$name-$mode.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * No glyph is squeezed: every text on the face has no geometric transform (scaleX 1, no skew; a squeeze would stretch
     * one stroke direction against the other), and every drawn glyph sits in a square box, so its scaleX equals its scaleY.
     */
    private fun assertGlyphsUndistorted() {
        rule.onAllNodes(hasAnyAncestor(hasTestTag("face-info")) and SemanticsMatcher("has text") {
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult in it.config }, useUnmergedTree = true)
            .fetchSemanticsNodes().also { assertTrue("no text on the face", it.isNotEmpty()) }.forEach { node ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val t = results.single().layoutInput.style.textGeometricTransform
                assertTrue("${results.single().layoutInput.text} drawn with $t", t == null || (t.scaleX == 1f && t.skewX == 0f))
            }
        rule.onAllNodes(hasTestTag("comp-glyph") or hasTestTag("comp-ring"), useUnmergedTree = true).fetchSemanticsNodes().forEach {
            assertEquals("glyph box ${it.size}", it.size.width, it.size.height)
        }
    }

    /** The face draws no card: none of the Smart Stack's cards (live-<pkg>, now-playing, widget-<id>) is inside it. */
    private fun assertNoCard() {
        val cards = rule.onAllNodes(hasAnyAncestor(hasTestTag("face-info")) and SemanticsMatcher("a Smart Stack card") {
            val tag = it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } ?: return@SemanticsMatcher false
            tag == "now-playing" || tag.startsWith("live-") || tag.startsWith("widget-")
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("cards on the face: ${cards.map { it.config }}", 0, cards.size)
    }

    private fun onFace(tag: String) = rule.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("face-info")), useUnmergedTree = true)
        .fetchSemanticsNodes().size

    private fun check() { assertGlyphsUndistorted(); assertNoCard() }

    @SdkSuppress(minSdkVersion = 36)
    @Test fun threeStates() {
        nm.cancel(LiveUpdateTest.ID)
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
        if (MediaListener.Defaults.live == null)
            android.service.notification.NotificationListenerService.requestRebind(android.content.ComponentName(ctx, MediaListener::class.java))
        rule.waitUntil(20_000) { MediaListener.Defaults.live != null }
        rule.onNodeWithTag("faces").performTouchInput { longClick(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("pick-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("pick-1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("face-info").fetchSemanticsNodes().isNotEmpty() }

        // nothing live: the date over the time, no circle
        shot("1-nothing-live"); check()
        assertEquals(0, onFace("info-circles"))

        val session = MediaSession(ctx, "info-face-shots").apply {
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Pink + White")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Frank Ocean").build())
            setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        try {
            // something playing: the note circle under the time
            rule.waitUntil(8_000) { onFace("comp-media") > 0 }
            shot("2-now-playing"); check()

            // a Live Update too: its ring leads the row, the note beside it
            LiveUpdateTest.post(nm, ctx)
            rule.waitUntil(8_000) { onFace("comp-live") > 0 }
            assertEquals(1, onFace("comp-live")); assertEquals(1, onFace("comp-media"))
            shot("3-live-update-and-playing"); check()
        } finally { session.release(); nm.cancel(LiveUpdateTest.ID) }
    }
}
