package org.honeycomb.launcher

import androidx.compose.animation.core.Easing
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * The classic Apple Watch honeycomb, as pure functions. Every constant is a fit to Apple's 2015 Apple Watch
 * footage (the welcome video and its motion shots). Each fit comes from a single shot, so the values are tuned by eye.
 * Lengths are in units of D0, the diameter of an icon at the centre of the lens at zoom 1, unless named otherwise.
 */
object Honeycomb {
    /** D0 as a share of the screen width (geometry.json D0_over_display_width). */
    const val D0_PER_WIDTH = 0.254f

    /** Icon diameter over lattice pitch (geometry.json D0_over_pitch); the pitch is D0 / 0.90. */
    const val D0_PER_PITCH = 0.90f

    /** Row step over pitch: a hexagonal lattice. */
    val ROW = sqrt(3f) / 2f

    // The lens, s = max(0, 1 - (|u|/A)^N - (|v|/B)^N)^K * clamp(eta/E, 0, 1) (edge-fit.json, rms 0.014 over 20 icons).
    const val LENS_A = 3.459f
    const val LENS_B = 3.781f
    const val LENS_N = 1.567f
    const val LENS_K = 0.603f
    const val LENS_E = 0.481f

    /** Time constant of the edge return, e(t) = 55.46 exp(-t / 0.1505) px (bounce-fit.json, rms 0.8 px). */
    const val EDGE_TAU = Motion.Grid.HONEYCOMB_EDGE_TAU

    /** Time constant of the tap-to-open expansion, w(t) = W - (W - w0) exp(-t / 0.1054) (tap-fit.json, rms 1.3 px). */
    const val OPEN_TAU = Motion.Grid.HONEYCOMB_OPEN_TAU

    /** The expansion fills the screen in about 400 ms (tap-snapshot-widths.csv reaches 287 of 288 px at 0.40 s). */
    const val OPEN_MS = Motion.Grid.HONEYCOMB_OPEN_MS

    /** The edge return is visually done in about 0.43 s (within 5 px); the tween runs four time constants. */
    const val EDGE_MS = Motion.Grid.HONEYCOMB_EDGE_MS

    /** The Crown's zoom spans about 4x; zoom 1 is the measured default view. */
    const val ZOOM_MIN = 0.5f
    const val ZOOM_MAX = 2.0f

    /** The largest overshoot after a fling hits the edge, in D0: the footage peaks at 56-80 px against an 83 px icon. */
    const val BOUNCE_PEAK = 0.75f
}

/** Centre of lattice cell (q, r) in units of the pitch: x = q + r/2, y = sqrt(3)/2 * r. */
fun cellX(q: Int, r: Int): Float = q + r / 2f
fun cellY(r: Int): Float = Honeycomb.ROW * r

/** How far into the lens a point is: 0 at the centre, 1 where the lens shrinks an icon to nothing. */
fun lensDistance(u: Float, v: Float): Float =
    (abs(u) / Honeycomb.LENS_A).pow(Honeycomb.LENS_N) + (abs(v) / Honeycomb.LENS_B).pow(Honeycomb.LENS_N)

/**
 * Scale of an icon whose centre is (u, v) from the screen centre and eta from the nearest screen edge, all in D0.
 * 1 at the centre, 0 off screen. With [equal] (Reduce Motion) every icon whose centre is on screen is full size.
 */
fun lensScale(u: Float, v: Float, eta: Float, equal: Boolean = false): Float {
    if (eta <= 0f) return 0f
    if (equal) return 1f
    val core = 1f - lensDistance(u, v)
    if (core <= 0f) return 0f
    return core.pow(Honeycomb.LENS_K) * (eta / Honeycomb.LENS_E).coerceAtMost(1f)
}

/**
 * The first [n] cells of the lattice in the order the lens shows them, nearest first, as x0, y0, x1, y1, ... in pitch
 * units. Cell 0 is the centre. Ties go by angle so the order is stable.
 */
