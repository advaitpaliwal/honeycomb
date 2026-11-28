package org.honeycomb.launcher.design

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.honeycomb.launcher.FRAME_W
import org.honeycomb.launcher.Motion
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/*
 * The scale (docs/design-language.md, Scale): everything is specified in watch points. One wp is the window's full width
 * / 211, the width of the 49 mm watch's screen in points, so a Honeycomb screen is the watch screen, insets and all, enlarged
 * to the phone's width; no phone margin is added on top of the watch's own insets. This file is the only scale source:
 * [ProvideScale] computes it once from the window and provides it once, as the density every screen lays out in; no
 * screen divides by 211 or sets a density.
 */
object Wp {
    /** The watch screen's width in points (watchOS 27.2 simulator, Apple Watch Ultra 4, 422 px at 2x). */
    const val WATCH_W = 211f

    /** The widest the content gets: on an expanded window the screens stay a readable column, centred. */
    const val MAX_CONTENT_DP = 600f

    /** The content's width in dp: the window's full width, up to [MAX_CONTENT_DP]. */
    fun contentWidthDp(widthDp: Float): Float = minOf(widthDp, MAX_CONTENT_DP)

    /** Material expanded windows give grids more columns instead of scaling icons past the cap. */
    fun expanded(widthDp: Float): Boolean = widthDp >= 840f

    /** Android dp per wp in a window [widthDp] wide: width / 211. */
    fun dpPerWp(widthDp: Float): Float = contentWidthDp(widthDp) / WATCH_W

    /**
     * Layout units per wp. [ProvideScale] makes the window's width [FRAME_W] layout units on every window, so one wp is
     * always 360 / 211 of them, and a wp lands on width / 211 of Android's dp.
     */
    const val unit: Float = FRAME_W / WATCH_W

    /** The phone's font size setting, as [ProvideScale] last read it: face artwork divides it out. */
    var fontScale: Float = 1f
        internal set

    /** px per layout unit for a window [widthPx] wide at [density] px per Android dp. */
    fun pxPerUnit(widthPx: Int, density: Float): Float {
        // Whole, equal margins let a full-bleed layer recenter inside a capped column without half-pixel drift.
        val column = minOf(widthPx, (MAX_CONTENT_DP * density).toInt())
        val alignedColumn = column - (widthPx - column) % 2
        return alignedColumn / FRAME_W
    }
}

/** Lays [content] out in layout units: the one place the scale is computed (from the window) and provided. */
@Composable
fun ProvideScale(content: @Composable () -> Unit) {
    val m = LocalContext.current.resources.displayMetrics
    // the phone's font size applies to every UI text style (wsp); face artwork undoes it (Display), as faces fit a frame
    val fontScale = androidx.compose.ui.platform.LocalConfiguration.current.fontScale
    Wp.fontScale = fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth
        val d = remember(width, m.density, fontScale) { Density(Wp.pxPerUnit(width, m.density), fontScale) }
        CompositionLocalProvider(LocalDensity provides d) { HoneycombTheme { WatchTouchTargets(content) } }
    }
}

/** Compose expands pointer and accessibility bounds without remeasuring or redrawing the control.
 * Dialogs have a new owner and density, so install the same pixel minimum inside their window. */
@Composable
internal fun WatchTouchTargets(content: @Composable () -> Unit) {
    val metrics = LocalContext.current.resources.displayMetrics
    val minPx = maxOf(48f * metrics.density, Wp.pxPerUnit(metrics.widthPixels, metrics.density) * ControlSize.minHitArea.value)
    val min = with(LocalDensity.current) { minPx.toDp() }
    val vc = LocalViewConfiguration.current
    val touch = remember(vc, min) { object : ViewConfiguration by vc {
        override val minimumTouchTargetSize = DpSize(min, min)
    } }
    CompositionLocalProvider(LocalViewConfiguration provides touch, content = content)
}

val Float.wp: Dp get() = (this * Wp.unit).dp
val Int.wp: Dp get() = (this * Wp.unit).dp

/** Text in watch points, as sp so it still follows Android's font size. */
val Float.wsp: TextUnit get() = (this * Wp.unit).sp

/*
 * Space and control sizes in watch points, like the text (wsp), so a whole screen scales together: the watch screen
 * enlarged to the phone's width. Read each time, as [Wp.unit] is.
 */
object Space {
    val xs: Dp get() = 4.wp
    val s: Dp get() = 8.wp
    val m: Dp get() = 12.wp
    val l: Dp get() = 16.wp
    val xl: Dp get() = 24.wp
}

object ControlSize {
    val mini: Dp get() = 28.wp
    val small: Dp get() = 32.wp
    val regular: Dp get() = 44.wp
    val large: Dp get() = 52.wp
    val xLarge: Dp get() = 64.wp

