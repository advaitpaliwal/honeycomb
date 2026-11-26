package org.honeycomb.launcher.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import org.honeycomb.launcher.Inter
import org.honeycomb.launcher.R

/*
 * Honeycomb's type (docs/design-language.md, Type): Material 3's type roles in the phone's own font. Every UI style is
 * FontFamily.Default, so a Samsung, OnePlus or Pixel font choice carries through, and Compose's font resolver applies
 * Android's Bold text setting (Configuration.fontWeightAdjustment) to every weight here. Sizes are in watch points
 * ([wsp]), so the watch structure keeps its proportions, and they follow the phone's font size ([ProvideScale]). Each size
 * was measured as a cap height in the watchOS 27.2 captures (422 px = 211 pt) over a cap-height ratio of 0.727.
 */

/** The cap height the sizes were measured with, as a share of the font size. */
const val INTER_CAP_RATIO = 1490f / 2048f

/** The font size whose capitals are [capPt] watch points tall. */
fun sizeForCap(capPt: Float): Float = capPt / INTER_CAP_RATIO

/** The phone's own font: the platform default, never a bundled face. */
val NativeFont: FontFamily = FontFamily.Default

private fun role(weight: FontWeight, size: Float, lineHeight: Float, tracking: Float = 0f) = TextStyle(
    fontFamily = NativeFont, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp, color = HoneycombColors.label, platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** The one way to set UI text: `HoneycombType.<style>`, phone-proportioned Material 3 / iOS roles in the device font. */
object HoneycombType {
    /** headlineSmall: a screen's title (Setup, Settings, Feeds, Quiet), in on-surface. */
    val largeTitle get() = role(FontWeight.SemiBold, 24f, 29f, -0.2f)

    /** titleLarge: a song, a list's header, a sheet's title. */
    val title get() = role(FontWeight.SemiBold, 18f, 23f, -0.1f)

    /** titleMedium: a notification's or a card's title. */
    val headline get() = role(FontWeight.Medium, 15f, 20f)

    /** bodyLarge: a notification's text, a row's label. */
    val body get() = role(FontWeight.Normal, 14f, 19f)

    /** bodyMedium: secondary text under a heading. */
    val caption get() = role(FontWeight.Normal, 13f, 18f)

    /** labelMedium: a time, a subtitle, a footnote. */
    val footnote get() = role(FontWeight.Medium, 12f, 16f, 0.1f)

    /** labelLarge: a small button's label (Show Less). */
    val smallControl get() = role(FontWeight.Medium, 12f, 16f, 0.1f)

    /** titleMedium: tabular status time. */
    val time get() = role(FontWeight.Medium, 16f, 20f).copy(fontFeatureSettings = "tnum")

    /** Material 3's own roles in the phone's font, for anything drawn by a Material component. */
    val material: androidx.compose.material3.Typography = androidx.compose.material3.Typography()
}

/**
 * Watch-face numerals and face text: artwork, never UI. Faces scale their text with the face, so a size is given in sp.
 */
object Display {
    /** Numerals at [size]: tracking -4% of the size, tabular figures so the time does not jitter. */
    fun numerals(size: Float, weight: FontWeight = FontWeight.SemiBold) = TextStyle(
        fontFamily = NativeFont, fontWeight = weight, fontSize = size.sp, lineHeight = size.sp,
        letterSpacing = (-0.04f * size).sp, fontFeatureSettings = "tnum", color = HoneycombColors.label,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    /** A face's text at [size] sp, exactly as the faces have always drawn it. */
    fun face(
        size: Float,
        weight: Int,
        color: Color = Color.White,
        tracking: Float = 0f,
        family: FontFamily = Inter,
        align: TextAlign = TextAlign.Start,
    ) = TextStyle(
        color = color, fontSize = faceSp(size), fontFamily = family, fontWeight = FontWeight(weight), letterSpacing = tracking.sp,
        textAlign = align, platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    /** [style] at [size] sp, its tracking set to [tracking] sp when given: faces fit their numerals to their space. */
    fun resized(style: TextStyle, size: Float, tracking: Float? = null): TextStyle =
        if (tracking == null) style.copy(fontSize = faceSp(size)) else style.copy(fontSize = faceSp(size), letterSpacing = tracking.sp)

    /** [size] sp with the phone's font size divided out: a face is artwork fitted to its frame, not text to enlarge. */
    fun faceSp(size: Float) = (size / Wp.fontScale).sp

    /** Smart Stack 3-line uppercase date (MON / AUG / 19) on the left of the header, in the phone's default font. */
    fun stackDate(color: Color) = TextStyle(
        fontFamily = NativeFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14f.sp,
        lineHeight = 16.5f.sp,
        letterSpacing = 0.5f.sp,
        color = color,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    /** Smart Stack digital time on the right in the phone's default font, matching the 3-line date height cleanly. */
    fun stackTime(color: Color) = TextStyle(
        fontFamily = NativeFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44f.sp,
        lineHeight = 48f.sp,
        letterSpacing = (-1.2f).sp,
        fontFeatureSettings = "tnum",
        color = color,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )

    /** Notification Centre's big time above the notifications (displayLarge's role, bold 84 / 90, -2 tracking). */
    val lockTime get() = TextStyle(
        fontFamily = NativeFont, fontWeight = FontWeight.Bold, fontSize = 84.sp, lineHeight = 90.sp,
        letterSpacing = (-2).sp, fontFeatureSettings = "tnum", color = HoneycombColors.label,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}
