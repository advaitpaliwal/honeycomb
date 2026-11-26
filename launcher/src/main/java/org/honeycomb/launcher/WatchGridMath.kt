package org.honeycomb.launcher

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * watchOS 27.2's full app grid, measured from Apple's simulator on a 49 mm watch (422 x 514 native px). It scrolls only vertically; rows alternate 3 and 4 icons, offset
 * like a honeycomb; icons shrink toward the top and bottom edges and the rows bunch up there.
 *
 * Every number below is in the watch's native px and is scaled by our width / 422. The edge falloff is measured from the
 * screen's top and bottom edge, so on a taller screen the middle rows stay full size and the same falloff sits at its edges.
 */
object WatchGrid {
    const val NATIVE_W = 422f
    const val NATIVE_HALF_H = 257f

    /** Content distance between rows, and the horizontal pitch in the centre row (static-grid-row-spacing.json). */
    const val ROW = 105f
    const val PITCH = 118f

    /** The List View pill: 282 x 54, its centre 160.5 px below the last row when the grid rests at the bottom (grid-bottom.png). */
    const val PILL_W = 282f
    const val PILL_H = 54f
    const val PILL_BELOW_LAST_ROW = 160.5f

    /** At the grid's end the pill's centre sits this far above the screen's bottom edge (or the navigation bar's). */
    const val PILL_ABOVE_BOTTOM = NATIVE_HALF_H - PILL_BELOW_LAST_ROW

    /** All icons the same size with Remove animations on: 102 leaves the measured 16 px gap at the 118 pitch. */
    const val EQUAL_D = 102f

    /**
     * A row whose largest icon the edge falloff has shrunk below this (native px) is squeezed: at rest only the first
     * squeezed row past the whole rows shows, as a scroll cue (WatchGridLayout.shownAtRest). The smallest whole row at any
     * resting row is 64 native px; the bunched rows are 30 or less.
     */
    const val REST_MIN_D = 60f

    /** How fast the edge rows fade in when the grid starts moving and out once it has settled, seconds. */
    const val EDGE_FADE_IN_S = Motion.Grid.WATCH_EDGE_FADE_IN_S
    const val EDGE_FADE_OUT_S = Motion.Grid.WATCH_EDGE_FADE_OUT_S

    /** Fling: projected distance = release velocity x 0.4 s, rounded to a row (13/22: 526 px/s moved one icon 2 rows). */
    const val FLING_PROJECTION_S = Motion.Grid.WATCH_FLING_PROJECTION_S

    /** Snap spring, critically damped, stiffness 256 (w = 16/s): 210 px at 526 px/s settles within 1 px in 0.45 s, as clip 22. */
    const val SNAP_STIFFNESS = Motion.Grid.WATCH_SNAP_STIFFNESS

    /** Lattice x offsets of the four column kinds: 0 and 118 in 3-icon rows, 59 and 177 in 4-icon rows. */
    val COLUMN_X = floatArrayOf(0f, 59f, 118f, 177f)

