package org.honeycomb.launcher.design

import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import org.honeycomb.launcher.FRAME_W
import org.honeycomb.launcher.Motion
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spot-checks the design tokens against docs/design-language.md. */
class TokensTest {
    @Test fun typeIsMaterialRolesInThePhonesFontInPhoneProportions() {
        assertEquals(24f, HoneycombType.largeTitle.fontSize.value, 0.01f)
        assertEquals(18f, HoneycombType.title.fontSize.value, 0.01f)
        assertEquals(15f, HoneycombType.headline.fontSize.value, 0.01f)
        assertEquals(14f, HoneycombType.body.fontSize.value, 0.01f)
        assertEquals(13f, HoneycombType.caption.fontSize.value, 0.01f)
        assertEquals(12f, HoneycombType.footnote.fontSize.value, 0.01f)
        assertEquals(15f, sizeForCap(11f), 0.15f)
        with(HoneycombType.body) { assertEquals(19f, lineHeight.value, 0.01f); assertEquals(FontWeight.Normal, fontWeight) }
        with(HoneycombType.largeTitle) { assertEquals(FontWeight.SemiBold, fontWeight); assertEquals(HoneycombColors.label, color) }
        assertEquals(FontWeight.Medium, HoneycombType.headline.fontWeight)
        assertEquals(FontWeight.Medium, HoneycombType.footnote.fontWeight)
        assertEquals((-0.04f * 64), Display.numerals(64f).letterSpacing.value, 0.001f)
    }

    @Test fun oneWatchPointIsTheContentWidthOver211() {
        // wp = the window's full width / 211, in Android dp, no margin: a compact 349 dp phone and a 412 dp phone
        assertEquals(349f / 211f, Wp.dpPerWp(349f), 1e-4f)
        assertEquals(412f / 211f, Wp.dpPerWp(412f), 1e-4f)
        // the provided density lands one wp on exactly width / 211: 480 px at 220 dpi (1.375 px/dp)
        assertEquals(480f / 211f, Wp.pxPerUnit(480, 1.375f) * Wp.unit, 1e-3f)
        // an expanded window stays a 600 dp column
        assertEquals(600f / 211f, Wp.dpPerWp(900f), 1e-4f)
        assertEquals(360f / 211f, Wp.unit, 1e-6f)
        // corners are Material 3 Expressive's scale: cards extraLarge (28), tiles largeIncreased (20)
        assertEquals(28f, Radius.platter.value, 0.01f)
        assertEquals(20f, Radius.button.value, 0.01f)
        assertEquals(58f * Wp.unit, WatchButtonSize.height.value, 0.01f)
    }

    @Test fun fullWidthFacesRecenterWithoutDriftingPastTheWindow() {
        for ((window, density) in listOf(480 to 1.375f, 2208 to 2.625f, 2561 to 2f)) {
            val column = (FRAME_W * Wp.pxPerUnit(window, density)).roundToInt()
            val columnLeft = ((window - column) / 2f).roundToInt()
            val faceLeft = columnLeft + ((column - window) / 2f).roundToInt()
            assertEquals("face right edge in a $window px window", window, faceLeft + window)
        }
    }

    @Test fun springsAreAppleResponseAndDamping() {
        // stiffness = (2 pi / response)^2 with a unit mass
        assertEquals(438.65f, Motion.snappy.stiffness, 0.1f)
        assertEquals(0.85f, Motion.snappy.damping, 0f)
        assertEquals(157.91f, Motion.smooth.stiffness, 0.1f)
        val spec = Motion.bouncy.spec<Float>() as SpringSpec<Float>
        assertEquals(0.7f, spec.dampingRatio, 0f)
    }
}
