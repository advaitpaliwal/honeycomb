package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/*
 * Notification Centre, laid out as watchOS 27's (watchos-sim/screens/07-notification-centre.png, 09-notification-long-look.png,
 * 422 x 514 px at 2x, so 211 pt wide; every size below is measured there in pt and set in wp): no big clock, only the time
 * at the top right; "Clear All" at the top of the scroll; each notification a platter with the app's icon disc over its
 * top-left corner, how long ago at the top right, the title and the body. An app's notifications pile as one stack
 * (CardStack.kt); a tap opens the pile, and a tap on a card opens its long look: the card at the top, x and bell-slash below.
 * It reads the notifications through the face's NotificationListenerService (MediaListener); with Quiet on they still
 * arrive here, silently. No dot or badge anywhere.
 */

class Note(val key: String, val pkg: String, val title: String, val text: String, val posted: Long)

/** "now", "12m ago", "1h ago", "2d ago", as the design writes it. */
fun ago(now: Long, then: Long): String {
    val m = ((now - then).coerceAtLeast(0) / 60_000)
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m ago"
        m < 24 * 60 -> "${m / 60}h ago"
        else -> "${m / (24 * 60)}d ago"
    }
}

/** What Notification Centre lists: not ongoing (media, calls in progress), not a group's summary, not our own; newest first. */
fun notes(all: List<StatusBarNotification>, self: String): List<Note> = all
    .filter { it.isClearable && it.packageName != self && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
    .mapNotNull { n ->
        val x = n.notification.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) null
        else Note(n.key, n.packageName, title.orEmpty(), text.orEmpty(), n.postTime)
    }
    .sortedByDescending { it.posted }

/** One app's notifications, newest first, as iOS groups them into a stack. */
class NoteGroup(val pkg: String, val notes: List<Note>)

/** Notes grouped per app, each group newest first and the groups ordered by their newest note. */
fun groups(notes: List<Note>): List<NoteGroup> = notes.groupBy { it.pkg }
    .map { (pkg, l) -> NoteGroup(pkg, l.sortedByDescending { it.posted }) }
    .sortedByDescending { it.notes.first().posted }