    /**
     * Diameter by distance from the screen centre, per column kind. Column 0 is vertical-center-size-knots.csv (the
     * validated fit, 0.44 px rms); the others are medians of icon-boundaries.csv binned by column (size-position-fit.json
     * warns its 2-D formula is too coarse, so the outer columns are measured, not extrapolated).
     */
    val SIZE = arrayOf(
        Pchip(
            floatArrayOf(0f, 2.25f, 7f, 12f, 17.75f, 22.5f, 27.5f, 33.5f, 38.5f, 41.5f, 46f, 51.5f, 57.5f, 62f, 67f, 73.5f, 77f, 81.5f, 88.5f, 93f, 97.5f, 102.5f, 107.5f, 112.5f, 117.5f, 122f, 126.5f, 132.5f, 138f, 141.75f, 146f, 153.75f, 158f, 162f, 167.5f, 173.5f, 177.5f, 181.75f, 188f, 192.5f, 197.75f, 202.25f, 207f, 213f, 217.75f, 222.25f, 227f, 233f, 237f, 240f, 257f),
            floatArrayOf(108.25f, 108.25f, 108f, 108f, 107.75f, 107f, 106.5f, 105.75f, 105f, 105f, 104f, 103f, 103f, 102.5f, 101.5f, 101f, 100.5f, 99.5f, 99f, 98.5f, 98f, 98f, 97f, 97f, 96.5f, 96f, 95.5f, 95f, 94.5f, 94.25f, 94f, 93.25f, 92.5f, 92f, 91f, 90f, 88.5f, 87.5f, 86f, 84f, 80.25f, 76.75f, 72f, 63.5f, 54.5f, 46.75f, 36.5f, 24f, 15.75f, 9.75f, 0f),
        ),
        Pchip(floatArrayOf(0f, 20f, 60f, 90f, 150f, 170f, 180f, 190f, 200f, 210f, 220f, 230f, 240f, 257f), floatArrayOf(95f, 96f, 97f, 95f, 94f, 92f, 87f, 81f, 78f, 68f, 50f, 27f, 18f, 0f)),
        Pchip(floatArrayOf(0f, 60f, 100f, 130f, 160f, 170f, 180f, 190f, 200f, 210f, 220f, 230f, 240f, 257f), floatArrayOf(96f, 97f, 97f, 94f, 87f, 83f, 78f, 74f, 69f, 50f, 34f, 23f, 8f, 0f)),
        Pchip(floatArrayOf(0f, 100f, 120f, 130f, 140f, 150f, 160f, 170f, 180f, 190f, 200f, 210f, 220f, 257f), floatArrayOf(82f, 82f, 78f, 74f, 72f, 68f, 65f, 58f, 50f, 44f, 35f, 20f, 13f, 0f)),
    )

    /** Screen |x| from the centre by distance from the screen centre: the outer columns lean in toward the edges. */
    val X = arrayOf(
        Pchip(floatArrayOf(0f, 257f), floatArrayOf(0f, 0f)),
        Pchip(floatArrayOf(0f, 200f, 215f, 235f, 257f), floatArrayOf(56f, 56f, 60f, 64f, 64f)),
        Pchip(floatArrayOf(0f, 15f, 100f, 130f, 170f, 200f, 257f), floatArrayOf(118f, 115f, 113f, 110f, 107f, 105f, 105f)),
        Pchip(floatArrayOf(0f, 120f, 150f, 180f, 200f, 220f, 257f), floatArrayOf(160f, 158f, 152f, 147f, 143f, 140f, 138f)),
    )

    /**
     * Rows bunch up toward the edges: per column, screen distance S grows with content distance c as
     * dS/dc = (size(S) + G) / N. G and N are fitted so the rows land where grid-middle.png has them
     * (column 0: 210 -> 194; 59: 105 -> 105 and 315 -> 232; 118: 210 -> 190; 177: 105 -> 95).
     */
    val GAP = floatArrayOf(16f, -16f, 16f, 16f)
    val NORM = floatArrayOf(123.4f, 80.5f, 120f, 108.4f)
}

/** Fritsch-Carlson monotone cubic (what SciPy's PchipInterpolator does), flat beyond the ends. */
class Pchip(private val x: FloatArray, private val y: FloatArray) {
    private val m = FloatArray(x.size)

