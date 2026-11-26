package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The faces, after the watch faces owners rate highest (TechRadar's 2026 ranking and r/AppleWatch threads, kept with
 * their reference images in the research folder): stacked numerals, a split colour field, one big hour numeral with
 * hands, a modular information face, a classic dial and a single-colour dial. Each one sizes itself from the box it is
 * given and fills the whole screen: everything sits inside Material's window margins (16 dp on a compact window, 24 dp
 * on a medium or larger one; m3.material.io/foundations/layout/applying-layout/window-size-classes), clear of any camera
 * cutout, and digits and dials are sized from the real width and height left inside them.
 */

/** Material's window margin in real dp for a window [widthDp] real dp wide: 16 below 600 dp (compact), 24 from there up. */
fun windowMargin(widthDp: Float): Float = if (widthDp < 600f) 16f else 24f

/** Every face, in the picker's order. Index 1 is where the old information face was, so a saved choice still means the same kind of face. */
enum class FaceKind(val label: String) { Stacked("Stacked"), Info("Info"), Split("Split"), Hour("Hour"), Classic("Classic"), Plain("Plain") }

/** The safe rectangle of a face: inside the margins. */
internal data class Safe(val left: Dp, val top: Dp, val width: Dp, val height: Dp)

/**
 * The face's safe rectangle in this layout's units. The launcher scales its dp (LocalDensity), so the margin is worked
 * out in the screen's real dp and converted back; the camera cutout's and the system bars' insets (even while the bars are
 * hidden) are added where they sit, so nothing on a face runs under the status bar or the gesture handle. The watch's own
 * faces pass [margins] false: their watch-point layout already carries the watch's insets.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BoxWithConstraintsScope.safe(margins: Boolean = true): Safe {
    val d = LocalDensity.current
    val real = LocalContext.current.resources.displayMetrics.density
    val toLocal = real / d.density // one real dp in layout units
    val m = if (margins) windowMargin(maxWidth.value / toLocal) * toLocal else 0f
    val cut = WindowInsets.displayCutout.union(WindowInsets.systemBarsIgnoringVisibility)
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    val l = cut.getLeft(d, dir) / d.density; val t = cut.getTop(d) / d.density
    val r = cut.getRight(d, dir) / d.density; val b = cut.getBottom(d) / d.density
    return Safe((l + m).dp, (t + m).dp, (maxWidth.value - l - r - 2 * m).dp, (maxHeight.value - t - b - 2 * m).dp)
}

/** The hour and minutes as the phone's own 12/24-hour setting writes them: "9" and "41", or "21" and "41". */
fun digits(ms: Long, h24: Boolean): Pair<String, String> =
    SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(ms)).split(':').let { it[0] to it[1] }

