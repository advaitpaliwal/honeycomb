package org.honeycomb.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Process
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** How far the face behind the dynamic grid blurs, watch px on watchOS's 422 px wide simulator screen (clip 01). */
internal const val FACE_BLUR_PX = 26f

// ---- pure-math contextual app predictor (zero LLM tokens, zero GPS, <1 ms on-device) -----------------------------

private const val COUNTS = "launch_counts"
private const val CONTEXT_PREFS = "launch_context"
private const val KEY_HISTORY = "history_v1"
private const val MAX_LOCAL_HISTORY = 240

/** The window UsageEvents are read over (7 days). */
private const val USE_WINDOW_MS = 7 * 24 * 3600_000L

/** Half-lives & kernels for the 6-signal pure-math predictor. */
private const val FAST_BURST_HALF_LIFE_MS = 20.0 * 60_000.0        // 20 min: party/event bursts (Partiful -> X -> Messages)
private const val SLOW_HABIT_HALF_LIFE_MS = 72.0 * 3600_000.0      // 72 hr: steady weekly staples
private const val MARKOV_CHAIN_GAP_MS = 3.0 * 60_000.0             // 3 min max gap between consecutive apps in a session
private const val MARKOV_RECENCY_WINDOW_MS = 10.0 * 60_000.0       // last app stays predictive for 10 min
private const val TOD_SIGMA_HOURS = 1.5                            // smooth circular Gaussian kernel (+/- 1.5 hr)
private const val NOTIF_HALF_LIFE_MS = 5.0 * 60_000.0              // 5 min exponential decay for unread notifications

private const val W_BURST = 2.4
private const val W_HABIT = 1.0
private const val W_MARKOV = 1.8
private const val W_TOD = 1.2
private const val W_CONTEXT = 1.1
private const val W_NOTIF = 2.2

/** One foreground launch event in the last 7 days. */
data class LaunchEvent(val pkg: String, val timestampMs: Long)

/** Passive zero-battery device context buckets (zero GPS polling, zero new Android permissions). */
data class DeviceContext(
    val onWifi: Boolean = false,
    val headphonesConnected: Boolean = false,
    val charging: Boolean = false,
) {
    fun activeBuckets(): List<String> = buildList {
        add(if (onWifi) "net_wifi" else "net_away")
        if (headphonesConnected) add("audio_hp")
        if (charging) add("pwr_chg")
    }
}

/** One unread notification signal for recency boosting. */
data class NotifSignal(val pkg: String, val postedAtMs: Long)

/** Result of contextual ranking: ordered package list (best first) + raw numeric score per package for hysteresis. */
data class ContextualRanking(
    val ordered: List<String>,
    val scores: Map<String, Double>,
)

/**
 * Pure-math, deterministic, <1 ms 6-signal contextual app ranker:
 *   1. Fast Burst Hawkes Decay (20 min half-life) — surfaces sudden bursts like Partiful at a party in 2 launches,
 *      and naturally fades them out ~2 hours later.
 *   2. Slow Habit Decay (72 hr half-life) — smooth weekly baseline without harsh 7-day cliff-edge resets.
 *   3. First-Order Markov Chain P(a | a_prev) — learns consecutive app chains within 3 min (Partiful -> X -> Messages,
 *      Uber -> Maps, Camera -> Photos).
 *   4. Circular Time-of-Day + Weekday/Weekend Gaussian Kernel (sigma = 1.5 hr, wraps midnight smoothly).
 *   5. Passive Context Lift — log-lift affinity for Wi-Fi vs Away, Headphones/BT audio, and Charging.
 *   6. Live Notification Recency (5 min half-life) — boosts apps with freshly arrived unread notifications.
 */
