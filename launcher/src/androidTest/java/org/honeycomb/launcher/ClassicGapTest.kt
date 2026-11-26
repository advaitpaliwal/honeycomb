package org.honeycomb.launcher

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the Classic face, no tick touches or crowds a numeral, on any phone shape: the emulator's display is switched to a
 * Pixel 9a (1080 x 2424), a 4:3 screen (960 x 1280) and a tall 20:9 screen (720 x 1600), and on each, every numeral's
 * ink box as drawn keeps at least [minGap] of the numeral size clear of every tick's stroke, neighbouring numerals keep
 * clear of each other, and numerals and ticks stay inside the safe area while the face's colour runs edge to edge.
 * Measured gaps and shots go to the app's evidence/classic-gap folder.
 */
@RunWith(AndroidJUnit4::class)
class ClassicGapTest {
    @get:Rule(order = -2) val setup = SetupFinished()
    @get:Rule(order = -1) val clock = FixedClock()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    private class Phone(val name: String, val w: Int, val h: Int, val dpi: Int)

    private val phones = listOf(
        Phone("pixel-9a", 1080, 2424, 420),
        Phone("four-three", 960, 1280, 320),
        Phone("tall-20x9", 720, 1600, 300),
    )

    private companion object {
        /** Clear air between a numeral's ink and any tick's stroke, per px of numeral size. */
        const val minGap = 0.2f
        /** Clear air between neighbouring numerals' ink on at least one axis, per px of numeral size. */
        const val neighbourGap = 0.4f
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("timeout 30 $command")).bufferedReader().readText()

    @After fun restore() {
        shell("wm size reset"); shell("wm density reset")
        FaceChoice.save(ctx, 0)
    }

    @Test fun noTickTouchesANumeralOnAnyPhoneShape() {
        val dir = File(ctx.getExternalFilesDir(null), "evidence/classic-gap").apply { mkdirs() }
        val problems = mutableListOf<String>()
        val report = StringBuilder()
        for (p in phones) {
            shell("wm size ${p.w}x${p.h}"); shell("wm density ${p.dpi}")
            Thread.sleep(1500)
            FaceChoice.save(ctx, FaceKind.Classic.ordinal)
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("classic-dial").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle(); Thread.sleep(700); rule.waitForIdle()
            val node = rule.onAllNodesWithTag("classic-dial", useUnmergedTree = true).fetchSemanticsNodes().first()
            fun find(n: androidx.compose.ui.semantics.SemanticsNode): androidx.compose.ui.semantics.SemanticsNode? =
                if (n.config.contains(ClassicLayoutKey)) n else n.children.firstNotNullOfOrNull(::find)
            val canvas = checkNotNull(find(node)) { "${p.name}: the Classic face carries no layout" }
            val layout = canvas.config[ClassicLayoutKey]
            val dial = layout.dial
            val s = dial.numeralSize
            val window = rule.activity.window.decorView
            val ins = window.rootWindowInsets
            val bars = ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val at = canvas.boundsInWindow
            // colour edge to edge: the face's canvas covers the whole window
            if (at.left > 0.5f || at.top > 0.5f || at.right < window.width - 0.5f || at.bottom < window.height - 0.5f)
                problems += "${p.name}: the face covers $at, not the ${window.width}x${window.height} window"
            val safe = Rect(bars.left.toFloat(), bars.top.toFloat(), (window.width - bars.right).toFloat(), (window.height - bars.bottom).toFloat())
            fun inSafe(r: Rect) = r.left >= safe.left - 0.5f && r.top >= safe.top - 0.5f && r.right <= safe.right + 0.5f && r.bottom <= safe.bottom + 0.5f
            var tickMin = Float.MAX_VALUE; var tickWorst = ""
            for ((i, ink) in layout.inkBoxes.withIndex()) {
                val label = californiaNumerals[i]
                if (!inSafe(ink.translate(at.topLeft))) problems += "${p.name}: $label at $ink leaves the safe area $safe"
                for (t in 0 until 60) {
                    val gap = segmentBoxDistance(dial.tickOuter[t], dial.tickInner[t], ink.center, ink.width / 2, ink.height / 2) - dial.tickWidth(t) / 2
                    if (gap < tickMin) { tickMin = gap; tickWorst = "$label to tick $t" }
                    if (gap < minGap * s) problems += "${p.name}: $label is ${"%.1f".format(gap)} px from tick $t, under ${"%.1f".format(minGap * s)} px"
                }
            }
            for (t in 0 until 60) {
                val half = dial.tickWidth(t) / 2
                val o = dial.tickOuter[t] + at.topLeft
                if (!inSafe(Rect(o.x - half, o.y - half, o.x + half, o.y + half))) problems += "${p.name}: tick $t's end $o leaves the safe area $safe"
            }
            var nMin = Float.MAX_VALUE
            for (i in 0 until 12) {
                val a = layout.inkBoxes[i]; val b = layout.inkBoxes[(i + 1) % 12]
                val gap = maxOf(maxOf(a.left, b.left) - minOf(a.right, b.right), maxOf(a.top, b.top) - minOf(a.bottom, b.bottom))
                nMin = minOf(nMin, gap)
                if (gap < neighbourGap * s) problems += "${p.name}: ${californiaNumerals[i]} and ${californiaNumerals[(i + 1) % 12]} are ${"%.1f".format(gap)} px apart"
            }
            report.appendLine("${p.name} ${p.w}x${p.h}: numeral ${"%.1f".format(s)} px; nearest tick ${"%.1f".format(tickMin)} px (${"%.2f".format(tickMin / s)} of a numeral, $tickWorst); nearest neighbours ${"%.1f".format(nMin)} px (${"%.2f".format(nMin / s)})")
            inst.uiAutomation.takeScreenshot()?.let { d -> File(dir, "${p.name}-${d.width}x${d.height}.png").outputStream().use { d.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        File(dir, "gaps.txt").writeText(report.toString())
        android.util.Log.i("ClassicGapTest", report.toString())
        assertTrue(problems.joinToString("\n", postfix = "\n$report"), problems.isEmpty())
    }
}
