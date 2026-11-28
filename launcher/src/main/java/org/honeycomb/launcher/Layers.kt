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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.HoneycombTheme

/*
 * Interruptible, velocity-preserving GPU-only layer transitions (WWDC18 803 Fluid Interfaces):
 *   - Face (0f) <-> Dynamic Grid (1f) <-> All Apps (2f): DepthController tracks live animated progress (zero
 *     teleport when catching mid-flight), applies soft rubber-band resistance at boundaries, and hands finger
 *     velocity straight into a gesture spring (or critically damped tap spring when triggered by a tap/button).
 *   - Smart Stack (Rise): RiseState follows the finger 1:1 with soft rubber-banding and velocity handoff.
 *   - Edge sheets (Notification Centre / Control Centre): SheetController captures live mid-flight position,
 *     rubber-bands past fully open, and inherits flick velocity on release.
 */

private const val MAX_LAYER_VELOCITY = 10f

/** One spring for settling edge sheets (follow the finger, then settle; interruptible). */
fun <T> settle() = spring<T>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)

/** Interruptible state for Face (0f) <-> Favourites (1f) <-> Apps (2f) with rubber-banding and velocity handoff. */
internal class DepthController(initial: Depth) {
    private val spring = Animatable(initial.ordinal.toFloat())
    private var finger by mutableStateOf<Float?>(null)
    var tracking by mutableStateOf(false)
        private set
    private var start = initial.ordinal.toFloat()
    private var velocity = 0f
    private var fromGesture = false

    val progress: Float get() = finger ?: spring.value

    fun drag(deltaProgress: Float) {
        if (!tracking) start = progress
        tracking = true
        fromGesture = true
        finger = Motion.rubberBandClamp(start + deltaProgress, 0f, 2f, stretch = 0.22f)
    }

    fun release(velocityProgressPerSec: Float) {
        velocity = velocityProgressPerSec.coerceIn(-MAX_LAYER_VELOCITY, MAX_LAYER_VELOCITY)
        tracking = false
    }

    fun cancel() {
        velocity = 0f
        tracking = false
    }

    suspend fun settle(target: Float, reduce: Boolean) {
        val released = finger
        if (released != null) spring.snapTo(released)
        finger = null
        val gestureRelease = released != null && fromGesture
        val initialV = if (gestureRelease) velocity else 0f
        velocity = 0f
        fromGesture = false
        if (spring.value == target && initialV == 0f) return
        if (reduce) {
            spring.snapTo(target)
        } else {
            val spec = if (gestureRelease) Motion.gesture.spec<Float>() else Motion.tap.spec<Float>()
            spring.animateTo(target, spec, initialVelocity = initialV)
        }
    }
}

/**
 * Interruptible 3-level transition across Face (0f), Dynamic Grid (1f), and All Apps (2f).
 * While dragging, [controller] tracks the finger 1:1 from its live position with soft boundary rubber-banding;
 * on release, the spring inherits the finger's exact velocity.
 */
