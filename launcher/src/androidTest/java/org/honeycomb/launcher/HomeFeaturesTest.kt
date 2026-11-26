package org.honeycomb.launcher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual home activity and Android's widget host. */
@RunWith(AndroidJUnit4::class)
class HomeFeaturesTest {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(1200)
        rule.waitForIdle()
        val dir = File(ctx.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theHomeHoldsNoAssistantRoleOrPrivilegedPermission() {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
        val requested = info.requestedPermissions.orEmpty().toSet()
        listOf("android.permission.RECORD_AUDIO", "android.permission.WRITE_SECURE_SETTINGS", "android.permission.BIND_APPWIDGET").forEach {
            assertFalse("App must not request $it", it in requested)
        }
        assertFalse(info.services.orEmpty().any { it.permission == android.Manifest.permission.BIND_VOICE_INTERACTION })
        assertTrue(pm.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(ctx.packageName), 0).isEmpty())
        assertFalse(pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.packageName), 0).isEmpty())
    }
}
