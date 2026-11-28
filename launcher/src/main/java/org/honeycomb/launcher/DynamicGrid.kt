package org.honeycomb.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** How far the face behind the dynamic grid blurs, watch px on watchOS's 422 px wide simulator screen (clip 01). */
internal const val FACE_BLUR_PX = 26f

// ---- zero-hardcoding real-time on-device intelligence engine (<1 ms inference, online FTRL + passive-negative) ----

private const val COUNTS = "launch_counts"
private const val CONTEXT_PREFS = "launch_context"
private const val KEY_HISTORY = "history_v1"
private const val KEY_LAST_NET_ID = "last_net_id_v2"
private const val KEY_LAST_NET_CHANGE_MS = "last_net_change_ms_v2"
private const val KEY_LAST_LAUNCH_MS = "last_launch_ms_v2"
private const val MAX_LOCAL_HISTORY = 320
private const val MAX_ONLINE_WEIGHTS = 700

/** The window UsageEvents are read over (7 days). */
private const val USE_WINDOW_MS = 7 * 24 * 3600_000L

/** Half-lives & kernels for the multi-scale on-device predictor. */
private const val FAST_BURST_HALF_LIFE_MS = 18.0 * 60_000.0        // 18 min: active session bursts
private const val SLOW_HABIT_HALF_LIFE_MS = 72.0 * 3600_000.0      // 72 hr: steady weekly staples
private const val MARKOV_CHAIN_GAP_MS = 3.0 * 60_000.0             // 3 min max gap between consecutive apps in a session
private const val MARKOV_RECENCY_WINDOW_MS = 10.0 * 60_000.0       // last app stays predictive for 10 min
private const val TOD_SIGMA_HOURS = 1.5                            // smooth circular Gaussian kernel (+/- 1.5 hr)
private const val NOTIF_HALF_LIFE_MS = 5.0 * 60_000.0              // 5 min exponential decay for unread notifications
private const val FATIGUE_HALF_LIFE_MS = 25.0 * 60_000.0           // 25 min decay for skipped-impression fatigue
private const val CONTEXT_SHIFT_WINDOW_MS = 45.0 * 60_000.0        // network switch flushes prior-context bursts for 45 min
private const val CONTEXT_SHIFT_DISCOUNT = 0.18                    // bursts from before arriving home/leaving drop 82%

private const val W_BURST = 2.2
private const val W_HABIT = 1.0
private const val W_MARKOV = 1.8
private const val W_TRIGRAM = 1.3
private const val W_TOD = 1.2
private const val W_CALENDAR = 0.75
private const val W_CONTEXT = 1.25
private const val W_ONLINE = 1.6
private const val W_NOTIF = 2.2
private const val W_LIVE = 2.5
private const val W_FATIGUE = 1.15

/**
 * One foreground launch event with measured dwell time (from UsageEvents RESUMED->PAUSED pairs).
 * Quick bounces (<8s) get a small exploratory weight; committed sessions (>25s) carry full weight.
 */
data class LaunchEvent(
    val pkg: String,
    val timestampMs: Long,
    val dwellMs: Long = 30_000L,
)

/**
 * Rich, zero-GPS, zero-hardcoding snapshot of the exact moment the user is in:
 * network identity (Home Wi-Fi vs Work Wi-Fi vs Cellular via LinkProperties hash), network transition timestamp,
 * headphones/car audio, media playback, volume level, ringer mode, screen brightness (dark room vs daylight),
 * dark mode, charging & battery level, power-save mode, and pocket/idle gap since last launch.
 */