@Composable
internal fun is24(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

@Composable
fun AnyFace(kind: FaceKind, ms: Long, style: FaceStyle = FaceStyle()) {
    // the face spans the whole window, centred on the frame, and keeps its own margins from the screen's edges
    val screen = LocalScreen.current
    // the phone's wallpaper scheme, light or dark, unless the face editor picked a colour (Palettes.kt)
    val p = FacePalette(style.scheme(HoneycombColors.scheme, HoneycombColors.dark))
    Box(Modifier.fillMaxSize()) { Box(Modifier.align(Alignment.Center).requiredSize(screen.width.dp, screen.height.dp).testTag("face-${kind.name.lowercase()}")) {
        when (kind) {
            FaceKind.Stacked -> StackedFace(ms, p)
            FaceKind.Info -> InfoFace(ms, p)
            FaceKind.Split -> SplitFace(ms, p)
            FaceKind.Hour -> HourFace(ms, p)
            FaceKind.Classic -> ClassicFace(ms, p)
            FaceKind.Plain -> PlainFace(ms, p)
        }
    } }
}

/**
 * What a face draws under the status bar and the navigation bar: (top, bottom). Its colour fields run edge to edge, under
 * the bars and the cutout; only its digits, numerals, ticks and hands keep inside [safe]. Null where the face sits on
 * the screen's ground.
 */
internal fun faceBars(kind: FaceKind, p: FacePalette): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>? = when (kind) {
    FaceKind.Split -> p.splitField to p.rising
    FaceKind.Plain -> p.plain to p.plain
    FaceKind.Classic -> p.dial to p.dial
    else -> null
}

// ---- Watch measurements ---------------------------------------------------------------------------------------------

/**
 * The faces' sizes in watch points, measured from the watchOS 27.2 simulator captures they copy (Apple Watch Ultra,
 * 422 x 514 px at 2x = 211 x 257 pt; watchos-sim/screens/01 and 02). A face multiplies them by [watchUnit].
 */
object WatchFace {
    const val W = 211f
    const val H = 257f

    // Siri Modular (02): the date's caps 10 pt tall with their top at 29.5 pt, the time's digits 42.5 pt tall with their
    // top at 51 pt, both centred
    const val DATE_CAP = 10f
    const val DATE_TOP = 29.5f
    const val TIME_CAP = 42.5f
    const val TIME_TOP = 51f

    /** Inter's cap and digit height: 1490 of 2048 units; the Info face now sets the phone's font, whose caps run within about 3% of it (Roboto 1456). */
    const val INTER_CAP = 1490f / 2048f

    /** Font size in watch points that gives digits [cap] pt tall in a font whose digits are [ratio] of its size. */
    fun sizeFor(cap: Float, ratio: Float) = cap / ratio
}

/** One watch point in this face's layout units: the safe width (the whole window on the watch's own faces) over the watch's 211 pt, or less when the safe area is shorter than the watch's shape. */
private fun watchUnit(s: Safe): Float = min(s.width.value / WatchFace.W, s.height.value / WatchFace.H)

/** The hour and minutes as two digits each, as Numerals Duo shows them: "09" and "41", or "21" and "41". */
fun duoDigits(ms: Long, h24: Boolean): Pair<String, String> =
    SimpleDateFormat(if (h24) "HH:mm" else "hh:mm", Locale.getDefault()).format(Date(ms)).split(':').let { it[0] to it[1] }

/** [text] centred across its parent, its baseline at [baseline] from the parent's top. */
@Composable
internal fun AtBaseline(text: String, style: TextStyle, baseline: Dp, modifier: Modifier = Modifier) =
    BasicText(text, modifier.fillMaxWidth().layout { m, c ->
        val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
        layout(c.maxWidth, p.height) { p.place((c.maxWidth - p.width) / 2, baseline.roundToPx() - p[FirstBaseline]) }
    }, style = style, softWrap = false, maxLines = 1)

// ---- The watch-screen digits: Stacked, Split and Hour ---------------------------------------------------------------

/*
 * The whole phone screen is the face, as the whole watch display is: the digits are sized from the real screen box inside
 * Material's window margins and clear of the cutout and system bars ([safe]), never from the watch's points. The three
 * faces share one bundled condensed display face, Big Shoulders Display ExtraBold (OFL, Patric King / XYZ Type,
 * fonts.google.com/specimen/Big+Shoulders+Display), cut down to its ten digits; a numeral it does not draw falls back to
 * the phone's font. Each run of digits is drawn as its outline, scaled by one factor in both directions, never squeezed.
 */
private fun watchDigits(ctx: android.content.Context): android.graphics.Typeface = watchTypeface
    ?: ctx.resources.getFont(R.font.watch_digits).also { watchTypeface = it }
private var watchTypeface: android.graphics.Typeface? = null

/** The digits' tracking, in ems: the mockup sets the condensed digits 4 units tighter at 150 (watch-screen.html, gen2.py). */
internal const val DIGIT_TRACKING = -0.027f

/** The reference size the digits' outlines are taken at, in px; [placeDigits] scales them from it. */
private const val REF = 100f

/** A run of digits' outline at [REF] px with its baseline at y = 0, and its ink box. */
private class Run(val path: android.graphics.Path, val ink: android.graphics.RectF)

/**
 * Each run's outline, built once: the faces redraw every second (and Split every frame of its glide), and building the
 * outline from the font each time made garbage the collector then ran on the frames (Perfetto, the face idling).
 */
private val runs = HashMap<Pair<android.graphics.Typeface, String>, Run>()

private fun digitRun(face: android.graphics.Typeface, text: String): Run = runs.getOrPut(face to text) { outline(face, text) }

private fun outline(face: android.graphics.Typeface, text: String): Run {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face; textSize = REF; letterSpacing = DIGIT_TRACKING
    }
    val path = android.graphics.Path().also { paint.getTextPath(text, 0, text.length, 0f, 0f, it) }
    return Run(path, android.graphics.RectF().also { path.computeBounds(it, true) })
}

