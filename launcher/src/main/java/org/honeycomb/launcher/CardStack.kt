package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt

/*
 * One card stack for the Smart Stack and Notification Centre, as iOS stacks a group of notifications: collapsed it is a
 * pile, the front card full size and the next two peeking below it, each a little narrower and lower; a tap on the pile
 * or a drag down on it springs it open into a list of every card, with a header holding "Show Less"; "Show Less" or a
 * swipe up on the open list springs it shut. Expanding is one progress p (0 pile, 1 list) driven by a spring, and every
 * card's place is a function of p, so the cards fan out and back together.
 */

/** How far each of the two cards behind the front one peeks below it, and how much narrower each is (iOS's pile). */
const val PILE_PEEK = 10f
const val PILE_NARROW = 0.05f

/**
 * iOS 26's notification-stack spring: a group opens in about 8 frames at 30 fps (about 270 ms, first movement to
 * settled, measured from a screen recording, card-stack/ios-ref/), which is Motion's snappy spring (0.3 s, 0.85).
 */
fun <T> pileSpring() = Motion.snappy.spec<T>()

/**
 * Card [i]'s top at progress [p], for cards [h] tall (each its own height). Collapsed, each card behind the front one ends
 * [PILE_PEEK] below the one before it (the first three; the rest hide behind): it takes the front card's height, or with
 * a [sliver] height it is that short, as iOS 27 shows the notifications behind the front one as one-line slivers. Open,
 * one under another [gap] apart below the [header].
 */
fun pileTop(i: Int, p: Float, h: List<Float>, gap: Float, header: Float, peek: Float = PILE_PEEK, sliver: Float = 0f, levels: Int = 2): Float {
    val closed = if (i == 0) 0f else h[0] + peek * i.coerceAtMost(levels) - pileCardHeight(i, 0f, h, sliver)
    val open = header + (0 until i).sumOf { (h[it] + gap).toDouble() }.toFloat()
    return closed + (open - closed) * p
}

/** Card [i]'s drawn height at progress [p]: piled, the front card's height or the [sliver] height; its own once open. */
fun pileCardHeight(i: Int, p: Float, h: List<Float>, sliver: Float = 0f): Float {
    if (i == 0) return h[0]
    val closed = if (sliver > 0f) sliver else h[0]
    return closed + (h[i] - closed) * p
}

/** Card [i]'s width scale at progress [p]: 1, 0.95 and 0.90 in the pile, all full width open. */
fun pileScale(i: Int, p: Float): Float = 1f - PILE_NARROW * i.coerceAtMost(2) * (1f - p)

/** Card [i]'s width at progress [p] for a stack [w] wide whose piled cards narrow by [inset] a side per step (iOS 27's slivers). */
fun pileWidth(i: Int, p: Float, w: Float, inset: Float, levels: Int = 2): Float = w - 2f * inset * i.coerceAtMost(levels) * (1f - p)

/** Card [i]'s opacity at progress [p]: past the [levels] peeks behind the front card it shows only as the pile opens. */
fun pileAlpha(i: Int, p: Float, levels: Int = 2): Float = if (i <= levels) 1f else p.coerceIn(0f, 1f)

/** The stack's height at progress [p]: the front card and its peeks closed, the header and every card open. */
fun pileHeight(p: Float, h: List<Float>, gap: Float, header: Float, peek: Float = PILE_PEEK, levels: Int = 2): Float {
    if (h.isEmpty()) return 0f
    val closed = h[0] + peek * (h.size - 1).coerceAtMost(levels)
    val open = header + h.sum() + (h.size - 1) * gap
    return closed + (open - closed) * p
}

/** Where the Smart Stack's collapsed piles are on screen (root coordinates), so a drag down that starts on one opens it. */
object PileHits {
    val rects = androidx.compose.runtime.snapshots.SnapshotStateMap<String, androidx.compose.ui.geometry.Rect>()
    fun contains(at: androidx.compose.ui.geometry.Offset) = rects.values.any { it.contains(at) }
}

