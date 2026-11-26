package org.honeycomb.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * A Live Activity / Live Update shown at the top of the Smart Stack and on the Info watch face:
 * matches Android 16 promoted Live Updates (FLAG_PROMOTED_ONGOING), ProgressStyle notifications,
 * and active ongoing live activities (rides like Uber/Lyft, deliveries like DoorDash/Uber Eats,
 * navigation like Google Maps/Waze, active timers/calls/flights) while filtering out media players
 * (handled by Now Playing) and silent system/VPN background services.
 */
class LiveUpdate(
    val key: String, val pkg: String, val app: String, val title: String, val text: String?, val chip: String?,
    val chronometer: Long?, val countDown: Boolean, val progress: Float?, val icon: Icon?, val color: Int, val tap: PendingIntent?,
    val largeIcon: Icon? = null,
) {
    /** The one live value: a running chronometer, else the app's short critical text, else its text. */
    fun value(now: Long): String? = chronometer?.let { chronoText(if (countDown) it - now else now - it) } ?: chip ?: text

    /** True when the live value is a compact metric (chronometer or short critical chip) suited for large display numerals. */
    val hasShortMetric: Boolean get() = chronometer != null || !chip.isNullOrBlank()

    companion object {
        /**
         * A Live Update is an ongoing notification the system promoted. Android 16's first release (36.0) takes the
         * request but never promotes (promotion ships in 36.1), so there an ongoing notification that asked for it counts.
         */
        fun isLive(flags: Int, requested: Boolean, sdkFull: Int = Build.VERSION.SDK_INT_FULL): Boolean = when {
            sdkFull >= 3600001 -> flags and FLAG_PROMOTED_ONGOING != 0
            else -> requested && flags and Notification.FLAG_ONGOING_EVENT != 0
        }

        /** Notification.FLAG_PROMOTED_ONGOING (API 36). */
        const val FLAG_PROMOTED_ONGOING = 0x00040000

        private const val PROGRESS_STYLE_TEMPLATE = "android.app.Notification\$ProgressStyle"

        private val EXCLUDED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.vending",
            "com.google.android.gms",
            "com.android.providers.downloads",
            "com.tailscale.ipn",
        )

        private val EXCLUDED_CATEGORIES = setOf(
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_TRANSPORT,
        )

        private val LIVE_CATEGORIES = setOf(
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_STOPWATCH,
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_WORKOUT,
            Notification.CATEGORY_LOCATION_SHARING,
            Notification.CATEGORY_PROGRESS,
        )

        private val LIVE_ACTIVITY_PACKAGES = setOf(
            "com.ubercab",
            "com.ubercab.eats",
            "me.lyft.android",
            "com.dd.doordash",
            "com.instacart.client",
            "com.grubhub.android",
            "com.google.android.apps.maps",
            "com.waze",
            "com.flightyapp.flighty",
            "com.united.mobile.android",
            "com.delta.mobile.android",
            "com.aa.android",
            "com.southwestairlines.mobile",
            "com.google.android.deskclock",
        )

        /** Pure decision helper for unit tests and [liveUpdates]. */
        internal fun matchesLiveActivity(
            pkg: String,
            flags: Int,
            requestedPromoted: Boolean,
            category: String?,
            template: String?,
            hasMediaSession: Boolean,
            hasChronometer: Boolean,
            hasProgress: Boolean,
            hasContent: Boolean,
            sdkFull: Int = Build.VERSION.SDK_INT_FULL,
        ): Boolean {
            if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
            if (isLive(flags, requestedPromoted, sdkFull)) return true
            if (flags and Notification.FLAG_ONGOING_EVENT == 0) return false
            if (hasMediaSession) return false
            if (pkg in EXCLUDED_PACKAGES) return false
            if (category in EXCLUDED_CATEGORIES) return false
            if (!hasContent && !hasChronometer && !hasProgress) return false
            return template == PROGRESS_STYLE_TEMPLATE ||
                hasProgress ||
                hasChronometer ||
                category in LIVE_CATEGORIES ||
                pkg in LIVE_ACTIVITY_PACKAGES
        }

        /** Extracts 0..1 progress from standard EXTRA_PROGRESS_MAX or Android 16 Notification.ProgressStyle segments. */
        internal fun extractProgress(progress: Int, max: Int, indeterminate: Boolean, segmentTotal: Int = 0): Float? {
            if (indeterminate) return null
            if (max > 0) return (progress.toFloat() / max).coerceIn(0f, 1f)
            if (segmentTotal > 0 && progress >= 0) return (progress.toFloat() / segmentTotal).coerceIn(0f, 1f)
            return null
        }
    }
}

/** "4:59" or "1:04:59", as a chronometer counts. */
fun chronoText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Suppress("DEPRECATION")
private fun progressSegmentTotal(extras: Bundle): Int {
    val list = extras.getParcelableArrayList<Bundle>("android.progressSegments") ?: return 0
    return list.sumOf { it.getInt("length", 0).coerceAtLeast(0) }
}

private fun extractProgress(extras: Bundle): Float? {
    val p = extras.getInt(Notification.EXTRA_PROGRESS, -1)
    val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
    val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
    val segTotal = if (max <= 0) progressSegmentTotal(extras) else 0
    return LiveUpdate.extractProgress(if (p < 0) 0 else p, max, indeterminate, segTotal)
}

/** The Live Updates and ongoing Live Activities now active, newest first, read through Honeycomb's notification listener. */
fun liveUpdates(ctx: Context): List<LiveUpdate> {
    val listener = MediaListener.Defaults.live ?: return emptyList()
    val active = runCatching { listener.activeNotifications }.getOrNull() ?: return emptyList()
    return active.filter { sbn ->
        val n = sbn.notification
        val x = n.extras
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        LiveUpdate.matchesLiveActivity(
            pkg = sbn.packageName,
            flags = n.flags,
            requestedPromoted = x.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, false),
            category = n.category,
            template = x.getString(Notification.EXTRA_TEMPLATE),
            hasMediaSession = x.containsKey(Notification.EXTRA_MEDIA_SESSION),
            hasChronometer = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L,
            hasProgress = extractProgress(x) != null,
            hasContent = title.isNotBlank() || text.isNotBlank(),
        )
    }.sortedByDescending { it.postTime }.map { liveUpdate(ctx, it) }
}

private fun liveUpdate(ctx: Context, sbn: StatusBarNotification): LiveUpdate {
    val n = sbn.notification
    val x = n.extras
    val app = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
        .getOrDefault(sbn.packageName)
    val progress = extractProgress(x)
    val chrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L
    return LiveUpdate(
        key = sbn.key, pkg = sbn.packageName, app = app,
        title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { app },
        text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.ifBlank { null },
        chip = if (Build.VERSION.SDK_INT >= 36) n.shortCriticalText else null,
        chronometer = if (chrono) n.`when` else null,
        countDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
        progress = progress, icon = n.smallIcon, color = n.color, tap = n.contentIntent,
        largeIcon = n.getLargeIcon(),
    )
}