/** The digits' common height: the ink of all ten, so every row stands on the same line whatever it shows. */
private fun capOf(face: android.graphics.Typeface) = digitRun(face, "0123456789").ink

/** Where a run lands: moved by [dx], [dy] and scaled by [scaleX], [scaleY] from its reference outline. */
data class GlyphFit(val dx: Float, val dy: Float, val scaleX: Float, val scaleY: Float)

/**
 * Places a run whose ink spans [inkLeft] to [inkLeft] + [inkWidth] across, on digits standing [capTop] to [capTop] +
 * [capHeight] (its reference outline, baseline at 0), in the box [x], [y], [w], [h]: scaled by one factor [k], by
 * default the largest at which it fits, and centred in the box.
 */
fun placeDigits(inkLeft: Float, inkWidth: Float, capTop: Float, capHeight: Float, x: Float, y: Float, w: Float, h: Float,
    k: Float = min(w / inkWidth, h / capHeight)): GlyphFit =
    GlyphFit(x + w / 2 - (inkLeft + inkWidth / 2) * k, y + h / 2 - (capTop + capHeight / 2) * k, k, k)

/** Draws [run] where [fit] puts it, in [colour]. */
private fun DrawScope.drawDigits(run: Run, fit: GlyphFit, colour: Color) = withTransform({
    translate(fit.dx, fit.dy); scale(fit.scaleX, fit.scaleY, Offset.Zero)
}) { drawPath(run.path.asComposePath(), colour) }

/** The safe box in px: left, top, width, height. */
@Composable
private fun Safe.px(): FloatArray = with(LocalDensity.current) { floatArrayOf(left.toPx(), top.toPx(), width.toPx(), height.toPx()) }

// ---- Stacked: the hour over the minutes, each its own colour, filling the screen ----------------------------------------

/** The gap between Stacked's rows as a share of a row's height (the mockup: 26 under 120-unit digits, about 0.12). */
internal const val STACKED_GAP = 0.12f

@Composable
private fun StackedFace(ms: Long, p: FacePalette) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val s = safe()
    val (x, y, w, h) = s.px()
    val (hour, minutes) = duoDigits(ms, is24())
    val face = watchDigits(LocalContext.current)
    val cap = remember(face) { capOf(face) }
    // one size for both rows, from the widest pair of digits, so the time never changes size from minute to minute
    val widest = remember(face) { (0..99).maxOf { digitRun(face, "%02d".format(Locale.ROOT, it)).ink.width() } }
    val k = min(w / widest, h / (cap.height() * (2 + STACKED_GAP)))
    val rowH = cap.height() * k
    val top = y + (h - rowH * (2 + STACKED_GAP)) / 2
    val rows = listOf(digitRun(face, hour) to top, digitRun(face, minutes) to top + rowH * (1 + STACKED_GAP))
    // the digits' box, inside the margins, bars and cutout
    Spacer(Modifier.padding(start = s.left, top = s.top).size(s.width, s.height).testTag("face"))
    Canvas(Modifier.fillMaxSize()) {
        rows.forEachIndexed { i, (r, rowTop) ->
            drawDigits(r, placeDigits(r.ink.left, r.ink.width(), cap.top, cap.height(), x, rowTop, w, rowH, k),
                if (i == 0) p.hours else p.minutes)
        }
    }
}

// ---- Split: the colour field, the lower colour rising with the seconds, the digits filling both halves ----------------

