package org.honeycomb.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tiny Live Update poster, after Google's sample (developer.android.com/develop/ui/views/notifications/live-update):
 * an ongoing ProgressStyle notification that requests promotion, as a timer app's would: a chronometer counting down
 * to its end, and its progress. [post] leaves it up so the
 * Smart Stack can be looked at; [cancel] takes it down.
 */
@RunWith(AndroidJUnit4::class)
class LiveUpdateTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    @SdkSuppress(minSdkVersion = 36)
    @Test fun post() = post(nm, ctx)

    @Test fun cancel() { nm.cancel(ID) }

    companion object {
        const val ID = 4242

        /** Posts the countdown and checks the listener will read it as live (on 36.0, by its request; on 36.1, promoted). */
        fun post(nm: NotificationManager, ctx: android.content.Context) {
            // honeycomb-setup.sh grants it; UiAutomation is asked only when it has not (agent-device's helper may hold it)
            if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            nm.createNotificationChannel(NotificationChannel("live", "Live updates", NotificationManager.IMPORTANCE_DEFAULT))
            val n = Notification.Builder(ctx, "live")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Pasta")
                .setContentText("Timer")
                .setColor(0xFFFF9F0A.toInt())
                .setWhen(System.currentTimeMillis() + 7 * 60_000L + 30_000L)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                // the extra itself: Builder.setRequestPromotedOngoing is not in the API 36.0 framework, only in later releases
                .addExtras(android.os.Bundle().apply { putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true) })
                .setStyle(Notification.ProgressStyle().setProgress(620).setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000))))
                .build()
            nm.notify(ID, n)
            Thread.sleep(1500)
            val posted = nm.activeNotifications.first { it.id == ID }
            assertTrue("promoted: flags=${posted.notification.flags}", LiveUpdate.isLive(posted.notification.flags, posted.notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING)))
        }
    }
}
