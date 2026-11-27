package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space

import android.content.ComponentName
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Honeycomb's home: a watchOS-style face, the utility face, the favourites grid and the all-apps list. Every number below
 * is a coordinate in the 360 x 413 layout frame; the activity sets 1 dp to 1/360 of the screen width, so 1.dp here is
 * one layout unit.
 */

const val FRAME_W = 360f
const val FRAME_H = 413f

/**
 * The screen's height in layout units. Width sets the scale on every screen, as Apple Watch scales one layout across
 * case sizes and Wear OS by screen percentage; height is whatever the phone has, 413 on a nearly square screen and about
 * 780 on a 9:20 phone. [FrameSize] reads it once with BoxWithConstraints (developer.android.com/develop/ui/compose/layouts/adaptive).
 */
val LocalFrameH = staticCompositionLocalOf { FRAME_H }

/** How far a design laid out in the 413-unit frame moves down to sit centred on this screen. */
@Composable @ReadOnlyComposable
fun frameShift(): Float = (LocalFrameH.current - FRAME_H) / 2

/** Keep the original first-row position, but scroll that space away with the content. */
internal val scrollTopInset @Composable get() = ((LocalScreen.current.height - LocalFrameH.current) / 2).dp
internal val scrollBottomInset @Composable get() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** The whole window in layout units, for the backgrounds that bleed past the frame's margins. */
val LocalScreen = staticCompositionLocalOf { androidx.compose.ui.geometry.Size(FRAME_W, FRAME_H) }

/**
 * Measures the window once (BoxWithConstraints) and gives every screen below it the frame's height and the window's
 * size. The top and bottom inset is the larger of the system bars' heights (even while hidden), the same at both ends
 * so the content stays centred and clears the status bar and the gesture handle.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FrameSize(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val bars = WindowInsets.systemBarsIgnoringVisibility
    val barsPx = maxOf(bars.getTop(d), bars.getBottom(d))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sw = maxWidth.value; val sh = maxHeight.value
        val v = barsPx.toFloat() / d.density
        CompositionLocalProvider(LocalFrameH provides sh - 2 * v, LocalScreen provides androidx.compose.ui.geometry.Size(sw, sh)) { content() }
    }
}

/**
 * The frame the screens are laid out in: the window's width (360 units), centred, inset at the top and bottom by
 * [FrameSize]. Gestures and the app-launch zoom stay outside it, on the whole window.
 */
@Composable
fun Frame(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.Center).requiredSize(FRAME_W.dp, LocalFrameH.current.dp), content = content)
    }
}

/**
 * A group designed [span] units wide grows until it spans the whole content width. It zooms as a layer (taps and drags
 * follow the layer's transform), so the one scale (design/Scale.kt) stays the same inside it.
 */
@Composable
fun FillWidth(span: Float, content: @Composable BoxScope.() -> Unit) {
    val k = FRAME_W / span
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val h = maxHeight.value / k
        CompositionLocalProvider(LocalFrameH provides h) {
            Box(Modifier.requiredSize(FRAME_W.dp, h.dp).graphicsLayer {
                scaleX = k; scaleY = k; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }, content = content)
        }
    }
}