@Composable
private fun SplitFace(ms: Long, p: FacePalette) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val s = safe()
    val (x, y, w, h) = s.px()
    val (hour, minutes) = digits(ms, is24())
    val face = watchDigits(LocalContext.current)
    val cap = remember(face) { capOf(face) }
    val sec = (ms / 1000 % 60).toInt()
    // the rising colour steps up 1/60 of the screen each second in a short glide, as Flux's does (measured
    // real-faces/flux-motion/pts.json: each step done within one 100 ms sample); it drops back at the minute, and with
    // motion reduced every step snaps
    val reduce = Motion.rememberReduceMotion()
    val rise = remember { Animatable(sec / 60f) }
    LaunchedEffect(sec, reduce) {
        val to = sec / 60f
        if (to < rise.value) rise.snapTo(to) else rise.animateTo(to, Motion.orSnap(reduce) { tween(Motion.FLUX_STEP_MS) })
    }
    val rows = listOf(digitRun(face, hour) to y, digitRun(face, minutes) to y + h / 2)
    // the digits' box, inside the margins, bars and cutout
    Spacer(Modifier.padding(start = s.left, top = s.top).size(s.width, s.height).testTag("face"))
    Canvas(Modifier.fillMaxSize()) {
        drawRect(p.splitField)
        val line = size.height * (1f - rise.value)
        drawRect(p.rising, Offset(0f, line), Size(size.width, size.height - line))
        // the hour fills the top half and the minutes the bottom, each as large as its half allows
        val fits = rows.map { (r, rowTop) -> r to placeDigits(r.ink.left, r.ink.width(), cap.top, cap.height(), x, rowTop, w, h / 2) }
        // a digit crossing the line takes each field's own ink there (PR #53's clip), so it reads on both
        clipRect(bottom = line) { fits.forEach { (r, f) -> drawDigits(r, f, p.ink) } }
        clipRect(top = line) { fits.forEach { (r, f) -> drawDigits(r, f, p.risingInk) } }
    }
}

// ---- hands, shared by the analog faces -----------------------------------------------------------------------------

/** Hour, minute and second angles in degrees from twelve, from the phone's local time. */
fun handAngles(ms: Long): Triple<Float, Float, Float> {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val s = c.get(Calendar.SECOND).toFloat()
    val m = c.get(Calendar.MINUTE) + s / 60f
    val h = c.get(Calendar.HOUR) + m / 60f
    return Triple(h * 30f, m * 6f, s * 6f)
}

/** The hands, as the dials draw them: a thin stem then a wide rounded arm; a thin second hand with a short tail, pinned in the dial's [pin] colour. */
private fun DrawScope.hands(ms: Long, centre: Offset, r: Float, hand: Color, second: Color, pin: Color, stem: Color = hand,
    edge: Color? = null) {
    val (ha, ma, sa) = handAngles(ms)
    // an edge, when given, is the same strokes drawn a little wider underneath: the hand keeps its colour and gains a rim
    // that reads on the ground where the fill reads on the numeral
    val e = if (edge == null) 0f else r * 0.008f
    fun arm(deg: Float, len: Float, width: Float) = rotate(deg, centre) {
        if (edge != null) {
            drawLine(edge, centre, centre - Offset(0f, len * 0.18f), r * 0.012f + 2 * e, StrokeCap.Round)
            drawLine(edge, centre - Offset(0f, len * 0.18f), centre - Offset(0f, len), width + 2 * e, StrokeCap.Round)
        }
        drawLine(stem, centre, centre - Offset(0f, len * 0.18f), r * 0.012f, StrokeCap.Round)
        drawLine(hand, centre - Offset(0f, len * 0.18f), centre - Offset(0f, len), width, StrokeCap.Round)
    }
    arm(ha, r * 0.55f, r * 0.05f)
    arm(ma, r * 0.85f, r * 0.05f)
    if (edge != null) drawCircle(edge, r * 0.04f + e, centre)
    drawCircle(hand, r * 0.04f, centre)
    rotate(sa, centre) {
        if (edge != null) drawLine(edge, centre + Offset(0f, r * 0.15f), centre - Offset(0f, r * 0.87f), r * 0.012f + 2 * e, StrokeCap.Round)
        drawLine(second, centre + Offset(0f, r * 0.15f), centre - Offset(0f, r * 0.87f), r * 0.012f, StrokeCap.Round)
    }
    if (edge != null) drawCircle(edge, r * 0.022f + e, centre)
    drawCircle(second, r * 0.022f, centre)
    drawCircle(pin, r * 0.008f, centre)
}

