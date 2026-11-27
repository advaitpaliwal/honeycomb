package org.honeycomb.launcher

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.Flow
import org.honeycomb.launcher.design.CapsuleButton
import org.honeycomb.launcher.design.CircleButton
import org.honeycomb.launcher.design.Symbols
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * True Apple Watch (watchOS 10+) staggered 3-4-3-4 hexagonal Honeycomb grid built on Compose's native
 * LazyVerticalGrid(columns = GridCells.Fixed(8)) + Calvin-LL/Reorderable 3.1.0:
 *   - Even rows (3 icons): [1-col spacer] [2-col app] [2-col app] [2-col app] [1-col spacer] = 8 spans.
 *   - Odd rows  (4 icons): [2-col app] [2-col app] [2-col app] [2-col app] = 8 spans.
 *   - Every partial last row is centred with symmetric spacers so every row sums to 8 spans.
 *   - Every app icon is a top-level ReorderableItem keyed by stable appKey (never inside nested Row composables),
 *     so Compose's native animateItem() glides icons diagonally across 3- and 4-icon rows at 120fps with exact
 *     1:1 Reorderable hitboxes, built-in edge auto-scroll, and spring drop-settle.
 */

private const val HONEYCOMB_GRID_SPANS = 8
private const val ICON_D = 68f
private const val ROW_GAP = 5f // 68 + 5 = 73 dp vertical pitch ≈ 84 * sqrt(3)/2 -> uniform ~16 dp horizontal & diagonal gap

internal val REORDER_OFFSET_SPRING = spring<Offset>(dampingRatio = 0.78f, stiffness = 420f)
internal val DROP_SETTLE_SPRING = spring<Offset>(dampingRatio = 0.80f, stiffness = 460f)
internal val PICKUP_SCALE_SPRING = spring<Float>(dampingRatio = 0.70f, stiffness = 480f)

internal sealed interface HoneycombCell {
    val gridKey: String
    val span: Int

    data class Spacer(override val gridKey: String, override val span: Int) : HoneycombCell
    data class App(val appIndex: Int, val appKey: String) : HoneycombCell {
        override val gridKey: String get() = appKey
        override val span: Int get() = 2
    }
}

/**
 * Packs [keys] into Apple Watch's alternating 3-4-3-4 staggered honeycomb rows on an 8-column grid.
 * Even rows hold up to 3 icons (shifted half a column via 1-span side spacers); odd rows hold up to 4 icons.
 * Every row sums to exactly 8 spans, and partial trailing rows stay symmetrically centred.
 */
internal fun honeycombCells(keys: List<String>): List<HoneycombCell> {
    if (keys.isEmpty()) return emptyList()
    val out = ArrayList<HoneycombCell>(keys.size + (keys.size / 3) + 2)
    var i = 0
    var row = 0
    while (i < keys.size) {
        val capacity = if (row % 2 == 0) 3 else 4
        val count = minOf(capacity, keys.size - i)
        val freeSpans = HONEYCOMB_GRID_SPANS - count * 2
        val leftPad = freeSpans / 2
        val rightPad = freeSpans - leftPad
        if (leftPad > 0) out.add(HoneycombCell.Spacer("hc-pad-l-$row", leftPad))
        for (c in 0 until count) {
            val idx = i + c
            out.add(HoneycombCell.App(idx, keys[idx]))
        }
        if (rightPad > 0) out.add(HoneycombCell.Spacer("hc-pad-r-$row", rightPad))
        i += count
        row++
    }
    return out
}

