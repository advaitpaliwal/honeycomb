package org.honeycomb.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process

/** How far the face behind the dynamic grid blurs, watch px on watchOS's 422 px wide simulator screen (clip 01). */
internal const val FACE_BLUR_PX = 26f

// ---- recent apps --------------------------------------------------------------------------------------------------

private const val COUNTS = "launch_counts"

/** The home screen counts each app it opens, for when usage access is not granted. */
fun recordLaunch(ctx: Context, component: ComponentName) {
    val counts = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE)
    counts.edit().putInt(component.packageName, counts.getInt(component.packageName, 0) + 1).apply()
}

private fun usageGranted(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED
}

/** The window use is counted over, as watchOS's grid follows recent use. */
private const val USE_WINDOW_MS = 7 * 24 * 3600_000L

/**
 * How many times each package came to the foreground over the last week (UsageStatsManager.queryEvents,
 * ACTIVITY_RESUMED), or null without usage access.
 */
private fun openCounts(ctx: Context): Map<String, Int>? {
    if (!usageGranted(ctx)) return null
    val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
    val now = System.currentTimeMillis()
    val events = usm.queryEvents(now - USE_WINDOW_MS, now) ?: return null
    val counts = HashMap<String, Int>()
    val e = UsageEvents.Event()
    while (events.getNextEvent(e)) if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) counts.merge(e.packageName, 1, Int::plus)
    return counts
}

/** Packages from [counts], most opened first; ties by name, so the order is stable. Honeycomb itself is left out. */
fun byCount(counts: Map<String, Int>, self: String): List<String> =
    counts.filter { (p, n) -> n > 0 && p != self }.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }

/**
 * Packages most used first: how often each was opened over the last week with usage access, else how often Honeycomb
 * opened it; empty when neither knows any (the grids then fall back to alphabetical).
 */
fun usedPackages(ctx: Context): List<String> =
    openCounts(ctx)?.let { byCount(it, ctx.packageName) }?.takeIf { it.isNotEmpty() }
        ?: byCount(ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap(), ctx.packageName)