/** The hands' reach on a full-screen dial: the minute hand (0.85 of it) ends at 80% of the half-width, the second hand inside the margin. */
private fun DrawScope.handRadius() = min(size.width, size.height) / 2 * 0.80f / 0.85f

// ---- Hour: one hour numeral filling the screen, the hands centred over it -----------------------------------------------

@Composable
private fun HourFace(ms: Long, p: FacePalette) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val (x, y, w, h) = safe().px()
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val hour = (c.get(Calendar.HOUR).let { if (it == 0) 12 else it }).toString()
    val face = watchDigits(LocalContext.current)
    val cap = remember(face) { capOf(face) }
    val r = digitRun(face, hour)
    Canvas(Modifier.fillMaxSize().testTag("hour-numeral")) {
        // the numeral as large as the safe box allows, centred in it
        drawDigits(r, placeDigits(r.ink.left, r.ink.width(), cap.top, cap.height(), x, y, w, h), p.numeral)
    }
    Canvas(Modifier.fillMaxSize()) {
        hands(ms, Offset(size.width / 2, size.height / 2), handRadius(), p.hourHands, p.hourSecond, p.numeral, edge = p.hourEdge)
    }
}

// ---- the full-screen dials: the whole screen is the face, as the watch's display is -------------------------------

/**
 * The dial's box on the screen, in px: its centre is the screen's, its half-sizes [a] and [b] reach the nearer edge of
 * the safe area on each axis, and its corner [r] is the display's own corner radius brought in concentrically.
 */
class DialBox(val centre: Offset, val a: Float, val b: Float, val r: Float)

/** The dial box for a [w] x [h] px face whose safe area is [l], [t], [rgt], [btm] px, on a display whose corners are [corner] px round. */
fun dialBox(w: Float, h: Float, l: Float, t: Float, rgt: Float, btm: Float, corner: Float): DialBox {
    val c = Offset(w / 2, h / 2)
    val a = min(c.x - l, rgt - c.x); val b = min(c.y - t, btm - c.y)
    // concentric with the screen's corner: the track stands in from the edge by the margin, so its radius is that much smaller
    val r = (corner - min(c.x - a, c.y - b)).coerceIn(0f, min(a, b))
    return DialBox(c, a, b, r)
}

/**
 * The hands' radius on a full-screen dial: the minute hand (0.85 of it) reaches 80% of the dial's mean half-size, so a
 * taller screen gets longer hands, and the second hand (0.87 of it) never passes [reach] on the dial's short axis.
 */
fun dialHandRadius(box: DialBox, reach: Float = min(box.a, box.b)) =
    min(0.80f * kotlin.math.sqrt(box.a * box.b) / 0.85f, reach / 0.87f)

// ---- Plain: one colour, the hands alone (Apple's Color face) ---------------------------------------------------------

@Composable
private fun PlainFace(ms: Long, p: FacePalette) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val s = safe()
    Canvas(Modifier.fillMaxSize()) {
        drawRect(p.plain)
        val box = dialBox(size.width, size.height, s.left.toPx(), s.top.toPx(), (s.left + s.width).toPx(), (s.top + s.height).toPx(), 0f)
        hands(ms, box.centre, dialHandRadius(box), p.plainHands, p.plainSecond, p.plain)
    }
}

// ---- Classic: Apple's California across the whole screen -------------------------------------------------------------

/** California's hours (apple-california.png): Roman at XII, I, II, X and XI, Arabic from 3 to 9. */
val californiaNumerals = listOf("XII", "I", "II", "3", "4", "5", "6", "7", "8", "9", "X", "XI")

/** Signed distance from (x, y) to a rounded rectangle centred on the origin with half-sizes [a], [b] and corner radius [r]. */
private fun roundedRectDistance(x: Float, y: Float, a: Float, b: Float, r: Float): Float {
    val qx = kotlin.math.abs(x) - a + r; val qy = kotlin.math.abs(y) - b + r
    return kotlin.math.hypot(maxOf(qx, 0f), maxOf(qy, 0f)) + min(maxOf(qx, qy), 0f) - r
}

