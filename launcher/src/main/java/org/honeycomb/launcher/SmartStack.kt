package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space
import androidx.compose.ui.unit.Dp

import android.appwidget.AppWidgetManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap

/*
 * The Smart Stack, built the way watchOS builds it and from Android's own parts: Live Updates first (Android 16's promoted
 * ongoing notifications, as the watch's stack shows Live Activities), then what is playing (the active media session),
 * then only the widgets he added himself through Edit (real app widgets, hosted with AppWidgetHost; the stack never
 * seeds any), and Edit at the bottom to add, remove and reorder them. No date or clock sits over the stack, as neither
 * iOS's widget page nor Pixel Launcher's puts one there: the status bar and the face already show the time (sizes are
 * measured in 04-smart-stack-top and 05-smart-stack-lower in points and set in wp).
 * With nothing live, playing or added, the stack is only Edit, centred; no placeholder card.
 * Live Updates and what is playing are each their own open card; the widgets are one CardStack pile (CardStack.kt, the
 * same stack Notification Centre uses) that a tap or a drag down opens into a list. The page scrolls; a swipe down
 * from its top, or back, returns to the face.
 */

sealed interface StackItem {
    data class Live(val key: String) : StackItem
    data object Playing : StackItem
    data class Widget(val id: Int) : StackItem
    data object Edit : StackItem
}

/** Live Updates first, now playing second, then the widgets in their saved order; Edit always closes the stack. */
fun stackItems(live: List<String>, playing: Boolean, widgets: List<Int>): List<StackItem> =
    live.map { StackItem.Live(it) } + listOfNotNull(StackItem.Playing.takeIf { playing }) +
        widgets.map { StackItem.Widget(it) } + StackItem.Edit


/** A Live Update's and Now Playing's card height in wp (160 dp at 349 dp wide), and the gap between cards. */
internal const val CARD_H = 106.5f
internal const val GAP = 8f
/** The tallest a widget's card grows, in wp (280 dp at 349 dp wide), so a widget never stretches past what it lays out for. */
internal const val CARD_MAX = 186.5f

/**
 * The least a widget's card is tall, in px: uses its declared height (minHeight, else minResizeHeight),
 * floored to [minPx] so 1-row widgets (like Apple Music's 30 dp pill) never collapse into a thin strip,
 * and capped by [maxPx].
 */
/**
 * Standard Android portrait launcher cell calculation (developer.android.com/develop/ui/views/appwidgets/layouts):
 * Android widget `minHeight` encodes LANDSCAPE cell height (~50 dp per cell: `70n - 16`), whereas portrait cells are
 * ~104 dp tall (`1 row = 104 dp`, `2 rows = 216 dp`, `3 rows = 328 dp`). Honors Android 12+ `targetCellHeight` first,
 * with optional user row override from Edit Smart Stack.
 */
internal fun defaultWidgetRows(targetCellHeight: Int, minHeightPx: Int, realDensity: Float): Int {
    if (targetCellHeight > 0) return targetCellHeight.coerceIn(1, 4)
    val minDp = minHeightPx / realDensity.coerceAtLeast(1f)
    return kotlin.math.ceil((minDp + 16f) / 70f).toInt().coerceIn(1, 4)
}

internal fun portraitWidgetHeightPx(rows: Int, realDensity: Float): Int {
    val r = rows.coerceIn(1, 4)
    val dp = r * 104f + (r - 1) * 8f
    return (dp * realDensity).toInt().coerceAtLeast(1)
}

internal fun widgetHeight(minHeight: Int, minResizeHeight: Int, maxResizeHeight: Int, fallbackPx: Int, maxPx: Int, minPx: Int = 1): Int {
    val default = minHeight.takeIf { it > 0 } ?: minResizeHeight.takeIf { it > 0 } ?: fallbackPx
    val capped = if (maxResizeHeight > 0) minOf(default, maxResizeHeight) else default
    return maxOf(capped, minPx).coerceIn(1, maxPx)
}

