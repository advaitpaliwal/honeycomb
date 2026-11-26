package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The depth transitions' curves against what the watchOS 27.2 recordings show (Motion.kt). */
class MotionTest {
    private val steps = (0..100).map { it / 100f }

    @Test fun depthLayersNeverShrinkBelowTheScreen() {
        // a layer smaller than the screen shows its own edge and the black behind it (the "card with a black border")
        for (p in steps) for (l in listOf(Motion.leaving(p), Motion.arriving(p)))
            assertTrue("scale ${l.scale} at $p", l.scale >= 1f)
    }

    @Test fun theLayerGoingAwayIsBlurredBeforeTheArrivingOneIsLegible() {
        // no double exposure: whenever the arriving layer is visible and sharp-ish, the leaving one is fully blurred
        for (p in steps) {
            val inn = Motion.arriving(p); val out = Motion.leaving(p)
            if (inn.alpha > 0.05f) assertEquals("leaving blur at $p", 1f, out.blur, 0.01f)
        }
    }

    @Test fun stackIsReadableWhileTheFaceIsStillReceding() {
        val stack = Motion.stack(0.6f)
        val face = Motion.underStack(0.6f)
        assertTrue("stack text stays blurred", stack.blur < 0.2f && stack.alpha > 0.5f)
        assertTrue("the face remains behind the stack", face.scale < 1f && face.alpha in 0.1f..0.8f)
    }

    @Test fun everyTransitionSettlesSharpAndWhole() {
        for (l in listOf(Motion.arriving(1f), Motion.stack(1f), Motion.leaving(0f), Motion.underStack(0f))) {
            assertEquals(0f, l.blur, 0f); assertEquals(1f, l.alpha, 0f); assertEquals(1f, l.scale, 0f)
        }
    }

    @Test fun durationsAreWithinAFifthOfTheWatch() {
        // measured at 60 fps: crown press 450 ms, app close (the open reversed) 300 ms
        assertEquals(450f, Motion.DEPTH_MS.toFloat(), 90f)
        assertEquals(300f, Motion.APP_CLOSE_MS.toFloat(), 60f)
    }

    @Test fun appOpenBlursOnlyTheGrowingIconAndEndsOnASharpSplashIcon() {
        // the blurred copy of the icon is gone by 120 ms; after that only the sharp icon shows, at the splash size
        val after = 121f / Motion.APP_OPEN_MS
        for (p in steps) if (p >= after) assertEquals("ghost at $p", 0f, Motion.appGhost(p), 0f)
        assertEquals(1f, Motion.appGrowth(1f), 0f)
        assertEquals(1f / 3f, Motion.SPLASH_ICON, 0.02f)
    }

    @Test fun appCloseShrinksFromTheFirstFrameWithNoHold() {
        // one 60 fps frame into the close the plate is already visibly smaller than the screen, and it ends on the icon
        val frame = 1f - (1000f / 60f) / Motion.APP_CLOSE_MS
        assertTrue("growth after one frame ${Motion.appCloseGrowth(frame)}", Motion.appCloseGrowth(frame) < 0.95f)
        assertEquals(1f, Motion.appCloseGrowth(1f), 0f)
        assertEquals(0f, Motion.appCloseGrowth(0f), 0f)
    }
}
