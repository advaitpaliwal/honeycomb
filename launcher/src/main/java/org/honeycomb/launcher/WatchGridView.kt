package org.honeycomb.launcher

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.honeycomb.launcher.design.CapsuleButton
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.Symbols
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Lean, silky Honeycomb app grid built on standard Jetpack Compose LazyColumn:
 *   - Staggered 3-4-3-4 hexagonal rows (68 dp icons, 14 dp uniform gap horizontally and diagonally).
 *   - Native 120 fps momentum scroll & stretch overscroll when browsing.
 *   - iOS Haptic Touch + watchOS Honeycomb jiggle-mode reordering:
 *       * Hold (~300 ms) -> crisp haptic pop; lift to open Quick Actions ("Move to top", "Rearrange grid", ...),
 *         OR keep moving finger immediately to grab and drag straight into live jiggle-mode rearrangement!
 *       * Once in jiggle/edit mode -> instant zero-delay drag on any icon with live hex-grid reflow,
 *         tactile detent ticks on every swap, and smooth top/bottom edge auto-scroll.
 */

private const val ICON_D = 68f
private const val ICON_GAP = 14f
private const val ROW_GAP = 3f // 68 + 3 = 71 dp row pitch = 82 * sqrt(3)/2 -> uniform 14 dp diagonal gap
private const val HAPTIC_HOLD_MS = 300L

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

@Composable
private fun gridWiggle(on: Boolean, seed: Int): Float {
    if (!on) return 0f
    val t = rememberInfiniteTransition(label = "grid-wiggle")
    val a by t.animateFloat(-1.8f, 1.8f, infiniteRepeatable(Motion.jiggle(seed), RepeatMode.Reverse), label = "a")
    return a
}