/** Clean, subtle iOS/watchOS-style rotation-only tilt for explicit edit mode (no erratic X/Y buzzing). */
@Composable
internal fun rememberGentleJiggle(on: Boolean, seed: Int): Float {
    if (!on) return 0f
    val t = rememberInfiniteTransition(label = "gentle-jiggle")
    val dir = if (abs(seed) % 2 == 0) 1f else -1f
    val angle by t.animateFloat(
        initialValue = -1.75f * dir,
        targetValue = 1.75f * dir,
        animationSpec = infiniteRepeatable(
            animation = Motion.jiggle(seed),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "jiggle-rot",
    )
    return angle
}

/**
 * Native Compose NestedScrollConnection for true overscroll-past-top to focus Search (never fires during a normal
 * scroll back up from further down in the list), while dismissing the keyboard when scrolling down into apps.
 */
@Composable
internal fun rememberPullToSearchNestedScroll(
    enabled: () -> Boolean,
    isAtTop: () -> Boolean,
    isDragging: Boolean,
    onPullToSearch: () -> Unit,
    onDismissKeyboard: () -> Unit,
): NestedScrollConnection {
    val px = LocalDensity.current.density
    val view = LocalView.current
    val curEnabled by rememberUpdatedState(enabled)
    val curAtTop by rememberUpdatedState(isAtTop)
    val curTrigger by rememberUpdatedState(onPullToSearch)
    val curDismiss by rememberUpdatedState(onDismissKeyboard)
    val state = remember(px, view) {
        object : NestedScrollConnection {
            var pullDownPx = 0f
            var startedAtTop = false
            var scrolledContentInGesture = false
            var fired = false
            private val thresholdPx = 72f * px

            fun onDragStart(atTopNow: Boolean) {
                pullDownPx = 0f
                startedAtTop = atTopNow
                scrolledContentInGesture = !atTopNow
                fired = false
            }

            fun reset() {
                pullDownPx = 0f
                startedAtTop = false
                scrolledContentInGesture = false
                fired = false
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (consumed.y < -0.5f) {
                    scrolledContentInGesture = true
                    pullDownPx = 0f
                    curDismiss()
                } else if (consumed.y > 0.5f) {
                    // Regular scroll up toward the top — never hijack a regular scroll into Search.
                    scrolledContentInGesture = true
                    pullDownPx = 0f
                } else if (startedAtTop && !scrolledContentInGesture && curEnabled() && curAtTop() && available.y > 0f) {
                    // Pure overscroll started while already resting at the top with Search visible.
                    pullDownPx += available.y
                    if (!fired && pullDownPx >= thresholdPx) {
                        fired = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        curTrigger()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                reset()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                reset()
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(isDragging) {
        if (isDragging) state.onDragStart(curAtTop()) else state.reset()
    }
    return state
}

@Composable
fun WatchGrid(
    icons: List<ImageBitmap>,
    labels: List<String>,
    crownKeys: Flow<Int>,
    /** [from] is the tapped icon's circle in window px. */
    onTap: (index: Int, from: android.graphics.Rect?) -> Unit,
    modifier: Modifier = Modifier,
    /** Stable per-icon keys (e.g. flattened ComponentName) so LazyVerticalGrid animateItem() & drags survive reorders. */
    keys: List<String> = emptyList(),
    /** The List View button at the grid's end. */
    onListView: () -> Unit = {},
    /** Top content padding (overridden when a persistent search bar sits above the grid). */
    topPadding: Dp = scrollTopInset + 14.dp,
    /** Long-press without dragging opens the Quick Actions sheet ("Move to top", "Rearrange grid", ...). */
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
    /** Called when the user overscrolls downward past the top of the grid to focus Search. */
    onPullToSearch: (() -> Unit)? = null,
    /** Optional replacement body below [header] (e.g. live search hits) so [header] never unmounts or loses IME focus. */
    overrideBody: (LazyGridScope.() -> Unit)? = null,
) {
    val stableKeys = remember(keys, labels, icons.size) {
        if (keys.size == icons.size) keys else List(icons.size) { i -> "${labels.getOrNull(i).orEmpty()}#$i" }
    }
    val cells = remember(stableKeys) { honeycombCells(stableKeys) }
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val px = density.density
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridScrolling by gridState.interactionSource.collectIsDraggedAsState()

    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentEditing by rememberUpdatedState(editing)
    val currentOnEditing by rememberUpdatedState(onEditing)
    val currentKeys by rememberUpdatedState(stableKeys)

    val slotRectsWindow = remember { mutableStateMapOf<String, android.graphics.Rect>() }
    var swappedInDrag by remember { mutableStateOf(false) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var anyDragging by remember { mutableStateOf(false) }

    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyGridState
        val toKey = to.key as? String ?: return@rememberReorderableLazyGridState
        val fromIdx = currentKeys.indexOf(fromKey)
        val toIdx = currentKeys.indexOf(toKey)
        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
            swappedInDrag = true
            currentOnMove?.invoke(fromIdx, toIdx)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    // Lock out root Space.kt navigation swipes whenever in edit/jiggle mode or actively dragging an icon.
    DisposableEffect(editing) {
        DragOwners.editingLock = editing
        onDispose {
            DragOwners.editingLock = false
            DragOwners.activeDrag = false
        }
    }

    val pullToSearchConnection = rememberPullToSearchNestedScroll(
        enabled = { onPullToSearch != null && !currentEditing && !anyDragging },
        isAtTop = { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0 },
        isDragging = gridScrolling,
        onPullToSearch = { onPullToSearch?.invoke() },
        onDismissKeyboard = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        },
    )

    LaunchedEffect(gridState, crownKeys, px) {
        crownKeys.collect { step ->
            gridState.animateScrollBy(-step * (ICON_D + ROW_GAP) * px)
        }
    }
    LaunchedEffect(headerScrollKey) {
        if (headerScrollKey > 0) gridState.scrollToItem(0)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (editing && overrideBody == null) {
                    Modifier.pointerInput(editing) {
                        detectTapGestures { if (!anyDragging) currentOnEditing(false) }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(HONEYCOMB_GRID_SPANS),
            state = gridState,
            modifier = Modifier.fillMaxSize().nestedScroll(pullToSearchConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topPadding,
                bottom = scrollBottomInset + if (editing) 84.dp else 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    val headerAlpha by animateFloatAsState(
                        targetValue = if (editing || anyDragging) 0.25f else 1f,
                        animationSpec = Motion.snappy.spec(),
                        label = "header-alpha",
                    )
                    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = headerAlpha }) {
                        header()
                    }
                }
            }
            if (overrideBody != null) {
                overrideBody()
            } else {
                items(
                    items = cells,
                    key = { it.gridKey },
                    span = { GridItemSpan(it.span) },
                ) { cell ->
                    when (cell) {
                        is HoneycombCell.Spacer -> {
                            Box(Modifier.size(1.dp))
                        }
                        is HoneycombCell.App -> {
                            val index = cell.appIndex
                            val appKey = cell.appKey
                            ReorderableItem(reorderableState, key = appKey) { isDragging ->
                                val interactionSource = remember { MutableInteractionSource() }
                                val scale by animateFloatAsState(
                                    targetValue = if (isDragging) 1.14f else 1f,
                                    animationSpec = PICKUP_SCALE_SPRING,
                                    label = "grid-item-scale",
                                )
                                val jiggleRot = rememberGentleJiggle(editing && !isDragging, appKey.hashCode())
                                val handleModifier = if (currentOnMove != null) {
                                    if (editing) {
                                        Modifier.draggableHandle(
                                            enabled = true,
                                            interactionSource = interactionSource,
                                            onDragStarted = {
                                                DragOwners.activeDrag = true
                                                anyDragging = true
                                                swappedInDrag = false
                                                dragStartIndex = currentKeys.indexOf(appKey)
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            },
                                            onDragStopped = {
                                                DragOwners.activeDrag = false
                                                anyDragging = false
                                                if (swappedInDrag) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                }
                                            },
                                        )
                                    } else {
                                        Modifier.longPressDraggableHandle(
                                            enabled = true,
                                            interactionSource = interactionSource,
                                            onDragStarted = {
                                                DragOwners.activeDrag = true
                                                anyDragging = true
                                                swappedInDrag = false
                                                dragStartIndex = currentKeys.indexOf(appKey)
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            },
                                            onDragStopped = {
                                                DragOwners.activeDrag = false
                                                anyDragging = false
                                                if (swappedInDrag) {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                } else if (!currentEditing) {
                                                    val curIdx = currentKeys.indexOf(appKey).takeIf { it >= 0 } ?: dragStartIndex
                                                    if (curIdx >= 0) currentOnLongPress?.invoke(curIdx)
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (isDragging) 50f else 0f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(ICON_D.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                                rotationZ = jiggleRot
                                            }
                                            .then(if (isDragging) Modifier.shadow(14.dp, CircleShape, clip = false) else Modifier)
                                            .clip(CircleShape)
                                            .then(handleModifier)
                                            .then(
                                                if (!editing) {
                                                    Modifier.clickable(
                                                        interactionSource = interactionSource,
                                                        indication = null,
                                                    ) {
                                                        val curIdx = currentKeys.indexOf(appKey)
                                                        if (curIdx >= 0) currentOnTap(curIdx, slotRectsWindow[appKey])
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .onGloballyPositioned { coords ->
                                                val w = coords.boundsInWindow()
                                                slotRectsWindow[appKey] = android.graphics.Rect(
                                                    w.left.roundToInt(),
                                                    w.top.roundToInt(),
                                                    w.right.roundToInt(),
                                                    w.bottom.roundToInt(),
                                                )
                                            }
                                            .testTag("grid-$index")
                                            .semantics { contentDescription = labels.getOrNull(index).orEmpty() },
                                    ) {
                                        icons.getOrNull(index)?.let { bmp ->
                                            Image(bmp, null, Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!editing && !anyDragging) {
                    item(key = "list-view", span = { GridItemSpan(maxLineSpan) }) {
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

        // Floating "Done" pill in explicit jiggle mode
        if (editing && !anyDragging) {
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