data class DeviceContext(
    val onWifi: Boolean = false,
    val headphonesConnected: Boolean = false,
    val charging: Boolean = false,
    val networkId: String = if (onWifi) "wifi" else "cell",
    val networkChangedAtMs: Long = 0L,
    val batteryBucket: Int = 1,          // 0=low(<20%), 1=mid, 2=high(>=85%)
    val brightnessBucket: Int = 1,       // 0=dim/dark-room(<18%), 1=indoors, 2=bright/outdoors(>=72%)
    val volumeBucket: Int = 1,           // 0=muted, 1=quiet, 2=mid, 3=loud
    val ringerMode: Int = 2,             // 0=silent, 1=vibrate, 2=normal
    val darkMode: Boolean = true,
    val mediaPlaying: Boolean = false,
    val carAudioConnected: Boolean = false,
    val powerSave: Boolean = false,
    val idleGapBucket: Int = 1,          // 0=rapid(<2m), 1=short(2-20m), 2=pocket(20-120m), 3=fresh(>2h)
) {
    /**
     * Dynamic sparse feature tokens representing this exact moment (zero hardcoded app names).
     * Combines device environment, network identity, and temporal/calendar cycles + key feature crosses.
     */
    fun activeBuckets(nowMs: Long = 0L, zoneId: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
        val netBase = if (onWifi) "net_wifi" else "net_away"
        add(netBase)
        if (networkId.isNotEmpty() && networkId != "wifi" && networkId != "cell") {
            add("netid_$networkId")
        }
        if (headphonesConnected) add("audio_hp")
        if (carAudioConnected) add("audio_car")
        if (mediaPlaying) add("media_on")
        if (charging) add("pwr_chg")
        if (powerSave) add("pwr_save")
        add("bat_$batteryBucket")
        add("bri_$brightnessBucket")
        add("vol_$volumeBucket")
        add("rng_$ringerMode")
        if (darkMode) add("ui_dark") else add("ui_light")
        add("gap_$idleGapBucket")

        // Cross-features capturing distinct human moments (e.g. settled in bed vs commuting vs outdoors)
        if (charging && brightnessBucket == 0) add("x_bed")
        if (headphonesConnected && !onWifi) add("x_commute_hp")
        if (headphonesConnected && onWifi) add("x_desk_hp")
        if (brightnessBucket == 2 && !onWifi) add("x_outdoors")

        if (nowMs > 0L) {
            val z = Instant.ofEpochMilli(nowMs).atZone(zoneId)
            val todBin = z.hour / 2 // 12 two-hour bins (0..11)
            val dow = z.dayOfWeek.value // 1..7
            val weekend = z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY
            val domBand = when (z.dayOfMonth) {
                in 1..5 -> "start"
                in 25..31 -> "end"
                else -> "mid"
            }
            add("tod_$todBin")
            add("dow_$dow")
            add(if (weekend) "cal_wknd" else "cal_wkdy")
            add("dom_$domBand")
            // Network × time-of-day cross (learns "Home Wi-Fi at 11pm" vs "Home Wi-Fi at 8am" vs "Away at 11pm")
            val partOfDay = when (z.hour) {
                in 5..10 -> "morn"
                in 11..16 -> "day"
                in 17..21 -> "eve"
                else -> "late"
            }
            add("x_${netBase}_$partOfDay")
            if (networkId.isNotEmpty() && networkId != "wifi" && networkId != "cell") {
                add("x_netid_${networkId}_$partOfDay")
            }
        }
    }
}

/** One unread notification signal for recency boosting, weighted by learned open-vs-dismiss utility. */
data class NotifSignal(
    val pkg: String,
    val postedAtMs: Long,
    val isLiveActivity: Boolean = false,
    val utility: Double = 1.0,
)

/** Decaying passive-negative impression fatigue per package (Instagram "repetitive fatigue" / RecSys SINE). */
data class FatigueState(
    val count: Double,
    val updatedAtMs: Long,
)

/** Result of contextual ranking: ordered package list (best first) + raw numeric score per package for hysteresis. */
data class ContextualRanking(
    val ordered: List<String>,
    val scores: Map<String, Double>,
)

/**
 * Maps observed foreground dwell duration to an intent-quality weight:
 *   < 8s   (quick price-check / bounce / accidental open) -> 0.18x
 *   8..20s (brief peek)                                   -> smooth ramp 0.18x .. 0.72x
 *   20..60s (committed interaction)                       -> smooth ramp 0.72x .. 1.15x
 *   > 60s  (deep session)                                 -> saturates cleanly at 1.35x
 */
internal fun dwellWeight(dwellMs: Long): Double {
    val sec = (dwellMs.coerceAtLeast(0L)) / 1000.0
    return when {
        sec < 8.0 -> 0.18
        sec < 20.0 -> 0.18 + (sec - 8.0) / 12.0 * 0.54
        sec < 60.0 -> 0.72 + (sec - 20.0) / 40.0 * 0.43
        else -> (1.15 + ln(1.0 + (sec - 60.0) / 120.0) * 0.25).coerceAtMost(1.35)
    }
}

private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-12.0, 12.0)))

