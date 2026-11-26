package org.honeycomb.launcher

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/*
 * The honeycomb grid. Adapted from rjfahad44/HoneyCombListView (Apache-2.0, HoneycombList.kt): float pan states written
 * straight from the gesture, a VelocityTracker per gesture, a spline decay per axis, transforms read only in
 * graphicsLayer so dragging never recomposes. Its stepped grid, linear fisheye and hard clamp are replaced by the
 * hex lattice, the fitted lens, rubber-band edges with the measured return, pinch and key zoom, and the open animation
 * (HoneycombMath.kt). The two-finger gesture loop follows Compose's own detectTransformGestures.
 */

/** Pan is in lattice pitch units, so it does not depend on the screen size or the zoom. */
@Stable
class HoneycombState(val slots: FloatArray, private val scope: CoroutineScope, val equalSizes: Boolean) {
    val count = slots.size / 2
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)

    /** The icon being opened, or -1, and the open animation's progress 0..1. */
    var opening by mutableIntStateOf(-1)
    var openProgress by mutableFloatStateOf(0f)
    private var openFrom = FloatArray(3) // centre x, y and diameter in px when the tap landed

    // Pan bounds: any icon can be brought to the centre of the screen.
    private val minX: Float
    private val maxX: Float
    private val minY: Float
    private val maxY: Float

    init {
        var a = 0f; var b = 0f; var c = 0f; var d = 0f
        for (i in 0 until count) {
            a = min(a, -slots[2 * i]); b = maxOf(b, -slots[2 * i])
            c = min(c, -slots[2 * i + 1]); d = maxOf(d, -slots[2 * i + 1])
        }
        minX = a; maxX = b; minY = c; maxY = d
    }

    // Set from layout, constant while the screen is shown.
    var width = 1f
    var height = 1f
    var d0 = 1f
    val pitch get() = d0 / Honeycomb.D0_PER_PITCH

    /** Pixels per pitch unit now. */
    private val unit get() = pitch * zoom

    /** Rubber-band reach, in pitch units: half the screen, as UIScrollView uses the view's size. */
    private val reachX get() = width / 2f / unit
    private val reachY get() = height / 2f / unit

    private var motion: Job? = null
    private var zoomJob: Job? = null
    private var rawX = 0f
    private var rawY = 0f
    val moving get() = motion?.isActive == true || zoomJob?.isActive == true

    fun stop() {
        motion?.cancel(); motion = null
        zoomJob?.cancel(); zoomJob = null
        rawX = unband(panX, minX, maxX, reachX)
        rawY = unband(panY, minY, maxY, reachY)
    }

    fun dragBy(dx: Float, dy: Float) {
        rawX += dx / unit
        rawY += dy / unit
        panX = band(rawX, minX, maxX, reachX)
        panY = band(rawY, minY, maxY, reachY)
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(Honeycomb.ZOOM_MIN, Honeycomb.ZOOM_MAX)
    }

    fun zoomKey(direction: Int) {
        zoomJob?.cancel()
        val target = zoomStep(zoom, direction)
        zoomJob = scope.launch {
            Animatable(zoom).animateTo(target, Motion.Grid.honeycombEdgeSpring()) { zoom = value }
        }
    }

    /** Finger lifted with velocity [v] in px/s: fling inside the bounds, or spring back from past them. */
    fun release(v: Velocity, decay: DecayAnimationSpec<Float>) {
        val u = unit
        val cap = maxBounceSpeed(d0 * zoom)
        motion = scope.launch {
            coroutineScope {
                launch { axis(panX, v.x, u, minX, maxX, cap, decay) { panX = it } }
                launch { axis(panY, v.y, u, minY, maxY, cap, decay) { panY = it } }
            }
        }
    }

    private suspend fun axis(
        start: Float, velocity: Float, u: Float, lo: Float, hi: Float, cap: Float,
        decay: DecayAnimationSpec<Float>, set: (Float) -> Unit,
    ) {
        val a = Animatable(start * u)
        if (start < lo || start > hi) {
            // Released past the edge: the measured exponential return, e(t) = e0 exp(-t / 0.1505).
            a.animateTo(start.coerceIn(lo, hi) * u, Motion.Grid.honeycombEdgeReturn()) {
                set(value / u)
            }
            return
        }
        a.updateBounds(lo * u, hi * u)
        val result = a.animateDecay(velocity, decay) { set(value / u) }
        if (result.endReason == AnimationEndReason.BoundReached) {
            // Hit the edge moving: overshoot and come back on the same time constant (critically damped spring).
            val edge = a.value
            a.updateBounds(null, null)
            a.animateTo(edge, Motion.Grid.honeycombEdgeSpring(), result.endState.velocity.coerceIn(-cap, cap)) { set(value / u) }
        }
    }

    /** Settles if a touch ended without a drag while past an edge. */
    fun settle(decay: DecayAnimationSpec<Float>) {
        if (panX < minX || panX > maxX || panY < minY || panY > maxY) release(Velocity.Zero, decay)
    }

    /** Places icon [i]: its centre on screen, scale from the lens, and the open animation on top. */
    fun place(i: Int, layer: GraphicsLayerScope) {
        val u = unit
        var x = width / 2f + (slots[2 * i] + panX) * u
        var y = height / 2f + (slots[2 * i + 1] + panY) * u
        var size = zoom * lensScale(
            (x - width / 2f) / d0, (y - height / 2f) / d0,
            min(min(x, width - x), min(y, height - y)) / d0, equalSizes,
        )
        var alpha = 1f
        val o = opening
        if (o >= 0) {
            val p = openProgress
            if (i == o) {
                // The tapped icon grows to cover the screen and moves to its centre.
                x = mix(openFrom[0], width / 2f, p)
                y = mix(openFrom[1], height / 2f, p)
                size = mix(openFrom[2], hypot(width, height), p) / d0
            } else {
                // Neighbours enlarge and leave: everything spreads out from the tapped icon and fades.
                val grow = 1f + 2f * p
                x = mix(openFrom[0], width / 2f, p) + (x - openFrom[0]) * grow
                y = mix(openFrom[1], height / 2f, p) + (y - openFrom[1]) * grow
                size *= grow
                alpha = 1f - p
            }
        }
        layer.translationX = x - d0 / 2f
        layer.translationY = y - d0 / 2f
        layer.scaleX = size
        layer.scaleY = size
        layer.alpha = if (size <= 0f) 0f else alpha // a zero-alpha layer is not drawn: this is the culling
    }

    /** The open animation for icon [i], then [then]; with Reduce Motion it opens at once. */
    fun open(i: Int, then: () -> Unit) {
        if (equalSizes || opening >= 0) { if (opening < 0) then(); return }
        stop()
        val u = unit
        val x = width / 2f + (slots[2 * i] + panX) * u
        val y = height / 2f + (slots[2 * i + 1] + panY) * u
        openFrom[0] = x; openFrom[1] = y
        openFrom[2] = d0 * zoom * lensScale((x - width / 2f) / d0, (y - height / 2f) / d0, min(min(x, width - x), min(y, height - y)) / d0)
        opening = i
        scope.launch {
            val p = Animatable(0f)
            p.animateTo(1f, Motion.Grid.honeycombOpen()) { openProgress = value }
            then()
            delay(500) // the app is in front by now; put the grid back for when it returns
            opening = -1
            openProgress = 0f
        }
    }
}

