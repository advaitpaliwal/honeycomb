package org.honeycomb.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Honeycomb as the default HOME app: Back on the face does nothing (Launcher3 never finishes on Back), and first-run setup
 * runs in its own task, so the home gesture during setup reaches the face and marks setup seen.
 */
class BackHomeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instrumentation.targetContext
    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setClassName(ctx, LauncherActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun shell(cmd: String) = instrumentation.uiAutomation.executeShellCommand(cmd).close()

    private fun resumed(): Activity? {
        var top: Activity? = null
        instrumentation.runOnMainSync { top = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return top
    }

    private fun waitFor(ms: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }
        fail("Timed out after $ms ms waiting for $what (resumed: ${resumed()})")
    }

    @Before fun beHome() {
        shell("cmd package set-home-activity ${ctx.packageName}/.LauncherActivity")
    }

    @After fun leaveSetupFinished() {
        Setup.finish(ctx)
        ctx.startActivity(homeIntent())
    }

    @Test fun backOnTheFaceKeepsTheSameHome() {
        Setup.finish(ctx)
        ctx.startActivity(homeIntent())
        waitFor(10_000, "the home") { resumed() is LauncherActivity }
        val home = resumed() as LauncherActivity
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        shell("input keyevent KEYCODE_BACK")
        Thread.sleep(2_000)
        instrumentation.waitForIdleSync()
        assertFalse("Back finished the home", home.isFinishing || home.isDestroyed)
        assertSame("Back replaced the home activity", home, resumed())
    }

    @Test fun homeGestureDuringSetupReachesTheFace() {
        assumeTrue("setup needs a missing step to show", SetupFlow.shows(false, Setup.granted(ctx)))
        ctx.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putBoolean("done", false).commit()
        // a cold home, so its first run opens setup
        resumed()?.let { a -> instrumentation.runOnMainSync { a.finish() } }
        Thread.sleep(500)
        ctx.startActivity(homeIntent())
        waitFor(15_000, "setup over the home") { resumed() is SetupActivity }
        Thread.sleep(1_000)
        homeGesture()
        waitFor(10_000, "the face after the home gesture") { resumed() is LauncherActivity }
        waitFor(5_000, "setup marked seen") { Setup.done(ctx) }
    }

    /** The real home gesture: a swipe up from the bottom edge with gesture navigation, the Home key otherwise. */
    private fun homeGesture() {
        if (Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", -1) == 2) {
            val b = ctx.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val x = b.width() / 2
            shell("input swipe $x ${b.height() - 2} $x ${b.height() / 2} 80") // a fling, so it is Home, not Recents
        } else shell("input keyevent KEYCODE_HOME")
    }
}