/**
 * Pure-math, zero-hardcoding, real-time on-device contextual app predictor (<1 ms inference):
 *   1. Dwell-Weighted Fast Burst Decay (18 min half-life) with Automatic Context-Boundary Discount:
 *      - Short bounces (<8s, e.g. price-shopping 4 apps) barely register vs a committed launch.
 *      - Switching network contexts (e.g. Cellular -> Home Wi-Fi) automatically discounts pre-arrival bursts by 82%.
 *   2. Slow Habit Decay (72 hr half-life, dwell-weighted) — steady weekly baseline.
 *   3. Multi-Order Sequential Chains — Bigram P(a | prev1) + Trigram P(a | prev2, prev1) within 3 min.
 *   4. Circular Time-of-Day Gaussian Kernel + Day-of-Week + Date-of-Month Calendar Affinity.
 *   5. Multi-Signal Passive Context Log-Lift — 20+ live moment features (Wi-Fi fingerprint, Away/Home,
 *      Headphones, Car BT, Volume, Brightness/Dark-room, Charging, Battery, Idle gap, and Time × Place crosses).
 *   6. Real-Time Online Logistic Model (`onlineWeights`) trained continuously from positive taps (+1) AND
 *      passive-negative skipped impressions (0) — dynamically learns what you want in *this exact moment*.
 *   7. Live Unread Notifications (5 min decay) & Ongoing Live Activities (instant drop when trip/timer ends).
 *   8. Repetitive Impression Fatigue (`fatigue`) — apps shown in the grid and repeatedly skipped decay out fast.
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
    onlineWeights: Map<String, Map<String, Double>> = emptyMap(),
    fatigue: Map<String, FatigueState> = emptyMap(),
): ContextualRanking {
    val ln2 = ln(2.0)
    val sorted = events
        .filter { it.pkg.isNotEmpty() && it.pkg != selfPkg && it.timestampMs <= nowMs && (nowMs - it.timestampMs) <= USE_WINDOW_MS }
        .sortedBy { it.timestampMs }

    val burst = HashMap<String, Double>()
    val habit = HashMap<String, Double>()
    val tod = HashMap<String, Double>()
    val calMatch = HashMap<String, Double>()
    val candidates = HashSet<String>()

    val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
    val nowHour = nowZdt.hour + nowZdt.minute / 60.0
    val nowDow = nowZdt.dayOfWeek
    val nowWeekend = nowDow == DayOfWeek.SATURDAY || nowDow == DayOfWeek.SUNDAY
    val nowDomBand = when (nowZdt.dayOfMonth) { in 1..5 -> 0; in 25..31 -> 2; else -> 1 }
    val twoSigmaSq = 2.0 * TOD_SIGMA_HOURS * TOD_SIGMA_HOURS

    // If the device recently switched network contexts (e.g. cellular -> Home Wi-Fi), pre-transition bursts are stale
    val recentContextShift = ctx.networkChangedAtMs in 1L..nowMs && (nowMs - ctx.networkChangedAtMs) <= CONTEXT_SHIFT_WINDOW_MS

    for (ev in sorted) {
        val dt = (nowMs - ev.timestampMs).coerceAtLeast(0L).toDouble()
        val dw = dwellWeight(ev.dwellMs)
        candidates.add(ev.pkg)

        // Context-boundary discount: bursts from before arriving on current Wi-Fi / leaving Wi-Fi fade immediately
        val shiftFactor = if (recentContextShift && ev.timestampMs < ctx.networkChangedAtMs) CONTEXT_SHIFT_DISCOUNT else 1.0
        burst.merge(ev.pkg, exp(-ln2 * dt / FAST_BURST_HALF_LIFE_MS) * dw * shiftFactor, Double::plus)
        habit.merge(ev.pkg, exp(-ln2 * dt / SLOW_HABIT_HALF_LIFE_MS) * (0.45 + 0.55 * dw), Double::plus)

        val z = Instant.ofEpochMilli(ev.timestampMs).atZone(zoneId)
        val evHour = z.hour + z.minute / 60.0
        val rawDiff = abs(nowHour - evHour)
        val circDiff = minOf(rawDiff, 24.0 - rawDiff)
        val evDow = z.dayOfWeek
        val evWeekend = evDow == DayOfWeek.SATURDAY || evDow == DayOfWeek.SUNDAY
        val dayMatch = when {
            evDow == nowDow -> 1.45
            evWeekend == nowWeekend -> 1.25
            else -> 0.72
        }
        val recencyFade = exp(-ln2 * dt / (14.0 * 24.0 * 3600_000.0))
        tod.merge(ev.pkg, exp(-(circDiff * circDiff) / twoSigmaSq) * dayMatch * recencyFade * dw, Double::plus)

        // Exact day-of-week & date-of-month pattern affinity (e.g. Friday nights, 1st-of-month rent/bills)
        val evDomBand = when (z.dayOfMonth) { in 1..5 -> 0; in 25..31 -> 2; else -> 1 }
        val calBoost = (if (evDow == nowDow) 0.65 else 0.0) + (if (evDomBand == nowDomBand && nowDomBand != 1) 0.45 else 0.0)
        if (calBoost > 0.0) {
            calMatch.merge(ev.pkg, calBoost * recencyFade * dw, Double::plus)
        }
    }

    // Signal 3: Multi-order sequential chains — Bigram P(next | prev1) + Trigram P(next | prev2, prev1)
    val markov = HashMap<String, Double>()
    val trigram = HashMap<String, Double>()
    val lastEv = sorted.lastOrNull()
    if (lastEv != null && (nowMs - lastEv.timestampMs) <= MARKOV_RECENCY_WINDOW_MS) {
        val prevPkg = lastEv.pkg
        val prev2Pkg = sorted.getOrNull(sorted.size - 2)?.takeIf {
            it.pkg != prevPkg && (lastEv.timestampMs - it.timestampMs) in 0L..MARKOV_CHAIN_GAP_MS.toLong()
        }?.pkg
        val transitions = HashMap<String, Int>()
        val triTransitions = HashMap<String, Int>()
        var totalOut = 0
        var totalTri = 0
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (a.pkg == prevPkg && b.pkg != prevPkg && (b.timestampMs - a.timestampMs) in 0L..MARKOV_CHAIN_GAP_MS.toLong()) {
                transitions.merge(b.pkg, 1, Int::plus)
                totalOut++
                if (prev2Pkg != null && i > 0) {
                    val prev = sorted[i - 1]
                    if (prev.pkg == prev2Pkg && (a.timestampMs - prev.timestampMs) in 0L..MARKOV_CHAIN_GAP_MS.toLong()) {
                        triTransitions.merge(b.pkg, 1, Int::plus)
                        totalTri++
                    }
                }
            }
        }
        val recencyScale = exp(-ln2 * (nowMs - lastEv.timestampMs).coerceAtLeast(0L).toDouble() / MARKOV_RECENCY_WINDOW_MS)
        if (totalOut > 0) {
            val denom = totalOut + 2.0
            for ((nextPkg, cnt) in transitions) {
                candidates.add(nextPkg)
                markov[nextPkg] = (cnt.toDouble() / denom) * recencyScale
            }
        }
        if (totalTri > 0) {
            val denom = totalTri + 1.5
            for ((nextPkg, cnt) in triTransitions) {
                candidates.add(nextPkg)
                trigram[nextPkg] = (cnt.toDouble() / denom) * recencyScale
            }
        }
    }

    // Signal 5: Multi-signal passive device + moment context log-lift (both positive AND negative lift!)
    val contextLift = HashMap<String, Double>()
    val activeBuckets = ctx.activeBuckets(nowMs, zoneId)
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
                // Allow modest negative lift too when an app strongly belongs to another context
                val lift = ln(pGivenC / pGlobal).coerceIn(-0.6, 2.2)
                contextLift.merge(pkg, lift, Double::plus)
            }
        }
    }

    // Signal 6: Real-time online logistic model P(tap | pkg, activeBuckets) from positive taps + skipped impressions
    val onlineScore = HashMap<String, Double>()
    for ((pkg, wMap) in onlineWeights) {
        if (pkg == selfPkg || wMap.isEmpty()) continue
        var z = wMap["_bias"] ?: 0.0
        var matched = false
        for (f in activeBuckets) {
            val w = wMap[f]
            if (w != null) {
                z += w
                matched = true
            }
        }
        if (matched || z != 0.0) {
            candidates.add(pkg)
            // Centered around 0: >0 boosts apps learned for this exact moment, <0 demotes apps repeatedly ignored here
            onlineScore[pkg] = (sigmoid(z) - 0.5) * 2.0
        }
    }

    // Signal 7: Live unread notifications (5 min decay) + active ongoing Live Activities
    val notif = HashMap<String, Double>()
    val liveAct = HashMap<String, Double>()
    for (n in activeNotifications) {
        if (n.pkg.isEmpty() || n.pkg == selfPkg || n.postedAtMs > nowMs + 60_000L) continue
        candidates.add(n.pkg)
        if (n.isLiveActivity) {
            liveAct[n.pkg] = 1.0
        } else {
            val age = (nowMs - n.postedAtMs).coerceAtLeast(0L).toDouble()
            if (age <= 60 * 60_000.0) {
                val boost = exp(-ln2 * age / NOTIF_HALF_LIFE_MS) * n.utility.coerceIn(0.15, 1.5)
                notif[n.pkg] = maxOf(notif[n.pkg] ?: 0.0, boost) + 0.15 * boost
            }
        }
    }

    // Include fallback counts for packages that haven't appeared in recent UsageEvents
    val maxFallback = fallbackCounts.entries.filter { it.key != selfPkg && it.value > 0 }.maxOfOrNull { it.value } ?: 0
    for ((pkg, c) in fallbackCounts) {
        if (pkg != selfPkg && c > 0) candidates.add(pkg)
    }

    if (candidates.isEmpty()) return ContextualRanking(emptyList(), emptyMap())

    val scores = HashMap<String, Double>(candidates.size)
    for (pkg in candidates) {
        val sBurst = ln(1.0 + (burst[pkg] ?: 0.0) * 1.8)
        val sHabit = ln(1.0 + (habit[pkg] ?: 0.0) * 0.45)
        val sMarkov = markov[pkg] ?: 0.0
        val sTrigram = trigram[pkg] ?: 0.0
        val sTod = ln(1.0 + (tod[pkg] ?: 0.0))
        val sCal = ln(1.0 + (calMatch[pkg] ?: 0.0))
        val sCtx = (contextLift[pkg] ?: 0.0).coerceIn(-1.2, 2.8)
        val sOnline = onlineScore[pkg] ?: 0.0
        val sNotif = (notif[pkg] ?: 0.0).coerceAtMost(1.8)
        val sLive = liveAct[pkg] ?: 0.0
        val sFallback = if (maxFallback > 0 && sorted.isEmpty()) {
            ln(1.0 + (fallbackCounts[pkg] ?: 0).toDouble()) * 0.55
        } else if (maxFallback > 0 && (habit[pkg] ?: 0.0) == 0.0) {
            0.25 * ((fallbackCounts[pkg] ?: 0).toDouble() / maxFallback.toDouble())
        } else 0.0

        // Signal 8: Passive-negative impression fatigue (decays smoothly with 25-min half-life)
        val fState = fatigue[pkg]
        val sFatigue = if (fState != null && fState.count > 0.0 && fState.updatedAtMs <= nowMs) {
            val age = (nowMs - fState.updatedAtMs).coerceAtLeast(0L).toDouble()
            val decayed = fState.count * exp(-ln2 * age / FATIGUE_HALF_LIFE_MS)
            ln(1.0 + decayed)
        } else 0.0

        val total = W_BURST * sBurst +
            W_HABIT * sHabit +
            W_MARKOV * sMarkov +
            W_TRIGRAM * sTrigram +
            W_TOD * sTod +
            W_CALENDAR * sCal +
            W_CONTEXT * sCtx +
            W_ONLINE * sOnline +
            W_NOTIF * sNotif +
            W_LIVE * sLive +
            sFallback -
            W_FATIGUE * sFatigue

        if (total > 0.0) scores[pkg] = total
    }

    val ordered = scores.entries
        .sortedWith(compareBy({ -it.value }, { it.key }))
        .map { it.key }
    return ContextualRanking(ordered, scores)
}

/**
 * Pure online logistic gradient update (L2-regularized SGD) for both positive launches (y=1)
 * and passive-negative skipped impressions (y=0, gentler weight per RecSys 2023 SINE).
 */