    /** The smallest area anything tappable may have, on each side. */
    val minHitArea: Dp get() = 44.wp
}

/**
 * Unified phone-proportioned button and icon-disc dimensions (Material 3 Expressive capsule scale).
 * Visual bounds stay crisp while [WatchTouchTargets] expands pointer and accessibility hit-slop automatically.
 */
object ButtonSize {
    /** Inline row, card, and top-bar actions (Get, Show Less, Turn On, header Done). */
    val compact: Dp = 36.dp

    /** Standalone centered pill actions (Edit, Done, Add, All Apps, List View / Grid View). */
    val regular: Dp = 42.dp

    /** Full-width primary and secondary page / sheet actions (Setup, Pause, Clear All, Website Add). */
    val large: Dp = 50.dp

    /** Minimum width for standalone action pills (Edit, Done, Add) so labels never look stubby. */
    val minPillWidth: Dp = 112.dp

    /** Minimum width for icon+label view-switcher pills (All Apps, List View, Grid View). */
    val minWidePillWidth: Dp = 136.dp

    /** Standard stacked button gap on full-screen sheets and onboarding pages. */
    val stackGap: Dp = 10.dp

    /** Circular icon buttons: compact inline, standard toolbar/transport, and prominent play/action. */
    val circleSmall: Dp = 36.dp
    val circleRegular: Dp = 40.dp
    val circleLarge: Dp = 44.dp
}

/**
 * Material 3 Expressive's corner scale (m3.material.io/styles/shape/corner-radius-scale), in layout units (one is a dp on
 * a 360 dp wide phone). Every corner in Honeycomb is one of these, or a circle.
 */
object NativeShapes {
    /** Inner corners of grouped cards and segmented buttons. */
    val extraSmall: Dp = 4.dp
    val small: Dp = 8.dp

    /** A pressed button's corner (buttons morph from full to this). */
    val medium: Dp = 12.dp

    /** Artwork, the play button. */
    val large: Dp = 16.dp

    /** Control Centre tiles and sliders, the outer corners of a card group. */
    val largeIncreased: Dp = 20.dp

    /** Cards and dialogs. */
    val extraLarge: Dp = 28.dp

    val material = androidx.compose.material3.Shapes(
        extraSmall = RoundedCornerShape(extraSmall), small = RoundedCornerShape(small), medium = RoundedCornerShape(medium),
        large = RoundedCornerShape(large), extraLarge = RoundedCornerShape(extraLarge),
    )
}

/** Honeycomb's corners, each a [NativeShapes] step. */
object Radius {
    /** A card's corner (extraLarge). */
    val platter: Dp get() = NativeShapes.extraLarge

    /** A Control Centre tile's corner (largeIncreased). */
    val button: Dp get() = NativeShapes.largeIncreased

    /** A notification's app icon disc. */
    val appIcon: Dp get() = 34.wp

    /** The face picker's card: the phone's screen corner (62 units) shrunk with the face. */
    fun picker(scale: Float): Dp = (62f * scale).dp
}

/** A platter's shape. */
val PlatterShape: RoundedCornerShape get() = RoundedCornerShape(Radius.platter)

/** A capsule: radius = height / 2. */
val Capsule: RoundedCornerShape = RoundedCornerShape(50)

/** A circle. */
val Circle: RoundedCornerShape = CircleShape

/** A rounded rectangle of [corner]. */
fun rounded(corner: Dp): RoundedCornerShape = RoundedCornerShape(corner)

val LocalReduceMotion = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Follows the phone (docs/design-language.md, Follows the phone): its colour scheme and light or dark setting
 * ([Native]), and its remove-animations setting ([Motion.rememberReduceMotion]), as Material 3's theme and Honeycomb's
 * tokens. Nested calls are cheap and agree.
 */
@Composable
fun HoneycombTheme(content: @Composable () -> Unit) {
    if (LocalThemed.current) return content()
    val ctx = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val scheme = remember(ctx, dark, cfg) { Native.scheme(dark, Native.wallpaper(ctx, dark)) }
    HoneycombColors.scheme = scheme
    HoneycombColors.dark = dark
    val reduce = Motion.rememberReduceMotion()
    androidx.compose.material3.MaterialTheme(colorScheme = scheme, shapes = NativeShapes.material, typography = HoneycombType.material) {
        CompositionLocalProvider(LocalReduceMotion provides reduce, LocalThemed provides true, content = content)
    }
}

private val LocalThemed = staticCompositionLocalOf { false }

object HoneycombTheme {
    /** True when Android's remove-animations setting is on: springs become cross-fades. */
    val reduceMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/** A rounded rectangle whose corners are [percent] of its shorter side (50 is a capsule). */
fun rounded(percent: Int): RoundedCornerShape = RoundedCornerShape(percent = percent)
