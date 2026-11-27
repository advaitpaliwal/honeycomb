package org.honeycomb.launcher

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.hypot

/*
 * The Space: Honeycomb's navigation in one table.
 *   depth     Face <-> Favourites (the 7-app dynamic grid) <-> Apps (full Honeycomb grid / List View).
 *             Tap the face, press Home on the bare face, or swipe left to open the dynamic grid;
 *             tap All Apps, swipe up, or swipe left on the dynamic grid to open the full grid;
 *             Back, swipe right (or swipe down on the dynamic grid) steps back out.
 *   vertical  on the Face, swipe up raises the Smart Stack; down from its first card or Back lowers it.
 *   edge      swipe down from the top edge: Notification Centre (left/centre) or Control Centre (right third).
 */

enum class Depth { Face, Favourites, Apps }

/** Taller than 3:2: All apps opens in Grid View until List View is chosen. */
const val TALL = 1.5f

fun tallScreen(widthPx: Int, heightPx: Int): Boolean = heightPx > TALL * widthPx
enum class Edge { Notifications, Control }

data class Space(
    val depth: Depth = Depth.Face,
    /** The Smart Stack is raised over the face. */
    val stack: Boolean = false,
    /** The sheet pulled down from the top edge, if any. */
    val edge: Edge? = null,
    /** Control Centre has gone in to its Quiet page. */
    val quiet: Boolean = false,
    /** The face picker is open. */
    val picking: Boolean = false,
    /** The Smart Stack shows its first card, so a swipe down lowers it (otherwise the stack moves back a card). */
    val stackTop: Boolean = true,
    /** The sheet's content is scrolled to its end, so a swipe up closes it (otherwise the content scrolls). */
    val sheetEnd: Boolean = true,
    /** Opened Apps directly from Face via swipe-down Search, so Back returns straight to Face without flashing Favourites. */
    val searchedFromFace: Boolean = false,
)

enum class Input {
    /** Android's system Home intent, delivered by a swipe up from the navigation line, coming back from an app. */
    Home,
    /**
     * The same Home intent while Honeycomb is already in front: on the bare face it opens the dynamic grid,
     * anywhere else it returns to the face.
     */
    HomeAgain,
    /** The side-edge swipe or the Back key. */
    Back,
    /** A swipe down that starts on the top edge, left or centre / right third. */
    TopLeft, TopRight,
    /** A swipe anywhere, starting below the top edge. */
    Up, Down,
    /** Sideways swipe: Left goes in a level (Face -> Favourites -> Apps), Right steps back out. */
    Right, Left,
    /** A tap or a touch and hold on the face. */
    Tap, Hold,
    /** Touch and hold on Control Centre's Quiet tile. */
    HoldQuiet,
    /** The dynamic grid's "All Apps" button / accessibility action. */
    AllApps,
}

enum class Intent { None, Home, In, Out, Search, Notifications, Control, CloseEdge, Quiet, CloseQuiet, RaiseStack, LowerStack, PickFace, EndPick }

/** One row of the input table: in a state where [where] holds, [input] means [intent]. The first matching row wins. */
class Row(val input: Input, val intent: Intent, val where: (Space) -> Boolean)

private val onFace: (Space) -> Boolean = { it.depth == Depth.Face && !it.stack && it.edge == null && !it.picking }

/** Every navigation input Honeycomb has, in one place. */
val TABLE: List<Row> = listOf(
    Row(Input.Home, Intent.Home) { true },
    Row(Input.HomeAgain, Intent.In, onFace),
    Row(Input.HomeAgain, Intent.Home) { true },
    // edges: Back and a swipe back up close the sheet (the Quiet page first)
    Row(Input.Back, Intent.CloseQuiet) { it.edge == Edge.Control && it.quiet },
    Row(Input.Back, Intent.CloseEdge) { it.edge != null },
    Row(Input.Up, Intent.CloseEdge) { it.edge != null && !it.quiet && it.sheetEnd },
    Row(Input.HoldQuiet, Intent.Quiet) { it.edge == Edge.Control },
    // the top edge, from any level without a sheet or the face picker
    Row(Input.TopLeft, Intent.Notifications) { it.edge == null && !it.picking },
    Row(Input.TopRight, Intent.Control) { it.edge == null && !it.picking },
    // the face picker
    Row(Input.Back, Intent.EndPick) { it.picking },
    // the Smart Stack: rises from below the face, goes back down
    Row(Input.Back, Intent.LowerStack) { it.stack && it.edge == null },
    Row(Input.Down, Intent.LowerStack) { it.stack && it.stackTop && it.edge == null },
    // the face: swipe up raises Smart Stack, swipe down opens iOS-style Spotlight Search, tap opens Dynamic Grid
    Row(Input.Up, Intent.RaiseStack, onFace),
    Row(Input.Down, Intent.Search, onFace),
    Row(Input.Tap, Intent.In, onFace),
    Row(Input.Hold, Intent.PickFace, onFace),
    // depth: on Favourites (dynamic grid), AllApps or swipe up goes to full grid; swipe down returns to Face
    Row(Input.AllApps, Intent.In) { it.depth == Depth.Favourites && it.edge == null },
    Row(Input.Up, Intent.In) { it.depth == Depth.Favourites && it.edge == null && !it.picking },
    Row(Input.Down, Intent.Out) { it.depth == Depth.Favourites && it.edge == null && !it.picking },
    // depth, sideways: Left goes in a level, Right comes back out one
    Row(Input.Left, Intent.In, onFace),
    Row(Input.Left, Intent.In) { it.depth == Depth.Favourites && it.edge == null && !it.picking },
    Row(Input.Right, Intent.Out) { it.depth != Depth.Face && it.edge == null && !it.picking },
    Row(Input.Back, Intent.Out) { it.depth != Depth.Face },
)