/** Whether widgets [h] tall (wp, each its own height) all fit the [room] (wp) as a list. */
fun widgetsFit(h: List<Float>, room: Float): Boolean = h.isNotEmpty() && h.sum() + (h.size - 1) * GAP <= room

/** When widgets [h] tall (wp) do not fit the [room], how many lead as a list before the rest pile in the last row (the pile keeps its two peeks). */
fun widgetsBeforePile(h: List<Float>, room: Float): Int {
    val n = h.size
    var lead = 0
    while (lead < n - 2) {
        val pile = h.drop(lead + 1).max() + 2 * PILE_PEEK
        if (h.take(lead + 1).sum() + (lead + 1) * GAP + pile > room) break
        lead++
    }
    return lead
}

@Composable
fun SmartStack(onTop: (Boolean) -> Unit, onOpenMedia: (String) -> Unit) {
    val ctx = LocalContext.current
    var ms by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { ms = System.currentTimeMillis(); kotlinx.coroutines.delay(1000 - ms % 1000) } }
    val media by Providers.media.collectAsState()
    val posted by MediaListener.Defaults.posted.collectAsState()
    val connected by MediaListener.Defaults.changes.collectAsState()
    val live = remember(posted, connected) { liveUpdates(ctx) }
    val changes by Widgets.changes.collectAsState()
    val mgr = remember { AppWidgetManager.getInstance(ctx) }
    val widgets = remember(changes) { Widgets.ids(ctx).filter { mgr.getAppWidgetInfo(it) != null } }
    // Avoid duplicating a music player when the user already pinned a widget for the active media app (e.g. Apple Music / Spotify).
    val widgetPackages = remember(widgets) { widgets.mapNotNull { mgr.getAppWidgetInfo(it)?.provider?.packageName }.toSet() }
    val showNowPlaying = media?.let { it.pkg !in widgetPackages } ?: false
    // the stack never adds a widget on its own: the only bind requests are the ones he starts from Edit
    val add = rememberWidgetAdder()
    // stacks seeded before wl-live-stack still hold the phone's Calendar and Clock widgets; take those out once
    LaunchedEffect(Unit) { Widgets.dropSeeded(ctx) }
    var editing by remember { mutableStateOf(false) }
    val items = stackItems(live.map { it.key }, showNowPlaying, widgets)
    HoneycombTheme { Backdrop(Modifier.testTag("smart-stack")) {
        // Subtle blurred live watch face behind the Smart Stack, matching watchOS
        BlurredFace(opacity = 0.30f)
        // laid out in wp like every screen (design/Scale.kt); FrameSize keeps the 48 dp minimum touch target
        val scroll = rememberScrollState()
        LaunchedEffect(scroll) { snapshotFlow { scroll.value == 0 }.collect(onTop) }
        Box(Modifier.fillMaxSize()) {
            val ws = items.filterIsInstance<StackItem.Widget>()
            // Each widget gets its true Android portrait cell height (104 real dp/row) so Glance/Material You widgets
            // (Apple Music, Spotify, Weather, Calendar, Clock) render their full portrait layout instead of cramped landscape strips.
            val realDensity = ctx.resources.displayMetrics.density
            val heights = remember(ws, changes, realDensity) {
                val fallback = portraitWidgetHeightPx(2, realDensity)
                ws.associate { w ->
                    val info = mgr.getAppWidgetInfo(w.id)
                    val rows = Widgets.customRows(ctx, w.id)
                        ?: info?.let { defaultWidgetRows(it.targetCellHeight, it.minHeight, realDensity) }
                        ?: 2
                    w.id to (if (info != null) portraitWidgetHeightPx(rows, realDensity) else fallback)
                }
            }
            Column(Modifier.fillMaxSize().softTopScrollFade(clearTop = statusBarInset, feather = 12.dp).verticalScroll(scroll).padding(top = statusBarInset + StackTokens.top, bottom = scrollBottomInset).padding(horizontal = StackTokens.side), verticalArrangement = Arrangement.spacedBy(GAP.wp)) {
                // Date and time header at the top of the Smart Stack, matching watchOS
                DateClock(ms, Modifier.padding(bottom = StackTokens.clockGap - GAP.wp).testTag("stack-time"))
                if (items.size == 1) {
                    // Nothing live, playing, or added yet: show a clean card instead of an empty void
                    Platter(Modifier.fillMaxWidth().testTag("stack-empty").pointerInput(Unit) { detectTapGestures { editing = true } }, padding = Space.l) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            BasicText("Smart Stack", style = HoneycombType.headline.copy(color = HoneycombColors.label))
                            BasicText("Live updates, now playing, and your widgets show up here. Tap to add widgets.",
                                style = HoneycombType.body.copy(color = HoneycombColors.secondaryLabel))
                        }
                    }
                } else {
                    // Live Updates and what is playing: each its own open card on top
                    items.forEach { item ->
                        when (item) {
                            is StackItem.Live -> live.firstOrNull { it.key == item.key }?.let { u -> Box(Modifier.fillMaxWidth().height(CARD_H.wp)) { LiveCard(u, ms) } }
                            StackItem.Playing -> media?.let { m -> MediaCard(m) { onOpenMedia(m.pkg) } }
                            else -> {}
                        }
                    }
                    // Every widget sits flat directly in the scrolling stack — never nested inside a pile or double card
                    ws.forEachIndexed { i, w ->
                        key(w.id) { Box(Modifier.fillMaxWidth().testTag("widget-$i")) { WidgetCard(w.id, heights.getValue(w.id)) } }
                    }
                }
                EditButton(Modifier.padding(top = StackTokens.editGap - GAP.wp)) { editing = true }
                Spacer(Modifier.height(StackTokens.bottom - GAP.wp))
            }
        }
    } }
    if (editing) StackEditor(widgets, onAdd = { add(listOf(it)) }, onClose = { editing = false })
}

