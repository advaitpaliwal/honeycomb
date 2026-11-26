package org.honeycomb.launcher

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/*
 * The depth transitions, measured frame by frame from watchOS 27.2 simulator recordings (60 fps):
 *  - Crown press, face to the app grid: the face blurs in place (about 180 ms), then the grid comes in over it, larger
 *    and blurred, settling sharp at its own size (about 220 ms). About 400 ms in all.
 *  - Smart Stack (clip 15): the first card rises into focus during the face fade on a half-second drag.
 *  - App open: the icon grows and blurs into the app (about 100 ms of growth), and the app sharpens in (about 330 ms).
 * During crown transitions the departing layer blurs before the next becomes legible. The stack instead overlaps the face.
 * Each curve is a pure function of the transition's progress (0 at the start, 1 settled), so it is tested on its own.
 */
object Motion {
    /** Face to favourites and back (watchOS crown press, measured 400 ms). */
    const val DEPTH_MS = 450

    /** One frame: the depth layers swap in it, so every change of look is their graphicsLayer. */
    const val SWAP_MS = 1

    /** App open: the icon's growth into the app (measured about 100 ms of growth plus the app sharpening in). */
    const val APP_OPEN_MS = 420

    /** App close: the plate shrinks back into its icon from the first frame after the cut (the open reversed, ~300 ms). */
    const val APP_CLOSE_MS = 300

    /** Split's rising colour: each second's step glides in about 100 ms (Flux, real-faces/flux-motion/pts.json). */
    const val FLUX_STEP_MS = 100

    /** The largest blur, as a fraction of the screen's width (the watch's blurred face, 422 px wide, blurs about 21 px). */
    const val BLUR = 0.05f

    /** How much larger the arriving grid starts (the watch grid's outer icons start about 15% further out). */
    const val GRID_GROW = 0.15f

    /** Clip 15: roughly half a screen of travel reveals the stack. Shorter drags spring the remaining distance. */
    const val STACK_TRAVEL = 0.53f

    private fun ramp(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)
    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun easeInOut(x: Float) = x * x * (3f - 2f * x)

    /** One layer's look at a moment: blur (fraction of [BLUR]), opacity and scale. */
    data class Look(val blur: Float, val alpha: Float, val scale: Float)

    /** Where the arriving layer starts: after the watch's ~200 ms of the face blurring and glowing on its own. */
    private const val HANDOFF = 0.44f

    /** The layer going away: it blurs in place at once (glowing as its bright parts spread) and fades only once blurred. */
    fun leaving(p: Float) = Look(blur = easeOut(ramp(p, 0f, 0.4f)), alpha = 1f - easeInOut(ramp(p, 0.55f, 0.95f)), scale = 1f)

    /** The layer arriving: it appears only once the other is blurred, larger and blurred, then settles sharp. */
    fun arriving(p: Float, grow: Float = GRID_GROW) = ramp(p, HANDOFF, 1f).let { q ->
        // the watch's grid is legible early but keeps sharpening to the end (recording 01: sharp ~200 ms after it appears)
        Look(blur = 1f - easeInOut(q), alpha = easeOut(ramp(p, HANDOFF, 0.72f)), scale = 1f + grow * (1f - easeOut(q)))
    }

    /** In clip 15 the outgoing digits soften without disappearing before the incoming date can be read. */
    fun underStack(p: Float) = Look(blur = easeOut(p), alpha = 1f - p, scale = 1f - 0.06f * p)

    fun stack(p: Float) = Look(blur = (1f - p) * (1f - p), alpha = easeInOut(p), scale = 1f)

    /** The plate's growth from the icon's rect to the whole screen (0 to 1). */
    fun appGrowth(p: Float) = easeOut(ramp(p, 0f, 0.6f))

    /**
     * The plate's size while closing ([p] runs from 1 down to 0): it starts shrinking on the first frame, with no hold at
     * full screen, and slows as it lands on the icon.
     */
    fun appCloseGrowth(p: Float) = p.coerceIn(0f, 1f).let { it * it }

    /** The first ~120 ms: the icon grows with the plate and blurs out ([appBlur]) as it fades ([appGhost], its opacity). */
    private const val GHOST = 120f / APP_OPEN_MS
    fun appBlur(p: Float) = easeOut(ramp(p, 0f, GHOST))
    fun appGhost(p: Float) = 1f - ramp(p, GHOST * 0.4f, GHOST)