fun intentFor(s: Space, input: Input): Intent = TABLE.firstOrNull { it.input == input && it.where(s) }?.intent ?: Intent.None

/** What an intent does to the Space. [homeDepth] sets where System Home lands (default Watch Face for tests; configurable in Settings). */
fun reduce(s: Space, intent: Intent, homeDepth: Depth = Depth.Face): Space = when (intent) {
    Intent.None -> s
    Intent.Home -> Space(depth = homeDepth, sheetEnd = s.sheetEnd)
    Intent.In -> s.copy(depth = if (s.depth == Depth.Face) Depth.Favourites else Depth.Apps, searchedFromFace = false)
    Intent.Out -> if (s.depth == Depth.Apps && s.searchedFromFace) {
        s.copy(depth = Depth.Face, searchedFromFace = false)
    } else {
        s.copy(depth = if (s.depth == Depth.Apps) Depth.Favourites else Depth.Face, searchedFromFace = false)
    }
    Intent.Search -> s.copy(depth = Depth.Apps, searchedFromFace = true)
    Intent.Notifications -> s.copy(edge = Edge.Notifications, quiet = false)
    Intent.Control -> s.copy(edge = Edge.Control, quiet = false)
    Intent.CloseEdge -> s.copy(edge = null, quiet = false)
    Intent.Quiet -> s.copy(quiet = true)
    Intent.CloseQuiet -> s.copy(quiet = false)
    Intent.RaiseStack -> s.copy(stack = true, stackTop = true)
    Intent.LowerStack -> s.copy(stack = false)
    Intent.PickFace -> s.copy(picking = true)
    Intent.EndPick -> s.copy(picking = false)
}

// ---- input: gestures become Inputs ---------------------------------------------------------------------------------

object EdgeSwipe {
    const val EDGE_DP = 24f
    /** Reachable band below the Android status bar so system shade never steals top-edge pulls on real phones. */
    const val TOP_REACH_DP = 72f
    const val DISTANCE_DP = 48f
    const val FLICK_DISTANCE_DP = 16f
    const val FLICK_SPEED_DP = 1000f

    const val RISE_DISTANCE_DP = 16f
    const val RISE_FLICK_SPEED_DP = 500f

    fun nearTop(downY: Float, edge: Float): Boolean = downY <= edge

    /** [travel] and [speed] are along the swipe's direction (positive means the right way). */
    fun opens(travel: Float, speed: Float, distance: Float, flickDistance: Float, flickSpeed: Float): Boolean =
        travel >= distance || (travel >= flickDistance && speed >= flickSpeed)

    /** A swipe down from the top edge: the right third pulls Control Centre, the rest Notification Centre. */
    fun fromTop(x: Float, width: Float): Input = if (x > width * 2f / 3f) Input.TopRight else Input.TopLeft
}

/**
 * The sideways swipe's claim on the right edge: keeps [BAND_DP] in the middle of the right edge while the face or
 * dynamic grid shows so a swipe left goes in a level; Back still works from the whole left edge and the full grid.
 */
object SideSwipe {
    const val BAND_DP = 200f
    const val EDGE_WIDTH_DP = 48f

    fun band(w: Float, h: Float, dp: Float): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry.Rect(maxOf(0f, w - EDGE_WIDTH_DP * dp), (h - BAND_DP * dp) / 2f, w, (h + BAND_DP * dp) / 2f)

    fun claims(s: Space): Boolean = intentFor(s, Input.Left) != Intent.None

    fun kept(s: Space, at: Offset, size: IntSize): Boolean = claims(s) &&
        band(size.width.toFloat(), size.height.toFloat(), android.content.res.Resources.getSystem().displayMetrics.density).contains(at)

    /** How far the finger travels for a whole level change, as a fraction of the screen's width. */
    const val TRAVEL = 0.5f
}

/** Real dp (not the frame's layout units). */
private val realDp get() = android.content.res.Resources.getSystem().displayMetrics.density