/** A card: a [Platter], tappable when [onTap] is set (spec §2, Smart Stack cards). */
@Composable
private fun Card(tag: String, onTap: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) =
    Platter(Modifier.fillMaxSize().testTag(tag)
        .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { onTap() } }), padding = 0.dp, content = content)

/** A card's label: footnote, semibold, in the card's accent (WWDC23 10309's Smart Stack type). */
private fun label(accent: Color) = HoneycombType.footnote.copy(color = accent, fontWeight = HoneycombType.headline.fontWeight)

/**
 * A Live Update as watchOS shows a Live Activity in its Smart Stack: the app's icon on a disc in its colour and the title
 * on the first line, then its one live value large (a running chronometer, else the short critical text, else the text),
 * then its progress when it has one.
 */
@Composable
internal fun LiveCard(u: LiveUpdate, ms: Long) = Card("live-${u.pkg}", onTap = { runCatching { u.tap?.send() } }) {
    val ctx = LocalContext.current
    val icon = remember(u.key) { runCatching { u.icon?.loadDrawable(ctx)?.toBitmap(48, 48)?.asImageBitmap() }.getOrNull() }
    val thumb = remember(u.key) { runCatching { u.largeIcon?.loadDrawable(ctx)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
    val accent = if (u.color != 0) Color(u.color).copy(alpha = 1f) else HoneycombColors.accent
    Column(Modifier.fillMaxSize().padding(Space.m).semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(StackTokens.appIcon).clip(Circle).background(accent).testTag("live-icon"), contentAlignment = Alignment.Center) {
                icon?.let { Image(it, u.app, Modifier.size(StackTokens.appGlyph), colorFilter = ColorFilter.tint(HoneycombColors.label)) }
            }
            Spacer(Modifier.width(Space.s))
            if (u.hasShortMetric) {
                BasicText(u.title, Modifier.weight(1f).testTag("live-title"), style = HoneycombType.headline.copy(color = HoneycombColors.label), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                BasicText(u.app, Modifier.weight(1f), style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            thumb?.let {
                Spacer(Modifier.width(Space.s))
                Image(it, contentDescription = null, modifier = Modifier.size(34.dp).clip(Circle))
            }
        }
        Spacer(Modifier.weight(1f))
        if (u.hasShortMetric) {
            u.value(ms)?.let {
                BasicText(it, Modifier.testTag("live-value"), style = Display.numerals(StackTokens.liveValue * Wp.unit).copy(color = accent),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            BasicText(u.title, Modifier.testTag("live-title"), style = HoneycombType.title.copy(color = HoneycombColors.label),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            u.text?.let {
                BasicText(it, Modifier.padding(top = 2.dp).testTag("live-value"), style = HoneycombType.body.copy(color = HoneycombColors.secondaryLabel),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        u.progress?.let { p ->
            Spacer(Modifier.height(Space.s))
            Box(Modifier.fillMaxWidth().height(StackTokens.progress).clip(Capsule).background(HoneycombColors.button).testTag("live-progress")) {
                Box(Modifier.fillMaxWidth(p).height(StackTokens.progress).clip(Capsule).background(accent))
            }
        }
    }
}

/** Now playing, from the active media session: album art, title, artist, and live playback controls. */
@Composable
internal fun MediaCard(m: Media, onTap: () -> Unit) {
    val ctx = LocalContext.current
    NowPlayingCard(
        title = m.title,
        artist = m.artist,
        appName = m.appName,
        playing = m.playing,
        art = m.art,
        onOpen = onTap,
        onPrevious = { skipPrevious(ctx, m.pkg) },
        onPlayPause = { playPause(ctx, m.pkg, m.playing) },
        onNext = { skipNext(ctx, m.pkg) },
        modifier = Modifier.testTag("now-playing"),
    )
}

/**
 * One user widget hosted directly via AppWidgetHost with no outer platter, border, clip, or inset padding —
 * each widget renders its own shape, corners, and background on the stack just like on Pixel Launcher.
 */
@Composable
private fun WidgetCard(id: Int, height: Int) {
    val ctx = LocalContext.current
    val info = remember(id) { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) } ?: return
    Box(
        Modifier
            .fillMaxWidth()
            .testTag("widget-$id"),
    ) {
        AndroidView(
            factory = { c ->
                val host = Widgets.host(c).createView(c, id, info).apply {
                    setPadding(0, 0, 0, 0)
                    var told = 0
                    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                        val wPx = r - l
                        val hPx = b - t
                        val size = wPx * 100_000 + hPx
                        if (wPx > 0 && hPx > 0 && size != told) {
                            told = size
                            tellSize(c, id, wPx, hPx)
                        }
                    }
                }
                WidgetFrame(c, host, height)
            },
            update = { frame ->
                if (frame.targetHeightPx != height) {
                    frame.targetHeightPx = height
                    frame.requestLayout()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Measures the hosted widget at its exact portrait cell height [targetHeightPx] so `match_parent` RemoteViews fill cleanly. */
private class WidgetFrame(c: android.content.Context, private val host: android.view.View, var targetHeightPx: Int) :
    android.widget.FrameLayout(c) {
    init { addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val exactH = android.view.View.MeasureSpec.makeMeasureSpec(targetHeightPx, android.view.View.MeasureSpec.EXACTLY)
        host.measure(widthSpec, exactH)
        setMeasuredDimension(android.view.View.MeasureSpec.getSize(widthSpec), targetHeightPx)
    }
}

/**
 * Reports exact portrait dp dimensions directly via AppWidgetManager.updateAppWidgetOptions without subtracting
 * AppWidgetHostView's legacy 16dp default padding, so responsive Android 12–16 widgets pick their full portrait layout.
 */
private fun tellSize(ctx: android.content.Context, appWidgetId: Int, wPx: Int, hPx: Int) {
    val dp = ctx.resources.displayMetrics.density.coerceAtLeast(1f)
    val wDp = (wPx / dp).coerceAtLeast(1f)
    val hDp = (hPx / dp).coerceAtLeast(1f)
    val mgr = AppWidgetManager.getInstance(ctx)
    val sizes = arrayListOf(android.util.SizeF(wDp, hDp))
    val old = mgr.getAppWidgetOptions(appWidgetId)
    if (old.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) == wDp.toInt() &&
        old.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) == hDp.toInt()
    ) return
    val opts = android.os.Bundle(old).apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp.toInt())
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
    }
    mgr.updateAppWidgetOptions(appWidgetId, opts)
}

/** Edit, under the last card: a clean, compact capsule instead of a huge full-width bar. */
@Composable
private fun EditButton(modifier: Modifier, onTap: () -> Unit) = Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    CapsuleButton("Edit", onTap, Modifier.testTag("stack-edit"), height = 40.dp, minWidth = 96.dp)
}

/**
 * Apple Watch Smart Stack header: 3-line stacked uppercase date (TUE / NOV / 24) on the left,
 * tall condensed watchOS digital time (5:22) spanning the same height on the right.
 */
@Composable
private fun DateClock(ms: Long, modifier: Modifier) = Row(
    modifier.fillMaxWidth().padding(horizontal = StackTokens.dateInset).clearAndSetSemantics {
        contentDescription = java.text.SimpleDateFormat("EEEE, MMMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms))
    },
    verticalAlignment = Alignment.CenterVertically,
) {
    val dow = remember(ms / 60_000) {
        java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(ms)).uppercase()
    }
    val mon = remember(ms / 60_000) {
        java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(java.util.Date(ms)).uppercase()
    }
    val dayNum = remember(ms / 60_000) {
        java.text.SimpleDateFormat("d", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }
    val timeText = remember(ms / 60_000) { clock(ms) }
    Column {
        BasicText(dow, style = Display.stackDate(HoneycombColors.label))
        BasicText(mon, style = Display.stackDate(HoneycombColors.label))
        BasicText(dayNum, style = Display.stackDate(HoneycombColors.secondaryLabel))
    }
    Spacer(Modifier.weight(1f))
    BasicText(timeText, style = Display.stackTime(HoneycombColors.label), maxLines = 1, softWrap = false)
}

/** The Smart Stack's own sizes. */
private object StackTokens {
    /** A Live Update's app icon disc beside its title, and the glyph on it. */
    val appIcon: Dp get() = 20.wp
    val appGlyph: Dp get() = 13.wp
    /** A Live Update's live value (its countdown, its short text), large as a Live Activity's. */
    const val liveValue = 30f
    /** A Live Update's progress track. */
    val progress: Dp get() = 4.wp
    /** Sleek Edit capsule under the last card (compact pill, not a giant billboard). */
    val editWidth: Dp get() = 64.wp
    val editHeight: Dp get() = 24.wp
    /** From the last card to Edit; under Edit to the screen's end. */
    val editGap: Dp get() = 14f.wp
    val bottom: Dp get() = 20f.wp
    /** The cards' inset from the screen's sides: 7 pt. */
    val side: Dp get() = 7.wp
    /** Header clock & date dimensions. */
    val clock: Dp get() = 66f.wp
    val top: Dp get() = 8f.wp
    val clockGap: Dp get() = 14.wp
    val dateInset: Dp get() = 8f.wp
    /** Minimum height floor and per-cell height for hosted Android widgets so 1-row/2-row music widgets never squish. */
    val widgetMinH: Dp get() = 52.wp
    val widgetCellH: Dp get() = 52.wp
}
