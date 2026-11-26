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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.honeycomb.launcher.design.HoneycombColors
import org.honeycomb.launcher.design.Native
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/** The full-screen Classic and Plain faces at Apple's 10:09, dark and light, captured as the whole screen (system bars included). */
@RunWith(AndroidJUnit4::class)
class DialFaceShots {
    @get:Rule val rule = createComposeRule()

    @Test fun classicAndPlainDarkAndLight() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dir = File(inst.targetContext.getExternalFilesDir(null), "evidence/dial-faces").apply { mkdirs() }
        val ms = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 9); set(Calendar.SECOND, 31) }.timeInMillis
        val before = HoneycombColors.scheme to HoneycombColors.dark
        val shown = mutableStateOf(Triple(FacePalette.seeded(Color(0xFF2F6BFF), true), true, FaceKind.Classic))
        rule.setContent {
            val (scheme, dark, kind) = shown.value
            BoxWithConstraints(Modifier.fillMaxSize().background(Native.ground(scheme, dark))) {
                CompositionLocalProvider(LocalScreen provides Size(maxWidth.value, maxHeight.value)) {
                    Box(Modifier.fillMaxSize()) { AnyFace(kind, ms) }
                }
            }
        }
        try {
            for (kind in listOf(FaceKind.Classic, FaceKind.Plain)) for (dark in listOf(true, false)) {
                val scheme = FacePalette.seeded(Color(0xFF2F6BFF), dark)
                rule.runOnIdle { HoneycombColors.scheme = scheme; HoneycombColors.dark = dark; shown.value = Triple(scheme, dark, kind) }
                rule.waitForIdle()
                Thread.sleep(400)
                val shot = inst.uiAutomation.takeScreenshot()
                File(dir, "${kind.name.lowercase()}-${if (dark) "dark" else "light"}-${shot.width}x${shot.height}.png").outputStream().use {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally { rule.runOnIdle { HoneycombColors.scheme = before.first; HoneycombColors.dark = before.second } }
    }
}
