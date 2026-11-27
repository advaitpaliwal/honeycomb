package org.honeycomb.launcher

import org.honeycomb.launcher.design.HoneycombColors
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** An installed app. [icon] is its circle at the grid's size. */
class App(val label: String, val component: ComponentName, val icon: ImageBitmap)

/** Play Store and Settings sit in the middle, as the watch keeps its App Store and Settings there; then the everyday apps; the rest alphabetically. */
private val first = listOf(
    "com.android.vending", "com.android.settings", "com.google.android.apps.messaging", "com.google.android.dialer", "com.google.android.GoogleCamera",
    "com.android.camera2", "com.google.android.apps.maps", "com.google.android.apps.photos",
    "com.android.chrome", "com.google.android.gm", "com.google.android.calendar", "com.google.android.youtube.music",
    "com.google.android.apps.youtube.music",
)

/**
 * Every installed app with its icon, loaded once when the home starts and again when a package is added, removed or
 * changed, so the full grid has its icons the moment it opens (clip 21: the watch's hand-over is about 250 ms).
 */
object AppCache {
    val apps = MutableStateFlow<List<App>?>(null)
    private var started = false
    private var refresh: () -> Unit = {}

    /** Draw every icon again: the Themed icons switch, light or dark, or the wallpaper's colours changed. */
    fun refresh() = refresh.invoke()

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        // One load at a time: a burst of package broadcasts (a first boot sends hundreds) queues one more load instead of
        // starting a parallel load per broadcast, each rendering every icon and finishing in any order.
        val requests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { for (r in requests) apps.value = installedApps(app) }
        val reload = { requests.trySend(Unit) }
        refresh = { reload() }
        reload()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { reload() }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}

private val BUILTIN_APPS = setOf(FeedsActivity::class.java.name)
private fun includeInAppList(pkg: String, cls: String): Boolean = !isInternal(pkg) || cls in BUILTIN_APPS

