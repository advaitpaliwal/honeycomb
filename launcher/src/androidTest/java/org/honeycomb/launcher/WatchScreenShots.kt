package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.Native
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/** Stacked, Split and Hour edge to edge on the whole screen, a blue wallpaper's scheme, dark and light, captured as the screen shows them. */
@RunWith(AndroidJUnit4::class)
class WatchScreenShots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun stackedSplitAndHourDarkAndLight() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "evidence/watch-screen").apply { mkdirs() }
        fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 31) }.timeInMillis
        val before = HoneycombColors.scheme to HoneycombColors.dark
        val shown = mutableStateOf(Triple(FacePalette.seeded(Color(0xFF2F6BFF), true), true, FaceKind.Stacked))
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            val (scheme, dark, kind) = shown.value
            BoxWithConstraints(Modifier.fillMaxSize().background(Native.ground(scheme, dark))) {
                CompositionLocalProvider(LocalScreen provides Size(maxWidth.value, maxHeight.value)) {
                    Box(Modifier.fillMaxSize()) { AnyFace(kind, if (kind == FaceKind.Hour) at(10, 9) else at(9, 41)) }
                }
            }
        }
        try {
            for (kind in listOf(FaceKind.Stacked, FaceKind.Split, FaceKind.Hour)) for (dark in listOf(true, false)) {
                val scheme = FacePalette.seeded(Color(0xFF2F6BFF), dark)
                rule.runOnIdle { HoneycombColors.scheme = scheme; HoneycombColors.dark = dark; shown.value = Triple(scheme, dark, kind) }
                rule.waitForIdle()
                Thread.sleep(300)
                val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(dir, "${kind.name.lowercase()}-${if (dark) "dark" else "light"}.png").outputStream().use {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally { rule.runOnIdle { HoneycombColors.scheme = before.first; HoneycombColors.dark = before.second } }
    }
}