/** The unit vector from the centre at [deg] clockwise from twelve. */
private fun ray(deg: Float): Offset {
    val rad = Math.toRadians(deg.toDouble())
    return Offset(sin(rad).toFloat(), -cos(rad).toFloat())
}

/**
 * Where the ray from the centre at [deg] (clockwise from twelve) meets a rounded rectangle with half-sizes [a], [b] and
 * corner radius [r]: the dial's track follows the face's own shape, so every mark sits on the angle its hand points to.
 */
fun trackPoint(deg: Float, a: Float, b: Float, r: Float): Offset {
    val d = ray(deg)
    var lo = 0f; var hi = kotlin.math.hypot(a, b)
    repeat(30) { val mid = (lo + hi) / 2; if (roundedRectDistance(d.x * mid, d.y * mid, a, b, r) < 0f) lo = mid else hi = mid }
    return Offset(d.x * lo, d.y * lo)
}

/** The furthest point along the ray at [deg] where a box of half-sizes [hw] x [hh] centred on it stays inside the rounded rectangle. */
private fun boxOnTrack(deg: Float, hw: Float, hh: Float, a: Float, b: Float, r: Float): Offset {
    val d = ray(deg)
    fun inside(t: Float) = listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f)
        .all { (sx, sy) -> roundedRectDistance(d.x * t + sx * hw, d.y * t + sy * hh, a, b, r) <= 0f }
    var lo = 0f; var hi = kotlin.math.hypot(a, b)
    repeat(30) { val mid = (lo + hi) / 2; if (inside(mid)) lo = mid else hi = mid }
    return Offset(d.x * lo, d.y * lo)
}

/**
 * California laid on the screen, in px from the screen's top left: the 60 ticks' outer and inner ends, the 12 numerals'
 * ink centres, the numerals' font size in px, the ticks' stroke widths and the hands' radius.
 */
class ClassicDial(val box: DialBox, val tickOuter: List<Offset>, val tickInner: List<Offset>, val numerals: List<Offset>,
    val numeralSize: Float, val minorWidth: Float, val majorWidth: Float, val handRadius: Float) {
    /** Tick [i]'s stroke width: every fifth tick is an hour's. */
    fun tickWidth(i: Int) = if (i % 5 == 0) majorWidth else minorWidth
}

/** Clear air between a numeral's ink and the nearest tick's stroke, per px of numeral size: about a third of a figure's height, as on California. */
const val classicTickGap = 0.25f

/** Clear air between neighbouring numerals' ink on at least one axis, per px of numeral size. */
const val classicNeighbourGap = 0.5f

/** True when the segment [p]-[q] passes through the box centred on [c] with half-sizes [hw] x [hh] (Liang-Barsky clipping). */
private fun segmentHitsBox(p: Offset, q: Offset, c: Offset, hw: Float, hh: Float): Boolean {
    var t0 = 0f; var t1 = 1f
    for ((d, start, lo, hi) in listOf(listOf(q.x - p.x, p.x, c.x - hw, c.x + hw), listOf(q.y - p.y, p.y, c.y - hh, c.y + hh))) {
        if (kotlin.math.abs(d) < 1e-6f) { if (start < lo || start > hi) return false; continue }
        var a = (lo - start) / d; var b = (hi - start) / d
        if (a > b) { val t = a; a = b; b = t }
        t0 = maxOf(t0, a); t1 = min(t1, b)
        if (t0 > t1) return false
    }
    return true
}

private fun pointBoxDistance(p: Offset, c: Offset, hw: Float, hh: Float) =
    kotlin.math.hypot(maxOf(kotlin.math.abs(p.x - c.x) - hw, 0f), maxOf(kotlin.math.abs(p.y - c.y) - hh, 0f))

private fun pointSegmentDistance(x: Offset, p: Offset, q: Offset): Float {
    val d = q - p; val len2 = d.x * d.x + d.y * d.y
    val t = if (len2 == 0f) 0f else (((x.x - p.x) * d.x + (x.y - p.y) * d.y) / len2).coerceIn(0f, 1f)
    return (x - (p + d * t)).getDistance()
}