    /** The launch state's icon: Android's splash screen draws it at about a third of the screen's short side. */
    const val SPLASH_ICON = 1f / 3f

    /**
     * A spring in Apple's terms (SwiftUI's response and dampingFraction), turned into Compose's: with a unit mass the
     * stiffness is (2 pi / response) squared and the damping ratio carries over unchanged.
     */
    data class Spring(val response: Float, val damping: Float) {
        val stiffness: Float get() = (2f * Math.PI.toFloat() / response).let { it * it }
        fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> = spring(damping, stiffness, visibilityThreshold)
    }

    /** A control responding to a touch (docs/design-language.md §7). */
    val snappy = Spring(response = 0.3f, damping = 0.85f)

    /** Sheets, settling, cards. */
    val smooth = Spring(response = 0.5f, damping = 1.0f)

    /** The app grid, icons. */
    val bouncy = Spring(response = 0.5f, damping = 0.7f)

    /**
     * Reduce Motion, the one place Honeycomb reads it: Android's Remove animations (Accessibility) and an animator duration
     * scale of 0 (Developer options) both set Settings.Global.ANIMATOR_DURATION_SCALE to 0, and then every motion snaps.
     */
    fun snaps(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

    fun animatorDurationScale(ctx: Context): Float =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    fun reduceMotion(ctx: Context): Boolean = snaps(animatorDurationScale(ctx))

    /** The same setting read from the animator system, for code without a context. */
    fun reduceMotion(): Boolean = !android.animation.ValueAnimator.areAnimatorsEnabled()

    /** [spec], or a snap when motion is reduced. */
    fun <T> orSnap(reduce: Boolean, spec: () -> androidx.compose.animation.core.AnimationSpec<T>): androidx.compose.animation.core.AnimationSpec<T> =
        if (reduce) androidx.compose.animation.core.snap() else spec()

    /** Reduce Motion as state: re-read whenever the setting changes, so turning it on applies at once. */
    @androidx.compose.runtime.Composable
    fun rememberReduceMotion(): Boolean {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val state = androidx.compose.runtime.remember(ctx) { androidx.compose.runtime.mutableStateOf(reduceMotion(ctx)) }
        androidx.compose.runtime.DisposableEffect(ctx) {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { state.value = reduceMotion(ctx) }
            }
            ctx.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
            onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
        }
        return state.value
    }

    /** Edit mode's jiggle: each icon rocks on its own period (140, 155 or 170 ms) so neighbours never move in step. */
    fun <T> jiggle(seed: Int): TweenSpec<T> = tween(JIGGLE_MS + seed % 3 * JIGGLE_STEP_MS)
    private const val JIGGLE_MS = 140
    private const val JIGGLE_STEP_MS = 15

    /** The feed pause's breath (in 2.5 s, out 2.5 s), before the choices appear. */
    const val BREATH_MS = 5_000L

    /** How long the feed pause's two choices take to fade in after the breath. */
    const val CHOICES_FADE_MS = 400

    /*
     * The app grid's motion. Every number was measured from watchOS simulator recordings and is unchanged: the
     * honeycomb's fitted time constants (bounce-fit.json, tap-fit.json) and the watch grid's snap spring and sampled
     * curves (clips 13, 20, 22).
     */
    object Grid {
        // ---- The honeycomb (Honeycomb.kt; aliased by [Honeycomb]) ----

        /** Time constant of the edge return, e(t) = 55.46 exp(-t / 0.1505) px (bounce-fit.json, rms 0.8 px). */
        const val HONEYCOMB_EDGE_TAU = 0.1505f

        /** Time constant of the tap-to-open expansion, w(t) = W - (W - w0) exp(-t / 0.1054) (tap-fit.json, rms 1.3 px). */
        const val HONEYCOMB_OPEN_TAU = 0.1054f

        /** The expansion fills the screen in about 400 ms (tap-snapshot-widths.csv reaches 287 of 288 px at 0.40 s). */
        const val HONEYCOMB_OPEN_MS = 400

        /** The edge return is visually done in about 0.43 s (within 5 px); the tween runs four time constants. */
        const val HONEYCOMB_EDGE_MS = 600