/**
 * The one gesture recognizer at the root of Honeycomb. Claims a swipe only when the table gives it an intent in the
 * current [space], so vertical scrolling in Apps never collides with navigation.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.spaceInput(
    space: () -> Space,
    onInput: (Input) -> Unit,
    onDrag: (Input, Float) -> Unit = { _, _ -> },
    onRelease: (Input, Float, Boolean) -> Unit = { _, _, _ -> },
): Modifier = composed {
    val read by rememberUpdatedState(space)
    val send by rememberUpdatedState(onInput)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val systemBand by rememberUpdatedState(WindowInsets.systemGestures.getBottom(LocalDensity.current))
    val density = LocalDensity.current
    val statusTop by rememberUpdatedState(WindowInsets.statusBarsIgnoringVisibility.getTop(density))
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val leftBand by rememberUpdatedState(WindowInsets.systemGestures.getLeft(density, layoutDirection))
    val rightBand by rememberUpdatedState(WindowInsets.systemGestures.getRight(density, layoutDirection))
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (DragOwners.activeDrag || DragOwners.editingLock) return@awaitEachGesture
            if (down.position.y >= size.height - systemBand) return@awaitEachGesture
            val s = read()
            val topEdgePx = maxOf(EdgeSwipe.EDGE_DP * realDp, statusTop + EdgeSwipe.TOP_REACH_DP * realDp)
            val top = EdgeSwipe.nearTop(down.position.y, topEdgePx)
            val armed = candidates(s, top, down.position, size).let { if (DragOwners.owns(down.position)) it - Input.Up - Input.Down else it }
                .let { if (down.position.x < leftBand) it - Input.Right else it }
                .let { if (down.position.x >= size.width - rightBand && !SideSwipe.kept(s, down.position, size)) it - Input.Left else it }
            if (armed.isEmpty()) return@awaitEachGesture
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var d = Offset.Zero
            var taken: Input? = null
            var fired = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (DragOwners.activeDrag || DragOwners.editingLock) return@awaitEachGesture
                tracker.addPosition(c.uptimeMillis, c.position)
                d += c.positionChange()
                if (taken == null && hypot(d.x, d.y) > viewConfiguration.touchSlop * 2f) {
                    if (s.depth != Depth.Face && c.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
                    taken = armed.firstOrNull { travel(it, d) > 0 && abs(travel(it, d)) > abs(across(it, d)) }
                    if (taken == null) return@awaitEachGesture
                }
                val t = taken
                if (t != null) {
                    e.changes.forEach { it.consume() }
                    drag(t, travel(t, d))
                    if (!fired && !tracks(s, t) && travel(t, d) >= EdgeSwipe.DISTANCE_DP * realDp) { fired = true; send(t) }
                }
                if (!c.pressed) {
                    if (t != null && !fired) {
                        val v = tracker.calculateVelocity().let { travel(t, Offset(it.x, it.y)) }
                        val rise = rises(s, t)
                        val ok = EdgeSwipe.opens(travel(t, d), v,
                            (if (rise) EdgeSwipe.RISE_DISTANCE_DP else EdgeSwipe.DISTANCE_DP) * realDp,
                            EdgeSwipe.FLICK_DISTANCE_DP * realDp,
                            (if (rise) EdgeSwipe.RISE_FLICK_SPEED_DP else EdgeSwipe.FLICK_SPEED_DP) * realDp)
                        release(t, v, ok)
                        if (ok) send(t)
                    }
                    break
                }
            }
        }
    }
}

private fun candidates(s: Space, top: Boolean, at: Offset, size: IntSize): List<Input> = buildList {
    if (top) EdgeSwipe.fromTop(at.x, size.width.toFloat()).let { if (intentFor(s, it) != Intent.None) add(it) }
    else if (intentFor(s, Input.Down) != Intent.None && !(s.stack && PileHits.contains(at))) add(Input.Down)
    if (intentFor(s, Input.Up) != Intent.None) add(Input.Up)
    if (!top) for (side in listOf(Input.Right, Input.Left)) if (intentFor(s, side) != Intent.None) add(side)
}

private fun travel(i: Input, d: Offset) = when (i) {
    Input.TopLeft, Input.TopRight, Input.Down -> d.y
    Input.Up -> -d.y
    Input.Right -> d.x
    Input.Left -> -d.x
    else -> 0f
}

private fun across(i: Input, d: Offset) = if (i == Input.Right || i == Input.Left) d.y else d.x

private fun rises(s: Space, i: Input) = (i == Input.Up && s.depth == Depth.Face && !s.stack) || (i == Input.Down && s.stack)

private fun tracks(s: Space, i: Input) = i == Input.Right || i == Input.Left || i == Input.TopLeft || i == Input.TopRight ||
    (i == Input.Up && (s.depth == Depth.Face || s.depth == Depth.Favourites)) || i == Input.Down