internal fun updateOnlineWeights(
    weights: MutableMap<String, MutableMap<String, Double>>,
    pkg: String,
    activeBuckets: List<String>,
    positive: Boolean,
    lr: Double = if (positive) 0.18 else 0.08,
    l2: Double = 0.005,
) {
    val wMap = weights.getOrPut(pkg) { HashMap() }
    var z = wMap["_bias"] ?: 0.0
    for (f in activeBuckets) z += wMap[f] ?: 0.0
    val pred = sigmoid(z)
    val target = if (positive) 1.0 else 0.0
    val err = target - pred

    val nextBias = ((wMap["_bias"] ?: 0.0) * (1.0 - l2) + lr * 0.5 * err).coerceIn(-3.0, 3.0)
    wMap["_bias"] = nextBias
    for (f in activeBuckets) {
        val cur = wMap[f] ?: 0.0
        val next = (cur * (1.0 - l2) + lr * err).coerceIn(-3.5, 3.5)
        wMap[f] = next
    }
}

// ---- Android platform adapters (zero GPS, zero extra runtime popups) ----------------------------------------------

/**
 * Reads rich live device moment with zero GPS and zero Location permission:
 * uses ConnectivityManager (works on Android 12-16 without Location) + anonymized LinkProperties hash
 * to distinguish Home Wi-Fi vs Office Wi-Fi vs Cellular and automatically detect context transitions.
 */