fun rankContextualPackages(
    events: List<LaunchEvent>,
    fallbackCounts: Map<String, Int>,
    contextCounts: Map<String, Map<String, Int>>,
    nowMs: Long,
    ctx: DeviceContext,
    activeNotifications: List<NotifSignal>,
    selfPkg: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ContextualRanking {
    val ln2 = ln(2.0)
    val sorted = events
        .filter { it.pkg.isNotEmpty() && it.pkg != selfPkg && it.timestampMs <= nowMs && (nowMs - it.timestampMs) <= USE_WINDOW_MS }
        .sortedBy { it.timestampMs }

    val burst = HashMap<String, Double>()
    val habit = HashMap<String, Double>()
    val tod = HashMap<String, Double>()
    val candidates = HashSet<String>()

    val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
    val nowHour = nowZdt.hour + nowZdt.minute / 60.0
    val nowWeekend = nowZdt.dayOfWeek == DayOfWeek.SATURDAY || nowZdt.dayOfWeek == DayOfWeek.SUNDAY
    val twoSigmaSq = 2.0 * TOD_SIGMA_HOURS * TOD_SIGMA_HOURS

    for (ev in sorted) {
        val dt = (nowMs - ev.timestampMs).coerceAtLeast(0L).toDouble()
        candidates.add(ev.pkg)
        burst.merge(ev.pkg, exp(-ln2 * dt / FAST_BURST_HALF_LIFE_MS), Double::plus)
        habit.merge(ev.pkg, exp(-ln2 * dt / SLOW_HABIT_HALF_LIFE_MS), Double::plus)

        val z = Instant.ofEpochMilli(ev.timestampMs).atZone(zoneId)
        val evHour = z.hour + z.minute / 60.0
        val rawDiff = abs(nowHour - evHour)
        val circDiff = minOf(rawDiff, 24.0 - rawDiff)
        val evWeekend = z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY
        val dayMatch = if (evWeekend == nowWeekend) 1.35 else 0.75
        val recencyFade = exp(-ln2 * dt / (14.0 * 24.0 * 3600_000.0))
        tod.merge(ev.pkg, exp(-(circDiff * circDiff) / twoSigmaSq) * dayMatch * recencyFade, Double::plus)
    }

    // Signal 3: First-order Markov transitions P(next | lastPkg) from consecutive launches within 3 min
    val markov = HashMap<String, Double>()
    val lastEv = sorted.lastOrNull()
    if (lastEv != null && (nowMs - lastEv.timestampMs) <= MARKOV_RECENCY_WINDOW_MS) {
        val prevPkg = lastEv.pkg
        val transitions = HashMap<String, Int>()
        var totalOut = 0
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (a.pkg == prevPkg && b.pkg != prevPkg && (b.timestampMs - a.timestampMs) in 0L..MARKOV_CHAIN_GAP_MS.toLong()) {
                transitions.merge(b.pkg, 1, Int::plus)
                totalOut++
            }
        }
        if (totalOut > 0) {
            val recencyScale = exp(-ln2 * (nowMs - lastEv.timestampMs).coerceAtLeast(0L).toDouble() / MARKOV_RECENCY_WINDOW_MS)
            val denom = totalOut + 2.0 // Laplace smoothing so 1 stray transition doesn't dominate
            for ((nextPkg, cnt) in transitions) {
                candidates.add(nextPkg)
                markov[nextPkg] = (cnt.toDouble() / denom) * recencyScale
            }
        }
    }

    // Signal 5: Passive device context log-lift (Wi-Fi vs Away, Headphones/BT, Charging)
    val contextLift = HashMap<String, Double>()
    val activeBuckets = ctx.activeBuckets()
    val globalTotals = HashMap<String, Int>()
    var grandTotal = 0
    for ((_, pkgMap) in contextCounts) {
        for ((pkg, c) in pkgMap) {
            if (pkg != selfPkg && c > 0) {
                globalTotals.merge(pkg, c, Int::plus)
                grandTotal += c
            }
        }
    }
    if (grandTotal >= 3) {
        for (bucket in activeBuckets) {
            val bMap = contextCounts[bucket] ?: continue
            val bTotal = bMap.entries.sumOf { (p, c) -> if (p != selfPkg && c > 0) c else 0 }
            if (bTotal < 2) continue
            for ((pkg, c) in bMap) {
                if (pkg == selfPkg || c <= 0) continue
                candidates.add(pkg)
                val pGivenC = (c + 0.5) / (bTotal + 3.0)
                val pGlobal = ((globalTotals[pkg] ?: 0) + 0.5) / (grandTotal + 3.0)
                val lift = ln(pGivenC / pGlobal).coerceIn(0.0, 2.2)
                if (lift > 0.0) contextLift.merge(pkg, lift, Double::plus)
            }
        }
    }

    // Signal 6: Live unread notification recency (5 min half-life)
    val notif = HashMap<String, Double>()
    for (n in activeNotifications) {
        if (n.pkg.isEmpty() || n.pkg == selfPkg || n.postedAtMs > nowMs + 60_000L) continue
        val age = (nowMs - n.postedAtMs).coerceAtLeast(0L).toDouble()
        if (age <= 60 * 60_000.0) {
            candidates.add(n.pkg)
            val boost = exp(-ln2 * age / NOTIF_HALF_LIFE_MS)
            // Keep max per package + small stacking bonus for multiple fresh pings
            notif[n.pkg] = maxOf(notif[n.pkg] ?: 0.0, boost) + 0.15 * boost
        }
    }

    // Include fallback counts for packages that haven't appeared in recent UsageEvents
    val maxFallback = fallbackCounts.entries.filter { it.key != selfPkg && it.value > 0 }.maxOfOrNull { it.value } ?: 0
    for ((pkg, c) in fallbackCounts) {
        if (pkg != selfPkg && c > 0) candidates.add(pkg)
    }

    if (candidates.isEmpty()) return ContextualRanking(emptyList(), emptyMap())

    // Normalize unbounded cumulative signals gently with log1p so a 2-launch party burst can beat a steady weekly app
    // without letting one runaway app crush every contextual signal.
    val scores = HashMap<String, Double>(candidates.size)
    for (pkg in candidates) {
        val sBurst = ln(1.0 + (burst[pkg] ?: 0.0) * 1.8)
        val sHabit = ln(1.0 + (habit[pkg] ?: 0.0) * 0.45)
        val sMarkov = markov[pkg] ?: 0.0
        val sTod = ln(1.0 + (tod[pkg] ?: 0.0))
        val sCtx = (contextLift[pkg] ?: 0.0).coerceAtMost(2.5)
        val sNotif = (notif[pkg] ?: 0.0).coerceAtMost(1.8)
        val sFallback = if (maxFallback > 0 && sorted.isEmpty()) {
            ln(1.0 + (fallbackCounts[pkg] ?: 0).toDouble()) * 0.55
        } else if (maxFallback > 0 && (habit[pkg] ?: 0.0) == 0.0) {
            0.25 * ((fallbackCounts[pkg] ?: 0).toDouble() / maxFallback.toDouble())
        } else 0.0

        val total = W_BURST * sBurst +
            W_HABIT * sHabit +
            W_MARKOV * sMarkov +
            W_TOD * sTod +
            W_CONTEXT * sCtx +
            W_NOTIF * sNotif +
            sFallback

        if (total > 0.0) scores[pkg] = total
    }

    val ordered = scores.entries
        .sortedWith(compareBy({ -it.value }, { it.key }))
        .map { it.key }
    return ContextualRanking(ordered, scores)
}