/**
 * A stack of [count] cards, each as tall as its content (at least [minH]). [expanded] is the open state, owned by the
 * caller; [onExpanded] asks to change it. A single card is never a pile. [title] names the group in the open header;
 * [actions] adds the group's own buttons beside "Show Less" (Notification Centre's Clear). [hits] registers the
 * collapsed pile with [PileHits]. Sizes are in the caller's dp.
 * With a [sliver] composable the stack is iOS 27's notification pile: the cards behind the front one are [sliverH] tall,
 * [inset] narrower a side per step and end [peek] below the card before them, each showing [sliver]'s one-line preview,
 * which cross-fades into the full card as the pile opens. [levels] is how many peek out behind the front card (the watch
 * shows one). Without [sliver] (the Smart Stack) the peeks are scaled copies.
 */
@Composable
fun CardStack(
    count: Int,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    minH: Dp,
    modifier: Modifier = Modifier,
    gap: Dp = 10.dp,
    title: String = "",
    tag: String = "card-stack",
    hits: Boolean = false,
    actions: @Composable () -> Unit = {},
    peek: Dp = PILE_PEEK.dp,
    sliverH: Dp = 0.dp,
    inset: Dp = 0.dp,
    corner: Dp = 0.dp,
    levels: Int = 2,
    sliver: (@Composable BoxScope.(Int) -> Unit)? = null,
    card: @Composable BoxScope.(Int) -> Unit,
) {
    val p = remember { Animatable(if (expanded && count > 1) 1f else 0f) }
    val open = expanded && count > 1
    val reduce = HoneycombTheme.reduceMotion
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var releaseVelocity by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(open, dragging) {
        if (dragging) return@LaunchedEffect
        val target = if (open) 1f else 0f
        val v = releaseVelocity
        releaseVelocity = 0f
        if (reduce) {
            p.snapTo(target)
        } else {
            val spec = if (v != 0f) Motion.gesture.spec<Float>() else pileSpring<Float>()
            p.animateTo(target, spec, initialVelocity = v)
        }
    }
    if (hits) androidx.compose.runtime.DisposableEffect(tag) { onDispose { PileHits.rects.remove(tag) } }
    val headerDp = if (count > 1) ControlSize.regular else 0.dp
    androidx.compose.ui.layout.Layout(
        modifier = modifier.fillMaxWidth().testTag(tag)
            .then(if (hits) Modifier.onGloballyPositioned { c ->
                if (count > 1 && !open) PileHits.rects[tag] = c.boundsInRoot() else PileHits.rects.remove(tag)
            } else Modifier)
            .pointerInput(count, open) {
                if (count < 2) return@pointerInput
                val dragSpan = (minH.toPx() * 1.4f).coerceAtLeast(120f)
                // WWDC18 803 continuous finger-tracked pile expansion/collapse with rubber-banding and momentum projection.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var dy = 0f
                    var active = false
                    var startP = p.value
                    try {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.isConsumed && !active) break
                            tracker.addPosition(c.uptimeMillis, c.position)
                            dy += c.position.y - c.previousPosition.y
                            if (!active) {
                                val startOpen = !open && dy > viewConfiguration.touchSlop
                                val startClose = open && dy < -viewConfiguration.touchSlop && down.position.y < headerDp.toPx() * 1.6f
                                if (startOpen || startClose) {
                                    active = true
                                    dragging = true
                                    startP = p.value
                                }
                            }
                            if (active) {
                                c.consume()
                                val nextP = Motion.rubberBandClamp(startP + dy / dragSpan, 0f, 1f, stretch = 0.14f)
                                scope.launch { p.snapTo(nextP) }
                            }
                            if (!c.pressed) {
                                if (active) {
                                    val vy = tracker.calculateVelocity().y
                                    val dir = if (open) -1f else 1f
                                    val committed = EdgeSwipe.commits(
                                        travel = dy * dir,
                                        velocity = vy * dir,
                                        span = dragSpan,
                                        minDistance = viewConfiguration.touchSlop * 2f,
                                    )
                                    releaseVelocity = (vy / dragSpan).coerceIn(-8f, 8f)
                                    dragging = false
                                    if (committed) onExpanded(!open)
                                }
                                break
                            }
                        }
                    } finally {
                        if (active && dragging) {
                            releaseVelocity = 0f
                            dragging = false
                        }
                    }
                }
            },
        content = {
            // the open header: the group's name, its actions and Show less, fading in as the pile opens
            Row(Modifier.fillMaxWidth().graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }, verticalAlignment = Alignment.CenterVertically) {
                if (count > 1) {
                    BasicText(title, Modifier.weight(1f).padding(start = Space.xs), HoneycombType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.value > 0.01f) {
                        actions()
                        Spacer(Modifier.width(Space.s))
                        StackPill("Show Less", Modifier.testTag("$tag-less")) { onExpanded(false) }
                    }
                }
            }
            for (i in 0 until count) Box(Modifier.testTag("$tag-$i")) {
                if (sliver != null && i > 0) {
                    Box(Modifier.graphicsLayer { alpha = p.value.coerceIn(0f, 1f) }) { card(i) }
                    // only the strip below the card in front shows, as on iOS: the front card's glass never shows a sliver through it
                    val under = remember(sliverH, peek, inset, corner) { UnderFront(inset, sliverH - peek, corner) }
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = (1f - p.value).coerceIn(0f, 1f); clip = true; shape = under }) { sliver(i) }
                } else card(i)
                // collapsed, a tap anywhere on the pile (the front card or a peek) opens it rather than one card
                if (count > 1 && !open) Box(Modifier.matchParentSize()
                    .semantics { contentDescription = "Show $count" }
                    .pointerInput(Unit) { detectTapGestures { onExpanded(true) } })
            }
        },
    ) { measurables, c ->
        val w = c.maxWidth
        val pv = p.value.coerceIn(0f, 1.15f)
        val pvUnit = pv.coerceIn(0f, 1f)
        val minPx = minH.roundToPx()
        val cards = measurables.drop(1)
        val hs = cards.map { it.maxIntrinsicHeight(w).coerceAtLeast(minPx).toFloat() }
        val headerPx = headerDp.toPx() * pvUnit
        val gapPx = gap.toPx()
        val head = measurables[0].measure(androidx.compose.ui.unit.Constraints.fixed(w, headerDp.roundToPx().coerceAtLeast(0)))
        val sliverPx = if (sliver != null) sliverH.toPx() else 0f
        val insetPx = if (sliver != null) inset.toPx() else 0f
        val ws = cards.indices.map { i -> if (sliver != null) pileWidth(i, pvUnit, w.toFloat(), insetPx, levels).roundToInt() else w }
        val placed = cards.mapIndexed { i, m -> m.measure(androidx.compose.ui.unit.Constraints.fixed(ws[i], pileCardHeight(i, pvUnit, hs, sliverPx).roundToInt().coerceAtLeast(0))) }
        val peekPx = peek.toPx()
        val total = pileHeight(pv, hs, gapPx, headerPx, peekPx, levels).roundToInt().coerceAtLeast(minPx)
        layout(w, total) {
            if (count > 1) head.place(0, 0)
            for (i in placed.indices.reversed()) {
                val a = pileAlpha(i, pv, levels)
                if (a <= 0f) continue
                val y = pileTop(i, pv, hs, gapPx, headerPx, peekPx, sliverPx, levels)
                placed[i].placeWithLayer((w - ws[i]) / 2, y.roundToInt(), zIndex = -i.toFloat()) {
                    val s = if (sliver != null) 1f else pileScale(i, pv)
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    alpha = a
                }
            }
        }
    }
}

/** The open stack's "Show Less": a compact inline capsule so the group's name beside it keeps its full width. */
@Composable
fun StackPill(text: String, modifier: Modifier = Modifier, onTap: () -> Unit) {
    CapsuleButton(text, onTap, modifier, icon = Symbols.showLess, height = ButtonSize.compact)
}

/** A piled sliver minus the card in front of it: that card is [inset] wider a side and ends [top] below the sliver's top. */
private class UnderFront(val inset: Dp, val top: Dp, val corner: Dp) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val i = with(density) { inset.toPx() }; val t = with(density) { top.toPx() }; val r = with(density) { corner.toPx() }
        val all = androidx.compose.ui.graphics.Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
        val front = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(-i, -size.height, size.width + i, t, androidx.compose.ui.geometry.CornerRadius(r)))
        }
        return androidx.compose.ui.graphics.Outline.Generic(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, all, front))
    }
}