fun readDeviceContext(ctx: Context): DeviceContext {
    val now = System.currentTimeMillis()
    val cPrefs = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE)

    var onWifi = false
    var netId = "offline"
    runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        if (caps != null) {
            onWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            netId = when {
                onWifi -> {
                    val lp = cm.getLinkProperties(active)
                    val sig = buildString {
                        lp?.dnsServers?.mapNotNull { it.hostAddress }?.sorted()?.forEach { append(it); append('|') }
                        lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress?.let { append(it) }
                        lp?.domains?.let { append('|'); append(it) }
                    }
                    if (sig.isNotEmpty()) "w${(sig.hashCode().toUInt()).toString(16)}" else "wifi"
                }
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                else -> "other"
            }
        }
    }

    // Track network context transitions (e.g. cellular -> Home Wi-Fi) so stale pre-arrival bursts flush immediately
    val prevNetId = cPrefs.getString(KEY_LAST_NET_ID, null)
    var netChangedAt = cPrefs.getLong(KEY_LAST_NET_CHANGE_MS, 0L)
    if (prevNetId == null) {
        cPrefs.edit().putString(KEY_LAST_NET_ID, netId).apply()
    } else if (prevNetId != netId && netId != "offline" && prevNetId != "offline") {
        netChangedAt = now
        cPrefs.edit().putString(KEY_LAST_NET_ID, netId).putLong(KEY_LAST_NET_CHANGE_MS, now).apply()
    }

    var headphones = false
    var carAudio = false
    var mediaPlaying = false
    var volumeBucket = 1
    var ringerMode = AudioManager.RINGER_MODE_NORMAL
    runCatching {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            headphones = outputs.any { d ->
                when (d.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> false
                }
            }
            mediaPlaying = am.isMusicActive
            ringerMode = am.ringerMode
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val frac = curVol.toFloat() / maxVol.toFloat()
            volumeBucket = when {
                curVol == 0 -> 0
                frac <= 0.32f -> 1
                frac < 0.72f -> 2
                else -> 3
            }
        }
    }
    runCatching {
        val uiMode = ctx.resources.configuration.uiMode
        carAudio = (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR
    }

    var charging = false
    var batteryBucket = 1
    runCatching {
        val intent = ctx.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        if (level >= 0) {
            val pct = (level * 100) / scale
            batteryBucket = when {
                pct < 20 -> 0
                pct >= 85 -> 2
                else -> 1
            }
        }
    }

    val powerSave = runCatching { ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
    val darkMode = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val brightnessBucket = runCatching {
        val raw = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val frac = raw / 255f
        when {
            frac < 0.18f -> 0
            frac >= 0.72f -> 2
            else -> 1
        }
    }.getOrDefault(1)

    val lastLaunchMs = cPrefs.getLong(KEY_LAST_LAUNCH_MS, 0L)
    val idleGapBucket = if (lastLaunchMs <= 0L) 1 else when (now - lastLaunchMs) {
        in 0L until 2 * 60_000L -> 0
        in 2 * 60_000L until 20 * 60_000L -> 1
        in 20 * 60_000L until 120 * 60_000L -> 2
        else -> 3
    }

    return DeviceContext(
        onWifi = onWifi,
        headphonesConnected = headphones,
        charging = charging,
        networkId = netId,
        networkChangedAtMs = netChangedAt,
        batteryBucket = batteryBucket,
        brightnessBucket = brightnessBucket,
        volumeBucket = volumeBucket,
        ringerMode = ringerMode,
        darkMode = darkMode,
        mediaPlaying = mediaPlaying,
        carAudioConnected = carAudio,
        powerSave = powerSave,
        idleGapBucket = idleGapBucket,
    )
}

/** Records whether the user actually opened (clicked) or swiped away a notification — learns useful vs noise per app. */
fun recordNotifOutcome(ctx: Context, pkg: String, opened: Boolean) {
    if (pkg.isEmpty() || pkg == ctx.packageName) return
    val p = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE)
    val k = if (opened) "no:$pkg" else "nd:$pkg"
    p.edit().putInt(k, p.getInt(k, 0) + 1).apply()
}