/** A 360 x 413 group (a face, the favourites) centred on any screen, its own coordinates unchanged. */
@Composable
fun CentredFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier.requiredSize(FRAME_W.dp, FRAME_H.dp), content = content)
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(id: Int, w: Int) = Font(id, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Inter = FontFamily(variable(R.font.inter, 500), variable(R.font.inter, 600))

/** Absolute position: x, y from the frame's top left. */
internal fun Modifier.at(x: Float, y: Float) = this.offset(x.dp, y.dp)

// ---- the ground ---------------------------------------------------------------------------------------------------

/** Filter only the live face, including the area behind the system bars. */
@Composable
internal fun BoxScope.BlurredFace(opacity: Float) {
    val ctx = LocalContext.current
    val face = remember(ctx) { FaceKind.entries[FaceChoice.saved(ctx)] }
    val screen = LocalScreen.current
    Box(Modifier.align(Alignment.Center).requiredSize(screen.width.dp, screen.height.dp)
        .clearAndSetSemantics { }.graphicsLayer { alpha = opacity }
        .blur((screen.width * FACE_BLUR_PX / 422f).dp)) { AnyFace(face, ticking()) }
}

/** The clock, ticking each second. */
@Composable
private fun ticking(): Long {
    var ms by remember { mutableLongStateOf(FaceClock.millis()) }
    LaunchedEffect(Unit) { while (true) { ms = FaceClock.millis(); delay(1000 - ms % 1000) } }
    return ms
}

// ---- A1: the face -------------------------------------------------------------------------------------------------

/** The hour and the minutes, as the face shows them: "9" and "41". */
fun faceDigits(ms: Long): Pair<String, String> = clock(ms).split(':').let { it[0] to it[1] }

/** The chosen face, kept across restarts: an index into [FaceKind]. */
object FaceChoice {
    val names = FaceKind.entries.map { it.label }
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("face", android.content.Context.MODE_PRIVATE)
    fun collection(ctx: android.content.Context) = FaceKind.entries + prefs(ctx).getString("added", "").orEmpty()
        .split(',').mapNotNull { name -> FaceKind.entries.find { it.name == name } }
    fun add(ctx: android.content.Context, kind: FaceKind): Int {
        val p = prefs(ctx); val extra = p.getString("added", "").orEmpty()
        val index = collection(ctx).size
        p.edit().putString("added", if (extra.isEmpty()) kind.name else "$extra,${kind.name}").putInt("face", index).apply()
        return index
    }
    fun style(ctx: android.content.Context, i: Int) = prefs(ctx).let {
        FaceStyle(it.getString("colour-$i", null))
    }
    fun saveStyle(ctx: android.content.Context, i: Int, style: FaceStyle) = prefs(ctx).edit()
        .putString("colour-$i", style.colour).apply()
    fun saved(ctx: android.content.Context) = prefs(ctx).getInt("face", 0).coerceIn(0, collection(ctx).lastIndex)
    fun save(ctx: android.content.Context, face: Int) = prefs(ctx).edit().putInt("face", face).commit()
}

/**
 * The chosen face. A tap opens the favourites; touch and hold opens the face picker, as on watchOS 10 and later
 * (support.apple.com/guide/watch/apda6559ad78: "touch and hold the display. Swipe to the watch face you want, then tap
 * it"): the current face shrinks into a card with the other beside it, a sideways swipe moves between them and a tap
 * chooses one. There is no sideways swipe between faces outside the picker; a swipe left goes in to the
 * dynamic grid and a swipe up raises the Smart Stack, both through the Space's table.
 */
@Composable
fun FacePager(picking: Boolean, onInput: (Input) -> Unit) {
    val ctx = LocalContext.current
    val ms = ticking()
    var face by remember { mutableIntStateOf(FaceChoice.saved(ctx)) }
    var collection by remember { mutableStateOf(FaceChoice.collection(ctx)) }
    val styles = remember { androidx.compose.runtime.mutableStateMapOf<Int, FaceStyle>() }
    @Composable fun Face(i: Int) = AnyFace(collection[i], ms, styles[i] ?: remember(i) { FaceChoice.style(ctx, i) })
    Backdrop {
        if (!picking) {
            // the face's colour fields run under the bars, so the bars' icons follow them
            faceBars(collection[face], FacePalette((styles[face] ?: FaceChoice.style(ctx, face)).scheme(HoneycombColors.scheme, HoneycombColors.dark)))
                ?.let { (top, bottom) -> BarsOver(top, bottom) }
            Box(Modifier.fillMaxSize().testTag("faces").semantics { contentDescription = collection[face].label }
                .tapOrHold(onTap = { onInput(Input.Tap) }, onHold = { onInput(Input.Hold) })) { Face(face) }
        } else {
            FaceSwitcher(collection, face, ms, style = { styles[it] ?: FaceChoice.style(ctx, it) },
                save = { i, value -> FaceChoice.saveStyle(ctx, i, value); styles[i] = value },
                select = { i -> face = i; FaceChoice.save(ctx, i); onInput(Input.Back) },
                add = { kind -> val i = FaceChoice.add(ctx, kind); collection = FaceChoice.collection(ctx); face = i; onInput(Input.Back) })
        }
    }
}

// ---- A3: favourites -----------------------------------------------------------------------------------------------

/**
 * The seven slots' top-left corners and diameters (180:14..20): centre, top, upper left, upper right, lower left, lower
 * right, bottom. The bottom slot is where watchOS puts its all-apps circle (180:20); a phone reaches all apps by a
 * swipe left or up, so it holds a seventh app at the top slot's size.
 */
/** The favourites' width in layout units: the ring spans 422 − 28 − 30.3 native watch pixels, centred. */
const val FAVOURITES_SPAN = (326.5f - 33.5f) * 422f / (422f - 28f - 30.3f)

val favouriteSlots = listOf(
    Triple(129.5f, 155.5f, 101f), Triple(135.5f, 43.5f, 89f), Triple(33.5f, 102.5f, 89f),
    Triple(237.5f, 102.5f, 89f), Triple(33.5f, 218.5f, 89f), Triple(237.5f, 218.5f, 89f), Triple(135.5f, 277.5f, 89f),
)

/** Material expanded width, measured in Android dp rather than the watch layout's units. */
@Composable
internal fun expandedGridWindow(): Boolean = Wp.expanded(LocalScreen.current.width * LocalDensity.current.density /
    LocalContext.current.resources.displayMetrics.density)

/** An app's round icon. No drop shadow: the language has none (docs/design-language.md §2). */
@Composable
fun AppDisc(icon: ImageBitmap, d: Float, modifier: Modifier = Modifier) {
    // the icon bitmap is already a disc (circleIcon), so it needs no clip
    Image(icon, null, modifier.size(d.dp))
}

@Composable
private fun wiggle(on: Boolean, seed: Int): Float {
    if (!on) return 0f
    val t = rememberInfiniteTransition(label = "wiggle")
    val a by t.animateFloat(-1.6f, 1.6f, infiniteRepeatable(Motion.jiggle(seed), RepeatMode.Reverse), label = "a")
    return a
}

/**
 * The dynamic grid: seven apps over the blurred face, the most used in the centre and the rest outward (AppOrder.favourites).
 * Slots the owner pinned stay where they were put. All apps is a swipe left or up away, and an "All apps" accessibility action
 * on the cluster for screen readers.
 * Press and hold an app to edit: drag it onto another slot to swap them, or tap it to pick its replacement from the list.
 */
@Composable
fun FavouritesScreen(
    apps: List<App>,
    pins: List<String>,
    savedPins: List<String> = emptyList(),
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onPins: (List<String>) -> Unit,
    onPick: (Int) -> Unit,
    onDontRecommend: (Int, String) -> Unit = { _, _ -> },
    onUnpin: (Int) -> Unit = {},
    onAllApps: () -> Unit,
    onSearch: () -> Unit = onAllApps,
    onFace: () -> Unit,
    onOpen: (ComponentName, android.graphics.Rect?) -> Unit,
) {
    val byKey = remember(apps) { apps.associateBy { it.component.flattenToString() } }
    val view = androidx.compose.ui.platform.LocalView.current
    val density = LocalDensity.current
    val px = density.density
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var pressedKey by remember { mutableStateOf<String?>(null) }
    var heldKey by remember { mutableStateOf<String?>(null) }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragCenterPx by remember { mutableStateOf(Offset.Zero) }
    var previewPins by remember { mutableStateOf<List<String>?>(null) }
    var hoverTargetSlot by remember { mutableIntStateOf(-1) }
    var hoverSinceMs by remember { mutableLongStateOf(0L) }
    var lastSwapMs by remember { mutableLongStateOf(0L) }
    var lastSwapSlot by remember { mutableIntStateOf(-1) }
    var menuSlot by remember { mutableStateOf<Pair<Int, App>?>(null) }

    val windowBounds = remember { androidx.compose.runtime.mutableStateMapOf<String, android.graphics.Rect>() }

    val displayPins = previewPins ?: pins
    val curPins by rememberUpdatedState(displayPins)
    val curCommittedPins by rememberUpdatedState(pins)
    val curByKey by rememberUpdatedState(byKey)
    val curEditing by rememberUpdatedState(editing)
    val curOnOpen by rememberUpdatedState(onOpen)
    val curOnPins by rememberUpdatedState(onPins)
    val curOnEditing by rememberUpdatedState(onEditing)

    androidx.compose.runtime.DisposableEffect(editing) {
        DragOwners.editingLock = editing
        onDispose {
            DragOwners.editingLock = false
            DragOwners.activeDrag = false
        }
    }

    fun slotTopLeftPx(slot: Int): Offset {
        val (x, y, d) = favouriteSlots[slot]
        val left = if (x > FRAME_W / 2) FRAME_W.dp.value * px - d * px - (FRAME_W - x - d) * px else x * px
        return Offset(left, y * px)
    }

    fun slotCenterPx(slot: Int): Offset {
        val (_, _, d) = favouriteSlots[slot]
        val tl = slotTopLeftPx(slot)
        return Offset(tl.x + d * px / 2f, tl.y + d * px / 2f)
    }

    fun hitTestSlot(localPos: Offset): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in curPins.indices) {
            val (_, _, d) = favouriteSlots.getOrNull(i) ?: continue
            val c = slotCenterPx(i)
            val dist = hypot(c.x - localPos.x, c.y - localPos.y)
            if (dist <= d * px * 0.56f && dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        return best
    }

    fun evaluateFavouriteSwap(activeKey: String, centerPx: Offset, forceImmediate: Boolean = false) {
        val list = previewPins ?: curPins
        val fromSlot = list.indexOf(activeKey)
        if (fromSlot < 0) return
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (j in list.indices) {
            if (j == fromSlot) continue
            val (_, _, td) = favouriteSlots.getOrNull(j) ?: continue
            val c = slotCenterPx(j)
            val dist = hypot(c.x - centerPx.x, c.y - centerPx.y)
            if (dist < td * px * 0.58f && dist < bestDist) {
                bestDist = dist
                best = j
            }
        }
        if (best < 0) {
            hoverTargetSlot = -1
            hoverSinceMs = 0L
            return
        }
        val (_, _, td) = favouriteSlots[best]
        val now = android.os.SystemClock.uptimeMillis()
        if (!forceImmediate && best == lastSwapSlot && (now - lastSwapMs) < 180L) return
        if (forceImmediate || bestDist <= td * px * 0.36f) {
            previewPins = AppOrder.swap(list, fromSlot, best)
            lastSwapMs = now
            lastSwapSlot = fromSlot
            hoverTargetSlot = -1
            hoverSinceMs = 0L
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            return
        }
        if (hoverTargetSlot != best) {
            hoverTargetSlot = best
            hoverSinceMs = now
        } else if (now - hoverSinceMs >= 65L) {
            previewPins = AppOrder.swap(list, fromSlot, best)
            lastSwapMs = now
            lastSwapSlot = fromSlot
            hoverTargetSlot = -1
            hoverSinceMs = 0L
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun commitPreviewPins() {
        val pending = previewPins
        previewPins = null
        if (pending != null && pending != curCommittedPins) {
            curOnPins(pending)
        }
    }

    val visualJiggle = editing || draggedKey != null

    androidx.activity.compose.BackHandler(enabled = menuSlot != null) { menuSlot = null }
    Backdrop(Modifier.testTag("favourites").semantics {
        customActions = listOf(CustomAccessibilityAction("All apps") { onAllApps(); true })
    }) {
        BlurredFace(opacity = 0.98f)
        Box(Modifier.fillMaxSize().pointerInput(editing, menuSlot) {
            detectTapGestures {
                when {
                    menuSlot != null -> menuSlot = null
                    editing -> onEditing(false)
                    else -> onFace()
                }
            }
        })
        // The ring spans 422 − 28 − 30.3 native watch pixels. Centre it rather than shifting the centre pin.
        FillWidth(FAVOURITES_SPAN) {
            CentredFrame(
                modifier = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val hitSlot = hitTestSlot(down.position)
                        if (hitSlot < 0) return@awaitEachGesture
                        val hitKey = curPins.getOrNull(hitSlot) ?: return@awaitEachGesture
                        val grabOffset = down.position - slotCenterPx(hitSlot)
                        val slop = viewConfiguration.touchSlop

                        try {
                            if (curEditing) {
                                // Explicit jiggle mode: instant zero-delay drag on Initial pass; root swipes locked out!
                                pressedKey = hitKey
                                var total = Offset.Zero
                                var started = false
                                while (true) {
                                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) {
                                        if (started) {
                                            ch.consume()
                                            evaluateFavouriteSwap(hitKey, dragCenterPx, forceImmediate = true)
                                            commitPreviewPins()
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                        }
                                        break
                                    }
                                    val delta = ch.positionChange()
                                    total += delta
                                    if (!started && hypot(total.x, total.y) > slop * 0.4f) {
                                        started = true
                                        DragOwners.activeDrag = true
                                        pressedKey = null
                                        previewPins = curPins
                                        draggedKey = hitKey
                                        dragCenterPx = ch.position - grabOffset
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        ch.consume()
                                    } else if (started) {
                                        ch.consume()
                                        dragCenterPx = ch.position - grabOffset
                                        evaluateFavouriteSwap(hitKey, dragCenterPx)
                                    }
                                }
                                return@awaitEachGesture
                            }

                            // Normal mode: crisp 2-stage Haptic Touch
                            pressedKey = hitKey
                            var cancelled = false
                            var liftedEarly = false
                            val endedBeforeHold = withTimeoutOrNull(260L) {
                                var moved = Offset.Zero
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.isConsumed) { cancelled = true; break }
                                    moved += ch.positionChange()
                                    if (hypot(moved.x, moved.y) > slop) { cancelled = true; break }
                                    if (!ch.pressed) { liftedEarly = true; ch.consume(); break }
                                }
                                true
                            }
                            if (cancelled) return@awaitEachGesture
                            if (liftedEarly && endedBeforeHold != null) {
                                pressedKey = null
                                curByKey[hitKey]?.let { app -> curOnOpen(app.component, windowBounds[hitKey]) }
                                return@awaitEachGesture
                            }

                            // Stage 2: Haptic Pop! Lock out root navigation on Initial pass immediately.
                            DragOwners.activeDrag = true
                            pressedKey = null
                            heldKey = hitKey
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

                            var dragAccum = Offset.Zero
                            var startedDrag = false
                            while (true) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    ch.consume()
                                    if (startedDrag) {
                                        evaluateFavouriteSwap(hitKey, dragCenterPx, forceImmediate = true)
                                        commitPreviewPins()
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                    } else {
                                        heldKey = null
                                        val curSlot = curPins.indexOf(hitKey)
                                        val app = curByKey[hitKey]
                                        if (curSlot >= 0 && app != null) menuSlot = curSlot to app
                                    }
                                    break
                                }
                                val delta = ch.positionChange()
                                ch.consume()
                                dragAccum += delta
                                if (!startedDrag && hypot(dragAccum.x, dragAccum.y) > slop * 0.75f) {
                                    startedDrag = true
                                    heldKey = null
                                    previewPins = curPins
                                    draggedKey = hitKey
                                    dragCenterPx = ch.position - grabOffset
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                } else if (startedDrag) {
                                    dragCenterPx = ch.position - grabOffset
                                    evaluateFavouriteSwap(hitKey, dragCenterPx)
                                }
                            }
                        } finally {
                            DragOwners.activeDrag = false
                            pressedKey = null
                            heldKey = null
                            previewPins = null
                            if (draggedKey == hitKey) draggedKey = null
                            hoverTargetSlot = -1
                            hoverSinceMs = 0L
                            lastSwapSlot = -1
                        }
                    }
                },
            ) {
                displayPins.forEachIndexed { i, appKey ->
                    val app = byKey[appKey] ?: return@forEachIndexed
                    val (_, _, d) = favouriteSlots.getOrNull(i) ?: return@forEachIndexed
                    androidx.compose.runtime.key(appKey) {
                        val isDragged = draggedKey == appKey
                        val isHeld = heldKey == appKey
                        val isPressed = pressedKey == appKey
                        val targetCenter = slotCenterPx(i)
                        val animCenter = remember(appKey) {
                            Animatable(targetCenter, Offset.VectorConverter)
                        }
                        val animD by animateFloatAsState(
                            targetValue = d,
                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                            label = "fav-d",
                        )
                        val scale by animateFloatAsState(
                            targetValue = when {
                                isDragged -> 1.16f
                                isHeld -> 1.10f
                                isPressed -> 0.92f
                                else -> 1f
                            },
                            animationSpec = PICKUP_SCALE_SPRING,
                            label = "fav-scale",
                        )
                        val lastDragPos = remember(appKey) { mutableStateOf<Offset?>(null) }
                        if (isDragged) lastDragPos.value = dragCenterPx
                        // Zero per-frame coroutine launches while dragging; spring smoothly only when displaced or released!
                        LaunchedEffect(isDragged, targetCenter) {
                            if (!isDragged) {
                                lastDragPos.value?.let { releasePos ->
                                    animCenter.snapTo(releasePos)
                                    lastDragPos.value = null
                                }
                                animCenter.animateTo(targetCenter, DROP_SETTLE_SPRING)
                            }
                        }
                        val jiggleRot = rememberGentleJiggle(editing && !isDragged, appKey.hashCode())
                        val drawCenter = if (isDragged) dragCenterPx else animCenter.value
                        val drawTopLeftX = drawCenter.x - animD * px / 2f
                        val drawTopLeftY = drawCenter.y - animD * px / 2f
                        AppDisc(
                            app.icon,
                            animD,
                            Modifier
                                .zIndex(if (isDragged) 20f else if (isHeld) 10f else 1f)
                                .offset { IntOffset(drawTopLeftX.roundToInt(), drawTopLeftY.roundToInt()) }
                                .graphicsLayer {
                                    rotationZ = jiggleRot
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .onGloballyPositioned { c ->
                                    val r = c.boundsInWindow()
                                    windowBounds[appKey] = android.graphics.Rect(
                                        r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt(),
                                    )
                                }
                                .testTag("pin-$i")
                                .semantics { contentDescription = app.label },
                        )
                    }
                }
            }
        }
        if (editing && menuSlot == null) {
            CapsuleButton(
                text = "Done",
                onClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    onEditing(false)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = scrollBottomInset + 22.dp)
                    .testTag("favourites-edit-done")
                    .semantics { contentDescription = "Done editing" },
                height = 40.dp,
                minWidth = 112.dp,
            )
        }
        if (!visualJiggle && menuSlot == null) {
            CapsuleButton(
                text = "All Apps",
                onClick = onAllApps,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = scrollBottomInset + 22.dp)
                    .testTag("all-apps-pill")
                    .semantics { contentDescription = "All Apps" },
                icon = Symbols.apps,
                height = 40.dp,
                minWidth = 136.dp,
            )
        }
        menuSlot?.let { (slot, app) ->
            val key = app.component.flattenToString()
            val isPinned = savedPins.getOrNull(slot).orEmpty().isNotEmpty()
            val actions = buildList {
                if (slot != 0) {
                    add(MenuAction("Move to centre", Symbols.arrowUp, "menu-move-centre") {
                        menuSlot = null; onEditing(false); onPins(AppOrder.swap(pins, slot, 0))
                    })
                }
                add(MenuAction("Rearrange icons", Symbols.apps, "menu-rearrange-favs") {
                    menuSlot = null; onEditing(true)
                })
                add(MenuAction("Replace app", Symbols.list, "menu-replace") {
                    menuSlot = null; onEditing(false); onPick(slot)
                })
                if (isPinned) {
                    add(MenuAction("Unpin", Symbols.back, "menu-unpin") {
                        menuSlot = null; onEditing(false); onUnpin(slot)
                    })
                }
                add(MenuAction("Don't recommend", Symbols.close, "menu-dont-recommend", destructive = true) {
                    menuSlot = null; onEditing(false); onDontRecommend(slot, key)
                })
            }
            ActionMenuSheet(
                title = app.label,
                icon = app.icon,
                actions = actions,
                onDismiss = { menuSlot = null },
                modifier = Modifier.testTag("favourite-menu"),
            )
        }
    }
}

