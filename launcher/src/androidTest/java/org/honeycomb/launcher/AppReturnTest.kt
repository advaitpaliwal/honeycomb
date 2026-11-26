package org.honeycomb.launcher

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class AppReturnTest {
    // Holds the HOME role, as HomeOnFaceTest and BackHomeTest do: on a phone whose home is another launcher, the HOME
    // intent below lands there and the face never shows.
    @get:Rule(order = -1) val beHome = object : ExternalResource() {
        override fun before() {
            val inst = InstrumentationRegistry.getInstrumentation()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                inst.uiAutomation.executeShellCommand("cmd package set-home-activity ${inst.targetContext.packageName}/.LauncherActivity")
            ).bufferedReader().use { it.readText() }
        }
    }
    @get:Rule(order = 0) val setup = SetupFinished()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun homeIntent() = Intent(ctx, LauncherActivity::class.java).setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // CATEGORY_LAUNCHER and CATEGORY_HOME can create separate tasks on Android 14.
    @get:Rule(order = 1) val rule = AndroidComposeTestRule(ActivityScenarioRule<LauncherActivity>(homeIntent())) { owner ->
        lateinit var activity: LauncherActivity
        owner.scenario.onActivity { activity = it }
        activity
    }

    @Test fun homeFromAnAppOpenedFromTheListReturnsToTheFace() {
        AppOrder.saveGrid(ctx, false)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
        rule.runOnIdle { rule.activity.input(Input.Tap) }
        rule.runOnIdle { rule.activity.input(Input.AllApps) }
        val cameraPackage = requireNotNull(Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(ctx.packageManager)) {
            "No default image capture activity"
        }.packageName
        val camera = AppCache.apps.value!!.first { it.component.packageName == cameraPackage }
        val row = rule.onNodeWithTag("row-${camera.component.flattenToString()}")
        row.performScrollTo()
        val activity = rule.activity
        row.performTouchInput { click(center) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.waitUntil(10_000) { !activity.hasWindowFocus() }
        instrumentation.targetContext.startActivity(homeIntent())
        rule.onNodeWithTag("faces").assertIsDisplayed()
    }
}
