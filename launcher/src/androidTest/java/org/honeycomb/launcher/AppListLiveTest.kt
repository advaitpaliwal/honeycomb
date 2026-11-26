package org.honeycomb.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The all-apps grid follows installs and uninstalls while the home is open, as Launcher3's does (LauncherApps.Callback):
 * the :stub app shows up after its install and is gone after its removal, with no restart.
 *
 * The wait starts at the system's own package broadcast, not at `pm install`'s return: on a loaded emulator the
 * broadcast itself can come seconds late, and that is the package manager's time, not the home's. The grid then gets
 * [FOLLOW_MS] to follow; the 2 s a person would notice is [TARGET_MS], logged under [TAG] each run, not asserted.
 */
@RunWith(AndroidJUnit4::class)
class AppListLiveTest {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val stub = "com.example.livestub"
    private val label = "Aaa Stub"

    private fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(cmd),
    ).use { it.readBytes().decodeToString() }

    @After fun clean() {
        shell("pm uninstall $stub")
        shell("rm -f /data/local/tmp/livestub.apk")
    }

    private fun shown() = rule.onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun gridFollowsInstallAndUninstall() {
        shell("pm uninstall $stub")
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
        // Open the full grid (on a short screen the face opens the favourites first).
        rule.onNodeWithTag("faces").performTouchInput { click(center) }
        if (!tallDevice(instrumentation.targetContext)) {
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("favourites").fetchSemanticsNodes().isNotEmpty() }
            rule.allAppsAction()
        }
        rule.waitForIdle()
        check(!shown()) { "$label listed before its install" }

        // Through the home's external files dir to /data/local/tmp, the one place system_server's installer may read.
        val apk = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "stub.apk")
        instrumentation.context.assets.open("stub.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        shell("cp ${apk.absolutePath} /data/local/tmp/livestub.apk")
        apk.delete()
        val added = afterBroadcast(Intent.ACTION_PACKAGE_ADDED) {
            val installed = shell("pm install -r /data/local/tmp/livestub.apk")
            check("Success" in installed) { "pm install: $installed" }
        }
        follows("install", added) { shown() }

        val gone = afterBroadcast(Intent.ACTION_PACKAGE_REMOVED) {
            val removed = shell("pm uninstall $stub")
            check("Success" in removed) { "pm uninstall: $removed" }
        }
        follows("uninstall", gone) { !shown() }
    }

    /** Runs [act] and returns the uptime at which the system broadcast [action] for the stub. */
    private fun afterBroadcast(action: String, act: () -> Unit): Long {
        val ctx = instrumentation.targetContext
        val at = CountDownLatch(1)
        var time = 0L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.data?.schemeSpecificPart == stub) { time = SystemClock.uptimeMillis(); at.countDown() }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(action).apply { addDataScheme("package") }, Context.RECEIVER_EXPORTED)
        try {
            act()
            check(at.await(BROADCAST_MS, TimeUnit.MILLISECONDS)) { "no $action for $stub within $BROADCAST_MS ms" }
        } finally { ctx.unregisterReceiver(receiver) }
        return time
    }

    /** Waits up to [FOLLOW_MS] from the broadcast for the grid to follow, and logs how long it took against [TARGET_MS]. */
    private fun follows(what: String, since: Long, done: () -> Boolean) {
        rule.waitUntil("the grid follows the $what", maxOf(1, FOLLOW_MS - (SystemClock.uptimeMillis() - since))) { done() }
        val took = SystemClock.uptimeMillis() - since
        val line = "grid followed the $what $took ms after its broadcast (target $TARGET_MS ms)"
        if (took <= TARGET_MS) Log.i(TAG, line) else Log.w(TAG, "OVER TARGET: $line")
    }

    private companion object {
        const val TAG = "AppListLive"
        const val TARGET_MS = 2_000L
        const val FOLLOW_MS = 10_000L
        const val BROADCAST_MS = 30_000L
    }
}
