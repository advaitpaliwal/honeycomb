package org.honeycomb.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.Native
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/** The Hour face at Apple's 10:09 for a blue and a green wallpaper's scheme, dark and light, on the ground the home draws. */
@RunWith(AndroidJUnit4::class)
class HourFaceShots {
    @get:Rule val rule = createComposeRule()

    @Test fun blueAndGreenDarkAndLight() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "evidence/hour-face").apply { mkdirs() }
        val ms = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 9); set(Calendar.SECOND, 31) }.timeInMillis
        val before = HoneycombColors.scheme to HoneycombColors.dark
        val shown = mutableStateOf(FacePalette.seeded(Color(0xFF2F6BFF), true) to true)
        rule.setContent {
            val (scheme, dark) = shown.value
            BoxWithConstraints(Modifier.fillMaxSize().background(Native.ground(scheme, dark))) {
                CompositionLocalProvider(LocalScreen provides Size(maxWidth.value, maxHeight.value)) {
                    Box(Modifier.fillMaxSize()) { AnyFace(FaceKind.Hour, ms) }
                }
            }
        }
        try {
            for ((name, seed) in listOf("blue" to 0xFF2F6BFF, "green" to 0xFF2E8B57)) for (dark in listOf(true, false)) {
                val scheme = FacePalette.seeded(Color(seed), dark)
                rule.runOnIdle { HoneycombColors.scheme = scheme; HoneycombColors.dark = dark; shown.value = scheme to dark }
                rule.waitForIdle()
                File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use {
                    rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally { rule.runOnIdle { HoneycombColors.scheme = before.first; HoneycombColors.dark = before.second } }
    }
}
