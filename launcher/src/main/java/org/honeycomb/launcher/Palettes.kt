package org.honeycomb.launcher

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import org.honeycomb.launcher.design.FaceSeeds
import org.honeycomb.launcher.design.HoneycombColors

/*
 * The artwork's colours: the watch faces, the all-apps glyph and the feed pause's breathing circle. Each is a Material 3
 * colour role on a scheme, never a fixed value, so they follow the phone's wallpaper colours and its light or dark
 * setting the way Pixel's own lock-screen clocks do (m3.material.io/styles/color/dynamic/user-generated-source). They
 * live outside design/ on purpose: DesignLanguageTest scans this file like any screen, so a colour literal here fails.
 */

/**
 * A face's colours, read from [scheme]: the phone's own scheme by default, or one generated from a colour the person
 * picked in the face editor ([FaceStyle.scheme]). Containers carry the big colour fields; the matching on-container role
 * draws on them; primary and tertiary are the two accents, which read on the ground in both light and dark.
 */
class FacePalette(val scheme: ColorScheme) {
    // Stacked: the hour over the minutes, each its own accent
    val hours: Color get() = scheme.primary
    val minutes: Color get() = scheme.tertiary

    // Info: the date in the accent, the time in the label colour
    val date: Color get() = scheme.primary
    val time: Color get() = scheme.onSurface

    // Split: a colour field, a second field rising with the seconds; a digit takes each field's own ink where it crosses it
    val splitField: Color get() = scheme.primaryContainer
    val rising: Color get() = scheme.tertiaryContainer
    val ink: Color get() = scheme.onPrimaryContainer
    val risingInk: Color get() = scheme.onTertiaryContainer

    // Hour (Apple's Numerals Mono): the numeral is the accent, primary (tone 40 on the light ground, 80 on the dark one,
    // so at least 3:1 on either; m3.material.io/styles/color/roles). No role reads on both the numeral and the ground at
    // once (that needs a tone 45-60 in light), so the hands are filled with primary-container, 50 tones from the numeral,
    // and edged with on-surface, the ground's text colour, where they cross the ground; the second hand is filled with
    // tertiary-container, the scheme's second hue, and edged the same way
    val numeral: Color get() = scheme.primary
    val hourHands: Color get() = scheme.primaryContainer
    val hourSecond: Color get() = scheme.tertiaryContainer
    val hourEdge: Color get() = scheme.onSurface

    // Classic: the dial, its marks, numerals and hands, the second hand
    val dial: Color get() = scheme.secondaryContainer
    val dialInk: Color get() = scheme.onSecondaryContainer
    val classicSecond: Color get() = scheme.tertiary

    // Plain: one colour field and the hands alone
    val plain: Color get() = scheme.tertiaryContainer
    val plainHands: Color get() = scheme.onTertiaryContainer
    val plainSecond: Color get() = scheme.primary

    /** Every colour a face draws, for the tests. */
    val all: List<Color> get() = listOf(hours, minutes, date, time, splitField, rising, ink, risingInk, numeral, hourHands, hourSecond,
        hourEdge, dial, dialInk, classicSecond, plain, plainHands, plainSecond)

    companion object {
        private val generated = HashMap<Pair<Color, Boolean>, ColorScheme>()

        /** The scheme for a colour picked in the face editor: TonalSpot from [seed], as Android builds a wallpaper's. */
        fun seeded(seed: Color, dark: Boolean): ColorScheme = synchronized(generated) {
            generated.getOrPut(seed to dark) { dynamicColorScheme(seed, isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot) }
        }
    }
}

/** The face editor's colour choices: the phone's own colours first and by default, then the fixed picks. */
val faceColours: Map<String, Color?> = linkedMapOf("Phone colours" to null, "Blue" to FaceSeeds.blue, "Mint" to FaceSeeds.mint,
    "Pink" to FaceSeeds.pink)

/** The feed pause's breathing circle: a radial gradient from the centre out, on the phone's scheme. */
object BreathPalette {
    private val s get() = HoneycombColors.scheme
    val centre: Color get() = s.tertiary
    val middle: Color get() = s.primary
    val edge: Color get() = s.primaryContainer
    val gradient: List<Color> get() = listOf(centre, middle, edge)
}