// ---- Android platform adapters (zero new permissions) -------------------------------------------------------------

/** Reads passive Wi-Fi, headphone/Bluetooth audio, and charging state with zero GPS and zero extra permissions. */
fun readDeviceContext(ctx: Context): DeviceContext {
    val onWifi = runCatching {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wm != null && wm.isWifiEnabled && (wm.connectionInfo?.networkId ?: -1) != -1
    }.getOrDefault(false)

    val headphones = runCatching {
        val am = ctx.getSystemService(AudioManager::class.java)
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { d ->
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> false
            }
        } ?: false
    }.getOrDefault(false)

    val charging = runCatching {
        val intent = ctx.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    return DeviceContext(onWifi = onWifi, headphonesConnected = headphones, charging = charging)
}

/** Reads clearable unread notifications from Honeycomb's existing MediaListener (zero new permissions). */
private fun readActiveNotifSignals(ctx: Context): List<NotifSignal> = runCatching {
    val live = MediaListener.Defaults.live?.activeNotifications.orEmpty().toList()
    notes(live, ctx.packageName).map { NotifSignal(it.pkg, it.posted) }
}.getOrDefault(emptyList())

/**
 * Records each app Honeycomb launches: total fallback count, rolling local timestamp buffer (for burst/Markov/time-of-day
 * even without Usage Access), and lightweight passive context bucket counts (Wi-Fi vs Away, Headphones, Charging).
 */