/** Learned Beta-smoothed notification utility in [0.18, 1.35]: apps whose alerts you tap boost strongly; swiped spam fades. */
internal fun notifUtility(opened: Int, dismissed: Int): Double {
    val rate = (opened + 1.5) / (opened + dismissed + 3.0)
    return (0.18 + rate * 1.17).coerceIn(0.18, 1.35)
}

/** Reads clearable unread notifications AND ongoing Live Activities from Honeycomb's existing MediaListener. */
private fun readActiveNotifSignals(ctx: Context): List<NotifSignal> = runCatching {
    val live = MediaListener.Defaults.live?.activeNotifications.orEmpty().toList()
    val p = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE)
    val out = ArrayList<NotifSignal>(live.size)
    for (n in notes(live, ctx.packageName)) {
        val u = notifUtility(p.getInt("no:${n.pkg}", 0), p.getInt("nd:${n.pkg}", 0))
        out.add(NotifSignal(n.pkg, n.posted, isLiveActivity = false, utility = u))
    }
    for (la in liveUpdates(ctx)) {
        out.add(NotifSignal(la.pkg, System.currentTimeMillis(), isLiveActivity = true, utility = 1.0))
    }
    out
}.getOrDefault(emptyList())

/**
 * Records each app launch AND runs a real-time online learning step:
 *  - Positive update (+1) for the launched app across all active moment features (clears its fatigue).
 *  - Passive-negative update (0) + fatigue bump for every unpinned app that was shown in the Dynamic Grid and skipped.
 */
