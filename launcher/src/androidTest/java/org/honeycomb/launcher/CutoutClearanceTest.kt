package org.honeycomb.launcher

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Nothing touches the camera or the system bars, and colour runs to every edge, on any phone shape. The emulator's display is switched at runtime to real phones' sizes
 * and densities (`wm size`, `wm density`) and to each of the system image's display-cutout overlays
 * (com.android.internal.display.cutout.emulation.*: a centred hole, a tall notch, a corner cut, a top and bottom notch,
 * a waterfall edge). On each one, every home screen is walked:
 * - at rest, no text, icon or control lies on the camera cutout, the status bar's band or the navigation bar's band
 *   (even while a bar is hidden), or off the screen;
 * - at rest, the bands under the status bar and the navigation bar show the screen's own background: no solid strip of
 *   another colour is drawn there (backgrounds, colour fields and faces run edge to edge; only content keeps clear);
 * - scrolled, the cutout's own pixels show only the ground: scrolled content has faded out before it gets there.
 * Shots go to the app's evidence/cutout folder. By default four very different combinations run (the per-PR set);
 * `-e cutoutMatrix full` runs every size against every overlay, `-e cutoutCombos pixel-9a-hole,flip-cover-corner` just those.
 */
@RunWith(AndroidJUnit4::class)
class CutoutClearanceTest {
    @get:Rule(order = -2) val setup = SetupFinished()
    @get:Rule(order = -1) val clock = FixedClock()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val problems = mutableListOf<String>()

    private class Phone(val name: String, val w: Int, val h: Int, val dpi: Int)

    private companion object { const val SHELL_SECONDS = 30L }

    private val phones = listOf(
        Phone("compact-small", 480, 854, 240),
        Phone("pixel-9a", 1080, 2424, 420),
        Phone("pixel-9-pro-xl", 1344, 2992, 486),
        Phone("galaxy-s25", 1080, 2340, 416),
        Phone("galaxy-s25-ultra", 1440, 3120, 505),
        Phone("iphone-shaped", 1179, 2556, 460),
        Phone("fold-inner", 2184, 1968, 374),
        Phone("flip-cover", 748, 720, 306),
        Phone("tablet-landscape", 2560, 1600, 320),
    )