@Composable
internal fun DepthLayers(
    depth: Depth,
    controller: DepthController,
    fullWidthApps: Boolean = false,
    face: @Composable () -> Unit,
    favourites: @Composable () -> Unit,
    apps: @Composable () -> Unit,
) {
    val reduce = HoneycombTheme.reduceMotion
    var lastDepth by remember { mutableStateOf(depth) }
    var directFaceApps by remember { mutableStateOf(false) }
    if (depth != lastDepth) {
        directFaceApps = (lastDepth == Depth.Face && depth == Depth.Apps) || (lastDepth == Depth.Apps && depth == Depth.Face)
        lastDepth = depth
    }
    LaunchedEffect(depth, controller.tracking) {
        if (controller.tracking) {
            directFaceApps = false
            return@LaunchedEffect
        }
        controller.settle(depth.ordinal.toFloat(), reduce)
        if (controller.progress == depth.ordinal.toFloat()) directFaceApps = false
    }
    Box(Modifier.fillMaxSize().background(HoneycombColors.background)) {
        val pRaw = controller.progress
        // Keep face(), favourites(), and apps() in a single stable composition slot so flipping directFaceApps
        // when the swipe-down Search spring settles never unmounts apps() or drops AppSearchBar focus/keyboard.
        val showFace = depth == Depth.Face || (if (directFaceApps) pRaw < 1.99f else pRaw < 0.995f)
        val showFavourites = !directFaceApps && (depth == Depth.Favourites || (pRaw > 0.005f && pRaw < 1.995f))
        val showApps = depth == Depth.Apps || (if (directFaceApps) pRaw > 0.01f else pRaw > 1.005f)

        if (showFace) {
            ScreenLayer({
                val t = if (directFaceApps) (pRaw / 2f).coerceIn(-0.25f, 1f) else pRaw.coerceIn(-0.25f, 1f)
                val s = 1f - (if (directFaceApps) 0.06f else 0.08f) * t
                scaleX = s
                scaleY = s
                alpha = (1f - t.coerceAtLeast(0f) * (if (directFaceApps) 1.6f else 1.4f)).coerceIn(0f, 1f)
            }) { face() }
        }
        if (showFavourites) {
            ScreenLayer({
                val p = pRaw.coerceIn(0f, 2f)
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
                val t = if (directFaceApps) (pRaw / 2f).coerceIn(0f, 1.25f) else (pRaw - 1f).coerceIn(0f, 1.25f)
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

// ---- Edge sheet (Notification Centre & Control Centre) ---------------------------------------------------------------

/** Interruptible state for a top-edge sheet (-1f closed, 0f open, soft rubber-band past 0f). */
internal class SheetController(initialHeightPx: Float = 1f) {
    private val spring = Animatable(-1f)
    private var finger by mutableStateOf<Float?>(null)
    var tracking by mutableStateOf(false)
        private set
    var heightPx = initialHeightPx.coerceAtLeast(1f)
    private var start = -1f
    private var velocity = 0f

    val offset: Float get() = finger ?: spring.value

    fun drag(deltaPx: Float) {
        if (!tracking) start = offset
        tracking = true
        val h = heightPx.coerceAtLeast(1f)
        finger = Motion.rubberBandClamp(start + deltaPx / h, -1f, 0f, stretch = 0.12f)
    }

    fun release(velocityPxPerSec: Float) {
        val h = heightPx.coerceAtLeast(1f)
        velocity = (velocityPxPerSec / h).coerceIn(-MAX_LAYER_VELOCITY, MAX_LAYER_VELOCITY)
        tracking = false
    }

    fun cancel() {
        velocity = 0f
        tracking = false
    }

    suspend fun settle(open: Boolean, reduce: Boolean) {
        val released = finger
        if (released != null) spring.snapTo(released)
        finger = null
        val target = if (open) 0f else -1f
        val initialV = if (released != null) velocity else 0f
        velocity = 0f
        if (spring.value == target && initialV == 0f) return
        if (reduce) {
            spring.snapTo(target)
        } else {
            val spec = if (released != null) Motion.gesture.spec<Float>() else Motion.tap.spec<Float>()
            spring.animateTo(target, spec, initialVelocity = initialV)
        }
    }
}

@Composable
internal fun EdgeSheet(
    open: Boolean,
    controller: SheetController,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduce = HoneycombTheme.reduceMotion
    var keep by remember { mutableStateOf(open) }
    LaunchedEffect(open, controller.tracking) {
        if (controller.tracking) {
            keep = true
            return@LaunchedEffect
        }
        if (open) keep = true
        controller.settle(open, reduce)
        keep = open
    }
    if (!keep && !open && !controller.tracking) return
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { controller.heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val y = controller.offset
                translationY = y * size.height
            },
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
    val progress get() = finger ?: spring.value

    fun drag(distance: Float) {
        if (!tracking) start = progress
        tracking = true
        finger = Motion.rubberBandClamp(start + distance / travel.coerceAtLeast(1f), 0f, 1f, stretch = 0.14f)
    }

    suspend fun settle(raised: Boolean) {
        val released = finger
        if (released != null) spring.snapTo(released)
        finger = null
        val target = if (raised) 1f else 0f
        val initialV = if (released != null) velocity else 0f
        velocity = 0f
        if (spring.value == target && initialV == 0f) return
        if (Motion.reduceMotion()) {
            spring.snapTo(target)
        } else {
            val spec = if (released != null) Motion.gesture.spec<Float>() else Motion.tap.spec<Float>()
            spring.animateTo(target, spec, initialVelocity = initialV)
        }
    }

    fun release(speed: Float) {
        velocity = (speed / travel.coerceAtLeast(1f)).coerceIn(-MAX_LAYER_VELOCITY, MAX_LAYER_VELOCITY)
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
            alpha = (1f - p.coerceAtLeast(0f) * 1.15f).coerceIn(0f, 1f)
            val sc = 1f - 0.06f * p
            scaleX = sc
            scaleY = sc
        }, content = under)
        if (state.tracking || keep || raised) ScreenLayer({
            val p = state.progress
            alpha = p.coerceIn(0f, 1f)
            translationY = size.height * 0.08f * (1f - p)
        }, content = over)
    }
}