fun recordLaunch(
    ctx: Context,
    component: ComponentName,
    shownDynamicPkgs: List<String> = emptyList(),
) {
    val pkg = component.packageName
    val now = System.currentTimeMillis()
    val counts = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE)
    counts.edit().putInt(pkg, counts.getInt(pkg, 0) + 1).apply()

    val cPrefs = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE)
    val devCtx = readDeviceContext(ctx)
    val activeBuckets = devCtx.activeBuckets(now)

    val editor = cPrefs.edit()
    editor.putLong(KEY_LAST_LAUNCH_MS, now)

    // Append to rolling local timestamp ring buffer ("pkg@ts\n...")
    val existing = cPrefs.getString(KEY_HISTORY, "").orEmpty()
        .split('\n')
        .filter { it.isNotEmpty() }
    val trimmed = if (existing.size >= MAX_LOCAL_HISTORY) existing.takeLast(MAX_LOCAL_HISTORY - 1) else existing
    editor.putString(KEY_HISTORY, (trimmed + "$pkg@$now").joinToString("\n"))

    // Increment passive moment context counts
    for (bucket in activeBuckets) {
        val k = "b:$bucket:$pkg"
        editor.putInt(k, cPrefs.getInt(k, 0) + 1)
    }

    // Real-time online logistic weights + passive-negative impression fatigue
    val weights = readOnlineWeights(ctx).mapValues { it.value.toMutableMap() }.toMutableMap()
    updateOnlineWeights(weights, pkg, activeBuckets, positive = true)
    // Clear fatigue on the app the user actually chose
    editor.remove("f:$pkg")

    val ln2 = ln(2.0)
    val prevFatigue = readFatigue(ctx)
    val skipped = shownDynamicPkgs.asSequence().filter { it.isNotEmpty() && it != pkg && it != ctx.packageName }.distinct().toList()
    for (sk in skipped) {
        updateOnlineWeights(weights, sk, activeBuckets, positive = false)
        val cur = prevFatigue[sk]
        val decayed = if (cur != null && cur.updatedAtMs <= now) {
            cur.count * exp(-ln2 * (now - cur.updatedAtMs).coerceAtLeast(0L).toDouble() / FATIGUE_HALF_LIFE_MS)
        } else 0.0
        val nextCount = (decayed + 1.0).coerceAtMost(8.0)
        editor.putString("f:$sk", "$nextCount@$now")
    }

    // Persist compact online weights (prune weakest entries if > MAX_ONLINE_WEIGHTS)
    val allEntries = weights.flatMap { (p, m) -> m.entries.map { (f, w) -> Triple(p, f, w) } }
    val kept = if (allEntries.size > MAX_ONLINE_WEIGHTS) {
        allEntries.sortedByDescending { abs(it.third) }.take(MAX_ONLINE_WEIGHTS)
    } else allEntries
    for (k in cPrefs.all.keys) {
        if (k.startsWith("w:")) editor.remove(k)
    }
    for ((p, f, w) in kept) {
        if (abs(w) >= 0.01) editor.putFloat("w:$p|$f", w.toFloat())
    }
    editor.apply()
}

