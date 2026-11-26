package org.honeycomb.launcher

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.honeycomb.launcher.design.CapsuleButton
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.Symbols
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Lean, 120fps Honeycomb app grid built on Jetpack Compose LazyColumn with AOSP Launcher3 + Calvin-LL/Reorderable
 * spring reorder physics:
 *   - Staggered 3-4-3-4 hexagonal rows (68 dp icons, 14 dp uniform gap horizontally and diagonally).
 *   - Unified container-level pointer tracking by STABLE APP KEY (never index), so swaps never cancel gestures or
 *     desync coordinates, and DragOwners.activeDrag prevents root Space navigation from hijacking icon drags.
 *   - FLIP (First-Last-Invert-Play) 2D spring reflow for every displaced icon across staggered 3/4 rows.
 *   - AOSP Launcher3-style swap dwell + core-hit shortcut + reverse-swap cooldown to eliminate boundary thrashing.
 *   - Smooth drop-settle spring (Animatable<Offset>) so released icons glide into their resting slot instead of
 *     snapping/vanishing, and one-shot hold-and-drag cleanly finishes on release without leaving jiggle stuck on.
 */

private const val ICON_D = 68f
private const val ICON_GAP = 14f
private const val ROW_GAP = 3f // 68 + 3 = 71 dp row pitch = 82 * sqrt(3)/2 -> uniform 14 dp diagonal gap
private const val HAPTIC_HOLD_MS = 280L
private const val SWAP_DWELL_MS = 70L
private const val SWAP_COOLDOWN_MS = 180L

internal val REORDER_OFFSET_SPRING = spring<Offset>(dampingRatio = 0.78f, stiffness = 420f)
internal val DROP_SETTLE_SPRING = spring<Offset>(dampingRatio = 0.80f, stiffness = 460f)
internal val PICKUP_SCALE_SPRING = spring<Float>(dampingRatio = 0.68f, stiffness = 480f)

/** Slices [count] indices into alternating staggered honeycomb rows of 3 and 4 icons. */
internal fun honeycombRows(count: Int): List<IntRange> {
    val rows = ArrayList<IntRange>((count / 3) + 1)
    var i = 0
    var rowIdx = 0
    while (i < count) {
        val take = if (rowIdx % 2 == 0) 3 else 4
        val end = minOf(i + take, count)
        rows.add(i until end)
        i = end
        rowIdx++
    }
    return rows
}

/** Organic iOS SpringBoard / watchOS sinusoidal wobble: rotation + micro X/Y drift with per-key random phase. */
internal data class JigglePose(val rotation: Float, val txDp: Float, val tyDp: Float)

