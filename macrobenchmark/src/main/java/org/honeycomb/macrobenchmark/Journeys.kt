package org.honeycomb.macrobenchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test

const val TARGET = "org.honeycomb.launcher"

/** Everything first-run setup asks for, granted from the shell, so the home opens straight onto the face. */
fun grantSetup() {
    val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    listOf(
        "cmd role add-role-holder android.app.role.HOME $TARGET 0",
        "cmd notification allow_listener $TARGET/$TARGET.MediaListener",
        "cmd notification allow_dnd $TARGET",
        "appops set $TARGET GET_USAGE_STATS allow",
        "appops set $TARGET WRITE_SETTINGS allow",
        "media volume --stream 3 --set 0",
    ).forEach { ui.executeShellCommand(it).close() }
}

/** Opens the home on the face, with nothing over it. */
fun MacrobenchmarkScope.home() {
    pressHome()
    startActivityAndWait(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(TARGET, "$TARGET.LauncherActivity")))
    // first-run setup asks for one thing a shell can't grant (Quiet's choice); skip through it once, then it never opens again
    repeat(10) {
        val b = device.findObject(By.text("Skip")) ?: device.findObject(By.text("Done")) ?: device.findObject(By.text("Got it"))
            ?: return@repeat
        b.click(); Thread.sleep(800)
    }
    device.waitForIdle()
}


/** Chooses the face called [label] in the face picker (touch and hold, then the picker's own pages). */
fun MacrobenchmarkScope.pickFace(label: String) {
    repeat(3) { if (device.findObject(By.text("Edit")) == null) hold() }
    val y = device.displayHeight / 2
    // look one way through the picker's pages, then the other
    for (towardsStart in listOf(true, false)) repeat(6) {
        device.findObject(By.desc(label))?.let { it.click(); Thread.sleep(1200); return }
        if (towardsStart) device.swipe(device.displayWidth / 5, y, device.displayWidth * 4 / 5, y, 15)
        else device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 15)
        Thread.sleep(700)
    }
    error("no face called $label in the picker")
}

fun MacrobenchmarkScope.hold() {
    val x = device.displayWidth / 2; val y = device.displayHeight / 2
    device.executeShellCommand("input swipe $x $y ${x + 1} ${y + 1} 900")
    Thread.sleep(1200)
}

fun MacrobenchmarkScope.stackUpAndDown() {
    val x = device.displayWidth / 2
    device.swipe(x, device.displayHeight * 3 / 4, x, device.displayHeight / 4, 20); settle()
    device.swipe(x, device.displayHeight / 4, x, device.displayHeight * 3 / 4, 20); settle()
}

fun MacrobenchmarkScope.centre() { device.click(device.displayWidth / 2, device.displayHeight / 2) }
fun MacrobenchmarkScope.settle(ms: Long = 1200) { Thread.sleep(ms) }
/** A swipe right across the middle: in a level (the dynamic grid to the full grid). */
fun MacrobenchmarkScope.swipeIn() {
    val y = device.displayHeight / 2
    device.swipe(device.displayWidth / 4, y, device.displayWidth * 3 / 4, y, 20)
}

/**
 * Honeycomb's journeys, each timed frame by frame (FrameTimingMetric: frameDurationCpuMs, frameOverrunMs) with the
 * Compose recompositions they cost (TraceSectionMetric on the runtime's own trace section).
 */
@OptIn(ExperimentalMetricApi::class)
class Journeys {
    @get:Rule val rule = MacrobenchmarkRule()

    @Before fun setUp() = grantSetup()

    private fun journey(setup: MacrobenchmarkScope.() -> Unit = {}, body: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = TARGET,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric("Recomposer:recompose", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.DEFAULT,
        iterations = ITERATIONS,
        setupBlock = { home(); setup() },
        measureBlock = body,
    )

    /** The face idling for 5 s (the face chosen beforehand: Split's per-second glide, Classic's second hand, Stacked). */
    @Test fun faceIdle() = journey { settle(5000) }

    /** A tap on the face: the dynamic grid (or, on a tall screen, the apps) zooms in over the blurred face, and back. */
    @Test fun tapToGrid() = journey {
        centre(); settle(); device.pressBack(); settle()
    }

    /** A swipe right on the dynamic grid: the full grid zooms in. */
    @Test fun gridToFullGrid() = journey {
        centre(); settle()
        swipeIn(); settle()
        device.pressBack(); settle(); device.pressBack(); settle()
    }

    /** The full grid with all 146 apps flung to the bottom and back (measured only: the stuck lane owns its scroll). */
    @Test fun fullGridScroll() = journey {
        centre(); settle()
        swipeIn(); settle()
        val x = device.displayWidth / 2; val lo = device.displayHeight * 3 / 4; val hi = device.displayHeight / 4
        repeat(4) { device.swipe(x, lo, x, hi, 8); settle(400) }
        repeat(4) { device.swipe(x, hi, x, lo, 8); settle(400) }
        device.pressBack(); settle(); device.pressBack(); settle()
    }

    /** The Smart Stack: raised by a swipe up from the face and lowered by a swipe down. */
    @Test fun smartStack() = journey { stackUpAndDown() }

    /** The top-edge sheets: Notification Centre from the left, Control Centre from the right. */
    @Test fun topSheets() = journey {
        val h = device.displayHeight
        device.swipe(device.displayWidth / 4, 5, device.displayWidth / 4, h * 2 / 3, 20); settle(); device.pressBack(); settle()
        device.swipe(device.displayWidth * 5 / 6, 5, device.displayWidth * 5 / 6, h * 2 / 3, 20); settle(); device.pressBack(); settle()
    }

    /** An app opened from the grid and the way back home. */
    @Test fun openAppAndHome() = journey {
        centre(); settle()
        val app = device.findObject(By.desc("Settings")) ?: device.findObject(By.desc("Clock"))
        app?.click()
        device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 3000); settle(800)
        device.pressHome(); settle()
    }

    /** The face picker: touch and hold, swipe between faces, back. */
    @Test fun facePicker() = journey {
        hold()
        val y = device.displayHeight / 2
        repeat(2) { device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 15); settle(700) }
        repeat(2) { device.swipe(device.displayWidth / 5, y, device.displayWidth * 4 / 5, y, 15); settle(700) }
        device.pressBack(); settle()
    }

    companion object {
        val ITERATIONS = InstrumentationRegistry.getArguments().getString("iterations")?.toInt() ?: 3
    }
}