/** The shortest distance from the segment [p]-[q] to the box centred on [c] with half-sizes [hw] x [hh]; 0 where they meet. */
fun segmentBoxDistance(p: Offset, q: Offset, c: Offset, hw: Float, hh: Float): Float {
    if (segmentHitsBox(p, q, c, hw, hh)) return 0f
    val corners = listOf(Offset(c.x - hw, c.y - hh), Offset(c.x + hw, c.y - hh), Offset(c.x - hw, c.y + hh), Offset(c.x + hw, c.y + hh))
    return minOf(pointBoxDistance(p, c, hw, hh), pointBoxDistance(q, c, hw, hh), corners.minOf { pointSegmentDistance(it, p, q) })
}

/**
 * The dial for [box], with numerals wanted at [base] px whose ink half-width and half-height per px of font size are
 * [ink] (one pair per numeral, in californiaNumerals' order). Ticks and numerals stand on the ray at their own angle,
 * so each mark lies exactly where its hand points. Each numeral sits as far out on its ray as it can while its ink keeps
 * [classicTickGap] of its size clear of every tick's stroke, so on a long side, where the ticks either side of 9 or 3
 * crowd in, it steps inward instead of touching them. Where the track brings neighbours close, the numerals' size comes
 * from the gap left between them ([classicNeighbourGap] of a numeral's size kept clear), never from squeezing their
 * shapes. The hands grow with the screen (dialHandRadius) until the minute hand reaches the nearest numeral, as on California.
 */
fun classicDial(box: DialBox, base: Float, ink: List<Pair<Float, Float>>): ClassicDial {
    val u = min(box.a, box.b)
    val minor = u * 0.035f; val major = u * 0.07f; val pad = u * 0.04f
    val minorWidth = u * 0.008f; val majorWidth = u * 0.02f
    val tickOuter = (0 until 60).map { box.centre + trackPoint(it * 6f, box.a, box.b, box.r) }
    val tickInner = tickOuter.mapIndexed { i, o ->
        val v = o - box.centre; val len = v.getDistance(); val cut = if (i % 5 == 0) major else minor
        box.centre + v * ((len - cut) / len)
    }
    val halfStroke = (0 until 60).map { (if (it % 5 == 0) majorWidth else minorWidth) / 2 }
    fun clearOfTicks(c: Offset, hw: Float, hh: Float, gap: Float) =
        (0 until 60).all { segmentBoxDistance(tickOuter[it], tickInner[it], c, hw, hh) - halfStroke[it] >= gap }
    val na = box.a - major - pad; val nb = box.b - major - pad; val nr = maxOf(box.r - major - pad, 0f)
    fun place(s: Float) = ink.mapIndexed { i, (hw, hh) ->
        val outer = boxOnTrack(i * 30f, hw * s, hh * s, na, nb, nr)
        if (clearOfTicks(box.centre + outer, hw * s, hh * s, classicTickGap * s)) return@mapIndexed box.centre + outer
        // step in along the ray: the centre is clear of every tick, the track's edge is not
        val d = ray(i * 30f); var lo = 0f; var hi = outer.getDistance()
        repeat(24) { val mid = (lo + hi) / 2; if (clearOfTicks(box.centre + d * mid, hw * s, hh * s, classicTickGap * s)) lo = mid else hi = mid }
        box.centre + d * lo
    }
    var size = base
    var at = place(size)
    for (step in 0 until 40) {
        // the largest size at which each pair of neighbours keeps the neighbour gap clear on one axis
        val fits = (0 until 12).minOf { i ->
            val j = (i + 1) % 12; val dx = kotlin.math.abs(at[i].x - at[j].x); val dy = kotlin.math.abs(at[i].y - at[j].y)
            maxOf(dx / (ink[i].first + ink[j].first + classicNeighbourGap), dy / (ink[i].second + ink[j].second + classicNeighbourGap))
        }
        if (fits >= size) break
        size = fits * 0.98f; at = place(size)
    }
    // as on California, the minute hand ends at the nearest numeral's inner edge and the second hand at the ticks
    val clear = at.indices.minOf { i ->
        val v = at[i] - box.centre; val len = v.getDistance()
        len - (ink[i].first * kotlin.math.abs(v.x) + ink[i].second * kotlin.math.abs(v.y)) / len * size
    } - size * 0.1f
    val hands = minOf(dialHandRadius(box, u - minor), clear / 0.85f)
    return ClassicDial(box, tickOuter, tickInner, at, size, minorWidth, majorWidth, hands)
}

