package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the WWDC18 803 Fluid Interfaces physics primitives in Motion.kt and Space.kt. */
class FluidPhysicsTest {

    @Test fun projectionMatchesScrollDecelerationFormula() {
        assertEquals(0f, Motion.project(0f), 0f)
        // 1000 px/s at UIScrollView.DecelerationRate.fast (0.990): (1000/1000) * 0.99 / 0.01 = 99 px
        assertEquals(99f, Motion.project(1000f, Motion.DECEL_FAST), 0.1f)
        // Normal scroll rate (0.998): (1000/1000) * 0.998 / 0.002 = 499 px
        assertEquals(499f, Motion.project(1000f, Motion.DECEL_NORMAL), 0.5f)
        // Symmetry for reverse flicks
        assertEquals(-Motion.project(1500f), Motion.project(-1500f), 0.01f)
    }

    @Test fun rubberBandSoftlyResistsBoundariesWithoutHittingAWall() {
        assertEquals(0f, Motion.rubberBand(0f, 100f), 0f)
        // Strictly monotonic and asymptotic below dimension
        val a = Motion.rubberBand(20f, 100f)
        val b = Motion.rubberBand(200f, 100f)
        val c = Motion.rubberBand(2000f, 100f)
        assertTrue("small offset resists gently ($a)", a > 0f && a < 20f)
        assertTrue("monotonic ($a < $b < $c)", a < b && b < c)
        assertTrue("asymptotic below dimension ($c < 100)", c < 100f)
        // Symmetric in negative direction
        assertEquals(-a, Motion.rubberBand(-20f, 100f), 0.001f)
    }

    @Test fun rubberBandClampIsIdentityInsideRangeAndSoftOutside() {
        assertEquals(0.5f, Motion.rubberBandClamp(0.5f, 0f, 1f, 0.2f), 0f)
        assertEquals(0f, Motion.rubberBandClamp(0f, 0f, 1f, 0.2f), 0f)
        assertEquals(1f, Motion.rubberBandClamp(1f, 0f, 1f, 0.2f), 0f)

        val over = Motion.rubberBandClamp(1.5f, 0f, 1f, 0.2f)
        assertTrue("softly stretches past 1f ($over)", over > 1f && over < 1.2f)
        val under = Motion.rubberBandClamp(-0.5f, 0f, 1f, 0.2f)
        assertTrue("softly stretches below 0f ($under)", under < 0f && under > -0.2f)
    }

    @Test fun purposefulDampingSeparatesTapFromMomentumGesture() {
        // WWDC18 803: tap = critically damped (1.0, zero overshoot); gesture = subtle cushion (~0.84)
        assertEquals(1.0f, Motion.tap.damping, 0f)
        assertTrue("gesture spring rewards momentum with subtle overshoot", Motion.gesture.damping in 0.78f..0.88f)
        assertTrue("press spring responds faster than depth transitions", Motion.press.response < Motion.tap.response)
    }

    @Test fun momentumProjectedCommitRewardsFlicksAndAllowsMidSwipeReversal() {
        val span = 540f
        val minDist = 120f
        // Lightweight flick (short 50 px drag, brisk 1400 px/s velocity) projects ~189 px -> commits!
        assertTrue(EdgeSwipe.commits(travel = 50f, velocity = 1400f, span = span, minDistance = minDist))
        // Slow drag past 40% of span with zero release velocity -> commits!
        assertTrue(EdgeSwipe.commits(travel = 220f, velocity = 0f, span = span, minDistance = minDist))
        // Hesitant drag that reverses direction mid-flight -> cancels cleanly!
        assertFalse(EdgeSwipe.commits(travel = 260f, velocity = -900f, span = span, minDistance = minDist))
        // Tiny accidental twitch -> does not commit
        assertFalse(EdgeSwipe.commits(travel = 15f, velocity = 200f, span = span, minDistance = minDist))
    }
}