fun installedApps(ctx: Context): List<App> {
    val pm = ctx.packageManager
    val px = iconPx(ctx)
    val themed = IconTheme.colours(ctx)
    val seen = LinkedHashMap<ComponentName, App>()

    // 1. Primary user launchable activities
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { includeInAppList(it.activityInfo.packageName, it.activityInfo.name) }
        .forEach { ri ->
            val comp = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
            if (comp !in seen) {
                val art = fullResIcon(pm, ri.activityInfo) ?: ri.loadIcon(pm)
                val crop = planIcon(art)
                seen[comp] = App(
                    ri.loadLabel(pm).toString(),
                    comp,
                    circleIcon(art, px, crop, themed).let { it.copy(Bitmap.Config.HARDWARE, false) ?: it }.asImageBitmap(),
                )
            }
        }

    // 2. Also include any launchable apps from secondary/work profiles via LauncherApps
    runCatching {
        val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
        la?.profiles?.forEach { profile ->
            la.getActivityList(null, profile).forEach { info ->
                val comp = info.componentName
                if (includeInAppList(comp.packageName, comp.className) && comp !in seen) {
                    val art = info.getIcon(android.util.DisplayMetrics.DENSITY_XXXHIGH)
                    val crop = planIcon(art)
                    seen[comp] = App(
                        info.label.toString(),
                        comp,
                        circleIcon(art, px, crop, themed).let { it.copy(Bitmap.Config.HARDWARE, false) ?: it }.asImageBitmap(),
                    )
                }
            }
        }
    }

    return seen.values
        .sortedWith(compareBy({ first.indexOf(it.component.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.label.lowercase() }))
}

/**
 * The app's icon from its xxxhdpi resources, as Launcher3's IconCache asks for a denser icon than the screen's: at the
 * tested phone's 220 dpi the system hands out hdpi bitmaps (149 px for 108 dp), which the
 * 7.3x app-open zoom blows up past their pixels. Null if the app has no icon resource; vectors are sharp at any density.
 */
private fun fullResIcon(pm: android.content.pm.PackageManager, info: android.content.pm.ActivityInfo): Drawable? = runCatching {
    val id = info.iconResource.takeIf { it != 0 } ?: return null
    pm.getResourcesForApplication(info.applicationInfo).getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
}.getOrNull()

/**
 * One app icon [px] across, as the phone renders it: clipped to the system's icon mask (AdaptiveIconDrawable's
 * config_icon_mask, so a Samsung squircle or a Pixel circle carries through). An adaptive icon is its own layers under the
 * mask, uncropped. With the phone's Themed icons switch on ([IconTheme]) an icon that ships a monochrome layer is that
 * layer in onSecondaryContainer on secondaryContainer, as Pixel's launcher draws it; an icon with none keeps its colours.
 *
 * A legacy (non-adaptive) icon is drawn to fill the mask: [crop] (from [planIcon]) is the square of its art that fills it,
 * as fractions of the art. The art is drawn straight from the app's drawable at [px], never from a smaller bitmap, so the
 * same icon drawn again at the app-open zoom's size stays as sharp as the app's own resources.
 */
fun circleIcon(icon: Drawable, px: Int, crop: FloatArray? = planIcon(icon), themed: IconTheme.Colours? = null): Bitmap {
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val mono = (icon as? AdaptiveIconDrawable)?.monochrome
    when {
        themed != null && mono != null -> {
            canvas.drawColor(themed.background)
            val o = (px * AdaptiveIconDrawable.getExtraInsetFraction()).roundToInt()
            mono.mutate().run { setTint(themed.glyph); setBounds(-o, -o, px + o, px + o); draw(canvas) }
        }
        icon is AdaptiveIconDrawable || crop == null -> drawArt(icon, canvas, px)
        else -> {
            val s = px / crop[2] // the whole art's size in px
            canvas.save()
            canvas.translate(-crop[0] * s, -crop[1] * s)
            canvas.scale(s / px, s / px)
            drawArt(icon, canvas, px)
            canvas.restore()
        }
    }
    // the system mask, anti-aliased: everything outside it cleared (a DST_IN shape leaves the corners, since a software
    // canvas applies a mode only where the shape draws; a software clipPath is not anti-aliased)
    val outside = android.graphics.Path(systemMask(px)).apply { fillType = android.graphics.Path.FillType.INVERSE_WINDING }
    canvas.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    return out
}

/** The phone's icon shape at [px] across: the mask every adaptive icon on this device is cut with. */
fun systemMask(px: Int): android.graphics.Path =
    AdaptiveIconDrawable(android.graphics.drawable.ColorDrawable(0), null).apply { setBounds(0, 0, px, px) }.iconMask

/** The size the art is measured at to find its crop: the 72 dp mask at 4 px per dp. */
const val PLAN_PX = 288

/** Where [icon]'s art fills the circle ([planCrop]), measured once at [PLAN_PX]. */
fun planIcon(icon: Drawable): FloatArray? {
    val art = Bitmap.createBitmap(PLAN_PX, PLAN_PX, Bitmap.Config.ARGB_8888)
    drawArt(icon, Canvas(art), PLAN_PX)
    return planCrop(pixelsOf(art), PLAN_PX, icon is AdaptiveIconDrawable)
}

/** The icon's art over [size]: an adaptive icon's layers as the mask shows them, or a legacy icon's whole square. */
private fun drawArt(icon: Drawable, canvas: Canvas, size: Int) {
    if (icon is AdaptiveIconDrawable) drawLayers(icon, canvas, size) else icon.run { setBounds(0, 0, size, size); draw(canvas) }
}

/**
 * The square of the art [argb] ([size] across) that fills the circle, as left, top and side in fractions of the art; null
 * for the whole art. Every rule reads the art's pixels; none names an app.
 *
 * Legacy icons ([adaptive] false): Launcher3's wrapToAdaptiveIcon either draws an icon of the mask's shape to fill it or
 * shrinks any other onto a white plate. The plate reads as a second outline around the
 * icon, so every legacy icon is drawn to fill: its opaque part (alpha >= 1/2, so a soft drop shadow does
 * not count) scaled to the circle.
 *
 * Adaptive icons whose rim is one colour (a plate) and whose content on it is an icon of its own: on a real tested phone
 * Clock, Calendar, Files and Camera are a white background layer under a foreground drawn as a whole
 * icon, a disc or a tile. Two cases:
 * - a disc (Chrome's ball, the Clock): cropped to the disc. A pale ring around a darker disc is the disc's bezel (the
 *   Clock's white rim, 175-247/255 on 255 white), so the disc is looked for first among pixels far from the plate.
 * - anything else on a plate (Calendar's 31, Camera, Files, Gmail's M, Settings' gear, the Phone handset, a map pin)
 *   keeps its plate: the whole 72 dp the mask shows, so the glyph sits whole and centred in Launcher3's safe zone, as
 *   Pixel's launcher and watchOS's white-plate icons (Photos) show it. Cropping a tile to a circle cut its glyph.
 */
internal fun planCrop(argb: IntArray, size: Int, adaptive: Boolean): FloatArray? {
    if (!adaptive) return opaqueBounds(IntArray(argb.size) { argb[it] ushr 24 }, size)?.let { square(it, size) }
    val plate = plateColour(argb, size) ?: return null
    for (tol in intArrayOf(BEZEL_TOL, PLATE_TOL)) {
        val m = contentMask(argb, size, plate, tol)
        val b = opaqueBounds(m, size) ?: continue
        val d = discBounds(b)
        // a hair inside the disc, so its anti-aliased rim against the plate is cut off
        if (d[3] <= size && discIn(m, size, d)) return square(d, size, inset = (d[2] - d[0]) / 32f)
    }
    return null
}

/** Differs from the plate by more than this: content. Far enough off the plate to leave a pale bezel out. */
internal const val BEZEL_TOL = 96
internal const val PLATE_TOL = 16

/** [b] (bounds, any shape) as a square about its middle with side its longer side less 2 [inset], in fractions of [size]. */
private fun square(b: IntArray, size: Int, inset: Float = 0f): FloatArray {
    val side = max(b[2] - b[0], b[3] - b[1]) - 2 * inset
    return floatArrayOf(((b[0] + b[2]) / 2f - side / 2) / size, ((b[1] + b[3]) / 2f - side / 2) / size, side / size)
}

/** The rim's colour if the rim just inside the mask is one colour (nine in ten of it, so a clock hand may cross it). */
internal fun plateColour(argb: IntArray, size: Int): Int? {
    val rim = ringPoints(size / 2f, size / 2f, size * 0.47f)
    val plate = argb[rim[rim.size * 3 / 4].let { (x, y) -> y * size + x }] // the top of the rim
    if (plate ushr 24 != 255 || rim.count { (x, y) -> near(argb[y * size + x], plate, 6) } < rim.size * 0.9) return null
    return plate
}

/** 255 where [argb] differs from [plate] by more than [tol] or is enclosed by such pixels, 0 elsewhere. */
internal fun contentMask(argb: IntArray, size: Int, plate: Int, tol: Int): IntArray {
    val m = IntArray(argb.size) { if (near(argb[it], plate, tol)) 0 else 255 }
    // fill holes: plate pixels not reached from the edge are inside the content (the Camera's lens, Calendar's 31)
    val outside = BooleanArray(m.size)
    val stack = ArrayDeque<Int>()
    for (i in 0 until size) for (e in intArrayOf(i, (size - 1) * size + i, i * size, i * size + size - 1)) if (m[e] == 0 && !outside[e]) { outside[e] = true; stack.add(e) }
    while (stack.isNotEmpty()) {
        val p = stack.removeLast(); val x = p % size; val y = p / size
        for (q in intArrayOf(if (x > 0) p - 1 else -1, if (x < size - 1) p + 1 else -1, if (y > 0) p - size else -1, if (y < size - 1) p + size else -1))
            if (q >= 0 && m[q] == 0 && !outside[q]) { outside[q] = true; stack.add(q) }
    }
    return IntArray(m.size) { if (outside[it]) 0 else 255 }
}

/** An adaptive icon's background then foreground, the 108 dp layers outset past the [size] square the mask shows. */
private fun drawLayers(a: AdaptiveIconDrawable, canvas: Canvas, size: Int) {
    val o = (size * AdaptiveIconDrawable.getExtraInsetFraction()).roundToInt()
    for (l in listOf(a.background, a.foreground)) l?.run { setBounds(-o, -o, size + o, size + o); draw(canvas) }
}

private fun pixelsOf(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

private fun ringPoints(cx: Float, cy: Float, r: Float, n: Int = 72) =
    (0 until n).map { i -> (cx + r * cos(2 * PI * i / n)).toInt() to (cy + r * sin(2 * PI * i / n)).toInt() }

private fun near(a: Int, b: Int, d: Int) = (0..3).all { s -> abs((a shr (s * 8) and 255) - (b shr (s * 8) and 255)) <= d }

/**
 * Whether the content [b] in [mask] is a clean disc: solid all round just inside its edge, and under 8% of its area
 * drawn outside it (a shadow or a clock hand's tip; never a square badge's corners, a logo's ends or a map pin's point).
 */
internal fun discIn(mask: IntArray, size: Int, b: IntArray): Boolean {
    val w = b[2] - b[0]; val h = b[3] - b[1]
    val cx = (b[0] + b[2]) / 2f; val cy = (b[1] + b[3]) / 2f; val r = (w + h) / 4f
    if (!ringPoints(cx, cy, r * 0.94f).all { (x, y) -> mask[y * size + x] == 255 }) return false
    var outside = 0
    for (y in 0 until size) for (x in 0 until size) if (mask[y * size + x] == 255 && (x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r) outside++
    return outside < 0.08f * PI.toFloat() * r * r
}

/** A disc's bounds from its content's bounds: its width across, down from its top, so a drop shadow under it does not count. */
internal fun discBounds(b: IntArray): IntArray = intArrayOf(b[0], b[1], b[2], b[1] + b[2] - b[0])

/** left, top, right (exclusive), bottom (exclusive) of the pixels with alpha >= [cut]; null if there are none. */
internal fun opaqueBounds(alpha: IntArray, size: Int, cut: Int = 128): IntArray? {
    var l = size; var t = size; var r = -1; var b = -1
    for (y in 0 until size) for (x in 0 until size) if (alpha[y * size + x] >= cut) {
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
    }
    return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
}

/**
 * The full app grid, copied from watchOS 27.2 (WatchGridView.kt): every installed app, scrolling vertically. Tap to
 * open; volume keys step a row, as the Crown does.
 */
@Composable
fun GridScreen(zoomKeys: Flow<Int>, onOpen: (ComponentName, android.graphics.Rect?) -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(ctx) { AppCache.start(ctx) }
    val apps by AppCache.apps.collectAsState()
    Box(Modifier.fillMaxSize().background(HoneycombColors.background)) { // pure black, as the watch draws its grid
        val list = apps ?: return@Box
        val icons = remember(list) { list.map { it.icon } }
        val labels = remember(list) { list.map { it.label } }
        WatchGrid(
            icons, labels, zoomKeys, onTap = { i, from -> onOpen(list[i].component, from) },
        )
    }
}

/**
 * The px an app's [App.icon] is drawn at: the largest disc that shows icons among many, the favourites' centre disc
 * ([favouriteSlots], scaled by the favourites' [FAVOURITES_SPAN]; 30% of the screen's width, the grid's largest is 23%).
 * Launcher3 renders its icon bitmaps at the size it draws them (IconCache, iconBitmapSize) for the same reason: every
 * icon is a GPU texture, and at 128 design units (576 px on a 1080 px phone) 146 apps held 183 MB of textures against
 * HWUI's 126 MB cache, so the grid uploaded icons again on every frame (97 ms frames, the grid "stuck").
 */
fun iconPx(ctx: android.content.Context): Int =
    kotlin.math.ceil(ctx.resources.displayMetrics.widthPixels * favouriteSlots.maxOf { it.third } / FAVOURITES_SPAN).toInt()
