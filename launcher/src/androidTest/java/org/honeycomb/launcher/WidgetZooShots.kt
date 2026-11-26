package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Evidence, not a gate (runs only with `-e zoo 1`): every widget installed on the device, one at a time in the Smart
 * Stack, shot and measured (its card's height and the empty space above and below its content, in dp), into
 * evidence/widget-zoo/zoo.tsv and one PNG per widget, plus the stack's top, empty and with one widget.
 */
@RunWith(AndroidJUnit4::class)
class WidgetZooShots {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val fit = WidgetFit(rule)

    @Test fun everyWidget() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("zoo") != null)
        val dir = File(fit.ctx.getExternalFilesDir(null), "evidence/widget-zoo").apply { deleteRecursively(); mkdirs() }
        fun screen(name: String) { rule.waitForIdle(); Thread.sleep(1500); rule.waitForIdle()
            File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) } }
        fit.removeStub(); fit.installStub(); fit.allowBinding(); fit.clear()
        fit.openStack()
        screen("stack-top-empty")
        val d = fit.ctx.resources.displayMetrics.density
        val providers = fit.mgr.installedProviders.filter { it.provider.packageName != fit.ctx.packageName }
            .sortedBy { (if (it.provider.packageName == WidgetFit.STUB) "0" else "1") + it.provider.flattenToString() }
        val tsv = StringBuilder("file\tapp\twidget\tminHeightDp\tcardHeightDp\temptyAboveDp\temptyBelowDp\n")
        providers.forEachIndexed { i, p ->
            fit.clear()
            // let the last card leave the composition, so this widget gets a fresh host view
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("widget-0").fetchSemanticsNodes().isEmpty() }
            val id = runCatching { fit.add(p.provider) }.getOrNull() ?: return@forEachIndexed
            val name = "%02d-%s".format(i, p.provider.className.substringAfterLast('.').replace('$', '-'))
            runCatching {
                fit.card(id); Thread.sleep(2500); rule.waitForIdle()
                val card = fit.card(id); val c = fit.content(id)
                File(dir, "$name.png").outputStream().use { rule.onNodeWithTag("widget-$id").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (p.provider.className.endsWith("Row")) screen("stack-top-row")
                val app = fit.ctx.packageManager.getApplicationLabel(fit.ctx.packageManager.getApplicationInfo(p.provider.packageName, 0))
                fun dp(px: Int) = "%.0f".format(px / d)
                tsv.append("$name.png\t$app\t${p.loadLabel(fit.ctx.packageManager)}\t${dp(p.minHeight)}\t${dp(card.height())}\t")
                    .append(if (c == null) "\t\n" else "${dp(c.top - card.top)}\t${dp(card.bottom - c.bottom)}\n")
            }.onFailure { tsv.append("$name.png\t${p.provider.packageName}\t${p.provider.className}\tERROR ${it.message?.take(80)}\n") }
        }
        File(dir, "zoo.tsv").writeText(tsv.toString())
        fit.clear(); fit.removeStub()
    }
}
