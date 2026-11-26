package org.honeycomb.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import org.honeycomb.launcher.design.HoneycombColors

/*
 * Lean, interruptible GPU-only layer transitions:
 *   - Face (0f) <-> Dynamic Grid (1f) <-> All Apps (2f): single spring Animatable, subtle scale + fade, zero blur flicker.
 *   - Smart Stack (Rise): single spring Animatable following the finger with velocity handoff.
 *   - Edge sheets: single spring Animatable sliding from the top edge.
 */

/** One spring for settling edge sheets (follow the finger, then settle; interruptible). */
fun <T> settle() = spring<T>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

/**
 * Simple, smooth, interruptible 3-level transition across Face (0f), Dynamic Grid (1f), and All Apps (2f).
 * While [seek] returns a non-null float in 0f..2f, the layers track the finger 1:1; on release or tap,
 * a single spring smoothly settles to [depth].ordinal with pure GPU scale + alpha (no blur flicker, no delay).
 */
@Composable
fun DepthLayers(
    depth: Depth,
    fullWidthApps: Boolean = false,
    seek: () -> Float? = { null },
    face: @Composable () -> Unit,
    favourites: @Composable () -> Unit,
    apps: @Composable () -> Unit,
) {
    val progress = remember { Animatable(depth.ordinal.toFloat()) }
    val currentDepth = rememberUpdatedState(depth)
    var lastDepth by remember { mutableStateOf(depth) }
    var directFaceApps by remember { mutableStateOf(false) }
    if (depth != lastDepth) {
        directFaceApps = (lastDepth == Depth.Face && depth == Depth.Apps) || (lastDepth == Depth.Apps && depth == Depth.Face)
        lastDepth = depth
    }
    LaunchedEffect(Unit) {
        snapshotFlow { seek() to currentDepth.value }.collectLatest { (drag, d) ->
            if (drag != null) {
                directFaceApps = false
                progress.snapTo(drag.coerceIn(0f, 2f))
                return@collectLatest
            }
            val target = d.ordinal.toFloat()
            if (progress.value != target) {
                if (Motion.reduceMotion()) progress.snapTo(target)
                else progress.animateTo(target, Motion.snappy.spec())
            }
            if (progress.value == target) directFaceApps = false
        }
    }
    Box(Modifier.fillMaxSize().background(HoneycombColors.background)) {
        // Keep face(), favourites(), and apps() in a single stable composition slot so flipping directFaceApps
        // when the swipe-down Search spring settles never unmounts apps() or drops AppSearchBar focus/keyboard.
        val showFace = depth == Depth.Face || (if (directFaceApps) progress.value < 1.99f else progress.value < 0.995f)
        val showFavourites = !directFaceApps && (depth == Depth.Favourites || (progress.value > 0.005f && progress.value < 1.995f))
        val showApps = depth == Depth.Apps || (if (directFaceApps) progress.value > 0.01f else progress.value > 1.005f)

        if (showFace) {
            ScreenLayer({
                val t = if (directFaceApps) (progress.value / 2f).coerceIn(0f, 1f) else progress.value.coerceIn(0f, 1f)
                val s = 1f - (if (directFaceApps) 0.06f else 0.08f) * t
                scaleX = s
                scaleY = s
                alpha = (1f - t * (if (directFaceApps) 1.6f else 1.4f)).coerceIn(0f, 1f)
            }) { face() }
        }
        if (showFavourites) {
            ScreenLayer({
                val p = progress.value.coerceIn(0f, 2f)
                if (p <= 1f) {
                    val s = 1.08f - 0.08f * p
                    scaleX = s
                    scaleY = s
                    alpha = ((p - 0.1f) / 0.9f).coerceIn(0f, 1f)
                } else {
                    val t = p - 1f
                    val s = 1f - 0.08f * t
                    scaleX = s
                    scaleY = s
                    alpha = (1f - t * 1.4f).coerceIn(0f, 1f)
                }
            }) { favourites() }
        }
        if (showApps) {
            ScreenLayer({
                val t = if (directFaceApps) (progress.value / 2f).coerceIn(0f, 1f) else (progress.value - 1f).coerceIn(0f, 1f)
                val s = if (directFaceApps) 1.05f - 0.05f * t else 1.08f - 0.08f * t
                scaleX = s
                scaleY = s
                alpha = if (directFaceApps) ((t - 0.15f) / 0.85f).coerceIn(0f, 1f) else ((t - 0.1f) / 0.9f).coerceIn(0f, 1f)
            }, fullWidth = fullWidthApps) { apps() }
        }
    }
}

