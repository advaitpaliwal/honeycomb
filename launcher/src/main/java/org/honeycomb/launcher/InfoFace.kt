package org.honeycomb.launcher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.honeycomb.launcher.design.Display
import org.honeycomb.launcher.design.NativeFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/*
 * Info: the whole phone screen is the watch face, as Siri Modular is the whole watch display (support.apple.com/guide/
 * watch/apde9218b440; watchos-sim/screens/02). The date, then the time as one line as wide as the screen allows, then
 * one row of round complications (developer.apple.com/design/human-interface-guidelines/complications, the circular
 * family) for what is real right now. Nothing is a card. Everything is sized from the face's own safe box, inside
 * Material's window margins, never from the watch's points.
 */

/** One round complication: a Live Update (its key), what is playing, or the next alarm. */
sealed interface Complication {
    data class Live(val key: String) : Complication
    data object Playing : Complication
    data object Alarm : Complication
}

/** Siri Modular's row holds three circles. */
const val INFO_MAX_CIRCLES = 3

/**
 * What the row shows, in order: Live Updates (newest first, as the Smart Stack leads with them), then what is playing,
 * then the next alarm, up to [INFO_MAX_CIRCLES]. Only sources that exist right now appear; with none the row is empty.
 */
fun complications(live: List<String>, playing: Boolean, alarm: Boolean): List<Complication> =
    (live.map { Complication.Live(it) } + listOfNotNull(Complication.Playing.takeIf { playing }, Complication.Alarm.takeIf { alarm }))
        .take(INFO_MAX_CIRCLES)

/** The Info face's proportions, as fractions of the safe box's width, from the watch-screen mockup (gen2.py: 144 wide, comps()). */
object InfoScale {
    /** A circle's diameter: 32 of 144. */
    const val CIRCLE = 32f / 144f
    /** Centre to centre: 40 of 144. */
    const val PITCH = 40f / 144f
    /** The date's font size: 10 of 144. */
    const val DATE = 10f / 144f
    /** The smallest gap between the date, the time and the circles: 8 of 144, the gap between two circles. */
    const val MIN_GAP = 8f / 144f
    /** The phone font's cap and digit height (Roboto: 1456 of 2048 units), which places text by its visible top. */
    const val CAP = 1456f / 2048f
}

/**
 * Where everything sits, in dp from the safe box's top: the date's cap top and height, the time's cap top and height, the
 * circles' top, diameter and pitch, and how many circles there are.
 */
data class InfoScreen(
    val dateTop: Float, val dateCap: Float, val timeTop: Float, val timeCap: Float,
    val circlesTop: Float, val circle: Float, val pitch: Float, val circles: Int,
)

/**
 * Lays the face out in a safe box [width] x [height] dp. The time stands [timeCapAtWidth] tall (the height at which it
 * spans the width) unless the screen is too short for that, then as tall as the rest leaves. With [circles] to show,
 * the spare height is shared evenly above the date, between the three rows and below the circles, as on the mockup, so
 * the face fills the screen; with none, the date and time stand together in the middle.
 */
fun infoScreen(width: Float, height: Float, timeCapAtWidth: Float, circles: Int): InfoScreen {
    val n = circles.coerceIn(0, INFO_MAX_CIRCLES)
    val d = width * InfoScale.CIRCLE
    val pitch = width * InfoScale.PITCH
    val dateCap = width * InfoScale.DATE * InfoScale.CAP
    val minGap = width * InfoScale.MIN_GAP
    val rows = if (n > 0) d else 0f
    val gaps = if (n > 0) 2 else 1
    val timeCap = min(timeCapAtWidth, height - dateCap - rows - gaps * minGap).coerceAtLeast(0f)
    val spare = (height - dateCap - timeCap - rows).coerceAtLeast(0f)
    return if (n > 0) {
        val g = spare / 4
        InfoScreen(g, dateCap, g + dateCap + g, timeCap, g + dateCap + g + timeCap + g, d, pitch, n)
    } else {
        val top = (spare - minGap) / 2
        InfoScreen(top, dateCap, top + dateCap + minGap, timeCap, 0f, d, pitch, 0)
    }
}

const val INFO_TIME_WEIGHT = 500
/** The 'wdth' axis's normal and narrowest values in the phone's default font (Roboto 3 on Android 12+: 75 to 100). */
const val INFO_WDTH_NORMAL = 100f
const val INFO_WDTH_NARROW = 75f

