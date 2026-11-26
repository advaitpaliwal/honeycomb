package org.honeycomb.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Generates launcher/src/main/baseline-prof.txt from the same journeys Journeys times, so the code they run ships
 * compiled (developer.android.com/topic/performance/baselineprofiles/create-baselineprofile). Run on an API 33+ device:
 * am instrument -e class org.honeycomb.macrobenchmark.BaselineProfileGenerator, then copy the *-baseline-prof.txt it writes.
 */
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() {
        grantSetup()
        rule.collect(packageName = TARGET, includeInStartupProfile = true) {
            home()
            settle(2500)
            // the dynamic grid and the full grid, flung to the end and back
            centre(); settle()
            swipeIn(); settle()
            val x = device.displayWidth / 2; val lo = device.displayHeight * 3 / 4; val hi = device.displayHeight / 4
            repeat(3) { device.swipe(x, lo, x, hi, 8); settle(400) }
            repeat(3) { device.swipe(x, hi, x, lo, 8); settle(400) }
            device.pressBack(); settle(); device.pressBack(); settle()
            // the Smart Stack
            stackUpAndDown()
            val y = device.displayHeight / 2
            // the top-edge sheets
            device.swipe(device.displayWidth / 4, 5, device.displayWidth / 4, device.displayHeight * 2 / 3, 20); settle(); device.pressBack(); settle()
            device.swipe(device.displayWidth * 5 / 6, 5, device.displayWidth * 5 / 6, device.displayHeight * 2 / 3, 20); settle(); device.pressBack(); settle()
            // the face picker, through every face
            hold()
            repeat(6) { device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 15); settle(700) }
            repeat(6) { device.swipe(device.displayWidth / 5, y, device.displayWidth * 4 / 5, y, 15); settle(700) }
            device.pressBack(); settle()
            // an app opened from the grid and home again
            centre(); settle()
            device.findObject(androidx.test.uiautomator.By.desc("Settings"))?.click(); settle(1500)
            device.pressHome(); settle()
        }
    }
}