fun honeycombSlots(n: Int): FloatArray {
    if (n <= 0) return FloatArray(0)
    var rings = 0
    while (3 * rings * (rings + 1) + 1 < 2 * n + 7) rings++
    val cells = ArrayList<FloatArray>()
    for (r in -rings..rings) for (q in -rings..rings) {
        if (abs(q + r) > rings) continue
        val x = cellX(q, r)
        val y = cellY(r)
        // Pitch units in, D0 units for the lens metric, so the cluster takes the lens's own oval shape.
        val d = lensDistance(x / Honeycomb.D0_PER_PITCH, y / Honeycomb.D0_PER_PITCH)
        cells.add(floatArrayOf(x, y, d, atan2(y, x)))
    }
    cells.sortWith(compareBy<FloatArray>({ it[2] }, { it[3] }))
    val out = FloatArray(2 * n)
    for (i in 0 until n) {
        out[2 * i] = cells[i][0]
        out[2 * i + 1] = cells[i][1]
    }
    return out
}

/**
 * Rubber band past an edge (the UIScrollView curve, c = 0.55): an overshoot x displays as (1 - 1/(x c/d + 1)) d,
 * so a drag moves the content less the further it goes and never past d.
 */
fun rubber(x: Float, d: Float, c: Float = 0.55f): Float = (1f - 1f / (x * c / d + 1f)) * d

/** Inverse of [rubber]: the overshoot that displays as y. */
fun unrubber(y: Float, d: Float, c: Float = 0.55f): Float = (d / c) * (1f / (1f - y.coerceAtMost(d * 0.999f) / d) - 1f)

/** A raw position shown with rubber bands outside [min, max]. */
fun band(raw: Float, min: Float, max: Float, d: Float): Float = when {
    raw < min -> min - rubber(min - raw, d)
    raw > max -> max + rubber(raw - max, d)
    else -> raw
}

/** Inverse of [band]: the raw position that shows as [shown]. */
fun unband(shown: Float, min: Float, max: Float, d: Float): Float = when {
    shown < min -> min - unrubber(min - shown, d)
    shown > max -> max + unrubber(shown - max, d)
    else -> shown
}

/**
 * Easing for a tween of [durationS] seconds that follows 1 - exp(-t/tau), the shape of every measured Apple motion
 * here, rescaled so it lands exactly on 1 at the end.
 */
class ExponentialEasing(private val tau: Float, private val durationS: Float) : Easing {
    private val end = 1f - exp(-durationS / tau)
    override fun transform(fraction: Float): Float = (1f - exp(-fraction * durationS / tau)) / end
}

/** The measured edge return, how far from rest an icon is t seconds after release from [start]. */
fun edgeReturn(start: Float, t: Float): Float = start * exp(-t / Honeycomb.EDGE_TAU)

/** The measured open, the snapshot width t seconds after the tap, from w0 to the full width [full]. */
fun openWidth(w0: Float, full: Float, t: Float): Float = full - (full - w0) * exp(-t / Honeycomb.OPEN_TAU)

/** Spring stiffness whose critically damped tail is exp(-t/tau): omega = 1/tau, stiffness = omega^2. */
fun stiffnessFor(tau: Float): Float = 1f / (tau * tau)

/**
 * Largest speed into an edge, px/s, so the critically damped bounce peaks at [Honeycomb.BOUNCE_PEAK] D0:
 * x(t) = v t exp(-t/tau) peaks at v tau / e.
 */
fun maxBounceSpeed(d0Px: Float): Float = Honeycomb.BOUNCE_PEAK * d0Px * kotlin.math.E.toFloat() / Honeycomb.EDGE_TAU

/** Peak of a critically damped bounce launched at speed v: v tau / e. */
fun bouncePeak(v: Float, tau: Float = Honeycomb.EDGE_TAU): Float = v * tau / kotlin.math.E.toFloat()

/** The zoom after one volume-key step: four steps of sqrt(2) span the 4x range. */
fun zoomStep(zoom: Float, direction: Int): Float =
    (zoom * sqrt(2f).pow(direction.toFloat())).coerceIn(Honeycomb.ZOOM_MIN, Honeycomb.ZOOM_MAX)

/** Lerp. */
fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t