/** How the Info face's time fits its width: [wdth] on the font's width axis, then a uniform [scale] of its size. */
data class InfoTimeFit(val wdth: Float, val scale: Float)

/**
 * Fits the time to [target] px given its width [wide] at normal width and [narrow] at the narrowest. The width axis is
 * linear between them, so the axis value is interpolated; [measure] then checks it, and whatever is still too wide
 * scales down whole, height and width alike, so no stroke is ever squeezed. A font without the axis has [narrow] equal
 * to [wide], and the time simply scales.
 */
fun infoTimeFit(target: Float, wide: Float, narrow: Float, measure: (Float) -> Float): InfoTimeFit {
    if (wide <= target) return InfoTimeFit(INFO_WDTH_NORMAL, 1f)
    val wdth = if (wide - narrow < 0.5f) INFO_WDTH_NORMAL
        else (INFO_WDTH_NORMAL - (INFO_WDTH_NORMAL - INFO_WDTH_NARROW) * (wide - target) / (wide - narrow))
            .coerceIn(INFO_WDTH_NARROW, INFO_WDTH_NORMAL).let { Math.round(it * 2) / 2f }
    val drawn = if (wdth == INFO_WDTH_NORMAL) wide else measure(wdth)
    return InfoTimeFit(wdth, min(1f, target / drawn))
}

private val infoTimeFamilies = HashMap<Float, FontFamily>()

/** The phone's own sans-serif at [wdth]: the device font with its variation axes set, never a bundled face. */
private fun infoTimeFamily(wdth: Float): FontFamily = infoTimeFamilies.getOrPut(wdth) {
    if (wdth == INFO_WDTH_NORMAL) NativeFont
    else FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight(INFO_TIME_WEIGHT),
        variationSettings = FontVariation.Settings(FontWeight(INFO_TIME_WEIGHT), FontStyle.Normal, FontVariation.width(wdth))))
}

/** The next alarm the phone will ring (AlarmManager.getNextAlarmClock), kept current by ACTION_NEXT_ALARM_CLOCK_CHANGED. */
@Composable
private fun nextAlarm(): AlarmManager.AlarmClockInfo? {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AlarmManager::class.java) }
    val next = remember { mutableStateOf(am?.nextAlarmClock) }
    DisposableEffect(ctx) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { next.value = am?.nextAlarmClock } }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    return next.value
}

@Composable
internal fun InfoFace(ms: Long, p: FacePalette) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val s = safe()
    val h24 = is24()
    val (h, m) = digits(ms, h24)
    val ctx = LocalContext.current
    val density = LocalDensity.current
    // what is real right now: the Live Updates and the media session the Smart Stack reads, and the next alarm
    val media by Providers.media.collectAsState()
    val posted by MediaListener.Defaults.posted.collectAsState()
    val connected by MediaListener.Defaults.changes.collectAsState()
    val live = remember(posted, connected) { liveUpdates(ctx) }
    val alarm = nextAlarm()
    val comps = complications(live.map { it.key }, media != null, alarm != null)

    // the time spans the safe width on the font's narrowest outlines, the tall cut of the watch's digits
    val measurer = rememberTextMeasurer()
    fun timeStyle(wdth: Float, size: Float) = Display.face(size, INFO_TIME_WEIGHT, p.time, family = infoTimeFamily(wdth))
    fun widthAt(wdth: Float, size: Float) = measurer.measure("$h:$m", timeStyle(wdth, size), softWrap = false).size.width.toFloat()
    val targetPx = with(density) { s.width.toPx() }
    val capAtWidth = 100f * targetPx / widthAt(INFO_WDTH_NARROW, 100f) * InfoScale.CAP
    val l = infoScreen(s.width.value, s.height.value, capAtWidth, comps.size)
    val size = l.timeCap / InfoScale.CAP
    val fit = infoTimeFit(targetPx, widthAt(INFO_WDTH_NORMAL, size), widthAt(INFO_WDTH_NARROW, size)) { widthAt(it, size) }
    val cap = l.timeCap * fit.scale
    val timeTop = l.timeTop + (l.timeCap - cap) / 2

    val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), Locale.getDefault()).format(Date(ms))
        .replace(",", "").uppercase(Locale.getDefault())
    Box(Modifier.padding(start = s.left, top = s.top).size(s.width, s.height).testTag("face")) {
        AtBaseline(date, Display.face(l.dateCap / InfoScale.CAP, 600, p.date, family = NativeFont), (l.dateTop + l.dateCap).dp,
            Modifier.testTag("info-date"))
        AtBaseline("$h:$m", timeStyle(fit.wdth, size * fit.scale), (timeTop + cap).dp, Modifier.testTag("info-time"))
        if (comps.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = l.circlesTop.dp).testTag("info-circles"),
            horizontalArrangement = Arrangement.spacedBy((l.pitch - l.circle).dp, Alignment.CenterHorizontally)) {
            comps.forEach { c ->
                when (c) {
                    is Complication.Live -> live.firstOrNull { it.key == c.key }?.let { u ->
                        LiveCircle(u, ms, l.circle, p, measurer) { runCatching { u.tap?.send() } }
                    }
                    Complication.Playing -> media?.let { mm ->
                        GlyphCircle("comp-media", R.drawable.ic_music_note, if (mm.playing) "Now Playing ${mm.title}" else "Paused ${mm.title}",
                            null, l.circle, p, measurer) { openMedia(ctx, mm.pkg) }
                    }
                    Complication.Alarm -> alarm?.let { a ->
                        val at = SimpleDateFormat(if (h24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(a.triggerTime))
                        GlyphCircle("comp-alarm", R.drawable.ic_alarm, "Alarm $at", at, l.circle, p, measurer) { runCatching { a.showIntent?.send() } }
                    }
                }
            }
        }
    }
}