@Composable
internal fun rememberOrganicJiggle(on: Boolean, keyHash: Int): JigglePose {
    if (!on) return JigglePose(0f, 0f, 0f)
    val t = rememberInfiniteTransition(label = "organic-jiggle")
    val h = abs(keyHash)
    val rotMs = 135 + (h % 5) * 8
    val shiftMs = 175 + ((h / 7) % 5) * 11
    val rotPhase = (h % rotMs)
    val shiftPhase = ((h / 3) % shiftMs)
    val dir = if (h % 2 == 0) 1f else -1f
    val rot by t.animateFloat(
        initialValue = -2.1f * dir,
        targetValue = 2.1f * dir,
        animationSpec = infiniteRepeatable(
            animation = tween(rotMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(rotPhase),
        ),
        label = "j-rot",
    )
    val tx by t.animateFloat(
        initialValue = -0.65f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(shiftMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(shiftPhase),
        ),
        label = "j-tx",
    )
    val ty by t.animateFloat(
        initialValue = 0.55f,
        targetValue = -0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(shiftMs + 24, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset((shiftPhase + 53) % (shiftMs + 24)),
        ),
        label = "j-ty",
    )
    return JigglePose(rot, tx, ty)
}

@Composable
fun WatchGrid(
    icons: List<ImageBitmap>,
    labels: List<String>,
    crownKeys: Flow<Int>,
    /** [from] is the tapped icon's circle in window px. */
    onTap: (index: Int, from: android.graphics.Rect?) -> Unit,
    modifier: Modifier = Modifier,
    /** Stable per-icon keys (e.g. flattened ComponentName) so FLIP spring animations & drags survive reorders. */
    keys: List<String> = emptyList(),
    /** The List View button at the grid's end. */
    onListView: () -> Unit = {},
    /** Top content padding (overridden when a persistent search bar sits above the grid). */
    topPadding: Dp = scrollTopInset + 14.dp,
    /** Long-press without drag opens the iOS-style Quick Actions menu ("Move to top", "Rearrange grid", ...). */
    onLongPress: ((index: Int) -> Unit)? = null,
    /** Live drag-and-drop reorder callback ([from] -> [to]). */
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    /** Whether the grid is currently in explicit multi-icon jiggle rearrangement mode. */
    editing: Boolean = false,
    onEditing: (Boolean) -> Unit = {},
    /** Optional scrollable header (e.g. search bar) that scrolls smoothly off with the grid. */
    header: (@Composable () -> Unit)? = null,
    /** Increment to scroll back to the header (e.g. swipe-down search trigger). */
    headerScrollKey: Int = 0,
    /** Optional replacement body below [header] (e.g. live search hits) so [header] never unmounts or loses IME focus. */
    overrideBody: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val stableKeys = remember(keys, labels, icons.size) {
        if (keys.size == icons.size) keys else List(icons.size) { i -> "${labels.getOrNull(i).orEmpty()}#$i" }
    }
    val listState = rememberLazyListState()
    val rows = remember(icons.size) { honeycombRows(icons.size) }
    val density = LocalDensity.current
    val px = density.density
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listScrolling by listState.interactionSource.collectIsDraggedAsState()

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentEditing by rememberUpdatedState(editing)
    val currentOnEditing by rememberUpdatedState(onEditing)
    val currentKeys by rememberUpdatedState(stableKeys)
    val currentIcons by rememberUpdatedState(icons)

    // Unified drag, hold, settle, and FLIP state — keyed by STABLE APP KEY
    var pressedKey by remember { mutableStateOf<String?>(null) }
    var heldKey by remember { mutableStateOf<String?>(null) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var draggedIcon by remember { mutableStateOf<ImageBitmap?>(null) }
    var dragCenterContainer by remember { mutableStateOf(Offset.Zero) }
    var dragGrabOffset by remember { mutableStateOf(Offset.Zero) }

    // Drop-settle overlay state (spring-animates released icon directly into its resting slot!)
    var settlingKey by remember { mutableStateOf<String?>(null) }
    var settlingIcon by remember { mutableStateOf<ImageBitmap?>(null) }
    val settleOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val settleScale = remember { Animatable(1f) }
    val pickupScale = remember { Animatable(1f) }

    // Container geometry & visible slot bounds in container-local px
    var containerOriginRoot by remember { mutableStateOf(Offset.Zero) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    val slotRectsContainer = remember { mutableStateMapOf<String, Rect>() }
    val slotRectsWindow = remember { mutableStateMapOf<String, android.graphics.Rect>() }

    // FLIP snapshot populated strictly at reorder moments (never during normal scroll)
    val pendingBeforeCenters = remember { HashMap<String, Offset>() }

    // AOSP Launcher3-style swap hysteresis (dwell + cooldown)
    var hoverCandidateKey by remember { mutableStateOf<String?>(null) }
    var hoverSinceMs by remember { mutableStateOf(0L) }
    var lastSwapMs by remember { mutableStateOf(0L) }
    var lastSwapPartnerKey by remember { mutableStateOf<String?>(null) }

    // Ensure root gesture lock is always cleared if this grid leaves composition
    DisposableEffect(Unit) {
        onDispose { DragOwners.activeDrag = false }
    }

    LaunchedEffect(listScrolling) {
        if (listScrolling) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(listState, crownKeys, px) {
        crownKeys.collect { step ->
            listState.animateScrollBy(-step * (ICON_D + ROW_GAP) * px)
        }
    }
    LaunchedEffect(headerScrollKey) {
        if (headerScrollKey > 0) listState.scrollToItem(0)
    }

    fun snapshotForFlip() {
        pendingBeforeCenters.clear()
        for ((k, r) in slotRectsContainer) {
            pendingBeforeCenters[k] = r.center
        }
    }

    fun performSwap(fromKey: String, targetKey: String) {
        val list = currentKeys
        val fromIdx = list.indexOf(fromKey)
        val toIdx = list.indexOf(targetKey)
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        snapshotForFlip()
        currentOnMove?.invoke(fromIdx, toIdx)
        lastSwapMs = SystemClock.uptimeMillis()
        lastSwapPartnerKey = targetKey
        hoverCandidateKey = null
        hoverSinceMs = 0L
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun hitTestKey(localPos: Offset): String? {
        val iconPx = ICON_D * px
        val hitRadius = iconPx * 0.54f
        var bestKey: String? = null
        var bestDist = Float.MAX_VALUE
        for (k in currentKeys) {
            val r = slotRectsContainer[k] ?: continue
            val d = hypot(r.center.x - localPos.x, r.center.y - localPos.y)
            if (d <= hitRadius && d < bestDist) {
                bestDist = d
                bestKey = k
            }
        }
        return bestKey
    }

    fun evaluateHoverSwap(centerContainer: Offset, forceImmediate: Boolean = false) {
        val active = draggedKey ?: return
        if (currentOnMove == null) return
        val iconPx = ICON_D * px
        val outerRadius = iconPx * 0.56f
        val coreRadius = iconPx * 0.34f
        var bestKey: String? = null
        var bestDist = Float.MAX_VALUE
        for (k in currentKeys) {
            if (k == active) continue
            val r = slotRectsContainer[k] ?: continue
            val d = hypot(r.center.x - centerContainer.x, r.center.y - centerContainer.y)
            if (d < outerRadius && d < bestDist) {
                bestDist = d
                bestKey = k
            }
        }
        if (bestKey == null) {
            hoverCandidateKey = null
            hoverSinceMs = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        // Reverse-swap cooldown prevents oscillation at the midpoint between two icons
        if (!forceImmediate && bestKey == lastSwapPartnerKey && (now - lastSwapMs) < SWAP_COOLDOWN_MS) {
            return
        }
        if (forceImmediate || bestDist <= coreRadius) {
            performSwap(active, bestKey)
            return
        }
        if (hoverCandidateKey != bestKey) {
            hoverCandidateKey = bestKey
            hoverSinceMs = now
        } else if (now - hoverSinceMs >= SWAP_DWELL_MS) {
            performSwap(active, bestKey)
        }
    }

    fun settleDraggedIcon(activeKey: String, iconBitmap: ImageBitmap?, releaseCenter: Offset) {
        // Check if finger released right on top of a neighbor before dwell timer expired
        evaluateHoverSwap(releaseCenter, forceImmediate = true)
        val iconPx = ICON_D * px
        val startTopLeft = releaseCenter - Offset(iconPx / 2f, iconPx / 2f)
        val targetCenter = slotRectsContainer[activeKey]?.center ?: releaseCenter
        val targetTopLeft = targetCenter - Offset(iconPx / 2f, iconPx / 2f)
        settlingKey = activeKey
        settlingIcon = iconBitmap
        draggedKey = null
        draggedIcon = null
        hoverCandidateKey = null
        hoverSinceMs = 0L
        lastSwapPartnerKey = null
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        scope.launch {
            settleOffset.snapTo(startTopLeft)
            settleScale.snapTo(pickupScale.value)
            val job1 = launch { settleOffset.animateTo(targetTopLeft, DROP_SETTLE_SPRING) }
            val job2 = launch { settleScale.animateTo(1f, PICKUP_SCALE_SPRING) }
            job1.join()
            job2.join()
            if (settlingKey == activeKey) {
                settlingKey = null
                settlingIcon = null
            }
        }
    }

    // Continuous smooth edge auto-scroll (Calvin-LL/Reorderable style proportional speed + live re-evaluation)
    LaunchedEffect(draggedKey) {
        if (draggedKey == null) return@LaunchedEffect
        val edgeZonePx = 92f * px
        val maxSpeedPx = 17f * px
        while (draggedKey != null) {
            val y = dragCenterContainer.y
            val h = containerHeightPx.toFloat()
            if (h > 0f) {
                val delta = when {
                    y < edgeZonePx -> -maxSpeedPx * ((edgeZonePx - y) / edgeZonePx).coerceIn(0.18f, 1f)
                    y > h - edgeZonePx -> maxSpeedPx * ((y - (h - edgeZonePx)) / edgeZonePx).coerceIn(0.18f, 1f)
                    else -> 0f
                }
                if (delta != 0f) {
                    val scrolled = listState.scrollBy(delta)
                    if (abs(scrolled) > 0.5f) {
                        evaluateHoverSwap(dragCenterContainer)
                    }
                }
            }
            delay(16L)
        }
    }

    val visualJiggle = editing || draggedKey != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerOriginRoot = coords.positionInRoot()
                containerHeightPx = coords.size.height
            }
            .then(
                if (overrideBody == null) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val hitKey = hitTestKey(down.position)
                            if (hitKey == null) {
                                // Empty space tap while in explicit jiggle mode exits jiggle cleanly
                                if (currentEditing && draggedKey == null) {
                                    val slop = viewConfiguration.touchSlop
                                    var moved = Offset.Zero
                                    var tapped = false
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Main)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (ch.isConsumed) break
                                        moved += ch.positionChange()
                                        if (hypot(moved.x, moved.y) > slop) break
                                        if (!ch.pressed) { tapped = true; break }
                                    }
                                    if (tapped && currentEditing && draggedKey == null) {
                                        currentOnEditing(false)
                                    }
                                }
                                return@awaitEachGesture
                            }

                            val startRect = slotRectsContainer[hitKey] ?: return@awaitEachGesture
                            val grabOffset = down.position - startRect.center
                            val wasScrolling = listState.isScrollInProgress
                            val slop = viewConfiguration.touchSlop

                            try {
                                if (currentEditing && currentOnMove != null) {
                                    // Explicit Jiggle Mode: instant zero-delay drag on any icon; tapping an icon never jumps to Search!
                                    pressedKey = hitKey
                                    var total = Offset.Zero
                                    var started = false
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) {
                                            if (started) {
                                                ch.consume()
                                                settleDraggedIcon(hitKey, draggedIcon, dragCenterContainer)
                                            }
                                            break
                                        }
                                        val delta = ch.positionChange()
                                        total += delta
                                        if (!started && hypot(total.x, total.y) > slop * 0.45f) {
                                            started = true
                                            DragOwners.activeDrag = true
                                            pressedKey = null
                                            val idx = currentKeys.indexOf(hitKey)
                                            draggedKey = hitKey
                                            draggedIcon = currentIcons.getOrNull(idx)
                                            dragGrabOffset = grabOffset
                                            dragCenterContainer = ch.position - grabOffset
                                            scope.launch {
                                                pickupScale.snapTo(1f)
                                                pickupScale.animateTo(1.18f, PICKUP_SCALE_SPRING)
                                            }
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            ch.consume()
                                        } else if (started) {
                                            ch.consume()
                                            dragCenterContainer = ch.position - grabOffset
                                            evaluateHoverSwap(dragCenterContainer)
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                // Normal Browsing Mode: 2-stage Haptic Touch
                                // Stage 1: Wait up to HAPTIC_HOLD_MS (280 ms) WITHOUT blocking LazyColumn scroll
                                pressedKey = hitKey
                                var cancelledByMove = false
                                var liftedEarly = false
                                val finishedBeforeHold = withTimeoutOrNull(HAPTIC_HOLD_MS) {
                                    var moved = Offset.Zero
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Main)
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (ch.isConsumed) {
                                            cancelledByMove = true
                                            break
                                        }
                                        moved += ch.positionChange()
                                        if (hypot(moved.x, moved.y) > slop) {
                                            cancelledByMove = true
                                            break
                                        }
                                        if (!ch.pressed) {
                                            liftedEarly = true
                                            ch.consume()
                                            break
                                        }
                                    }
                                    true
                                }

                                if (cancelledByMove) return@awaitEachGesture
                                if (liftedEarly && finishedBeforeHold != null) {
                                    pressedKey = null
                                    if (!wasScrolling) {
                                        val idx = currentKeys.indexOf(hitKey)
                                        if (idx >= 0) currentOnTap(idx, slotRectsWindow[hitKey])
                                    }
                                    return@awaitEachGesture
                                }

                                // Stage 2: Haptic Touch Pop! Lock out root navigation & LazyColumn scroll immediately.
                                DragOwners.activeDrag = true
                                pressedKey = null
                                heldKey = hitKey
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                                var dragAccum = Offset.Zero
                                var startedDrag = false
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) {
                                        ch.consume()
                                        if (startedDrag) {
                                            settleDraggedIcon(hitKey, draggedIcon, dragCenterContainer)
                                        } else {
                                            heldKey = null
                                            val idx = currentKeys.indexOf(hitKey)
                                            if (idx >= 0) currentOnLongPress?.invoke(idx)
                                        }
                                        break
                                    }
                                    val delta = ch.positionChange()
                                    ch.consume()
                                    dragAccum += delta
                                    if (!startedDrag && currentOnMove != null && hypot(dragAccum.x, dragAccum.y) > slop * 0.75f) {
                                        startedDrag = true
                                        heldKey = null
                                        val idx = currentKeys.indexOf(hitKey)
                                        draggedKey = hitKey
                                        draggedIcon = currentIcons.getOrNull(idx)
                                        val curRect = slotRectsContainer[hitKey] ?: startRect
                                        val curGrab = down.position - curRect.center
                                        dragGrabOffset = curGrab
                                        dragCenterContainer = ch.position - curGrab
                                        scope.launch {
                                            pickupScale.snapTo(1.12f)
                                            pickupScale.animateTo(1.18f, PICKUP_SCALE_SPRING)
                                        }
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    } else if (startedDrag) {
                                        dragCenterContainer = ch.position - dragGrabOffset
                                        evaluateHoverSwap(dragCenterContainer)
                                    }
                                }
                            } finally {
                                DragOwners.activeDrag = false
                                pressedKey = null
                                heldKey = null
                                if (draggedKey == hitKey) {
                                    draggedKey = null
                                    draggedIcon = null
                                }
                            }
                        }
                    }
                } else Modifier,
            ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            userScrollEnabled = draggedKey == null && heldKey == null,
            contentPadding = PaddingValues(top = topPadding, bottom = scrollBottomInset + if (editing) 84.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Keep header mounted during editing/dragging so the grid NEVER shifts vertically mid-drag!
            if (header != null) {
                item(key = "header") {
                    val headerAlpha by animateFloatAsState(
                        targetValue = if (visualJiggle) 0.25f else 1f,
                        animationSpec = Motion.snappy.spec(),
                        label = "header-alpha",
                    )
                    Box(Modifier.graphicsLayer { alpha = headerAlpha }) {
                        header()
                    }
                }
            }
            if (overrideBody != null) {
                overrideBody()
            } else {
                itemsIndexed(rows, key = { rowIndex, _ -> "row-$rowIndex" }) { _, range ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ICON_GAP.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (index in range) {
                            val appKey = stableKeys[index]
                            key(appKey) {
                                DisposableEffect(appKey) {
                                    onDispose {
                                        slotRectsContainer.remove(appKey)
                                        slotRectsWindow.remove(appKey)
                                    }
                                }
                                val isDragged = draggedKey == appKey
                                val isSettling = settlingKey == appKey
                                val isHeld = heldKey == appKey
                                val isPressed = pressedKey == appKey
                                val slideAnim = remember(appKey) { Animatable(Offset.Zero, Offset.VectorConverter) }
                                val scale by animateFloatAsState(
                                    targetValue = when {
                                        isHeld -> 1.12f
                                        isPressed -> 0.91f
                                        else -> 1f
                                    },
                                    animationSpec = PICKUP_SCALE_SPRING,
                                    label = "icon-scale",
                                )
                                val pose = rememberOrganicJiggle(visualJiggle && !isDragged && !isSettling, appKey.hashCode())
                                Box(
                                    Modifier
                                        .size(ICON_D.dp)
                                        .onGloballyPositioned { coords ->
                                            val rootRect = coords.boundsInRoot()
                                            val localRect = rootRect.translate(-containerOriginRoot)
                                            slotRectsContainer[appKey] = localRect
                                            val w = coords.boundsInWindow()
                                            slotRectsWindow[appKey] = android.graphics.Rect(
                                                w.left.roundToInt(),
                                                w.top.roundToInt(),
                                                w.right.roundToInt(),
                                                w.bottom.roundToInt(),
                                            )
                                            // FLIP: if a reorder just moved this icon from beforeCenter -> localRect.center,
                                            // invert its offset immediately and spring it smoothly to Offset.Zero!
                                            val beforeCenter = pendingBeforeCenters.remove(appKey)
                                            if (beforeCenter != null && appKey != draggedKey) {
                                                val delta = beforeCenter - localRect.center
                                                if (hypot(delta.x, delta.y) > 1.5f) {
                                                    scope.launch {
                                                        slideAnim.snapTo(slideAnim.value + delta)
                                                        slideAnim.animateTo(Offset.Zero, REORDER_OFFSET_SPRING)
                                                    }
                                                }
                                            }
                                        }
                                        .graphicsLayer {
                                            translationX = slideAnim.value.x + pose.txDp * px
                                            translationY = slideAnim.value.y + pose.tyDp * px
                                            scaleX = scale
                                            scaleY = scale
                                            rotationZ = pose.rotation
                                            alpha = if (isDragged || isSettling) 0f else 1f
                                        }
                                        .clip(CircleShape)
                                        .testTag("grid-$index")
                                        .semantics { contentDescription = labels[index] },
                                ) {
                                    Image(icons[index], null, Modifier.fillMaxSize())
                                    if (isPressed) Box(Modifier.fillMaxSize().background(HoneycombColors.pressed))
                                }
                            }
                        }
                    }
                }
                if (!visualJiggle) {
                    item(key = "list-view") {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            ViewSwitchButton(showingGrid = true, onSwitch = onListView)
                        }
                    }
                }
            }
        }

        // Unclipped floating overlay for actively dragged icon (1:1 finger tracking + pickup spring + shadow)
        val activeBitmap = draggedIcon
        if (draggedKey != null && activeBitmap != null) {
            val iconPx = ICON_D * px
            val topLeft = dragCenterContainer - Offset(iconPx / 2f, iconPx / 2f)
            val s = pickupScale.value
            Box(
                Modifier
                    .zIndex(100f)
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .size(ICON_D.dp)
                    .graphicsLayer {
                        scaleX = s
                        scaleY = s
                    }
                    .shadow(16.dp, CircleShape, clip = false)
                    .clip(CircleShape),
            ) {
                Image(activeBitmap, null, Modifier.fillMaxSize())
            }
        } else if (settlingKey != null && settlingIcon != null) {
            // Smooth spring settle back into the destination slot on release!
            val pos = settleOffset.value
            val s = settleScale.value
            Box(
                Modifier
                    .zIndex(99f)
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .size(ICON_D.dp)
                    .graphicsLayer {
                        scaleX = s
                        scaleY = s
                    }
                    .shadow((14f * ((s - 1f) / 0.18f).coerceIn(0f, 1f)).dp, CircleShape, clip = false)
                    .clip(CircleShape),
            ) {
                Image(settlingIcon!!, null, Modifier.fillMaxSize())
            }
        }

        // Floating "Done" pill only in explicit multi-icon jiggle mode
        if (editing && draggedKey == null) {
            CapsuleButton(
                text = "Done",
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    currentOnEditing(false)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = scrollBottomInset + 22.dp)
                    .testTag("grid-edit-done")
                    .semantics { contentDescription = "Done rearranging" },
                height = 40.dp,
                minWidth = 112.dp,
            )
        }
    }
}

/** The one button at the end of both all-apps views, compact and well-proportioned on phones. */
@Composable
fun ViewSwitchButton(showingGrid: Boolean, onSwitch: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (showingGrid) "List View" else "Grid View"
    CapsuleButton(
        label, onSwitch,
        modifier.testTag(if (showingGrid) "list-view" else "grid-view")
            .semantics { contentDescription = label },
        icon = if (showingGrid) Symbols.list else Symbols.apps,
        height = 40.dp,
        minWidth = 136.dp,
    )
}