// ---- A4: the list -------------------------------------------------------------------------------------------------

/** Row pitch and the first row's centre (176:10: icons centred at 74, 170, 266, 362, 458). */
const val ROW = 96f
private const val FIRST_CENTRE = 74f

/** The List View time's top (176:10), and the space between the bottom of the time and the first row at rest, over which scrolled rows fade. */
private const val CLOCK_TOP = 18f
private const val CLOCK_GAP = 24f

/**
 * How much a row shrinks with its centre [d] units from the screen's middle: full size within 60 units, then easing
 * smaller towards the edges, fitted to the design (77/82 at the top row, 75/82 and 70/82 at the bottom two).
 */
fun edgeScale(d: Float): Float = (1f - 0.00082f * (abs(d) - 60f).coerceAtLeast(0f)).coerceIn(0.6f, 1f)

/** Clean phone-proportioned row pitch for the all-apps list. */
private const val LIST_ROW = 58f

/** Section bucket ('A'..'Z', or '#' for numbers/symbols) matching iOS Contacts & watchOS List View. */
internal fun sectionLetter(label: String): Char {
    val first = label.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    return if (first in 'A'..'Z') first else '#'
}

/** Strictly alphabetical A -> Z ordering (with '#' symbols/digits at the end), as on Apple Watch List View. */
internal fun alphabeticalApps(apps: List<App>): List<App> =
    apps.sortedWith(
        compareBy<App>(
            { sectionLetter(it.label) == '#' },
            { it.label.trim().lowercase() },
            { it.component.flattenToString() },
        ),
    )

