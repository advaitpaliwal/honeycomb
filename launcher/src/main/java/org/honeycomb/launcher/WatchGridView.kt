package org.honeycomb.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import org.honeycomb.launcher.design.CapsuleButton
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.Symbols
import kotlin.math.roundToInt

/*
 * Lean, silky Honeycomb app grid built on standard Jetpack Compose LazyColumn:
 *   - Staggered 3-4-3-4 hexagonal rows (68 dp icons, 14 dp uniform gap horizontally and diagonally, 23 dp side margins).
 *   - Native 120 fps momentum scroll & stretch overscroll, zero custom gesture/fling fighting.
 *   - Native combinedClickable per icon: responsive press scale + instant tap launch + long-press ActionMenu.
 */

private const val ICON_D = 68f
private const val ICON_GAP = 14f
private const val ROW_GAP = 3f // 68 + 3 = 71 dp row pitch = 82 * sqrt(3)/2 -> uniform 14 dp diagonal gap

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
    /** Long-press on an icon opens quick actions ("Move to top", "Don't recommend"). */
    onLongPress: ((index: Int) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onMove: ((from: Int, to: Int) -> Unit)? = null,
    /** Optional scrollable header (e.g. search bar) that scrolls smoothly off with the grid. */
    header: (@Composable () -> Unit)? = null,
    /** Increment to scroll back to the header (e.g. swipe-down search trigger). */
    headerScrollKey: Int = 0,
    /** Optional replacement body below [header] (e.g. live search hits) so [header] never unmounts or loses IME focus. */
    overrideBody: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val rows = remember(icons.size) { honeycombRows(icons.size) }
    val px = LocalDensity.current.density
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    LaunchedEffect(listState, crownKeys, px) {
        crownKeys.collect { step ->
            listState.animateScrollBy(-step * (ICON_D + ROW_GAP) * px)
        }
    }
    LaunchedEffect(headerScrollKey) {
        if (headerScrollKey > 0) listState.scrollToItem(0)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = topPadding, bottom = scrollBottomInset + 28.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (header != null) {
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
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val pressScale by animateFloatAsState(
                            targetValue = if (pressed) 0.90f else 1f,
                            animationSpec = Motion.snappy.spec(),
                            label = "icon-press",
                        )
                        var bounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
                        Box(
                            Modifier
                                .size(ICON_D.dp)
                                .graphicsLayer {
                                    scaleX = pressScale
                                    scaleY = pressScale
                                }
                                .onGloballyPositioned { coords ->
                                    val r = coords.boundsInWindow()
                                    bounds = android.graphics.Rect(
                                        r.left.roundToInt(),
                                        r.top.roundToInt(),
                                        r.right.roundToInt(),
                                        r.bottom.roundToInt(),
                                    )
                                }
                                .clip(CircleShape)
                                .combinedClickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = { currentOnTap(index, bounds) },
                                    onLongClick = if (onLongPress != null) {
                                        { currentOnLongPress?.invoke(index) }
                                    } else null,
                                )
                                .testTag("grid-$index")
                                .semantics { contentDescription = labels[index] },
                        ) {
                            Image(icons[index], null, Modifier.fillMaxSize())
                            if (pressed) Box(Modifier.fillMaxSize().background(HoneycombColors.pressed))
                        }
                    }
                }
            }
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