@Composable
fun NotificationCentre(onEnd: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val posted by MediaListener.Defaults.posted.collectAsState()
    val connected by MediaListener.Defaults.changes.collectAsState()
    val apps by AppCache.apps.collectAsState()
    val list = remember(posted, connected) {
        runCatching { notes(MediaListener.Defaults.live?.activeNotifications.orEmpty().toList(), ctx.packageName) }.getOrDefault(emptyList())
    }
    val icons = remember(list.map { it.pkg }.toSet()) { list.map { it.pkg }.toSet().associateWith { squareIcon(ctx, it) } }
    val ms = System.currentTimeMillis()
    val state = rememberLazyListState()
    // the long look: one notification opened from the list, by its key; it closes when that notification goes
    var look by remember { mutableStateOf<String?>(null) }
    val looked = list.firstOrNull { it.key == look }
    LaunchedEffect(state) { androidx.compose.runtime.snapshotFlow { !state.canScrollForward }.collect(onEnd) }
    BackHandler(enabled = looked != null) { look = null }
    HoneycombTheme { Backdrop(Modifier.testTag("notifications"), sheet = true) {
        if (looked != null) {
            BlurredFace(opacity = 0.32f)
            Frame { LongLook(looked, icons[looked.pkg], ms, onClose = { look = null }, onOpen = { look = null; open(ctx, looked) },
                onDismiss = { look = null; runCatching { MediaListener.Defaults.live?.cancelNotification(looked.key) } },
                onMute = { look = null; runCatching { MediaListener.Defaults.live?.snoozeNotification(looked.key, MUTE_MS) } }) }
            return@Backdrop
        }
        if (list.isEmpty()) {
            BasicText("No Notifications", Modifier.fillMaxWidth().at(0f, 190f + frameShift() + scrollTopInset.value),
                HoneycombType.headline.copy(color = HoneycombColors.secondaryLabel, textAlign = TextAlign.Center))
        }
        val labels = remember(apps) { apps.orEmpty().associate { it.component.packageName to it.label } }
        val open = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
        LazyColumn(Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = NcSize.side, end = NcSize.side, top = statusBarInset + 12.dp, bottom = scrollBottomInset + NcSize.gap)) {
            // the watch's "Clear All" at the top of the scroll
            if (list.isNotEmpty()) item(key = "clear-all") {
                CapsuleButton("Clear All", { runCatching { MediaListener.Defaults.live?.cancelAllNotifications() } },
                    Modifier.fillMaxWidth().padding(bottom = NcSize.gap).testTag("clear-all").semantics { contentDescription = "Clear all" },
                    height = ButtonSize.large)
            }
            // one stack per app (CardStack.kt): its newest card in front, the next two as platter edges below it; opened,
            // its header is the watch's: the app's name on the left and Show Less on the right, nothing else
            items(groups(list), key = { it.pkg }) { g ->
                CardStack(g.notes.size, open[g.pkg] == true, { open[g.pkg] = it }, NcSize.minHeight,
                    modifier = Modifier.padding(bottom = NcSize.gap), gap = NcSize.gap,
                    title = labels[g.pkg] ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(g.pkg, 0)).toString() }.getOrDefault(g.pkg), tag = "group-${g.pkg}",
                    peek = NcSize.pilePeek, sliverH = NcSize.pilePeek + NcSize.corner, inset = NcSize.pileInset, corner = NcSize.corner, levels = NcSize.pileLevels,
                    sliver = { _ -> Box(Modifier.fillMaxSize().surface(HoneycombColors.pileSliver, NcSize.shape, rim = false)) }) { i ->
                    NoteCard(g.notes[i], icons[g.pkg], ms, list = true) { look = g.notes[i].key }
                }
            }
        }
    } }
}

/**
 * One notification as the watch draws it: the app's icon disc over the platter's top-left corner, how long ago at the
 * top right in secondaryLabel, the title in headline and the body in body. In the [list] the text keeps clear of the
 * right side as the watch's does (07: "Your walk starts in / ten minutes."); in the long look it runs the full width.
 */
@Composable
private fun NoteCard(n: Note, icon: ImageBitmap?, ms: Long, list: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val above = if (list) NcSize.iconAbove else NcSize.lookIconAbove
    Box(modifier.fillMaxWidth()
        .semantics { contentDescription = n.title }
        .testTag("note")
        .pointerInput(n.key) { detectTapGestures { onTap() } }) {
        Platter(Modifier.padding(top = above).fillMaxWidth().heightIn(min = NcSize.minHeight - above), shape = NcSize.shape, padding = 0.dp) {
            // the time is placed on its own at the top right; the title starts below it at the watch's height
            BasicText(ago(ms, n.posted), Modifier.align(Alignment.TopEnd).padding(top = if (list) NcSize.timePad else NcSize.lookTimePad, end = NcSize.pad), time, maxLines = 1)
            Column(Modifier.fillMaxWidth().padding(start = NcSize.pad, end = NcSize.pad,
                top = if (list) NcSize.titleTop else NcSize.lookTitleTop, bottom = if (list) NcSize.bottomPad else NcSize.lookBottomPad)) {
                if (n.title.isNotEmpty()) BasicText(n.title, style = HoneycombType.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.text.isNotEmpty()) BasicText(n.text, style = HoneycombType.body, maxLines = if (list) 4 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            }
        }
        AppIcon(icon, Modifier.padding(start = NcSize.iconStart))
    }
}

/**
 * The long look (09): the time at the top right, the notification at the top in full, and at the bottom two circles,
 * x (dismiss) on the left and bell-slash (mute for an hour) on the right. A tap on the card opens what it points at; a
 * tap anywhere else, or back, returns to the list.
 */
@Composable
private fun LongLook(n: Note, icon: ImageBitmap?, ms: Long, onClose: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit, onMute: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag("long-look").pointerInput(n.key) { detectTapGestures { onClose() } }) {
        NoteCard(n, icon, ms, list = false, Modifier.padding(start = NcSize.side, end = NcSize.side, top = NcSize.lookTop), onTap = onOpen)
        Box(Modifier.align(Alignment.Center).fillMaxWidth()
            .offset(y = (LocalScreen.current.height * (NcSize.buttonCentreY - 0.5f)).dp)
            .padding(horizontal = NcSize.buttonSide)) {
            CircleButton(Symbols.close, "Dismiss", onDismiss, Modifier.align(Alignment.CenterStart).testTag("look-dismiss"), size = NcSize.button)
            CircleButton(Symbols.bellSlash, "Mute for 1 hour", onMute, Modifier.align(Alignment.CenterEnd).testTag("look-mute"), size = NcSize.button)
        }
    }
}