/** Finds the target row index when scrubbing to [letter], smoothly snapping to the nearest section if [letter] has no apps. */
internal fun firstIndexForSection(apps: List<App>, letter: Char): Int? {
    if (apps.isEmpty()) return null
    apps.indexOfFirst { sectionLetter(it.label) == letter }.takeIf { it >= 0 }?.let { return it }
    if (letter == '#') return apps.lastIndex
    return apps.indexOfFirst {
        val s = sectionLetter(it.label)
        s == '#' || s > letter
    }.takeIf { it >= 0 } ?: apps.lastIndex
}

/** Filters [apps] by [query]: prefix matches first, then substring matches, each sorted alphabetically. */
internal fun filterApps(apps: List<App>, query: String): List<App> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return apps
    val (prefix, contains) = apps.filter { it.label.lowercase().contains(q) }
        .partition { it.label.lowercase().startsWith(q) }
    return prefix.sortedBy { it.label.lowercase() } + contains.sortedBy { it.label.lowercase() }
}

internal data class QuickSearchAction(
    val id: String,
    val label: String,
    val icon: Icon,
    val keywords: List<String>,
)

internal val QUICK_SEARCH_ACTIONS = listOf(
    QuickSearchAction(
        id = "block",
        label = "Honeycomb Block",
        icon = Symbols.block,
        keywords = listOf("block", "lock", "reels", "shorts", "feed", "feeds", "website", "websites", "strict"),
    ),
    QuickSearchAction(
        id = "control",
        label = "Control Centre & Brightness",
        icon = Symbols.sun,
        keywords = listOf("brightness", "control", "control centre", "control center", "flashlight", "quiet", "dnd", "wifi", "bluetooth"),
    ),
    QuickSearchAction(
        id = "settings",
        label = "Honeycomb Settings",
        icon = Symbols.settings,
        keywords = listOf("honeycomb", "settings", "update", "updates", "setup", "home"),
    ),
)