fun recordLaunch(ctx: Context, component: ComponentName) {
    val pkg = component.packageName
    val now = System.currentTimeMillis()
    val counts = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE)
    counts.edit().putInt(pkg, counts.getInt(pkg, 0) + 1).apply()

    val cPrefs = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE)
    val editor = cPrefs.edit()
    // Append to rolling local timestamp ring buffer ("pkg@ts\n...")
    val existing = cPrefs.getString(KEY_HISTORY, "").orEmpty()
        .split('\n')
        .filter { it.isNotEmpty() }
    val trimmed = if (existing.size >= MAX_LOCAL_HISTORY) existing.takeLast(MAX_LOCAL_HISTORY - 1) else existing
    editor.putString(KEY_HISTORY, (trimmed + "$pkg@$now").joinToString("\n"))

    // Increment passive context buckets (e.g. "b:audio_hp:com.spotify.music")
    val devCtx = readDeviceContext(ctx)
    for (bucket in devCtx.activeBuckets()) {
        val k = "b:$bucket:$pkg"
        editor.putInt(k, cPrefs.getInt(k, 0) + 1)
    }
    editor.apply()
}

private fun usageGranted(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED
}

/** Reads all ACTIVITY_RESUMED timestamps over the last 7 days from UsageStatsManager, or null without usage access. */
private fun readUsageEvents(ctx: Context, nowMs: Long): List<LaunchEvent>? {
    if (!usageGranted(ctx)) return null
    val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
    val events = usm.queryEvents(nowMs - USE_WINDOW_MS, nowMs) ?: return null
    val out = ArrayList<LaunchEvent>(256)
    val e = UsageEvents.Event()
    while (events.getNextEvent(e)) {
        if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && !e.packageName.isNullOrEmpty()) {
            out.add(LaunchEvent(e.packageName, e.timeStamp))
        }
    }
    return out
}

private fun readLocalHistory(ctx: Context): List<LaunchEvent> {
    val raw = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, "").orEmpty()
    if (raw.isEmpty()) return emptyList()
    return raw.split('\n').mapNotNull { line ->
        val at = line.lastIndexOf('@')
        if (at <= 0) null else line.substring(at + 1).toLongOrNull()?.let { ts -> LaunchEvent(line.substring(0, at), ts) }
    }
}

private fun readContextCounts(ctx: Context): Map<String, Map<String, Int>> {
    val all = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).all
    val out = HashMap<String, MutableMap<String, Int>>()
    for ((k, v) in all) {
        val n = v as? Int ?: continue
        if (n <= 0 || !k.startsWith("b:")) continue
        val second = k.indexOf(':', 2)
        if (second <= 2) continue
        val bucket = k.substring(2, second)
        val pkg = k.substring(second + 1)
        out.getOrPut(bucket) { HashMap() }[pkg] = n
    }
    return out
}

/** Packages from [counts], most opened first; ties by name, so the order is stable. Honeycomb itself is left out. */
fun byCount(counts: Map<String, Int>, self: String): List<String> =
    counts.filter { (p, n) -> n > 0 && p != self }.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }

/**
 * Contextual app ranking combining UsageEvents (or local history fallback), passive device context (Wi-Fi/Away,
 * Headphones, Charging), and live unread notifications.
 */
fun contextualPackages(ctx: Context): ContextualRanking {
    val now = System.currentTimeMillis()
    val usage = readUsageEvents(ctx, now)
    val events = if (!usage.isNullOrEmpty()) usage else readLocalHistory(ctx)
    val fallback = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE).all
        .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }
        .toMap()
    val contextCounts = readContextCounts(ctx)
    val devCtx = readDeviceContext(ctx)
    val notifs = readActiveNotifSignals(ctx)
    return rankContextualPackages(
        events = events,
        fallbackCounts = fallback,
        contextCounts = contextCounts,
        nowMs = now,
        ctx = devCtx,
        activeNotifications = notifs,
        selfPkg = ctx.packageName,
    )
}

/** Packages ranked most relevant right now (backwards-compatible entry point). */
fun usedPackages(ctx: Context): List<String> = contextualPackages(ctx).ordered