        /** The edge return as a spring on the same time constant (critically damped). */
        fun honeycombEdgeSpring(): SpringSpec<Float> = spring(1f, stiffnessFor(HONEYCOMB_EDGE_TAU))

        /** The edge return from a still finger: exponential on [HONEYCOMB_EDGE_TAU]. */
        fun honeycombEdgeReturn(): TweenSpec<Float> =
            tween(HONEYCOMB_EDGE_MS, easing = ExponentialEasing(HONEYCOMB_EDGE_TAU, HONEYCOMB_EDGE_MS / 1000f))

        /** Tap to open: exponential on [HONEYCOMB_OPEN_TAU]. */
        fun honeycombOpen(): TweenSpec<Float> =
            tween(HONEYCOMB_OPEN_MS, easing = ExponentialEasing(HONEYCOMB_OPEN_TAU, HONEYCOMB_OPEN_MS / 1000f))

        // ---- The watch grid (WatchGridView.kt; aliased by [WatchGrid]) ----

        /** How fast the edge rows fade in when the grid starts moving and out once it has settled, seconds. */
        const val WATCH_EDGE_FADE_IN_S = 0.1f
        const val WATCH_EDGE_FADE_OUT_S = 0.2f

        /** Fling: projected distance = release velocity x 0.4 s, rounded to a row (13/22: 526 px/s moved one icon 2 rows). */
        const val WATCH_FLING_PROJECTION_S = 0.4f

        /** A fling headed past the end slows on this multiple of the projection time before handing over to the bounce. */
        const val WATCH_FLING_DECAY_FACTOR = 4.2f

        /** Snap spring, critically damped, stiffness 256 (w = 16/s): 210 px at 526 px/s settles within 1 px in 0.45 s, as clip 22. */
        const val WATCH_SNAP_STIFFNESS = 256f

        /** The friction for a fling's slowdown past the end. */
        const val WATCH_FLING_FRICTION = 1f / (WATCH_FLING_DECAY_FACTOR * WATCH_FLING_PROJECTION_S)

        fun watchSnap(): SpringSpec<Float> = spring(1f, WATCH_SNAP_STIFFNESS)

        /** A sampled curve's whole run, linear in time, so the curve itself is read at each frame's time. */
        private fun sampled(durationS: Float, easing: Easing = LinearEasing): TweenSpec<Float> = tween((durationS * 1000).toInt(), easing = easing)

        /** App open: the grid zooms and fades about the icon on clip 20's sampled [OpenCurve]. */
        fun watchOpen(): TweenSpec<Float> = sampled(OpenCurve.DURATION_S)

        /** Back from the app, on [ReturnCurve]. */
        fun watchReturn(): TweenSpec<Float> = sampled(ReturnCurve.DURATION_S)

        /** The edge bounce from a pull, on clip 13's sampled [BounceCurve], run as 0 to 1. */
        fun watchBounce(): TweenSpec<Float> = sampled(BounceCurve.DURATION_S) { 1f - BounceCurve.at(it * BounceCurve.DURATION_S) }

        /** The edge rows' fade in ([on]) or out, on Compose's default easing. */
        fun watchEdgeFade(on: Boolean): TweenSpec<Float> = tween(((if (on) WATCH_EDGE_FADE_IN_S else WATCH_EDGE_FADE_OUT_S) * 1000).toInt())
    }
}

/**
 * Applies a [Motion.Look] to a graphicsLayer: a blur (RenderEffect.createBlurEffect, clamped at the edges so the layer
 * keeps its full bleed) only while it is blurred, so a layer at rest has no effect and costs nothing extra.
 */
fun GraphicsLayerScope.look(l: Motion.Look, transformOrigin: androidx.compose.ui.graphics.TransformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center, blur: Boolean = true) {
    if (blur) blur(l.blur) else renderEffect = null
    alpha = l.alpha
    scaleX = l.scale; scaleY = l.scale
    this.transformOrigin = transformOrigin
}

/** A blur of [amount] (fraction of [Motion.BLUR]) across this layer, or none at all when it is sharp. */
fun GraphicsLayerScope.blur(amount: Float) {
    val px = amount * Motion.BLUR * size.width
    renderEffect = if (px >= 0.5f) android.graphics.RenderEffect.createBlurEffect(px, px, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect() else null
}