internal fun matchQuickActions(query: String): List<QuickSearchAction> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    return QUICK_SEARCH_ACTIONS.filter { a ->
        a.label.lowercase().contains(q) || a.keywords.any { it.startsWith(q) || it.contains(q) }
    }
}

@Composable
private fun QuickActionSearchRow(
    action: QuickSearchAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LIST_ROW.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(HoneycombColors.button),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(action.icon, HoneycombColors.label, 20.dp)
        }
        BasicText(
            text = action.label,
            style = AppRowLabelStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * watchOS / iOS-style strictly alphabetical A–Z List View:
 *   - Edge-to-edge scrolling all the way behind the translucent search pill and native top status-bar clock (no opaque top block).
 *   - Contacts-style right-edge A–Z fast-scroll scrubber with haptic ticks and floating section bubble.
 */
@Composable
fun AppListScreen(
    apps: List<App>,
    @Suppress("UNUSED_PARAMETER") order: List<String>,
    rowSteps: Flow<Int>,
    @Suppress("UNUSED_PARAMETER") editing: Boolean,
    @Suppress("UNUSED_PARAMETER") onEditing: (Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOrder: (List<String>) -> Unit,
    pickFor: Int?,
    onPicked: (String) -> Unit,
    onOpen: (ComponentName, android.graphics.Rect?) -> Unit,
    onGridView: (() -> Unit)? = null,
    onOpenBlock: (() -> Unit)? = null,
    onOpenControl: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    retainScroll: () -> Boolean = { false },
    searchFocusKey: Int = 0,
    onSearchFocused: () -> Unit = {},
) {
    val sortedApps = remember(apps) { alphabeticalApps(apps) }
    // Preserve the viewport if Android reclaims Home behind an app. A fresh list still starts at its top.
    val state = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.listSaver(
        save = { s: androidx.compose.foundation.lazy.LazyListState ->
            if (retainScroll()) listOf(s.firstVisibleItemIndex, s.firstVisibleItemScrollOffset) else emptyList()
        },
        restore = { androidx.compose.foundation.lazy.LazyListState(it[0], it[1]) },
    )) { androidx.compose.foundation.lazy.LazyListState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val visible = remember(trimmed, sortedApps) { filterApps(sortedApps, trimmed) }
    val quickMatches = remember(trimmed, pickFor) { if (pickFor == null) matchQuickActions(trimmed) else emptyList() }
    val availableLetters = remember(sortedApps) { sortedApps.map { sectionLetter(it.label) }.toSet() }
    val activeLetter = remember(state.firstVisibleItemIndex, visible) {
        visible.getOrNull((state.firstVisibleItemIndex - 1).coerceAtLeast(0))?.let { sectionLetter(it.label) }
    }
    val px = LocalDensity.current.density
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val listDragging by state.interactionSource.collectIsDraggedAsState()
    var pullSearchKey by remember { mutableIntStateOf(0) }
    val listPullToSearch = rememberPullToSearchNestedScroll(
        enabled = { true },
        isAtTop = { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 },
        isDragging = listDragging,
        onPullToSearch = { pullSearchKey++ },
        onDismissKeyboard = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        },
    )
    LaunchedEffect(state, rowSteps, px) { rowSteps.collect { state.scrollBy(-it * LIST_ROW * px) } }
    LaunchedEffect(searchFocusKey) { if (searchFocusKey > 0) state.scrollToItem(0) }
    val switchButton = onGridView != null && pickFor == null
    val showScrubber = trimmed.isEmpty() && visible.size >= 6
    androidx.activity.compose.BackHandler(enabled = query.isNotEmpty()) { query = "" }
    Backdrop(Modifier.testTag("app-list")) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(listPullToSearch),
                state = state,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = statusBarInset + 8.dp,
                    bottom = scrollBottomInset + 28.dp,
                ),
            ) {
                item(key = "search") {
                    AppSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        onSubmit = {
                            visible.firstOrNull()?.let { app ->
                                val key = app.component.flattenToString()
                                query = ""
                                if (pickFor != null) onPicked(key) else onOpen(app.component, null)
                            }
                        },
                        focusKey = searchFocusKey + pullSearchKey,
                        onFocused = {
                            pullSearchKey = 0
                            onSearchFocused()
                        },
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                            .testTag("app-search"),
                    )
                }
                itemsIndexed(visible, key = { _, a -> a.component.flattenToString() }) { _, app ->
                    val key = app.component.flattenToString()
                    var bounds by remember { mutableStateOf(android.graphics.Rect()) }
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .height(LIST_ROW.dp)
                            .testTag("row-$key")
                            .semantics { contentDescription = app.label }
                            .pointerInput(key, pickFor, trimmed) {
                                detectTapGestures {
                                    if (pickFor != null) {
                                        query = ""
                                        onPicked(key)
                                    } else {
                                        query = ""
                                        onOpen(app.component, bounds)
                                    }
                                }
                            }
                            .padding(start = 8.dp, end = if (showScrubber) 26.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
                    ) {
                        AppDisc(
                            app.icon,
                            42f,
                            Modifier.onGloballyPositioned { c ->
                                val r = c.boundsInWindow()
                                bounds = android.graphics.Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                            },
                        )
                        BasicText(
                            app.label,
                            style = AppRowLabelStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (quickMatches.isNotEmpty()) {
                    itemsIndexed(quickMatches, key = { _, a -> "quick-${a.id}" }) { _, action ->
                        QuickActionSearchRow(
                            action = action,
                            onClick = {
                                query = ""
                                when (action.id) {
                                    "block" -> onOpenBlock?.invoke()
                                    "control" -> onOpenControl?.invoke()
                                    "settings" -> onOpenSettings?.invoke()
                                }
                            },
                            modifier = Modifier.testTag("search-quick-${action.id}"),
                        )
                    }
                }
                if (visible.isEmpty() && quickMatches.isEmpty() && trimmed.isNotEmpty()) {
                    item(key = "no-matches") {
                        SettingsNote(
                            "No apps matching \"$trimmed\"",
                            Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        )
                    }
                }
                if (switchButton && trimmed.isEmpty()) item(key = "grid-view") {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ViewSwitchButton(showingGrid = false, onSwitch = onGridView!!)
                        if (onOpenBlock != null) {
                            CapsuleButton(
                                text = "Block",
                                onClick = onOpenBlock,
                                modifier = Modifier.testTag("apps-block"),
                                icon = Symbols.block,
                                height = 40.dp,
                            )
                        }
                        if (onOpenSettings != null) {
                            CircleButton(
                                icon = Symbols.settings,
                                contentDescription = "Honeycomb settings",
                                onClick = onOpenSettings,
                                modifier = Modifier.testTag("apps-settings"),
                                size = 40.dp,
                            )
                        }
                    }
                }
            }

            if (showScrubber) {
                AlphabetScrubber(
                    activeLetter = activeLetter,
                    availableLetters = availableLetters,
                    onScrubLetter = { letter ->
                        firstIndexForSection(visible, letter)?.let { targetIdx ->
                            scope.launch { state.scrollToItem(targetIdx + 1) }
                        }
                    },
                    modifier = Modifier
                        .padding(top = statusBarInset + 16.dp, bottom = scrollBottomInset + 16.dp)
                        .testTag("app-list-scrubber"),
                )
            }
        }
    }
}

