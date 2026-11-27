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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.Flow
import org.honeycomb.launcher.design.CapsuleButton
import org.honeycomb.launcher.design.Symbols
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 120fps All-Apps circular icon grid built on Compose's native LazyVerticalGrid + Calvin-LL/Reorderable 3.1.0:
 *   - Clean 4-column round-icon grid with exact 1:1 Reorderable hitboxes, native cross-row animateItem() glides,
 *     built-in edge auto-scroll, and smooth spring drop-settle.
 *   - Normal browsing: tap launches immediately; press & hold drags directly into any slot (releasing without moving
 *     opens the Material 3 quick-actions sheet).
 *   - Explicit jiggle mode: DragOwners.editingLock blocks root screen swipes; draggableHandle gives instant zero-delay
 *     drag on every icon with a clean, subtle rotation-only tilt.
 */

private const val GRID_COLUMNS = 4
private const val ICON_D = 68f
private const val ICON_GAP = 14f

internal val REORDER_OFFSET_SPRING = spring<Offset>(dampingRatio = 0.78f, stiffness = 420f)
internal val DROP_SETTLE_SPRING = spring<Offset>(dampingRatio = 0.80f, stiffness = 460f)
internal val PICKUP_SCALE_SPRING = spring<Float>(dampingRatio = 0.70f, stiffness = 480f)

/** Clean, subtle iOS-style rotation-only tilt for explicit edit mode (no erratic X/Y buzzing). */
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
    /** Optional replacement body below [header] (e.g. live search hits) so [header] never unmounts or loses IME focus. */
    overrideBody: (LazyGridScope.() -> Unit)? = null,
) {
    val stableKeys = remember(keys, labels, icons.size) {
        if (keys.size == icons.size) keys else List(icons.size) { i -> "${labels.getOrNull(i).orEmpty()}#$i" }
    }
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
    val headerCount by rememberUpdatedState(if (header != null) 1 else 0)

    val slotRectsWindow = remember { mutableStateMapOf<String, android.graphics.Rect>() }
    var swappedInDrag by remember { mutableStateOf(false) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var anyDragging by remember { mutableStateOf(false) }

    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromIdx = from.index - headerCount
        val toIdx = to.index - headerCount
        val size = currentKeys.size
        if (fromIdx in 0 until size && toIdx in 0 until size && fromIdx != toIdx) {
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

    LaunchedEffect(gridScrolling) {
        if (gridScrolling) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(gridState, crownKeys, px) {
        crownKeys.collect { step ->
            gridState.animateScrollBy(-step * (ICON_D + ICON_GAP) * px)
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
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = topPadding,
                bottom = scrollBottomInset + if (editing) 84.dp else 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ICON_GAP.dp),
            horizontalArrangement = Arrangement.spacedBy(ICON_GAP.dp, Alignment.CenterHorizontally),
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
                itemsIndexed(stableKeys, key = { _, k -> k }) { index, appKey ->
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