/** Two-axis drag with fling, pinch zoom, and a touch that stops motion without tapping through. */
private suspend fun PointerInputScope.honeycombGestures(state: HoneycombState, decay: DecayAnimationSpec<Float>) =
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (state.opening >= 0) { down.consume(); return@awaitEachGesture } // an app is opening
        if (state.moving) down.consume() // a touch that stops a fling does not open the icon under it
        state.stop()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var pointers = 1
        var pastSlop = false
        var pan = Offset.Zero
        var zoom = 1f
        do {
            val event = awaitPointerEvent()
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (!pastSlop) {
                zoom *= zoomChange
                pan += panChange
                val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                pastSlop = zoomMotion > viewConfiguration.touchSlop || pan.getDistance() > viewConfiguration.touchSlop
            }
            if (pastSlop) {
                state.dragBy(panChange.x, panChange.y)
                if (zoomChange != 1f) state.zoomBy(zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            val pressed = event.changes.count { it.pressed }
            if (pressed != pointers) { tracker.resetTracking(); pointers = pressed }
            val c = event.calculateCentroid(useCurrent = true)
            if (pressed > 0 && c.isSpecified) tracker.addPosition(event.changes[0].uptimeMillis, c)
        } while (event.changes.any { it.pressed })
        if (pastSlop) state.release(tracker.calculateVelocity(), decay) else state.settle(decay)
    }

/** Reduce Motion on Android is Settings > Accessibility > Remove animations, which turns animators off. */
fun reduceMotion(): Boolean = Motion.reduceMotion()

/**
 * Icons on the honeycomb, item 0 at the centre. [zoomKeys] carries +1 / -1 per volume key press.
 */
@Composable
fun HoneycombGrid(
    icons: List<ImageBitmap>,
    labels: List<String>,
    zoomKeys: Flow<Int>,
    onTap: (index: Int, state: HoneycombState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val equal = remember { reduceMotion() }
    val state = remember(icons.size) { HoneycombState(honeycombSlots(icons.size), scope, equal) }
    LaunchedEffect(state) { zoomKeys.collect { state.zoomKey(it) } }
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().pointerInput(state) { honeycombGestures(state, decay) }) {
        val density = LocalDensity.current
        state.width = constraints.maxWidth.toFloat()
        state.height = constraints.maxHeight.toFloat()
        state.d0 = state.width * Honeycomb.D0_PER_WIDTH
        val d0 = with(density) { state.d0.toDp() }
        for (i in icons.indices) key(i) {
            Box(
                Modifier
                    .zIndex(if (state.opening == i) 1f else 0f)
                    .size(d0)
                    .graphicsLayer { state.place(i, this) }
                    .clip(CircleShape)
                    .clickable { onTap(i, state) }
                    .semantics { contentDescription = labels[i] },
            ) { Image(icons[i], null, Modifier.fillMaxSize()) }
        }
    }
}