/**
 * What the Classic face draws, in px from the face's top left: the dial, each numeral's text layout and top left, and
 * each numeral's ink box as drawn (measured from the laid-out glyphs at their final size, not scaled from the reference).
 */
class ClassicLayout(val dial: ClassicDial, val texts: List<androidx.compose.ui.text.TextLayoutResult>, val topLefts: List<Offset>,
    val inkBoxes: List<androidx.compose.ui.geometry.Rect>)

/** The Classic face's layout, read by device tests to measure what was drawn. */
val ClassicLayoutKey = androidx.compose.ui.semantics.SemanticsPropertyKey<ClassicLayout>("ClassicLayout")

/** The display's own corner radius in real px (RoundedCorner, API 31), 0 where the display reports square corners. */
@Composable
private fun displayCorner(): Float {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching { ctx.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() }.getOrNull() ?: 0f
    }
}

@Composable
private fun ClassicFace(ms: Long, p: FacePalette) = BoxWithConstraints(Modifier.fillMaxSize().testTag("classic-dial")) {
    val s = safe()
    val ink = p.dialInk
    val measurer = rememberTextMeasurer()
    val d = LocalDensity.current
    val corner = displayCorner()
    val screenPx = LocalContext.current.resources.displayMetrics.widthPixels.toFloat()
    val style = Display.face(100f, 600, ink, family = NativeFont, align = TextAlign.Center)
    val ref = with(d) { 100.sp.toPx() }
    // each numeral's ink box, per px of font size
    val inks = remember(measurer, style, ref) {
        californiaNumerals.map { label ->
            val b = measurer.measure(label, style, softWrap = false).multiParagraph.getPathForRange(0, label.length).getBounds()
            b.width / 2 / ref to b.height / 2 / ref
        }
    }
    // the dial and its numerals' text layouts only change with the screen, so they are laid out once per size, not on
    // every tick of the second hand
    val wPx = with(d) { maxWidth.toPx() }; val hPx = with(d) { maxHeight.toPx() }
    val layout = remember(inks, style, s, corner, screenPx, wPx, hPx) {
        val box = with(d) {
            dialBox(wPx, hPx, s.left.toPx(), s.top.toPx(), (s.left + s.width).toPx(), (s.top + s.height).toPx(), corner * (wPx / screenPx).coerceAtMost(1f))
        }
        val dial = classicDial(box, min(box.a, box.b) * 0.26f, inks)
        val sp = dial.numeralSize / ref * 100f
        val texts = californiaNumerals.map { measurer.measure(it, Display.resized(style, sp), softWrap = false) }
        val bounds = texts.mapIndexed { i, t -> t.multiParagraph.getPathForRange(0, californiaNumerals[i].length).getBounds() }
        val topLefts = bounds.mapIndexed { i, b -> dial.numerals[i] - b.center }
        ClassicLayout(dial, texts, topLefts, bounds.mapIndexed { i, b -> b.translate(topLefts[i]) })
    }
    Canvas(Modifier.fillMaxSize().semantics { this[ClassicLayoutKey] = layout }) {
        drawRect(p.dial)
        val dial = layout.dial
        val box = dial.box
        for (i in 0 until 60) {
            val major = i % 5 == 0
            drawLine(if (major) ink else ink.copy(alpha = 0.45f), dial.tickOuter[i], dial.tickInner[i], dial.tickWidth(i), StrokeCap.Round)
        }
        layout.texts.forEachIndexed { i, t -> drawText(t, topLeft = layout.topLefts[i]) }
        hands(ms, box.centre, dial.handRadius, ink, p.classicSecond, p.dial)
    }
}
