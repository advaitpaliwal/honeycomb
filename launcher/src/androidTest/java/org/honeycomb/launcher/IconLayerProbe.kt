package org.honeycomb.launcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Read-only record of every launcher app's icon as the phone hands it to us (no assertions): its drawable type, its
 * layers and a PNG of each layer, written to the app's external files dir, icons-real/, and pulled with adb. Run with
 * `am instrument -e class org.honeycomb.launcher.IconLayerProbe` after installing the debug and test APKs.
 */
@RunWith(AndroidJUnit4::class)
class IconLayerProbe {
    private val size = 432 // 108 dp at 4 px per dp: an adaptive layer's whole square

    private fun png(d: Drawable?, file: File, px: Int = size) {
        d ?: return
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px); d.draw(Canvas(b))
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun describe(d: Drawable?): String = when (d) {
        null -> "null"
        is ColorDrawable -> "ColorDrawable #%08X".format(d.color)
        else -> "${d.javaClass.name} ${d.intrinsicWidth}x${d.intrinsicHeight}"
    }

    @Test fun dump() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        val dir = File(ctx.getExternalFilesDir(null), "icons-real/xxxhdpi").apply { deleteRecursively(); mkdirs() }
        val lines = StringBuilder()
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val name = ri.activityInfo.packageName + "__" + ri.activityInfo.name.substringAfterLast('.')
            // as installedApps loads it: the xxxhdpi resource when the app has one
            val icon = ri.activityInfo.iconResource.takeIf { it != 0 }?.let { id ->
                runCatching { pm.getResourcesForApplication(ri.activityInfo.applicationInfo).getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull()
            } ?: ri.loadIcon(pm)
            val line = StringBuilder("$name\tlabel=${ri.loadLabel(pm)}\ttype=${describe(icon)}")
            if (icon is AdaptiveIconDrawable) {
                line.append("\tbg=${describe(icon.background)}\tfg=${describe(icon.foreground)}\tmono=${describe(icon.monochrome)}")
                png(icon.background, File(dir, "$name.bg.png"))
                png(icon.foreground, File(dir, "$name.fg.png"))
                png(icon.monochrome, File(dir, "$name.mono.png"))
                // both layers together, unmasked, as the 108 dp square
                val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                for (l in listOf(icon.background, icon.foreground)) l?.run { setBounds(0, 0, size, size); draw(Canvas(b)) }
                File(dir, "$name.layers.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                png(icon, File(dir, "$name.legacy.png"))
            }
            File(dir, "$name.circle.png").outputStream().use { circleIcon(icon, 256, planIcon(icon)).compress(Bitmap.CompressFormat.PNG, 100, it) }
            lines.append(line).append('\n')
        }
        File(dir, "index.tsv").writeText(lines.toString())
    }

    /** Each app's grid icon as installedApps makes it, to after/. */
    @Test fun dumpCircles() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "icons-real/after").apply { deleteRecursively(); mkdirs() }
        for (app in installedApps(ctx)) {
            val name = app.component.packageName
            File(dir, "$name.grid.png").outputStream().use { app.icon.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