private fun usageGranted(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED
}

/**
 * Reads UsageEvents over the last 7 days and pairs ACTIVITY_RESUMED -> ACTIVITY_PAUSED/STOPPED
 * to measure true dwell time per launch (so quick 6-second bounces never look like deep sessions).
 */
private fun readUsageEvents(ctx: Context, nowMs: Long): List<LaunchEvent>? {
    if (!usageGranted(ctx)) return null
    val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
    val events = usm.queryEvents(nowMs - USE_WINDOW_MS, nowMs) ?: return null
    val out = ArrayList<LaunchEvent>(256)
    val openAt = HashMap<String, Long>()
    val e = UsageEvents.Event()
    while (events.getNextEvent(e)) {
        val pkg = e.packageName
        if (pkg.isNullOrEmpty()) continue
        when (e.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                openAt[pkg] = e.timeStamp
            }
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                val start = openAt.remove(pkg)
                if (start != null) {
                    val dwell = (e.timeStamp - start).coerceIn(500L, 4 * 3600_000L)
                    out.add(LaunchEvent(pkg, start, dwell))
                }
            }
        }
    }
    // Any currently-foreground app still open right now counts as a committed active session
    for ((pkg, start) in openAt) {
        val dwell = (nowMs - start).coerceIn(15_000L, 4 * 3600_000L)
        out.add(LaunchEvent(pkg, start, dwell))
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

private fun readOnlineWeights(ctx: Context): Map<String, Map<String, Double>> {
    val all = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).all
    val out = HashMap<String, MutableMap<String, Double>>()
    for ((k, v) in all) {
        if (!k.startsWith("w:")) continue
        val w = (v as? Number)?.toDouble() ?: continue
        val sep = k.indexOf('|', 2)
        if (sep <= 2) continue
        val pkg = k.substring(2, sep)
        val feat = k.substring(sep + 1)
        out.getOrPut(pkg) { HashMap() }[feat] = w
    }
    return out
}

private fun readFatigue(ctx: Context): Map<String, FatigueState> {
    val all = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).all
    val out = HashMap<String, FatigueState>()
    for ((k, v) in all) {
        if (!k.startsWith("f:")) continue
        val raw = v as? String ?: continue
        val at = raw.indexOf('@')
        if (at <= 0) continue
        val cnt = raw.substring(0, at).toDoubleOrNull() ?: continue
        val ts = raw.substring(at + 1).toLongOrNull() ?: continue
        out[k.substring(2)] = FatigueState(cnt, ts)
    }
    return out
}

/** Packages from [counts], most opened first; ties by name, so the order is stable. Honeycomb itself is left out. */
fun byCount(counts: Map<String, Int>, self: String): List<String> =
    counts.filter { (p, n) -> n > 0 && p != self }.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }

/**
 * Full real-time contextual ranking combining dwell-weighted UsageEvents, multi-signal device moment,
 * network-fingerprint context boundaries, online logistic weights, passive-negative fatigue, and Live Activities.
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
    val weights = readOnlineWeights(ctx)
    val fatigue = readFatigue(ctx)
    return rankContextualPackages(
        events = events,
        fallbackCounts = fallback,
        contextCounts = contextCounts,
        nowMs = now,
        ctx = devCtx,
        activeNotifications = notifs,
        selfPkg = ctx.packageName,
        onlineWeights = weights,
        fatigue = fatigue,
    )
}

/** Packages ranked most relevant right now (backwards-compatible entry point). */
fun usedPackages(ctx: Context): List<String> = contextualPackages(ctx).ordered