/** How long ago, in secondaryLabel, right-aligned at the top of a card. */
private val time get() = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel, textAlign = TextAlign.End)

/** The app's icon as the watch's disc. */
@Composable
private fun AppIcon(icon: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier.size(NcSize.icon).clip(Circle)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
}

/** The watch's bell-slash mutes for an hour; Android's listener snoozes the notification that long. */
private const val MUTE_MS = 60 * 60_000L

/**
 * The app's icon as a square in its own colours: an adaptive icon's background and foreground layers (each 108 for the
 * 72 shown); any other drawable scaled into the same square. [AppIcon] clips it to the disc.
 */
private fun squareIcon(ctx: android.content.Context, pkg: String, px: Int = 144): ImageBitmap? = runCatching {
    val d = ctx.packageManager.getApplicationIcon(pkg)
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    if (d is android.graphics.drawable.AdaptiveIconDrawable) {
        val e = px / 4
        listOfNotNull(d.background, d.foreground).forEach { l -> l.setBounds(-e, -e, px + e, px + e); l.draw(c) }
    } else { d.setBounds(0, 0, px, px); d.draw(c) }
    b.asImageBitmap()
}.getOrNull()

/** A tap opens what the notification points at, then lets it go as the shade does. */
private fun open(ctx: android.content.Context, n: Note) {
    val sbn = runCatching { MediaListener.Defaults.live?.activeNotifications?.firstOrNull { it.key == n.key } }.getOrNull()
    val sent = sbn?.notification?.contentIntent?.let { runCatching { it.send() }.isSuccess } == true
    if (!sent) ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let { runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    if (sbn != null && sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) runCatching { MediaListener.Defaults.live?.cancelNotification(n.key) }
}

/**
 * Notification sizes in wp, measured in pt on the watchOS 27.2 simulator captures (07 the list, 09 the long look;
 * 422 px = 211 pt, so pt = px / 2).
 */
private object NcSize {
    val side: Dp get() = 16.dp
    val edge: Dp get() = 0.dp
    val corner: Dp get() = 22.dp
    val shape get() = rounded(corner)
    val pad: Dp get() = 16.dp
    val timePad: Dp get() = 10.dp
    val lookTimePad: Dp get() = 12.dp
    val titleTop: Dp get() = 24.dp
    val lookTitleTop: Dp get() = 24.dp
    val bottomPad: Dp get() = 14.dp
    val lookBottomPad: Dp get() = 14.dp
    val icon: Dp get() = 38.dp
    val iconStart: Dp get() = 14.dp
    val iconAbove: Dp get() = 14.dp
    val lookIconAbove: Dp get() = 20.dp
    val minHeight: Dp get() = iconAbove + 58.dp
    val gap: Dp get() = 12.dp
    val pilePeek: Dp get() = 6.dp
    val pileInset: Dp get() = 6.dp
    const val pileLevels = 1
    val lookTop: Dp get() = 32.dp
    val button: Dp get() = 44.dp
    val buttonSide: Dp get() = 28.dp
    const val buttonCentreY = 438f / 514f
}