    /** Runs [command] under toybox `timeout`, so a `wm` or `cmd overlay` call that never returns fails the test by name. */
    private fun shell(command: String): String {
        val start = System.nanoTime()
        val out = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("timeout $SHELL_SECONDS $command"))
            .bufferedReader().readText()
        check((System.nanoTime() - start) / 1_000_000_000 < SHELL_SECONDS) { "`$command` did not finish in $SHELL_SECONDS s" }
        return out
    }

    private val overlays by lazy {
        Regex("""com\.android\.internal\.display\.cutout\.emulation\.(\w+)""").findAll(shell("cmd overlay list android"))
            .map { it.groupValues[1] }.distinct().toList()
    }

    private val combos: List<Pair<Phone, String>> get() {
        val args = InstrumentationRegistry.getArguments()
        val all = phones.flatMap { p -> overlays.map { p to it } }
        if (args.getString("cutoutMatrix") == "full") return all
        // -e cutoutCombos pixel-9a-hole,flip-cover-corner: just these
        args.getString("cutoutCombos")?.split(',')?.toSet()?.let { want -> return all.filter { "${it.first.name}-${it.second}" in want } }
        val byName = phones.associateBy { it.name }
        return listOf("pixel-9a" to "hole", "iphone-shaped" to "tall", "galaxy-s25-ultra" to "corner", "tablet-landscape" to "double")
            .filter { it.second in overlays }.map { byName.getValue(it.first) to it.second }
    }

    private var oldSize = ""; private var oldDensity = ""

    private fun apply(p: Phone, overlay: String) {
        shell("wm size ${p.w}x${p.h}"); shell("wm density ${p.dpi}")
        shell("cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.$overlay")
        Thread.sleep(1500)
        AppOrder.clear(ctx); FaceChoice.save(ctx, 0)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
        seedApps()
        waitFor("faces")
    }

    /** A phone in use has a long app list: the emulator's own apps, repeated under made-up names, so every list scrolls. */
    private fun seedApps() {
        val real = AppCache.apps.value.orEmpty()
        if (real.size >= 80) return
        AppCache.apps.value = real + (0 until 80 - real.size).map { i ->
            val a = real[i % real.size]
            App("${a.label} $i", android.content.ComponentName("test.cutout.$i", "Main"), a.icon)
        }
        rule.waitForIdle()
    }

    /** Open the apps screen in the saved view ([grid] or List View), from the face. */
    private fun openApps(grid: Boolean) {
        AppOrder.saveGrid(ctx, grid); rule.activityRule.scenario.recreate(); waitFor("faces"); seedApps()
        tapFace()
        if (shown("pin-0")) { atRest("favourites"); rule.allAppsAction(); rule.waitForIdle() }
        rule.waitUntil(8_000) { shown("app-grid") || shown("app-list") }
    }

    /** Every step runs even when one fails, so the next test class always gets the display it started with. */
    @After fun restore() {
        val failed = listOf<() -> Unit>(
            { for (o in overlays) shell("cmd overlay disable com.android.internal.display.cutout.emulation.$o") },
            { shell(if (oldSize.isEmpty()) "wm size reset" else "wm size $oldSize") },
            { shell(if (oldDensity.isEmpty()) "wm density reset" else "wm density $oldDensity") },
            { FaceChoice.save(ctx, 0) },
        ).mapNotNull { runCatching(it).exceptionOrNull() }
        check(failed.isEmpty()) { "restoring the display failed: ${failed.joinToString { it.message.orEmpty() }}" }
    }

    /** The places no content may sit: each cutout rect, and the bands the cutout and the system bars take at their edges. */
    private fun keepOut(): List<Rect> {
        val ins = rule.activity.window.decorView.rootWindowInsets
        val w = rule.activity.window.decorView.width; val h = rule.activity.window.decorView.height
        val cut = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout())
        val bar = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
        val nav = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
        val out = mutableListOf<Rect>()
        ins.displayCutout?.boundingRects?.let { out += it }
        val top = maxOf(cut.top, bar.top); val bottom = maxOf(cut.bottom, nav.bottom)
        val left = maxOf(cut.left, nav.left); val right = maxOf(cut.right, nav.right)
        if (top > 0) out += Rect(0, 0, w, top)
        if (bottom > 0) out += Rect(0, h - bottom, w, h)
        if (left > 0) out += Rect(0, 0, left, h)
        if (right > 0) out += Rect(w - right, 0, w, h)
        return out
    }

    private fun cutoutRects(): List<Rect> = rule.activity.window.decorView.rootWindowInsets.displayCutout?.boundingRects.orEmpty()

    private fun waitFor(tag: String) = rule.waitUntil("$tag shows", 8_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle(); Thread.sleep(500) }

    private lateinit var combo: String

    private fun shot(screen: String): Bitmap {
        rule.waitForIdle(); Thread.sleep(700); rule.waitForIdle()
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(ctx.getExternalFilesDir(null), "evidence/cutout/$combo").apply { mkdirs() }
        File(dir, "$screen.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // and the whole display as a person sees it, with the system bars and the cutout drawn
        inst.uiAutomation.takeScreenshot()?.let { d -> File(dir, "$screen-display.png").outputStream().use { d.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return image
    }

    /**
     * Every text, icon and control that holds still on the screen: semantics leaves with something to read or tap,
     * outside anything that scrolls (a scroll action, or the honeycomb grid, which scrolls through the whole screen as
     * the watch's does). Scrolling content is judged by its pixels instead ([cutoutClean]).
     */
    private fun leaves(): List<SemanticsNode> {
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsActions.ScrollBy) != null || n.config.getOrNull(SemanticsProperties.TestTag) == "app-grid") return
            val kids = n.children
            val says = n.config.getOrNull(SemanticsProperties.Text) != null || n.config.getOrNull(SemanticsProperties.ContentDescription) != null ||
                n.config.getOrNull(SemanticsActions.OnClick) != null
            if (kids.isEmpty() && says) out += n
            kids.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    /** At rest: no leaf lies on a keep-out rect or past the screen's edge. */
    private fun atRest(screen: String) {
        val image = shot(screen)
        val keep = keepOut()
        val cuts = cutoutRects()
        val ins = rule.activity.window.decorView.rootWindowInsets
        val w = rule.activity.window.decorView.width; val h = rule.activity.window.decorView.height
        val cut = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout())
        val nav = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
        val statusClockKeepOut = buildList {
            addAll(cuts)
            val bottom = maxOf(cut.bottom, nav.bottom)
            val left = maxOf(cut.left, nav.left); val right = maxOf(cut.right, nav.right)
            if (bottom > 0) add(Rect(0, h - bottom, w, h))
            if (left > 0) add(Rect(0, 0, left, h))
            if (right > 0) add(Rect(w - right, 0, w, h))
        }
        for (n in leaves()) {
            var b = n.boundsInWindow
            if (b.isEmpty || b.width < 1f || b.height < 1f) continue // scrolled away or clipped to nothing
            b = ink(n, b)
            val r = Rect(b.left.toInt() + 1, b.top.toInt() + 1, b.right.toInt() - 1, b.bottom.toInt() - 1)
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            val what = tag ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "node ${n.id}"
            // TinyStatusTime intentionally sits in the top status-bar ear while still avoiding every physical cutout and edge bar.
            val activeKeep = if (tag == "status-time" || tag == "list-time") statusClockKeepOut else keep
            activeKeep.firstOrNull { Rect.intersects(it, r) }?.let { problems += "$combo $screen: '$what' at $r lies on the keep-out $it" }
            if (r.left < -1 || r.top < -1 || r.right > w + 1 || r.bottom > h + 1) problems += "$combo $screen: '$what' at $r runs off the ${w}x$h screen"
        }
        cutoutClean(screen, image)
        noBand(screen, image)
    }

    /**
     * Colour runs to every edge: the band the status bar (or a top cutout) takes and the band the navigation bar takes
     * show the screen's own colours. A strip is a band row of one flat colour that the screen itself uses nowhere past
     * the band and the 16 dp fade (sampled every 1/40 of the height), so a face's own field edge (Split's rising colour)
     * is never mistaken for one. Judged at rest; scrolled content passing under the band is judged by [cutoutClean].
     */
    private fun noBand(screen: String, image: Bitmap) {
        val ins = rule.activity.window.decorView.rootWindowInsets
        val cut = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout())
        val bar = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
        val nav = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
        val past = (2 * 16 * ctx.resources.displayMetrics.density).toInt()
        val top = maxOf(cut.top, bar.top); val bottom = maxOf(cut.bottom, nav.bottom)
        fun off(a: Int, b: Int) = maxOf(kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)), kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)))
        val xs = (0 until image.width step 4).toList()
        val rows = (top + past until image.height - bottom - past step maxOf(1, image.height / 40)).toList()
        fun check(edge: String, bandY: Int) {
            if (bandY !in 0 until image.height || rows.isEmpty()) return
            val band = xs.map { image.getPixel(it, bandY) }
            val g = band.groupingBy { it }.eachCount().maxBy { it.value }.key
            val flat = band.count { off(it, g) <= 12 }.toFloat() / band.size
            val used = rows.sumOf { y -> xs.count { off(image.getPixel(it, y), g) <= 12 } }.toFloat() / (rows.size * xs.size)
            if (flat >= 0.9f && used < 0.02f)
                problems += "$combo $screen: a solid strip under the $edge (#${Integer.toHexString(g)}, a colour the screen uses on ${"%.1f".format(used * 100)}% of itself)"
        }
        if (top > 0) check("status bar", top / 2)
        if (bottom > 0) check("navigation bar", image.height - 1 - bottom / 2)
    }

    /**
     * A text's own lines, not its whole box: a time set flush right in a full-width box leaves the box's empty left
     * end where a side cutout may be. The lines come from the text's layout, scaled as the node is drawn.
     */
    private fun ink(n: SemanticsNode, b: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect {
        val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return b
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        if (get.action?.invoke(results) != true || results.isEmpty()) return b
        val t = results[0]
        if (t.lineCount == 0 || n.size.width == 0) return b
        val k = b.width / n.size.width.toFloat()
        val l = (0 until t.lineCount).minOf { t.getLineLeft(it) }; val r = (0 until t.lineCount).maxOf { t.getLineRight(it) }
        val origin = n.positionInWindow
        return androidx.compose.ui.geometry.Rect(maxOf(b.left, origin.x + l * k), b.top, minOf(b.right, origin.x + r * k), b.bottom)
    }

    /** The cutout's own pixels show one flat ground: nothing is drawn where the camera is. */
    private fun cutoutClean(screen: String, image: Bitmap) {
        for (c in cutoutRects()) {
            val x0 = c.left.coerceIn(0, image.width - 1); val x1 = c.right.coerceIn(0, image.width)
            val y0 = c.top.coerceIn(0, image.height - 1); val y1 = c.bottom.coerceIn(0, image.height)
            if (x1 <= x0 || y1 <= y0) continue
            val g = image.getPixel(x0, y0)
            var most = 0
            for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
                val p = image.getPixel(x, y)
                most = maxOf(most, kotlin.math.abs(android.graphics.Color.red(p) - android.graphics.Color.red(g)),
                    kotlin.math.abs(android.graphics.Color.green(p) - android.graphics.Color.green(g)),
                    kotlin.math.abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(g)))
            }
            if (most > 24) problems += "$combo $screen: something is drawn on the cutout $c ($most/255 off its ground)"
        }
    }

    /** Scroll the screen's content up by about a third of the screen, then check the cutout shows only ground. */
    private fun scrolled(screen: String) {
        val h = rule.activity.window.decorView.height.toFloat()
        val scroller = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        if (scroller) rule.onAllNodes(hasScrollAction())[0].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, h / 3f) }
        else rule.onRoot().performTouchInput { swipe(Offset(centerX, height * 0.7f), Offset(centerX, height * 0.35f), 600) }
        cutoutClean("$screen-scrolled", shot("$screen-scrolled"))
    }

    private fun tapFace() {
        repeat(5) {
            if (!shown("faces") || shown("app-grid") || shown("pin-0")) return
            Thread.sleep(600); rule.waitForIdle()
            rule.onNodeWithTag("faces").performTouchInput { click(Offset(centerX, height * 0.36f)) }; rule.waitForIdle()
        }
    }

    private fun swipeDownFromTop(xFraction: Float) = rule.onRoot().performTouchInput {
        down(Offset(width * xFraction, 4f))
        repeat(10) { moveBy(Offset(0f, height / 40f), delayMillis = 16) }
        up()
    }

    private fun walk() {
        for (i in FaceKind.entries.indices) {
            FaceChoice.save(ctx, i); rule.activityRule.scenario.recreate(); waitFor("faces")
            atRest("face-${FaceKind.entries[i].name.lowercase()}")
        }
        FaceChoice.save(ctx, 0); rule.activityRule.scenario.recreate(); waitFor("faces")

        rule.onNodeWithTag("faces").performTouchInput { longClick(center) }
        // close the picker the way a person does, by choosing the face (as PhoneSizesTest does)
        waitFor("pick-0"); atRest("face-picker"); rule.onNodeWithTag("pick-0").performClick(); rule.waitForIdle()
        rule.waitUntil(8_000) { !shown("pick-0") }

        // swipe up raises the Smart Stack (watchOS); a swipe while the picker is still closing is taken by its motion
        repeat(4) {
            if (shown("smart-stack")) return@repeat
            Thread.sleep(600); rule.waitForIdle()
            rule.onNodeWithTag("faces").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
            rule.waitForIdle()
        }
        waitFor("smart-stack"); atRest("smart-stack"); scrolled("smart-stack"); back()
        rule.waitUntil(5_000) { !shown("smart-stack") }

        for (grid in listOf(true, false)) {
            openApps(grid)
            val v = if (shown("app-grid")) "full-grid" else "list-view"
            atRest(v); scrolled(v)
            repeat(3) { if (!shown("faces") || shown("app-grid") || shown("app-list") || shown("pin-0")) back() }
        }
        AppOrder.saveGrid(ctx, false); rule.activityRule.scenario.recreate(); seedApps()

        waitFor("faces"); swipeDownFromTop(0.85f)
        waitFor("control-centre"); atRest("control-centre"); scrolled("control-centre")
        rule.onNodeWithTag("tile-quiet").performScrollTo().performTouchInput { longClick(center) }
        if (runCatching { waitFor("quiet") }.isSuccess) { atRest("quiet"); back() }
        back(); rule.waitUntil(5_000) { !shown("control-centre") }

        swipeDownFromTop(0.15f)
        waitFor("notifications"); atRest("notification-centre"); back()
    }

    @Test fun nothingTouchesTheCamera() {
        oldSize = Regex("""Override size: (\d+x\d+)""").find(shell("wm size"))?.groupValues?.get(1).orEmpty()
        oldDensity = Regex("""Override density: (\d+)""").find(shell("wm density"))?.groupValues?.get(1).orEmpty()
        assertTrue("this image has no display-cutout overlays", overlays.isNotEmpty())
        for ((p, o) in combos) {
            combo = "${p.name}-$o"
            apply(p, o)
            assertTrue("$combo: the overlay gave no cutout", o == "waterfall" || cutoutRects().isNotEmpty() ||
                rule.activity.window.decorView.rootWindowInsets.displayCutout != null)
            runCatching { walk() }.onFailure { problems += "$combo: the walk stopped: ${it.message?.lineSequence()?.first()}" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