    init {
        val n = x.size
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
        if (n == 2) { m[0] = d[0]; m[1] = d[0] } else {
            for (i in 1 until n - 1) {
                m[i] = if (d[i - 1] * d[i] <= 0f) 0f else {
                    val h0 = x[i] - x[i - 1]; val h1 = x[i + 1] - x[i]
                    val w1 = 2 * h1 + h0; val w2 = h1 + 2 * h0
                    (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
            m[0] = end(x[1] - x[0], x[2] - x[1], d[0], d[1])
            m[n - 1] = end(x[n - 1] - x[n - 2], x[n - 2] - x[n - 3], d[n - 2], d[n - 3])
        }
    }

    private fun end(h0: Float, h1: Float, d0: Float, d1: Float): Float {
        var e = ((2 * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (sign(e) != sign(d0)) e = 0f else if (sign(d0) != sign(d1) && abs(e) > abs(3 * d0)) e = 3 * d0
        return e
    }

    operator fun invoke(t: Float): Float {
        if (t <= x[0]) return y[0]
        if (t >= x[x.size - 1]) return y[y.size - 1]
        var i = 0
        while (t > x[i + 1]) i++
        val h = x[i + 1] - x[i]
        val s = (t - x[i]) / h
        val s2 = s * s; val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * y[i] + (s3 - 2 * s2 + s) * h * m[i] + (-2 * s3 + 3 * s2) * y[i + 1] + (s3 - s2) * h * m[i + 1]
    }
}

/** One icon on screen: centre and diameter, px. */
data class Placed(val x: Float, val y: Float, val d: Float)

/**
 * The grid laid out for a [width] x [height] px screen. Slots fill rows of 3, 4, 3, 4... left to right. Scroll is the
 * content position (px) at the screen's vertical centre.
 */
class WatchGridLayout(val width: Float, val height: Float, val count: Int, val equalSizes: Boolean = false, bottomInset: Float = 0f,
    scaleWidth: Float = width, private val sideInset: Float = 0f) {
    val k = scaleWidth / WatchGrid.NATIVE_W
    private val wide = sideInset > 0f
    private val columns = if (wide) ((width - 2 * sideInset) / (108.25f * k)).toInt().coerceAtLeast(4) else 4
    val row = WatchGrid.ROW * k
    private val halfH = height / 2f

    /** Row, and lattice x in native px, of each slot. */
    val slotRow = IntArray(count)
    val slotX = FloatArray(count)

    init {
        var r = 0; var p = 0
        for (i in 0 until count) {
            val n = columns - if (r % 2 == 0) 1 else 0
            slotRow[i] = r
            slotX[i] = (p - (n - 1) / 2f) * WatchGrid.PITCH
            if (++p == n) { p = 0; r++ }
        }
    }

    val lastRow = if (count == 0) 0 else slotRow[count - 1]

    /**
     * The grid rests with its third row in the middle at the top (grid-rest.png) and its last row there at the bottom
     * (grid-bottom.png), on the watch's 514 px. A taller screen keeps the same distance from each edge (the falloff is
     * anchored there too), so the first row and the List View pill sit as close to the edges as Apple's and no black band
     * opens above the top row or below the pill (sbs-220/c-v1: 135 px of black on the tested phone).
     */
    private val extra = max(0f, halfH - WatchGrid.NATIVE_HALF_H * k)
    val maxScroll = maxOf(0f, lastRow * row - extra, pillBottom(0f) - height + bottomInset)
    val minScroll = min(2 * row + extra, maxScroll)

    /**
     * The slot nearest the screen's middle when the grid opens: everything else is placed outward from it, so the first
     * items (the most used) sit in the middle. On the watch that is the third row's centre; on a taller screen, whose
     * top rows keep the watch's distance from the top edge, it is the 3-icon row nearest the screen's middle, whose
     * centre column has the largest icon, as the watch's has.
     */
    val restCentre: Int = run {
        val r = (2 * (minScroll / row / 2f).roundToInt()).coerceIn(0, lastRow)
        (0 until count).filter { slotRow[it] == r }.minByOrNull { abs(slotX[it]) } ?: 0
    }

    /** Slots ordered by distance from [restCentre], so the first items land in the middle and the rest spread outward. */
    val slotsByDistance: IntArray = (0 until count).sortedBy { s ->
        hypot(slotX[s] - slotX[restCentre], (slotRow[s] - slotRow[restCentre]) * WatchGrid.ROW)
    }.toIntArray()

    /** Column kind 0..3 of a lattice x. */
    fun column(latticeX: Float): Int {
        val x = abs(latticeX) * if (wide) 3f / (columns - 1) else 1f
        return WatchGrid.COLUMN_X.indices.minBy { abs(WatchGrid.COLUMN_X[it] - x) }
    }

    /** Native distance from the screen centre that the measured curves use: the falloff is anchored to the edges. */
    fun nativeDistance(screenDy: Float) = max(0f, WatchGrid.NATIVE_HALF_H - (halfH - abs(screenDy)) / k)

    private val step = k // table step, px of content
    private val tables: Array<FloatArray> = Array(4) { c ->
        // integrate dS/dc = (size(S) + G) / N until the icon has left the screen
        val out = ArrayList<Float>()
        var s = 0f
        out.add(0f)
        while (s < halfH && out.size < 20000) {
            val sub = 8
            repeat(sub) {
                s += (WatchGrid.SIZE[c](nativeDistance(s)) + WatchGrid.GAP[c]) / WatchGrid.NORM[c] * step / sub
            }
            out.add(s)
        }
        out.toFloatArray()
    }

    /** Screen distance from the centre of an icon [c] px of content from the centre, in column kind [col]. */
    fun screenDy(c: Float, col: Int): Float {
        if (equalSizes) return c
        val t = tables[col]
        val f = abs(c) / step
        val i = f.toInt()
        val v = if (i >= t.size - 1) t[t.size - 1] + (f - (t.size - 1)) * k else t[i] + (t[i + 1] - t[i]) * (f - i)
        return sign(c) * v
    }

    /** Where slot [i] is drawn at [scroll]. */
    fun place(i: Int, scroll: Float): Placed {
        val c = slotRow[i] * row - scroll
        val lx = slotX[i]
        if (wide) {
            // Keep the watch scale for diameter and row pitch; spend extra width on staggered columns.
            val fraction = lx / ((columns - 1) * WatchGrid.PITCH / 2f)
            val col = column(lx)
            val dy = screenDy(c, col)
            val diameter = (if (equalSizes) WatchGrid.EQUAL_D else WatchGrid.SIZE[col](nativeDistance(dy))) * k
            return Placed(width / 2f + fraction * (width / 2f - sideInset - diameter / 2f), halfH + dy, diameter)
        }
        if (equalSizes) return Placed(width / 2f + lx * k, halfH + c, WatchGrid.EQUAL_D * k)
        val col = column(lx)
        val dy = screenDy(c, col)
        val s = nativeDistance(dy)
        return Placed(width / 2f + sign(lx) * WatchGrid.X[col](s) * k, halfH + dy, WatchGrid.SIZE[col](s) * k)
    }

    /** Slots of each row, in order. */
    private val rowSlots: Array<IntArray> = Array(lastRow + 1) { r -> (0 until count).filter { slotRow[it] == r }.toIntArray() }

    /** A row is whole at [scroll] when its largest icon is at least [WatchGrid.REST_MIN_D]. */
    private fun rowWhole(r: Int, scroll: Float) = rowSlots[r].any { place(it, scroll).d >= WatchGrid.REST_MIN_D * k }

    /**
     * Whether slot [i] shows while the grid rests at [scroll]. The whole rows show, and so does the one squeezed row just
     * past them at each edge, at its edge-shrunk size, as the scroll cue Apple leaves (11-full-grid: two 22 px icons below
     * the last whole row, clear of it; grid-bottom: three tiny icons above the first). Every row past that one is hidden,
     * so the bunched rows never pile up at the edge (real phone v8c2ebc9 04-full-grid: 22-30 px and 0.4-2 px rows
     * overlapping). A peek icon that would cross the screen's edge or overlap a whole icon is hidden too.
     */
    fun shownAtRest(i: Int, scroll: Float): Boolean {
        if (equalSizes) return true
        val r = slotRow[i]
        if (rowWhole(r, scroll)) return true
        val inner = r - if (r * row < scroll) -1 else 1
        if (inner !in 0..lastRow || !rowWhole(inner, scroll)) return false
        // a 4-icon peek row shows only its inner two (11-full-grid's bottom, grid-middle's top)
        if (column(slotX[i]) == 3) return false
        val p = place(i, scroll)
        if (p.y - p.d / 2f < 0f || p.y + p.d / 2f > height) return false
        return rowSlots[inner].none { val q = place(it, scroll); hypot(q.x - p.x, q.y - p.y) < (q.d + p.d) / 2f }
    }

    /**
     * Alpha of slot [i] drawn at [p] at [scroll]: [moving] runs 0 at rest to 1 while scrolling or bouncing. At rest only
     * the slots [shownAtRest] keeps show; the others fade in as the grid moves.
     */
    fun alpha(i: Int, p: Placed, scroll: Float, moving: Float): Float = when {
        p.d < 1f -> 0f
        shownAtRest(i, scroll) -> 1f
        else -> moving
    }

    /** The List View pill's centre y; it moves with the content without bunching up. */
    fun pillY(scroll: Float) = halfH + lastRow * row + WatchGrid.PILL_BELOW_LAST_ROW * k - scroll

    /** The List View pill's top and bottom y at [scroll]. */
    fun pillTop(scroll: Float) = pillY(scroll) - WatchGrid.PILL_H * k / 2f
    fun pillBottom(scroll: Float) = pillY(scroll) + WatchGrid.PILL_H * k / 2f

    /**
     * Where the grid comes to rest: the two ends and every whole row between them, as the watch settles on a row. On the
     * watch the bottom end is itself a whole row; on our taller screen it is not (the end keeps Apple's distance from the
     * edge), so the whole row just before it rested with the List View pill cut by the screen's bottom edge
     * (sbs-220/c-grid-bounds-v3, third panel: 26 apps rested 104 px short of the end, the pill's lower half off screen).
     * A row whose pill would show at all is dropped: the grid rests with the pill wholly below the screen, or at the end,
     * where the pill sits whole, as far from the bottom edge as in grid-bottom (68 of 514 native px).
     */
    val restStops: FloatArray = run {
        val rows = (Math.ceil((minScroll / row).toDouble()).toInt()..Math.floor((maxScroll / row).toDouble()).toInt())
            .map { it * row }.filter { it > minScroll && it < maxScroll && pillTop(it) >= height }
        (listOf(minScroll) + rows + maxScroll).distinct().toFloatArray()
    }

    private fun nearestStop(at: Float) = restStops.indices.minBy { abs(restStops[it] - at) }

    /** Where a fling released at [scroll] with [velocity] px/s (positive scrolls down the content) comes to rest. */
    fun flingTarget(scroll: Float, velocity: Float): Float =
        restStops[nearestStop(scroll + velocity * WatchGrid.FLING_PROJECTION_S)]

    /** One Crown step from [scroll]: the rest one stop up the content for [direction] 1, down for -1. */
    fun crownTarget(scroll: Float, direction: Int): Float =
        restStops[(nearestStop(scroll) - direction).coerceIn(0, restStops.size - 1)]
}

/** iOS rubber band (UIScrollView): offset = (1 - 1 / (x * 0.55 / d + 1)) * d for a pull of x past the edge. */
fun rubberBand(x: Float, d: Float): Float = sign(x) * (1f - 1f / (abs(x) * 0.55f / d + 1f)) * d

fun rubberBandInverse(y: Float, d: Float): Float = sign(y) * (d / (1f - abs(y) / d) - d) / 0.55f

/**
 * The edge bounce's return, copied from 10-bottom-edge-bounce-motion.csv: the List View pill's distance from rest, as a
 * fraction of its 96 px overshoot, by seconds after it peaked. It holds for about 0.17 s, then returns; within 1 px at 0.89 s.
 */
object BounceCurve {
    val T = floatArrayOf(0.0f, 0.033f, 0.05f, 0.065f, 0.082f, 0.098f, 0.117f, 0.135f, 0.15f, 0.168f, 0.183f, 0.202f, 0.218f, 0.233f, 0.25f, 0.267f, 0.283f, 0.302f, 0.318f, 0.335f, 0.35f, 0.367f, 0.383f, 0.402f, 0.418f, 0.433f, 0.448f, 0.468f, 0.485f, 0.502f, 0.517f, 0.533f, 0.55f, 0.567f, 0.585f, 0.618f, 0.635f, 0.652f, 0.668f, 0.685f, 0.702f, 0.718f, 0.783f, 0.885f, 1.165f)
    val F = floatArrayOf(1.0f, 0.9948f, 0.9844f, 0.974f, 0.9635f, 0.9479f, 0.9375f, 0.9167f, 0.9062f, 0.8906f, 0.8125f, 0.7396f, 0.6667f, 0.6042f, 0.5469f, 0.4948f, 0.4479f, 0.4062f, 0.3646f, 0.3333f, 0.3021f, 0.2708f, 0.25f, 0.2188f, 0.1979f, 0.1875f, 0.1667f, 0.1458f, 0.1354f, 0.125f, 0.1146f, 0.1042f, 0.0938f, 0.0833f, 0.0729f, 0.0625f, 0.0521f, 0.0521f, 0.0521f, 0.0417f, 0.0417f, 0.0312f, 0.0208f, 0.0104f, 0.0f)
    val DURATION_S = T[T.size - 1]

    /** Fraction of the overshoot left at [t] seconds. */
    fun at(t: Float): Float {
        if (t <= 0f) return 1f
        if (t >= DURATION_S) return 0f
        var i = 0
        while (t > T[i + 1]) i++
        return F[i] + (F[i + 1] - F[i]) * (t - T[i]) / (T[i + 1] - T[i])
    }
}

/**
 * Opening an app, copied from 14-app-open-return (frames 5..12, 60 fps capture): the whole grid zooms about the tapped icon
 * and the app shows at 98 ms. Scale is the tapped icon's diameter over its rest diameter (96 -> 345 px at 8 ms, then its
 * circle's top/bottom edges). The grid does not fade: on the watch it fades under the app's window fading in, but here the
 * app's window jump-cuts in only once it has drawn (a scene-transition launch, which also gets no starting window), so a
 * fade left the screen black from ~67 ms until the app's first frame (sbs-220 d1-app-open-v2, +67..+167 ms). The zoomed
 * icon stays on screen until the app covers it (d1-app-open-v3).
 */
object OpenCurve {
    val T = floatArrayOf(0f, 0.008f, 0.023f, 0.040f, 0.058f, 0.098f)
    val SCALE = floatArrayOf(1f, 3.6f, 4.8f, 6.1f, 7.3f, 7.3f)
    val DURATION_S = T[T.size - 1]

    fun scale(t: Float) = lerpTable(T, SCALE, t)
    fun alpha(@Suppress("UNUSED_PARAMETER") t: Float) = 1f
}

/**
 * Coming back from an app, copied from 20-settings-open-return (frames 60..80): the grid shows at once and zooms back to
 * rest about the icon that opened it, scale - 1 = 2.8 exp(-t / 0.055 s) (the Messages icon's area: 1.605 at 5.827 s,
 * 1.26 at 5.877 s, 1.03 at 5.992 s); it fades in over the first 33 ms.
 */
object ReturnCurve {
    const val START = 3.8f
    const val TAU_S = 0.055f
    const val FADE_S = 0.033f
    /** Within 1% of rest. */
    val DURATION_S = (TAU_S * kotlin.math.ln((START - 1f) / 0.01f))

    fun scale(t: Float) = if (t >= DURATION_S) 1f else 1f + (START - 1f) * kotlin.math.exp(-t / TAU_S)
    fun alpha(t: Float) = (t / FADE_S).coerceIn(0f, 1f)
}

/**
 * A fling that reaches the end runs on past it on the same snap spring and comes back (13-mid-grid-flick: the List View
 * pill crossed its rest line at about 200 px/s, 422 -> 415 px from 2.605 to 2.640 s, and peaked 4.5 px past it 48 ms later).
 * Peak of a critically damped spring started at rest with speed v: v / (w e).
 */
fun edgeOvershoot(speed: Float): Float = abs(speed) / (kotlin.math.sqrt(WatchGrid.SNAP_STIFFNESS) * kotlin.math.E.toFloat())

private fun lerpTable(t: FloatArray, v: FloatArray, x: Float): Float {
    if (x <= t[0]) return v[0]
    if (x >= t[t.size - 1]) return v[v.size - 1]
    var i = 0
    while (x > t[i + 1]) i++
    return v[i] + (v[i + 1] - v[i]) * (x - t[i]) / (t[i + 1] - t[i])
}
