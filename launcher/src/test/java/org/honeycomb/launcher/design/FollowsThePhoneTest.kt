package org.honeycomb.launcher.design

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.honeycomb.launcher.BreathPalette
import org.honeycomb.launcher.FacePalette
import org.honeycomb.launcher.FaceStyle
import org.honeycomb.launcher.Motion
import org.honeycomb.launcher.faceColours
import androidx.compose.material3.ColorScheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/design-language.md, Follows the phone: the phone's scheme, light or dark, font and Bold text, and animations. */
class FollowsThePhoneTest {
    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test fun thePhonesSchemeWinsAndHoneyIsOnlyTheFallback() {
        val wallpaper = darkColorScheme(primary = Color(0xFF8AB4F8))
        assertSame(wallpaper, Native.scheme(dark = true, wallpaper = wallpaper))
        // no scheme from the phone: generated from the icon's honey. The approved token sheet (material-color-utilities in
        // the browser) has primary #FABA73 and surfaceContainerHigh #302921; MaterialKolor's TonalSpot lands within 3% of each
        val honey = Native.scheme(dark = true, wallpaper = null)
        fun near(a: Color, b: Color) = maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue))
        println("honey dark: primary=${honey.primary} surfaceContainerHigh=${honey.surfaceContainerHigh} surface=${honey.surface}")
        assertTrue("primary ${honey.primary}", near(honey.primary, Color(0xFFFABA73)) < 0.07f)
        assertTrue("card ${honey.surfaceContainerHigh}", near(honey.surfaceContainerHigh, Color(0xFF302921)) < 0.03f)
    }

    @Test fun lightAndDarkFollowTheSystem() {
        val dark = Native.honey(dark = true)
        val light = Native.honey(dark = false)
        assertTrue("dark surface is dark", luminance(dark.surface) < 0.1f)
        assertTrue("light surface is light", luminance(light.surface) > 0.8f)
        assertTrue("dark text is light", luminance(dark.onSurface) > 0.7f)
        assertTrue("light text is dark", luminance(light.onSurface) < 0.2f)
        // the ground: true black in dark mode, the scheme's surface in light
        assertEquals(Color.Black, Native.ground(dark, dark = true))
        assertEquals(light.surface, Native.ground(light, dark = false))
        val wall = lightColorScheme(surface = Color(0xFFFAF8FF))
        assertEquals(Color(0xFFFAF8FF), Native.ground(wall, dark = false))
        // and the roles every screen reads swap with it
        HoneycombColors.scheme = light; HoneycombColors.dark = false
        assertEquals(light.surface, HoneycombColors.background)
        assertEquals(light.onSurface, HoneycombColors.label)
        assertEquals(light.onSurface, HoneycombType.body.color)
        assertEquals(light.onPrimary, HoneycombColors.contentFor(HoneycombColors.accent))
        HoneycombColors.scheme = dark; HoneycombColors.dark = true
        assertEquals(Color.Black, HoneycombColors.background)
        assertEquals(dark.onSurface, HoneycombType.body.color)
        assertNotEquals(light.surfaceContainerHigh, HoneycombColors.platter)
    }

    @Test fun boldTextAndTheFontAreThePhones() {
        // every UI style is the platform default family, so the OEM font applies and Compose's font resolver adds
        // Configuration.fontWeightAdjustment (Bold text) to its weight; none carries a bundled face
        val styles = listOf(HoneycombType.largeTitle, HoneycombType.title, HoneycombType.headline, HoneycombType.body,
            HoneycombType.caption, HoneycombType.footnote, HoneycombType.smallControl, HoneycombType.time, Display.lockTime,
            Display.numerals(40f))
        styles.forEach { assertEquals(FontFamily.Default, it.fontFamily) }
        // Bold text adds 300 to each weight on Android: titles reach bold, body reaches bold, nothing exceeds 1000
        styles.forEach { val w = it.fontWeight!!.weight + 300; assertTrue(w in 700..1000) }
    }

    @Test fun animatorScaleZeroSnaps() {
        assertTrue(Motion.snaps(0f))
        assertFalse(Motion.snaps(1f))
        assertFalse(Motion.snaps(0.5f))
        assertTrue(Motion.orSnap(reduce = true) { Motion.snappy.spec<Float>() } is SnapSpec<Float>)
        assertTrue(Motion.orSnap(reduce = false) { Motion.snappy.spec<Float>() } is SpringSpec<Float>)
    }

    /** Every colour role a scheme has: a face colour must be one of these, never a value of its own. */
    private fun roles(c: ColorScheme) = with(c) { setOf(primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
        secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer,
        onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
        inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer, outline, outlineVariant, scrim,
        surfaceBright, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, surfaceContainerLow,
        surfaceContainerLowest, surfaceDim) }

    /** WCAG contrast ratio between two opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        fun lin(v: Float) = if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        fun l(c: Color) = 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
        val (hi, lo) = listOf(l(a), l(b)).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    // two wallpapers as Android's Material You would build them, one blue and one green, light and dark
    private fun wall(seed: Long, dark: Boolean) = FacePalette.seeded(Color(seed), dark)
    private val blueWall = 0xFF2F6BFF
    private val greenWall = 0xFF2E8B57

    @Test fun theFacesTakeTheWallpapersColours() {
        for (dark in listOf(true, false)) {
            val a = wall(blueWall, dark); val b = wall(greenWall, dark)
            val pa = FacePalette(a); val pb = FacePalette(b)
            // a face shows no fixed colour: every colour it draws is one of the scheme's roles
            pa.all.forEach { assertTrue("$it is not a role of the scheme", it in roles(a)) }
            pb.all.forEach { assertTrue("$it is not a role of the scheme", it in roles(b)) }
            // so changing the wallpaper changes every face's colours
            pa.all.zip(pb.all).forEach { (x, y) -> assertNotEquals("dark=$dark", x, y) }
        }
        // "Phone colours" is the editor's first choice and the default, and it is the phone's scheme itself
        assertEquals("Phone colours", faceColours.keys.first())
        assertEquals(null, faceColours.values.first())
        val phone = wall(blueWall, dark = true)
        assertSame(phone, FaceStyle().scheme(phone, dark = true))
        assertSame(phone, FaceStyle("Phone colours").scheme(phone, dark = true))
        // a saved name from before ("Original") reads as the phone's colours
        assertSame(phone, FaceStyle("Original").scheme(phone, dark = true))
        // the fixed picks stay, each a whole scheme in the phone's light or dark
        listOf("Blue", "Mint", "Pink").forEach { name ->
            assertTrue(name in faceColours)
            assertNotEquals(phone.primary, FaceStyle(name).scheme(phone, dark = true).primary)
            assertTrue(luminance(FaceStyle(name).scheme(phone, dark = false).surface) > 0.8f)
        }
    }

    @Test fun aFaceReadsOnItsGroundInLightAndDark() {
        // never lime on white: the accents and the time stand out from the ground in both modes, and the ink on its fields
        for (dark in listOf(true, false)) for (seed in listOf(blueWall, greenWall, 0xFFFF9F0A)) {
            val s = wall(seed, dark); val p = FacePalette(s); val ground = Native.ground(s, dark)
            listOf(p.hours, p.minutes, p.date, p.time).forEach { assertTrue("dark=$dark $it on $ground", contrast(it, ground) >= 3f) }
            assertTrue(contrast(p.ink, p.splitField) >= 4.5f); assertTrue(contrast(p.ink, p.rising) >= 3f)
            assertTrue(contrast(p.risingInk, p.rising) >= 4.5f)
            assertTrue(contrast(p.dialInk, p.dial) >= 4.5f); assertTrue(contrast(p.classicSecond, p.dial) >= 3f)
            assertTrue(contrast(p.plainHands, p.plain) >= 4.5f); assertTrue(contrast(p.plainSecond, p.plain) >= 3f)
        }
    }

    @Test fun theHourNumeralStandsOutAndItsHandsReadOnTheNumeralAndTheGround() {
        // Apple's Numerals Mono: a strong colour field on the ground, hands that read over both it and the ground
        // (WCAG 1.4.11 non-text contrast, 3:1). A hand reads on a colour when its fill or its edge reaches 3:1 there
        for (dark in listOf(true, false)) for (seed in listOf(blueWall, greenWall, 0xFFFF9F0A, 0xFFE0389A, 0xFF7B3FE4, 0xFFFFD60A)) {
            val p = FacePalette(wall(seed, dark)); val ground = Native.ground(wall(seed, dark), dark)
            val at = "seed=${java.lang.Long.toHexString(seed)} dark=$dark"
            val numeral = contrast(p.numeral, ground)
            assertTrue("numeral on ground $numeral $at", numeral >= 3f)
            for ((name, fill) in listOf("hands" to p.hourHands, "second" to p.hourSecond)) {
                val onNumeral = maxOf(contrast(fill, p.numeral), contrast(p.hourEdge, p.numeral))
                val onGround = maxOf(contrast(fill, ground), contrast(p.hourEdge, ground))
                println("hour $at numeral=$numeral $name onNumeral=$onNumeral onGround=$onGround fill/numeral=${contrast(fill, p.numeral)} edge/ground=${contrast(p.hourEdge, ground)}")
                assertTrue("$name on numeral $onNumeral $at", onNumeral >= 3f)
                assertTrue("$name on ground $onGround $at", onGround >= 3f)
            }
        }
    }

    @Test fun theBreathFollowsThePhone() {
        val before = HoneycombColors.scheme to HoneycombColors.dark
        try {
            val a = wall(blueWall, dark = true); val b = wall(greenWall, dark = true)
            HoneycombColors.scheme = a
            val ba = BreathPalette.gradient
            ba.forEach { assertTrue(it in roles(a)) }
            HoneycombColors.scheme = b
            assertNotEquals(ba, BreathPalette.gradient)
            BreathPalette.gradient.forEach { assertTrue(it in roles(b)) }
        } finally { HoneycombColors.scheme = before.first; HoneycombColors.dark = before.second }
    }

    @Test fun theArtworkIsScannedByTheColourGateAndTheWatchScreenDigitsAreTheOneBundledFont() {
        val main = listOf(File("src/main/java"), File("launcher/src/main/java")).first { it.isDirectory }
        val launcher = File(main, "org/honeycomb/launcher")
        // the palettes live outside design/, where DesignLanguageTest exempts nothing: they hold no colour literal
        val palettes = File(launcher, "Palettes.kt").readText()
        assertFalse(Regex("""Color\(\s*(0x|\d)|Color\.(White|Black)""").containsMatchIn(palettes))
        val tokens = File(launcher, "design/HoneycombColors.kt").readText()
        listOf("FacePalette", "BreathPalette").forEach { assertFalse(it, Regex("""object $it\b""").containsMatchIn(tokens)) }
        // the faces set the phone's font, except Stacked, Split and Hour's one bundled digit face (Big Shoulders Display)
        val faces = File(launcher, "Faces.kt").readText()
        assertEquals(listOf("R.font.watch_digits"), Regex("""R\.font\.\w+""").findAll(faces).map { it.value }.toList())
        assertFalse(Regex("""family\s*=\s*(Inter|Nunito)\b""").containsMatchIn(faces))
        // it replaced Genos (Stacked) and Saira (Split), and its licence ships beside it
        val fonts = listOf(File("src/main/res/font"), File("launcher/src/main/res/font")).first { it.isDirectory }.list()!!.toList()
        assertTrue("watch_digits.ttf" in fonts)
        listOf("stacked_digits.ttf", "split_digits.ttf").forEach { assertFalse(it, it in fonts) }
        val root = if (File("NOTICE").exists()) File(".") else File("..")
        assertTrue(File(root, "licenses/BigShouldersDisplay-OFL.txt").readText().contains("SIL Open Font License"))
        assertTrue(File(root, "NOTICE").readText().contains("Big Shoulders Display"))
    }

}