/** A circle's disc, tappable, carrying [tag] and [label] for TalkBack. */
@Composable
private fun Disc(tag: String, label: String, d: Float, p: FacePalette, onTap: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(d.dp).clip(CircleShape).background(p.scheme.surfaceContainerHigh).testTag(tag)
        .semantics(mergeDescendants = true) { contentDescription = label }
        .pointerInput(Unit) { detectTapGestures { onTap() } }, contentAlignment = Alignment.Center) { content() }

/** [text] at the largest size up to [size] that fits [maxW] px, scaled whole. */
private fun fitted(measurer: TextMeasurer, text: String, style: TextStyle, size: Float, maxW: Float): TextStyle {
    val w = measurer.measure(text, Display.resized(style, size), softWrap = false).size.width
    return Display.resized(style, if (w <= maxW) size else size * maxW / w)
}

/** A Live Update as a progress ring with its short value inside (a timer's remaining time), in the app's colour. */
@Composable
private fun LiveCircle(u: LiveUpdate, ms: Long, d: Float, p: FacePalette, measurer: TextMeasurer, onTap: () -> Unit) {
    val accent = if (u.color != 0) Color(u.color).copy(alpha = 1f) else p.date
    val track = p.scheme.outlineVariant
    val value = u.value(ms)
    Disc("comp-live", listOfNotNull(u.title, value).joinToString(" "), d, p, onTap) {
        Canvas(Modifier.fillMaxSize().testTag("comp-ring")) {
            val w = size.width * 0.09f
            val inset = size.width * 0.12f + w / 2
            val box = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(w))
            u.progress?.let { drawArc(accent, -90f, 360f * it, false, Offset(inset, inset), box, style = Stroke(w, cap = StrokeCap.Round)) }
        }
        value?.let {
            val px = with(LocalDensity.current) { (d * 0.5f).dp.toPx() }
            BasicText(it, Modifier.testTag("comp-value"), softWrap = false, maxLines = 1,
                style = fitted(measurer, it, Display.face(d * 0.2f, 700, accent, family = NativeFont), d * 0.2f, px))
        }
    }
}

/** A glyph circle: Now Playing's note (opens the player) or the alarm clock with its time under it. */
@Composable
private fun GlyphCircle(tag: String, glyph: Int, label: String, value: String?, d: Float, p: FacePalette, measurer: TextMeasurer, onTap: () -> Unit) =
    Disc(tag, label, d, p, onTap) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(glyph), null, Modifier.size((d * if (value == null) 0.44f else 0.3f).dp).testTag("comp-glyph"),
                colorFilter = ColorFilter.tint(p.date))
            value?.let {
                val px = with(LocalDensity.current) { (d * 0.62f).dp.toPx() }
                BasicText(it, Modifier.testTag("comp-value"), softWrap = false, maxLines = 1,
                    style = fitted(measurer, it, Display.face(d * 0.18f, 600, p.time, family = NativeFont), d * 0.18f, px))
            }
        }
    }