@Composable
fun WatchGrid(
    icons: List<ImageBitmap>,
    labels: List<String>,
    crownKeys: Flow<Int>,
    /** [from] is the tapped icon's circle in window px. */
    onTap: (index: Int, from: android.graphics.Rect?) -> Unit,
    modifier: Modifier = Modifier,
    /** The List View button at the grid's end. */
    onListView: () -> Unit = {},
    /** Top content padding (overridden when a persistent search bar sits above the grid). */
    topPadding: Dp = scrollTopInset + 14.dp,
    /** Long-press without drag opens the iOS-style Quick Actions menu ("Move to top", "Rearrange grid", ...). */
    onLongPress: ((index: Int) -> Unit)? = null,
    /** Live drag-and-drop reorder callback ([from] -> [to]). */
    onMove: ((from: Int, to: Int) -> Unit)? = null,
    /** Whether the grid is currently in watchOS/iOS jiggle rearrangement mode. */
    editing: Boolean = false,
    onEditing: (Boolean) -> Unit = {},
    /** Optional scrollable header (e.g. search bar) that scrolls smoothly off with the grid. */
    header: (@Composable () -> Unit)? = null,
    /** Increment to scroll back to the header (e.g. swipe-down search trigger). */
    headerScrollKey: Int = 0,
    /** Optional replacement body below [header] (e.g. live search hits) so [header] never unmounts or loses IME focus. */
    overrideBody: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val rows = remember(icons.size) { honeycombRows(icons.size) }
    val density = LocalDensity.current
    val px = density.density
    val view = LocalView.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listScrolling by listState.interactionSource.collectIsDraggedAsState()

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentEditing by rememberUpdatedState(editing)
    val currentOnEditing by rememberUpdatedState(onEditing)
    val currentIcons by rememberUpdatedState(icons)

    // Live drag-and-drop state
    var pressedIndex by remember { mutableIntStateOf(-1) }
    var heldIndex by remember { mutableIntStateOf(-1) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragFingerRoot by remember { mutableStateOf(Offset.Zero) }
    var dragGrabOffset by remember { mutableStateOf(Offset.Zero) }
    var containerOriginRoot by remember { mutableStateOf(Offset.Zero) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    val slotBoundsRoot = remember { mutableStateMapOf<Int, Rect>() }
    val slotBoundsWindow = remember { mutableStateMapOf<Int, android.graphics.Rect>() }

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

    // Smooth top/bottom edge auto-scroll while dragging an icon across long grids
    LaunchedEffect(draggedIndex) {
        if (draggedIndex < 0) return@LaunchedEffect
        val edgeZonePx = 88f * px
        val maxSpeedPx = 18f * px
        while (draggedIndex >= 0) {
            val localY = dragFingerRoot.y - containerOriginRoot.y
            val h = containerHeightPx.toFloat()
            if (h > 0f) {
                val delta = when {
                    localY < edgeZonePx -> -maxSpeedPx * ((edgeZonePx - localY) / edgeZonePx).coerceIn(0.15f, 1f)
                    localY > h - edgeZonePx -> maxSpeedPx * ((localY - (h - edgeZonePx)) / edgeZonePx).coerceIn(0.15f, 1f)
                    else -> 0f
                }
                if (delta != 0f) listState.scrollBy(delta)
            }
            delay(16L)
        }
    }

    fun checkAndSwap(fingerRoot: Offset) {
        val from = draggedIndex
        if (from < 0 || currentOnMove == null) return
        val iconRadius = (ICON_D * px) * 0.52f
        val target = slotBoundsRoot.entries
            .asSequence()
            .filter { it.key != from && it.key in currentIcons.indices }
            .map { it.key to hypot(it.value.center.x - fingerRoot.x, it.value.center.y - fingerRoot.y) }
            .minByOrNull { it.second }
        if (target != null && target.second < iconRadius) {
            val to = target.first
            currentOnMove?.invoke(from, to)
            draggedIndex = to
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                containerOriginRoot = coords.positionInRoot()
                containerHeightPx = coords.size.height
            }
            .then(
                if (editing && draggedIndex < 0) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { currentOnEditing(false) }
                    }
                } else Modifier,
            ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            userScrollEnabled = draggedIndex < 0,
            contentPadding = PaddingValues(top = topPadding, bottom = scrollBottomInset + if (editing) 84.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (header != null && !editing) {
                item(key = "header") { header() }
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
                            val isDragged = draggedIndex == index
                            val isHeld = heldIndex == index
                            val isPressed = pressedIndex == index
                            val scale by animateFloatAsState(
                                targetValue = when {
                                    isHeld -> 1.12f
                                    isPressed -> 0.90f
                                    else -> 1f
                                },
                                animationSpec = Motion.snappy.spec(),
                                label = "icon-scale",
                            )
                            val wiggleAngle = gridWiggle(editing && !isDragged, index)
                            Box(
                                Modifier
                                    .size(ICON_D.dp)
                                    .onGloballyPositioned { coords ->
                                        val rootRect = coords.boundsInRoot()
                                        slotBoundsRoot[index] = rootRect
                                        val w = coords.boundsInWindow()
                                        slotBoundsWindow[index] = android.graphics.Rect(
                                            w.left.roundToInt(),
                                            w.top.roundToInt(),
                                            w.right.roundToInt(),
                                            w.bottom.roundToInt(),
                                        )
                                    }
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        rotationZ = wiggleAngle
                                        alpha = if (isDragged) 0.22f else 1f
                                    }
                                    .clip(CircleShape)
                                    .pointerInput(index, currentOnMove != null) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val startRect = slotBoundsRoot[index] ?: Rect.Zero
                                            val downRoot = startRect.topLeft + down.position
                                            pressedIndex = index
                                            var cancelledByScroll = false
                                            var startedDrag = false

                                            if (currentEditing && currentOnMove != null) {
                                                // In Jiggle/Edit mode: zero-delay drag immediately!
                                                val slop = viewConfiguration.touchSlop
                                                var total = Offset.Zero
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!ch.pressed) {
                                                        // Quick tap on an icon in edit mode opens its Quick Actions menu
                                                        if (!startedDrag) {
                                                            ch.consume()
                                                            currentOnLongPress?.invoke(index)
                                                        }
                                                        break
                                                    }
                                                    val delta = ch.positionChange()
                                                    total += delta
                                                    if (!startedDrag && hypot(total.x, total.y) > slop * 0.5f) {
                                                        startedDrag = true
                                                        pressedIndex = -1
                                                        draggedIndex = index
                                                        dragGrabOffset = down.position - Offset(startRect.width / 2f, startRect.height / 2f)
                                                        dragFingerRoot = downRoot + total
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        ch.consume()
                                                    } else if (startedDrag) {
                                                        ch.consume()
                                                        dragFingerRoot += delta
                                                        checkAndSwap(dragFingerRoot)
                                                    }
                                                }
                                                if (startedDrag) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                }
                                                draggedIndex = -1
                                                pressedIndex = -1
                                                heldIndex = -1
                                                return@awaitEachGesture
                                            }

                                            // Normal browsing mode: iOS Haptic Touch 2-stage gesture
                                            // Stage 1: Wait up to HAPTIC_HOLD_MS (300 ms). Tap launches; scroll yields to LazyColumn.
                                            var liftedEarly = false
                                            val endedBeforeHold = withTimeoutOrNull(HAPTIC_HOLD_MS) {
                                                val slop = viewConfiguration.touchSlop
                                                var moved = Offset.Zero
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (ch.isConsumed) {
                                                        cancelledByScroll = true
                                                        break
                                                    }
                                                    moved += ch.positionChange()
                                                    if (hypot(moved.x, moved.y) > slop) {
                                                        cancelledByScroll = true
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

                                            if (cancelledByScroll) {
                                                pressedIndex = -1
                                                return@awaitEachGesture
                                            }
                                            if (liftedEarly && endedBeforeHold != null) {
                                                pressedIndex = -1
                                                currentOnTap(index, slotBoundsWindow[index])
                                                return@awaitEachGesture
                                            }

                                            // Stage 2: Haptic Touch Pop! Finger is still down past 300 ms.
                                            pressedIndex = -1
                                            heldIndex = index
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                                            val slop = viewConfiguration.touchSlop
                                            var dragAccum = Offset.Zero
                                            while (true) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                if (!ch.pressed) {
                                                    ch.consume()
                                                    break
                                                }
                                                val delta = ch.positionChange()
                                                ch.consume()
                                                dragAccum += delta
                                                if (!startedDrag && currentOnMove != null && hypot(dragAccum.x, dragAccum.y) > slop) {
                                                    // Seamless transition into live Jiggle-mode drag!
                                                    startedDrag = true
                                                    heldIndex = -1
                                                    currentOnEditing(true)
                                                    draggedIndex = index
                                                    val curRect = slotBoundsRoot[index] ?: startRect
                                                    dragGrabOffset = down.position - Offset(curRect.width / 2f, curRect.height / 2f)
                                                    dragFingerRoot = curRect.topLeft + down.position + dragAccum
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                } else if (startedDrag) {
                                                    dragFingerRoot += delta
                                                    checkAndSwap(dragFingerRoot)
                                                }
                                            }

                                            if (startedDrag) {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                draggedIndex = -1
                                            } else {
                                                // Held & lifted without dragging -> open iOS Quick Actions Menu!
                                                heldIndex = -1
                                                currentOnLongPress?.invoke(index)
                                            }
                                            heldIndex = -1
                                            pressedIndex = -1
                                        }
                                    }
                                    .testTag("grid-$index")
                                    .semantics { contentDescription = labels[index] },
                            ) {
                                Image(icons[index], null, Modifier.fillMaxSize())
                                if (isPressed) Box(Modifier.fillMaxSize().background(HoneycombColors.pressed))
                            }
                        }
                    }
                }
                if (!editing) {
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

        // Floating dragged icon overlay (unclipped across staggered 3/4 rows!)
        val activeDrag = draggedIndex
        if (activeDrag in icons.indices) {
            val iconPx = ICON_D * px
            val topLeft = dragFingerRoot - containerOriginRoot - dragGrabOffset - Offset(iconPx / 2f, iconPx / 2f)
            Box(
                Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .size(ICON_D.dp)
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                    }
                    .shadow(14.dp, CircleShape, clip = false)
                    .clip(CircleShape),
            ) {
                Image(icons[activeDrag], null, Modifier.fillMaxSize())
            }
        }

        // Floating "Done" pill while rearranging in jiggle mode
        if (editing && draggedIndex < 0) {
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