/** A layer as large as the whole window, holding [content] in the frame at its centre. */
@Composable
fun ScreenLayer(
    layer: GraphicsLayerScope.() -> Unit,
    surface: Modifier = Modifier,
    fullWidth: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val screen = LocalScreen.current
    Box(Modifier.fillMaxSize().wrapContentSize(unbounded = true)) {
        Box(Modifier.requiredSize(screen.width.dp, screen.height.dp).graphicsLayer(layer).then(surface)) {
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(if (fullWidth) screen.width.dp else FRAME_W.dp, screen.height.dp),
                content = content,
            )
        }
    }
}

// ---- Edge sheet ------------------------------------------------------------------------------------------------------

@Composable
fun EdgeSheet(open: Boolean, pull: Float?, content: @Composable BoxScope.() -> Unit) {
    var h by remember { mutableStateOf(0f) }
    val y = remember { Animatable(-1f) } // 0 open, -1 closed (in screen heights)
    var keep by remember { mutableStateOf(open) }
    LaunchedEffect(open, pull) {
        if (pull != null && h > 0f) {
            if (open) y.snapTo((pull / h).coerceIn(-1f, 0f))
            else { keep = true; y.snapTo((-1f + pull / h).coerceIn(-1f, 0f)) }
            return@LaunchedEffect
        }
        if (open) { keep = true; y.animateTo(0f, settle()) } else { y.animateTo(-1f, settle()); keep = false }
    }
    if (!keep && !open) return
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { h = it.height.toFloat() }
            .graphicsLayer { translationY = y.value * size.height },
        content = content,
    )
}

// ---- Rise: the vertical layer over the face --------------------------------------------------------------------------

/** Drag distance is read by the drawing layers, never by the launcher composition. */
internal class RiseState {
    private val spring = Animatable(0f)
    private var finger by mutableStateOf<Float?>(null)
    var tracking by mutableStateOf(false)
        private set
    private var velocity = 0f
    var travel = 1f
    private var start = 0f
    val progress get() = finger ?: spring.value.coerceIn(0f, 1f)

    fun drag(distance: Float) {
        if (!tracking) start = progress
        tracking = true
        finger = (start + distance / travel).coerceIn(0f, 1f)
    }

    suspend fun settle(raised: Boolean) {
        val released = finger
        if (released != null) spring.snapTo(released)
        finger = null
        val target = if (raised) 1f else 0f
        if (spring.value == target) return
        if (Motion.reduceMotion()) spring.snapTo(target)
        else spring.animateTo(target, Motion.snappy.spec(), initialVelocity = if (released != null) velocity else 0f)
    }

    fun release(speed: Float) {
        velocity = speed / travel
        tracking = false
    }
}

/** The face and Smart Stack share the finger's progress; at release the spring inherits its velocity. */
@Composable
internal fun Rise(
    raised: Boolean,
    state: RiseState,
    under: @Composable BoxScope.() -> Unit,
    over: @Composable BoxScope.() -> Unit,
) {
    var keep by remember { mutableStateOf(raised) }
    LaunchedEffect(raised, state.tracking) {
        if (state.tracking) return@LaunchedEffect
        keep = raised || state.progress > 0f
        state.settle(raised)
        keep = raised
    }
    Box(
        Modifier.fillMaxSize()
            .background(HoneycombColors.faceGround)
            .onSizeChanged { state.travel = it.height * Motion.STACK_TRAVEL },
    ) {
        ScreenLayer({
            val p = state.progress
            alpha = (1f - p * 1.15f).coerceIn(0f, 1f)
            val sc = 1f - 0.06f * p
            scaleX = sc
            scaleY = sc
        }, content = under)
        if (state.tracking || keep || raised) ScreenLayer({
            val p = state.progress
            alpha = p
            translationY = size.height * 0.08f * (1f - p)
        }, content = over)
    }
}