// ---- the all-apps grid --------------------------------------------------------------------------------------------

/** watchOS 27's full grid is measured at 422 px wide; Honeycomb's frame is 360 units. */
const val VIEW_K = FRAME_W / 422f

/**
 * Full app grid: staggered 3-4-3-4 Honeycomb grid on LazyColumn with most-used apps at the top.
 * Tap an icon to launch; press and hold an icon for quick actions (Move to top, Don't recommend).
 */
@Composable
fun AppGridScreen(
    apps: List<App>,
    order: List<String>,
    rowSteps: Flow<Int>,
    hidden: Set<String> = emptySet(),
    editing: Boolean = false,
    onEditing: (Boolean) -> Unit = {},
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onMoveToTop: (String) -> Unit = {},
    onDontRecommend: (String) -> Unit = {},
    onRecommend: (String) -> Unit = {},
    onListView: () -> Unit,
    onOpen: (ComponentName, android.graphics.Rect?) -> Unit,
    onOpenBlock: (() -> Unit)? = null,
    onOpenControl: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    searchFocusKey: Int = 0,
    onSearchFocused: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val byKey = remember(apps) { apps.associateBy { it.component.flattenToString() } }
    val shown = remember(order, byKey) { order.mapNotNull { byKey[it] } }
    var menuApp by remember { mutableStateOf<App?>(null) }
    var query by remember { mutableStateOf("") }
    var pullSearchKey by remember { mutableIntStateOf(0) }
    val trimmed = query.trim()
    val matches = remember(trimmed, shown) {
        if (trimmed.isEmpty()) emptyList() else filterApps(shown, trimmed)
    }
    val quickMatches = remember(trimmed) { matchQuickActions(trimmed) }
    androidx.activity.compose.BackHandler(enabled = menuApp != null) { menuApp = null }
    androidx.activity.compose.BackHandler(enabled = menuApp == null && query.isNotEmpty()) { query = "" }
    val searchHeader: @Composable () -> Unit = {
        AppSearchBar(
            query = query,
            onQueryChange = { query = it },
            onSubmit = {
                matches.firstOrNull()?.let { app ->
                    query = ""
                    onOpen(app.component, null)
                }
            },
            focusKey = searchFocusKey + pullSearchKey,
            onFocused = {
                pullSearchKey = 0
                onSearchFocused()
            },
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)
                .testTag("app-search"),
        )
    }
    Backdrop(Modifier.testTag("app-grid")) {
        if (shown.isEmpty()) return@Backdrop
        Box(
            Modifier.align(Alignment.Center)
                .requiredSize(if (expandedGridWindow()) LocalScreen.current.width.dp else FRAME_W.dp, LocalScreen.current.height.dp),
        ) {
            WatchGrid(
                icons = shown.map { it.icon },
                labels = shown.map { it.label },
                keys = shown.map { it.component.flattenToString() },
                crownKeys = rowSteps,
                onTap = { i, from -> onOpen(shown[i].component, from) },
                onListView = onListView,
                onOpenBlock = onOpenBlock,
                onOpenSettings = onOpenSettings,
                topPadding = statusBarInset + 8.dp,
                onLongPress = { i -> menuApp = shown.getOrNull(i) },
                onMove = onMove,
                editing = editing,
                onEditing = onEditing,
                header = searchHeader,
                headerScrollKey = searchFocusKey,
                onPullToSearch = { pullSearchKey++ },
                overrideBody = if (trimmed.isEmpty()) null else {
                    {
                        items(
                            count = quickMatches.size,
                            key = { idx -> "quick-${quickMatches[idx].id}" },
                            span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                        ) { idx ->
                            val action = quickMatches[idx]
                            QuickActionSearchRow(
                                action = action,
                                onClick = {
                                    query = ""
                                    when (action.id) {
                                        "block" -> onOpenBlock?.invoke()
                                        "control" -> onOpenControl?.invoke()
                                        "settings" -> onOpenSettings?.invoke()
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 6.dp).testTag("search-quick-${action.id}"),
                            )
                        }
                        items(
                            count = matches.size,
                            key = { idx -> matches[idx].component.flattenToString() },
                            span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                        ) { idx ->
                            val app = matches[idx]
                            AppSearchRow(
                                label = app.label,
                                icon = app.icon,
                                onClick = {
                                    query = ""
                                    onOpen(app.component, null)
                                },
                                modifier = Modifier.padding(horizontal = 6.dp).testTag("search-hit-${app.component.flattenToString()}"),
                            )
                        }
                        if (matches.isEmpty() && quickMatches.isEmpty()) {
                            item(key = "no-matches", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                SettingsNote(
                                    "No apps matching \"$trimmed\"",
                                    Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        menuApp?.let { app ->
            val key = app.component.flattenToString()
            val isHidden = key in hidden
            val actions = buildList {
                add(MenuAction("Move to top", Symbols.arrowUp, "menu-move-top") {
                    menuApp = null; onEditing(false); onMoveToTop(key)
                })
                add(MenuAction(if (editing) "Done rearranging" else "Rearrange grid", Symbols.apps, "menu-rearrange-grid") {
                    menuApp = null; onEditing(!editing)
                })
                add(MenuAction("App info", Symbols.list, "menu-app-info") {
                    menuApp = null; onEditing(false)
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", app.component.packageName, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
                if (isHidden) {
                    add(MenuAction("Recommend again", Symbols.back, "menu-recommend") {
                        menuApp = null; onRecommend(key)
                    })
                } else {
                    add(MenuAction("Don't recommend", Symbols.close, "menu-dont-recommend", destructive = true) {
                        menuApp = null; onDontRecommend(key)
                    })
                }
            }
            ActionMenuSheet(
                title = app.label,
                icon = app.icon,
                actions = actions,
                onDismiss = { menuApp = null },
                modifier = Modifier.testTag("grid-app-menu"),
            )
        }
    }
}

/**
 * A tap, or a touch and hold, that a drag cancels: unlike detectTapGestures, a swipe across the face (that nothing
 * else took) is not read as a tap.
 */
fun Modifier.tapOrHold(onTap: () -> Unit, onHold: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            var moved = false
            var lifted = false
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (c.isConsumed) { moved = true; break }
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!c.pressed) { lifted = true; if (!moved) c.consume(); break }
                }
                true
            }
            when {
                moved -> Unit
                lifted -> tap()
                ended == null -> { // held still past the long-press timeout
                    hold()
                    while (true) { val e = awaitPointerEvent(); e.changes.forEach { it.consume() }; if (e.changes.none { it.pressed }) break }
                }
            }
        }
    }
}
