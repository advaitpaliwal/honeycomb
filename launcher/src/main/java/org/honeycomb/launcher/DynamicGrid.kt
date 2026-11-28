package org.honeycomb.launcher

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
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
import android.os.Build
import android.os.Parcelable
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** How far the face behind the dynamic grid blurs, watch px on watchOS's 422 px wide simulator screen (clip 01). */
internal const val FACE_BLUR_PX = 26f

// ---- zero-hardcoding OS-level on-device ML engine (FTRL-Proximal + AUC-PA + Thompson/UCB Bandit, <1 ms) ----

private const val COUNTS = "launch_counts"
private const val CONTEXT_PREFS = "launch_context"
private const val KEY_HISTORY = "history_v1"
private const val KEY_LAST_NET_ID = "last_net_id_v2"
private const val KEY_LAST_NET_CHANGE_MS = "last_net_change_ms_v2"
private const val KEY_LAST_NET_SAMPLE_MS = "last_net_sample_ms_v2"
private const val KEY_LAST_HP_STATE = "last_hp_state_v1"
private const val KEY_LAST_HP_CHANGE_MS = "last_hp_change_ms_v1"
private const val KEY_LAST_CHG_STATE = "last_chg_state_v1"
private const val KEY_LAST_CHG_CHANGE_MS = "last_chg_change_ms_v1"
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
private const val SESSION_CO_WINDOW_MS = 20 * 60_000L              // 20 min multi-app workflow co-occurrence window
private const val TRANSITION_EDGE_WINDOW_MS = 15 * 60_000L         // 15 min transition edge window (EverythingMe §6.1)
private const val TOD_SIGMA_HOURS = 1.5                            // smooth circular Gaussian kernel (+/- 1.5 hr)
private const val NOTIF_IMPORTANT_HALF_LIFE_MS = 45.0 * 60_000.0     // 45 min: real messages, calls, priority alerts stay urgent
private const val NOTIF_NORMAL_HALF_LIFE_MS = 18.0 * 60_000.0        // 18 min: standard app alerts
private const val NOTIF_LOW_HALF_LIFE_MS = 6.0 * 60_000.0            // 6 min: promos / low-priority notifications fade fast
private const val FATIGUE_HALF_LIFE_MS = 25.0 * 60_000.0             // 25 min decay for skipped-impression fatigue
private const val CONTEXT_SHIFT_WINDOW_MS = 45.0 * 60_000.0          // network switch flushes prior-context bursts for 45 min
private const val CONTEXT_SHIFT_DISCOUNT = 0.18                      // bursts from before arriving home/leaving drop 82%

private const val W_BURST = 2.2
private const val W_HABIT = 1.0
private const val W_MARKOV = 1.8
private const val W_TRIGRAM = 1.3
private const val W_COOCCUR = 0.85
private const val W_TOD = 1.2
private const val W_CALENDAR = 0.75
private const val W_CONTEXT = 1.25
private const val W_ONLINE = 1.75
private const val W_NOTIF = 2.6
private const val W_LIVE = 2.5
private const val W_MEDIA_ACTIVE = 1.4
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
 * Accumulated passive dwell profile for a single Wi-Fi network fingerprint (zero GPS, zero SSID/Location needed).
 * Used to infer semantic place roles (home vs work vs frequent third-place vs transient visit) purely from dwell timing.
 */
data class NetworkDwellProfile(
    val totalMin: Double = 0.0,
    val nightMin: Double = 0.0,      // 23:00 - 07:00 dwell minutes (strong Home signature)
    val workDayMin: Double = 0.0,    // Mon-Fri 09:00 - 18:00 dwell minutes (strong Work/Office signature)
    val chargingMin: Double = 0.0,   // Charging dwell minutes (reinforces Home / desk)
    val visits: Int = 0,
)

/**
 * Unsupervised semantic place classifier from pure Wi-Fi dwell patterns (zero GPS, zero hardcoding):
 *   - "home":      Wi-Fi with the highest overnight + charging dwell (>= 60 weighted night/charging min)
 *   - "work":      Non-home Wi-Fi with the highest weekday daytime dwell (>= 90 weekday min)
 *   - "regular":   Any other Wi-Fi with >= 120 total min across >= 2 visits (gym, partner's house, favorite cafe)
 *   - "transient": Brief/new Wi-Fi (restaurant, airport, hotel, friend's house)
 *   - "away":      Cellular / off-Wi-Fi
 */
internal fun inferPlaceRole(
    currentNetId: String,
    onWifi: Boolean,
    profiles: Map<String, NetworkDwellProfile>,
): String {
    if (!onWifi || currentNetId == "cell" || currentNetId == "offline" || currentNetId == "other") return "away"
    val wifiProfiles = profiles.filterKeys { it.startsWith("w") }
    if (wifiProfiles.isEmpty()) return "transient"

    val homeId = wifiProfiles.entries
        .map { (id, p) -> id to (p.nightMin * 1.5 + p.chargingMin * 0.8 + p.totalMin * 0.15) }
        .filter { it.second >= 60.0 }
        .maxByOrNull { it.second }
        ?.first
    if (currentNetId == homeId) return "home"

    val workId = wifiProfiles.entries
        .filter { it.key != homeId && it.value.workDayMin >= 90.0 }
        .maxByOrNull { it.value.workDayMin }
        ?.key
    if (currentNetId == workId) return "work"

    val cur = wifiProfiles[currentNetId]
    if (cur != null && cur.totalMin >= 120.0 && cur.visits >= 2) return "regular"
    return "transient"
}

/**
 * Rich, zero-GPS, zero-hardcoding snapshot of the exact moment the user is in:
 * network identity & unsupervised semantic place role (home / work / regular / transient / away),
 * continuous stay duration at this place, network transition timestamp, headphones/car audio, media playback,
 * volume level, ringer mode, screen brightness (dark room vs daylight), dark mode, charging & battery level,
 * power-save mode, and pocket/idle gap since last launch.
 */
data class DeviceContext(
    val onWifi: Boolean = false,
    val headphonesConnected: Boolean = false,
    val charging: Boolean = false,
    val networkId: String = if (onWifi) "wifi" else "cell",
    val networkChangedAtMs: Long = 0L,
    val placeRole: String = if (onWifi) "wifi" else "away",   // home, work, regular, transient, away
    val stayDurationMin: Long = 30L,                          // continuous minutes spent on current network
    val batteryBucket: Int = 1,          // 0=low(<20%), 1=mid, 2=high(>=85%)
    val brightnessBucket: Int = 1,       // 0=dim/dark-room(<18%), 1=indoors, 2=bright/outdoors(>=72%)
    val volumeBucket: Int = 1,           // 0=muted, 1=quiet, 2=mid, 3=loud
    val ringerMode: Int = 2,             // 0=silent, 1=vibrate, 2=normal
    val darkMode: Boolean = true,
    val mediaPlaying: Boolean = false,
    val carAudioConnected: Boolean = false,
    val powerSave: Boolean = false,
    val idleGapBucket: Int = 1,          // 0=rapid(<2m), 1=short(2-20m), 2=pocket(20-120m), 3=fresh(>2h)
    val alarmSoon: Boolean = false,      // true when an alarm clock is scheduled within 45 min
    val quietOn: Boolean = false,        // true when Do Not Disturb / Quiet focus mode is active
    val activeMediaPkg: String? = null,  // package actively playing audio right now (STATE_PLAYING)
    val btAudioId: String = "",          // anonymized Bluetooth output fingerprint (AirPods vs car vs desk speaker)
    val chargeSource: String = "",       // "wireless", "usb", "ac", or ""
    val hpChangedAtMs: Long = 0L,        // timestamp of last headphones connect/disconnect transition
    val chgChangedAtMs: Long = 0L,       // timestamp of last charger plug/unplug transition
    val justUnlocked: Boolean = false,   // true on first app open right after screen unlock / keyguard hide
) {
    /**
     * Dynamic sparse feature tokens representing this exact moment (zero hardcoded app names).
     * Combines semantic place role, stay duration, hardware fingerprints, 15-min transition edges
     * (EverythingMe §6.1), device environment, and temporal/calendar cycles + crosses.
     */
    fun activeBuckets(nowMs: Long = 0L, zoneId: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
        val netBase = if (onWifi) "net_wifi" else "net_away"
        add(netBase)
        add("place_$placeRole")
        val stayBand = when {
            stayDurationMin < 10L -> "arrived"
            stayDurationMin < 60L -> "settled"
            else -> "deep"
        }
        add("stay_$stayBand")
        add("x_place_${placeRole}_$stayBand")
        if (networkId.isNotEmpty() && networkId != "wifi" && networkId != "cell") {
            add("netid_$networkId")
        }
        if (headphonesConnected) add("audio_hp")
        if (btAudioId.isNotEmpty()) add("bt_$btAudioId")
        if (carAudioConnected) add("audio_car")
        if (mediaPlaying) add("media_on")
        if (charging) add("pwr_chg")
        if (chargeSource.isNotEmpty()) add("chg_$chargeSource")
        if (powerSave) add("pwr_save")
        if (alarmSoon) add("alarm_soon")
        if (quietOn) add("dnd_on")
        if (justUnlocked) add("edge_unlock")

        // 15-minute hardware & place transition edge tokens (highest-AUC mobile signals per arXiv:1512.07851)
        if (nowMs > 0L) {
            if (hpChangedAtMs in 1L..nowMs && (nowMs - hpChangedAtMs) <= TRANSITION_EDGE_WINDOW_MS) {
                add(if (headphonesConnected) "edge_hp_in" else "edge_hp_out")
            }
            if (chgChangedAtMs in 1L..nowMs && (nowMs - chgChangedAtMs) <= TRANSITION_EDGE_WINDOW_MS) {
                add(if (charging) "edge_chg_in" else "edge_chg_out")
            }
            if (networkChangedAtMs in 1L..nowMs && (nowMs - networkChangedAtMs) <= TRANSITION_EDGE_WINDOW_MS) {
                add(if (onWifi) "edge_net_in_$placeRole" else "edge_net_out")
            }
        }
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
            // Place × time-of-day cross (learns "Home late night" vs "Home morning" vs "Work daytime" vs "Away late")
            val partOfDay = when (z.hour) {
                in 5..10 -> "morn"
                in 11..16 -> "day"
                in 17..21 -> "eve"
                else -> "late"
            }
            add("x_${netBase}_$partOfDay")
            add("x_place_${placeRole}_$partOfDay")
            if (idleGapBucket == 3 && onWifi && partOfDay == "morn") add("x_wake_morning")
            if (networkId.isNotEmpty() && networkId != "wifi" && networkId != "cell") {
                add("x_netid_${networkId}_$partOfDay")
            }
        }
    }
}

/**
 * One unread notification signal weighted by:
 *  - [importanceWeight]: Android system importance + conversation/priority status + userSentiment + reply action + category
 *  - [messageCount]: unread messages bundled inside MessagingStyle / Notification.number
 *  - [utility]: learned per-app open-vs-dismiss probability from NotificationListenerService outcomes
 */
data class NotifSignal(
    val pkg: String,
    val postedAtMs: Long,
    val isLiveActivity: Boolean = false,
    val utility: Double = 1.0,
    val importanceWeight: Double = 1.0,
    val messageCount: Int = 1,
)

/**
 * Zero-hardcoding importance weight for a single Android notification using NotificationListenerService.Ranking
 * and standard OS notification metadata (importance, priority conversations, Android on-device userSentiment,
 * direct/smart reply actions, MessagingStyle people, and Notification.category).
 */
internal fun notifImportanceWeight(
    importance: Int,
    isConversation: Boolean,
    isImportantConversation: Boolean,
    userSentiment: Int,
    hasReplyAction: Boolean,
    hasPerson: Boolean,
    category: String?,
    isAmbient: Boolean,
): Double {
    if (importance <= NotificationManager.IMPORTANCE_NONE) return 0.0
    var w = when {
        isAmbient || importance <= NotificationManager.IMPORTANCE_MIN -> 0.08
        importance == NotificationManager.IMPORTANCE_LOW -> 0.25
        importance >= NotificationManager.IMPORTANCE_HIGH -> 1.45
        else -> 1.0
    }
    w *= when {
        isImportantConversation -> 1.85
        isConversation -> 1.45
        hasPerson -> 1.25
        else -> 1.0
    }
    w *= when (userSentiment) {
        NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE -> 1.30
        NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE -> 0.25
        else -> 1.0
    }
    if (hasReplyAction) w *= 1.25
    w *= when (category) {
        Notification.CATEGORY_MISSED_CALL,
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM -> 1.60
        Notification.CATEGORY_MESSAGE,
        Notification.CATEGORY_EVENT,
        Notification.CATEGORY_REMINDER -> 1.30
        Notification.CATEGORY_PROMO,
        Notification.CATEGORY_RECOMMENDATION,
        Notification.CATEGORY_STATUS,
        Notification.CATEGORY_SERVICE,
        Notification.CATEGORY_SYSTEM -> 0.20
        else -> 1.0
    }
    return w.coerceIn(0.04, 3.0)
}

/** Decaying passive-negative impression fatigue per package (Instagram "repetitive fatigue" / RecSys SINE). */
data class FatigueState(
    val count: Double,
    val updatedAtMs: Long,
)

/**
 * Result of contextual ranking:
 *  - [ordered]: best exploitation apps first
 *  - [scores]: raw numeric score per package for spatial hysteresis & Smart Stack
 *  - [exploreCandidate]: optional outer-slot Thompson/UCB contextual bandit probe (null when no exploration needed)
 */
data class ContextualRanking(
    val ordered: List<String>,
    val scores: Map<String, Double>,
    val exploreCandidate: String? = null,
)

/** Per-app contextual bandit stats (impressions shown, taps chosen) for Bayesian UCB / Thompson exploration. */
data class BanditStats(
    val impressions: Int = 0,
    val taps: Int = 0,
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
    installedPackages: List<String> = emptyList(),
    banditStats: Map<String, BanditStats> = emptyMap(),
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

    // Signal 3b: Multi-app session skip-gram co-occurrence across the last 4 distinct apps in the current 20-min session
    val sessionCo = HashMap<String, Double>()
    val recentSessionPkgs = sorted.asReversed()
        .takeWhile { (nowMs - it.timestampMs) <= SESSION_CO_WINDOW_MS }
        .map { it.pkg }
        .distinct()
        .take(4)
        .toSet()
    if (recentSessionPkgs.isNotEmpty() && sorted.size >= 3) {
        val coHits = HashMap<String, Int>()
        var sessMatches = 0
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            while (j < sorted.size && (sorted[j].timestampMs - sorted[j - 1].timestampMs) <= SESSION_CO_WINDOW_MS) j++
            val sessionSet = HashSet<String>(j - i)
            for (k in i until j) sessionSet.add(sorted[k].pkg)
            var overlap = 0
            for (r in recentSessionPkgs) if (r in sessionSet) overlap++
            if (overlap > 0) {
                sessMatches++
                for (p in sessionSet) {
                    if (p !in recentSessionPkgs) {
                        coHits.merge(p, overlap, Int::plus)
                    }
                }
            }
            i = j
        }
        if (sessMatches > 0) {
            val denom = sessMatches + 2.0
            for ((p, h) in coHits) {
                candidates.add(p)
                sessionCo[p] = (h.toDouble() / denom).coerceAtMost(1.5)
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

    // Signal 7: Importance- & volume-weighted unread notifications + active ongoing Live Activities
    val notifMass = HashMap<String, Double>()
    val liveAct = HashMap<String, Double>()
    for (n in activeNotifications) {
        if (n.pkg.isEmpty() || n.pkg == selfPkg || n.postedAtMs > nowMs + 60_000L) continue
        candidates.add(n.pkg)
        if (n.isLiveActivity) {
            liveAct[n.pkg] = 1.0
        } else {
            val imp = n.importanceWeight.coerceIn(0.0, 3.0)
            if (imp <= 0.0) continue
            val age = (nowMs - n.postedAtMs).coerceAtLeast(0L).toDouble()
            val (halfLife, maxAge) = when {
                imp >= 1.1 -> NOTIF_IMPORTANT_HALF_LIFE_MS to (4.0 * 3600_000.0)
                imp >= 0.4 -> NOTIF_NORMAL_HALF_LIFE_MS to (90.0 * 60_000.0)
                else -> NOTIF_LOW_HALF_LIFE_MS to (30.0 * 60_000.0)
            }
            if (age <= maxAge) {
                val countBoost = 1.0 + ln(n.messageCount.coerceIn(1, 25).toDouble()) * 0.75
                val util = n.utility.coerceIn(0.15, 1.5)
                val mass = exp(-ln2 * age / halfLife) * imp * util * countBoost
                notifMass.merge(n.pkg, mass, Double::plus)
            }
        }
    }
    val activeMedia = ctx.activeMediaPkg?.takeIf { it.isNotEmpty() && it != selfPkg }
    if (activeMedia != null) candidates.add(activeMedia)

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
        val sCooccur = sessionCo[pkg] ?: 0.0
        val sTod = ln(1.0 + (tod[pkg] ?: 0.0))
        val sCal = ln(1.0 + (calMatch[pkg] ?: 0.0))
        val sCtx = (contextLift[pkg] ?: 0.0).coerceIn(-1.2, 2.8)
        val sOnline = onlineScore[pkg] ?: 0.0
        // Signal 8: Passive-negative impression fatigue (decays smoothly with 25-min half-life).
        // Fatigue also directly attenuates unread notification pull so an app with a million unread messages
        // that you deliberately keep skipping gets out of your way immediately.
        val fState = fatigue[pkg]
        val decayedFatigue = if (fState != null && fState.count > 0.0 && fState.updatedAtMs <= nowMs) {
            val age = (nowMs - fState.updatedAtMs).coerceAtLeast(0L).toDouble()
            fState.count * exp(-ln2 * age / FATIGUE_HALF_LIFE_MS)
        } else 0.0
        val sFatigue = if (decayedFatigue > 0.0) ln(1.0 + decayedFatigue) else 0.0
        val notifAttenuation = 1.0 / (1.0 + decayedFatigue * 0.95)
        val sNotif = ln(1.0 + (notifMass[pkg] ?: 0.0) * 1.15).coerceAtMost(3.2) * notifAttenuation
        val sLive = liveAct[pkg] ?: 0.0
        val sMedia = if (pkg == activeMedia) 1.0 else 0.0
        val sFallback = if (maxFallback > 0 && sorted.isEmpty()) {
            ln(1.0 + (fallbackCounts[pkg] ?: 0).toDouble()) * 0.55
        } else if (maxFallback > 0 && (habit[pkg] ?: 0.0) == 0.0) {
            0.25 * ((fallbackCounts[pkg] ?: 0).toDouble() / maxFallback.toDouble())
        } else 0.0

        val total = W_BURST * sBurst +
            W_HABIT * sHabit +
            W_MARKOV * sMarkov +
            W_TRIGRAM * sTrigram +
            W_COOCCUR * sCooccur +
            W_TOD * sTod +
            W_CALENDAR * sCal +
            W_CONTEXT * sCtx +
            W_ONLINE * sOnline +
            W_NOTIF * sNotif +
            W_LIVE * sLive +
            W_MEDIA_ACTIVE * sMedia +
            sFallback -
            W_FATIGUE * sFatigue

        if (total > 0.0) scores[pkg] = total
    }

    val ordered = scores.entries
        .sortedWith(compareBy({ -it.value }, { it.key }))
        .map { it.key }

    // Signal 9: Controlled Bayesian UCB + 20-min Thompson Exploration (Reels/TikTok-style probing)
    // Picks at most ONE under-explored installed app outside the top 6 so the OS actively tests
    // hypotheses in an outer slot and learns from how you react — without ever touching top 5 stability.
    val exploreCandidate = pickExploreCandidate(
        installedPackages = installedPackages,
        topExploit = ordered.take(6).toSet(),
        scores = scores,
        onlineScore = onlineScore,
        fatigue = fatigue,
        banditStats = banditStats,
        nowMs = nowMs,
        selfPkg = selfPkg,
    )
    return ContextualRanking(ordered, scores, exploreCandidate)
}

/**
 * Selects a single controlled contextual bandit exploration candidate (Bayesian UCB + deterministic
 * 20-min Thompson window jitter so it stays rock-steady for 20 min and then rotates smoothly).
 */
internal fun pickExploreCandidate(
    installedPackages: List<String>,
    topExploit: Set<String>,
    scores: Map<String, Double>,
    onlineScore: Map<String, Double>,
    fatigue: Map<String, FatigueState>,
    banditStats: Map<String, BanditStats>,
    nowMs: Long,
    selfPkg: String,
): String? {
    if (installedPackages.size <= 7) return null
    val ln2 = ln(2.0)
    val totalImp = banditStats.values.sumOf { it.impressions }.coerceAtLeast(1).toDouble()
    val windowBucket = (nowMs / (20 * 60_000L)).toInt()

    var bestPkg: String? = null
    var bestUcb = Double.NEGATIVE_INFINITY
    for (pkg in installedPackages) {
        if (pkg.isEmpty() || pkg == selfPkg || pkg in topExploit) continue
        // Never probe an app the user recently skipped or strongly dislikes in this context
        if ((onlineScore[pkg] ?: 0.0) < -0.35) continue
        val f = fatigue[pkg]
        if (f != null && f.count > 0.0 && f.updatedAtMs <= nowMs) {
            val decayed = f.count * exp(-ln2 * (nowMs - f.updatedAtMs).coerceAtLeast(0L).toDouble() / FATIGUE_HALF_LIFE_MS)
            if (decayed >= 0.3) continue
        }
        val st = banditStats[pkg] ?: BanditStats()
        val meanCtr = (st.taps + 1.0) / (st.impressions + 3.0)
        val uncertainty = sqrt(ln(2.0 + totalImp) / (1.0 + st.impressions))
        val contextHint = ln(1.0 + (scores[pkg] ?: 0.0).coerceAtLeast(0.0)) * 0.25
        // Deterministic per-20-min pseudo-Thompson draw in [0, 0.45] so different uncertain apps get tested across windows
        val mix = ((pkg.hashCode() * 1103515245) xor (windowBucket * 2654435761L.toInt())).toUInt()
        val thompsonJitter = ((mix % 1000u).toDouble() / 1000.0) * 0.45
        val ucb = meanCtr + 0.55 * uncertainty + contextHint + thompsonJitter
        if (ucb > bestUcb) {
            bestUcb = ucb
            bestPkg = pkg
        }
    }
    return bestPkg
}

/**
 * FTRL-Proximal inspired per-coordinate adaptive online logistic update with L1 sparsity
 * (McMahan et al., KDD 2013) + optional pairwise AUC-PA margin boost (EverythingMe arXiv:1512.07851):
 *  - Rare high-signal features (e.g. just-plugged headphones, wireless nightstand charger, alarm soon)
 *    converge in 2-3 taps via adaptive per-feature learning rate 1 / (1 + sqrt(n)).
 *  - Noisy ubiquitous features are automatically shrunk toward 0 by L1 soft-thresholding.
 */
internal fun updateOnlineWeights(
    weights: MutableMap<String, MutableMap<String, Double>>,
    pkg: String,
    activeBuckets: List<String>,
    positive: Boolean,
    lr: Double = if (positive) 0.26 else 0.11,
    l2: Double = 0.004,
    l1: Double = 0.006,
    gradAccum: MutableMap<String, MutableMap<String, Double>>? = null,
    marginScale: Double = 1.0,
) {
    val wMap = weights.getOrPut(pkg) { HashMap() }
    val nMap = gradAccum?.getOrPut(pkg) { HashMap() }
    var z = wMap["_bias"] ?: 0.0
    for (f in activeBuckets) z += wMap[f] ?: 0.0
    val pred = sigmoid(z)
    val target = if (positive) 1.0 else 0.0
    val err = (target - pred) * marginScale

    val nextBias = ((wMap["_bias"] ?: 0.0) * (1.0 - l2) + lr * 0.5 * err).coerceIn(-3.0, 3.0)
    wMap["_bias"] = nextBias
    for (f in activeBuckets) {
        val nPrev = nMap?.get(f) ?: 0.0
        val effLr = lr / (1.0 + sqrt(nPrev) * 0.65)
        val cur = wMap[f] ?: 0.0
        val raw = cur * (1.0 - l2) + effLr * err
        // L1 soft-thresholding (FTRL sparsity: keeps genuinely predictive context features, zeroes out noise)
        val shrunk = when {
            raw > l1 -> raw - l1
            raw < -l1 -> raw + l1
            else -> 0.0
        }.coerceIn(-3.5, 3.5)
        wMap[f] = shrunk
        if (nMap != null) nMap[f] = (nPrev + err * err).coerceAtMost(200.0)
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

    // Track network context transitions AND accumulate per-Wi-Fi dwell time (night / weekday / charging / visits)
    // so Honeycomb infers Home Wi-Fi vs Work Wi-Fi vs Regular vs Transient purely from dwell duration!
    val prevNetId = cPrefs.getString(KEY_LAST_NET_ID, null)
    var netChangedAt = cPrefs.getLong(KEY_LAST_NET_CHANGE_MS, 0L)
    val lastSampleMs = cPrefs.getLong(KEY_LAST_NET_SAMPLE_MS, 0L)
    val profiles = readNetworkProfiles(ctx).toMutableMap()

    // Read charging early (including wireless vs USB vs AC source!) so overnight/charging dwell is credited accurately
    var charging = false
    var chargeSource = ""
    var batteryBucket = 1
    runCatching {
        val intent = ctx.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        chargeSource = when {
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "wireless"
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "usb"
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "ac"
            else -> ""
        }
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

    val netEditor = cPrefs.edit()
    if (prevNetId == null) {
        netChangedAt = now
        netEditor.putString(KEY_LAST_NET_ID, netId).putLong(KEY_LAST_NET_CHANGE_MS, now).putLong(KEY_LAST_NET_SAMPLE_MS, now)
        if (netId.startsWith("w")) {
            val p = profiles[netId] ?: NetworkDwellProfile()
            val updated = p.copy(visits = p.visits + 1)
            profiles[netId] = updated
            writeNetworkProfile(netEditor, netId, updated)
        }
    } else if (prevNetId != netId && netId != "offline" && prevNetId != "offline") {
        netChangedAt = now
        netEditor.putString(KEY_LAST_NET_ID, netId).putLong(KEY_LAST_NET_CHANGE_MS, now).putLong(KEY_LAST_NET_SAMPLE_MS, now)
        if (netId.startsWith("w")) {
            val p = profiles[netId] ?: NetworkDwellProfile()
            val updated = p.copy(visits = p.visits + 1)
            profiles[netId] = updated
            writeNetworkProfile(netEditor, netId, updated)
        }
    } else if (netId == prevNetId && netId.startsWith("w") && lastSampleMs in 1L..now) {
        // Credit elapsed dwell minutes (capped at 120 min per wake gap so long sleep still credits realistic overnight stay)
        val deltaMin = ((now - lastSampleMs).coerceIn(0L, 120 * 60_000L)) / 60_000.0
        if (deltaMin >= 0.25) {
            val z = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            val isNight = z.hour >= 23 || z.hour < 7
            val isWorkDay = z.dayOfWeek.value in 1..5 && z.hour in 9..17
            val p = profiles[netId] ?: NetworkDwellProfile(visits = 1)
            val updated = p.copy(
                totalMin = (p.totalMin + deltaMin).coerceAtMost(50_000.0),
                nightMin = (p.nightMin + if (isNight) deltaMin else 0.0).coerceAtMost(25_000.0),
                workDayMin = (p.workDayMin + if (isWorkDay) deltaMin else 0.0).coerceAtMost(25_000.0),
                chargingMin = (p.chargingMin + if (charging) deltaMin else 0.0).coerceAtMost(25_000.0),
                visits = p.visits.coerceAtLeast(1),
            )
            profiles[netId] = updated
            writeNetworkProfile(netEditor, netId, updated)
            netEditor.putLong(KEY_LAST_NET_SAMPLE_MS, now)
        }
    }
    netEditor.apply()

    val placeRole = inferPlaceRole(netId, onWifi, profiles)
    val stayDurationMin = if (netChangedAt in 1L..now) (now - netChangedAt) / 60_000L else 30L

    var headphones = false
    var btAudioId = ""
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
            // Anonymized Bluetooth output device fingerprint (AirPods vs Car stereo vs Desk headphones, zero BT permission)
            val btDev = outputs.firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
            val btName = btDev?.productName?.toString()?.trim().orEmpty()
            if (btName.isNotEmpty()) {
                btAudioId = (btName.hashCode().toUInt()).toString(16)
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

    val alarmSoon = runCatching {
        val nextTrigger = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: 0L
        nextTrigger > now && (nextTrigger - now) <= 45 * 60_000L
    }.getOrDefault(false)
    val quietOn = runCatching { Quiet.isOn(ctx) }.getOrDefault(false)
    val activeMediaPkg = runCatching { currentMedia(ctx)?.takeIf { it.playing }?.pkg }.getOrNull()

    // Track hardware transition edges (headphones connect/disconnect, charger plug/unplug)
    val prevHp = cPrefs.getInt(KEY_LAST_HP_STATE, -1)
    val curHp = if (headphones) 1 else 0
    var hpChangedAt = cPrefs.getLong(KEY_LAST_HP_CHANGE_MS, 0L)
    val prevChg = cPrefs.getInt(KEY_LAST_CHG_STATE, -1)
    val curChg = if (charging) 1 else 0
    var chgChangedAt = cPrefs.getLong(KEY_LAST_CHG_CHANGE_MS, 0L)
    if (prevHp != curHp || prevChg != curChg) {
        val edgeEdit = cPrefs.edit()
        if (prevHp != curHp) {
            if (prevHp != -1) hpChangedAt = now
            edgeEdit.putInt(KEY_LAST_HP_STATE, curHp).putLong(KEY_LAST_HP_CHANGE_MS, hpChangedAt)
        }
        if (prevChg != curChg) {
            if (prevChg != -1) chgChangedAt = now
            edgeEdit.putInt(KEY_LAST_CHG_STATE, curChg).putLong(KEY_LAST_CHG_CHANGE_MS, chgChangedAt)
        }
        edgeEdit.apply()
    }

    return DeviceContext(
        onWifi = onWifi,
        headphonesConnected = headphones,
        charging = charging,
        networkId = netId,
        networkChangedAtMs = netChangedAt,
        placeRole = placeRole,
        stayDurationMin = stayDurationMin,
        batteryBucket = batteryBucket,
        brightnessBucket = brightnessBucket,
        volumeBucket = volumeBucket,
        ringerMode = ringerMode,
        darkMode = darkMode,
        mediaPlaying = mediaPlaying,
        carAudioConnected = carAudio,
        powerSave = powerSave,
        idleGapBucket = idleGapBucket,
        alarmSoon = alarmSoon,
        quietOn = quietOn,
        activeMediaPkg = activeMediaPkg,
        btAudioId = btAudioId,
        chargeSource = chargeSource,
        hpChangedAtMs = hpChangedAt,
        chgChangedAtMs = chgChangedAt,
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

/**
 * Reads clearable unread notifications AND ongoing Live Activities from Honeycomb's existing MediaListener,
 * enriched with Android's NotificationListenerService.RankingMap (importance, priority conversations,
 * on-device userSentiment, smart/direct replies, MessagingStyle unread message count, and OS category).
 */
private fun readActiveNotifSignals(ctx: Context): List<NotifSignal> = runCatching {
    val listener = MediaListener.Defaults.live ?: return@runCatching emptyList()
    val active = listener.activeNotifications.orEmpty()
    val rankingMap = runCatching { listener.currentRanking }.getOrNull()
    val ranking = NotificationListenerService.Ranking()
    val p = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE)
    val self = ctx.packageName
    val out = ArrayList<NotifSignal>(active.size + 2)

    for (sbn in active) {
        if (!sbn.isClearable || sbn.packageName == self) continue
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) continue

        val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
        val importance = if (hasRanking) ranking.importance else NotificationManager.IMPORTANCE_DEFAULT
        val isConversation = hasRanking && ranking.isConversation
        val isImportantConversation = hasRanking && (ranking.channel?.isImportantConversation == true)
        val sentiment = if (hasRanking) ranking.userSentiment else NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL
        val isAmbient = hasRanking && ranking.isAmbient
        val hasSmartReplies = hasRanking && ranking.smartReplies.isNotEmpty()
        val hasDirectReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true

        val msgArray = if (Build.VERSION.SDK_INT >= 33) {
            extras?.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras?.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        val peopleList = if (Build.VERSION.SDK_INT >= 33) {
            extras?.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else null
        val hasPerson = !peopleList.isNullOrEmpty() ||
            extras?.containsKey(Notification.EXTRA_MESSAGING_PERSON) == true ||
            !msgArray.isNullOrEmpty()
        val msgCount = maxOf(1, msgArray?.size ?: 1, n.number.coerceAtLeast(1)).coerceAtMost(25)

        val impWeight = notifImportanceWeight(
            importance = importance,
            isConversation = isConversation,
            isImportantConversation = isImportantConversation,
            userSentiment = sentiment,
            hasReplyAction = hasDirectReply || hasSmartReplies,
            hasPerson = hasPerson,
            category = n.category,
            isAmbient = isAmbient,
        )
        val u = notifUtility(p.getInt("no:${sbn.packageName}", 0), p.getInt("nd:${sbn.packageName}", 0))
        out.add(
            NotifSignal(
                pkg = sbn.packageName,
                postedAtMs = sbn.postTime,
                isLiveActivity = false,
                utility = u,
                importanceWeight = impWeight,
                messageCount = msgCount,
            ),
        )
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

    // FTRL-Proximal adaptive online weights + pairwise AUC-PA margin boost + contextual bandit stats + fatigue
    val weights = readOnlineWeights(ctx).mapValues { it.value.toMutableMap() }.toMutableMap()
    val gradAccum = readOnlineAccum(ctx).mapValues { it.value.toMutableMap() }.toMutableMap()
    val skipped = shownDynamicPkgs.asSequence().filter { it.isNotEmpty() && it != pkg && it != ctx.packageName }.distinct().toList()

    // Pairwise AUC-PA margin check: if the chosen app scored below the top skipped Dynamic Grid app, amplify update
    fun logitOf(p: String): Double {
        val m = weights[p] ?: return 0.0
        var z = m["_bias"] ?: 0.0
        for (f in activeBuckets) z += m[f] ?: 0.0
        return z
    }
    val chosenZ = logitOf(pkg)
    val topSkippedZ = skipped.maxOfOrNull { logitOf(it) } ?: chosenZ
    val marginScale = if (skipped.isNotEmpty() && (chosenZ - topSkippedZ) < 1.0) 1.45 else 1.0

    updateOnlineWeights(weights, pkg, activeBuckets, positive = true, gradAccum = gradAccum, marginScale = marginScale)
    // Record bandit positive reward (tap + impression)
    editor.putInt("bt:$pkg", cPrefs.getInt("bt:$pkg", 0) + 1)
    editor.putInt("bi:$pkg", cPrefs.getInt("bi:$pkg", 0) + 1)
    // Clear fatigue on the app the user actually chose
    editor.remove("f:$pkg")

    // Learn notification utility even when the user opens or ignores apps straight from the Dynamic Grid
    // (not just when tapping/swiping in Notification Centre): if an app had unread alerts and you opened it,
    // count an open; if it had unread alerts and you skipped it in the grid, count an ignore.
    val activeNotifPkgs = runCatching {
        MediaListener.Defaults.live?.activeNotifications
            ?.asSequence()
            ?.filter { it.isClearable && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            ?.map { it.packageName }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())
    if (pkg in activeNotifPkgs) {
        editor.putInt("no:$pkg", cPrefs.getInt("no:$pkg", 0) + 1)
    }

    val ln2 = ln(2.0)
    val prevFatigue = readFatigue(ctx)
    for (sk in skipped) {
        val skMargin = if (logitOf(sk) >= chosenZ - 0.5) 1.35 else 1.0
        updateOnlineWeights(weights, sk, activeBuckets, positive = false, gradAccum = gradAccum, marginScale = skMargin)
        editor.putInt("bi:$sk", cPrefs.getInt("bi:$sk", 0) + 1)
        if (sk in activeNotifPkgs) {
            editor.putInt("nd:$sk", cPrefs.getInt("nd:$sk", 0) + 1)
        }
        val cur = prevFatigue[sk]
        val decayed = if (cur != null && cur.updatedAtMs <= now) {
            cur.count * exp(-ln2 * (now - cur.updatedAtMs).coerceAtLeast(0L).toDouble() / FATIGUE_HALF_LIFE_MS)
        } else 0.0
        val nextCount = (decayed + 1.0).coerceAtMost(8.0)
        editor.putString("f:$sk", "$nextCount@$now")
    }

    // Persist compact FTRL weights & accumulators (prune weakest entries if > MAX_ONLINE_WEIGHTS)
    val allEntries = weights.flatMap { (p, m) -> m.entries.map { (f, w) -> Triple(p, f, w) } }
    val kept = if (allEntries.size > MAX_ONLINE_WEIGHTS) {
        allEntries.sortedByDescending { abs(it.third) }.take(MAX_ONLINE_WEIGHTS)
    } else allEntries
    for (k in cPrefs.all.keys) {
        if (k.startsWith("w:") || k.startsWith("n:")) editor.remove(k)
    }
    for ((p, f, w) in kept) {
        if (abs(w) >= 0.01) {
            editor.putFloat("w:$p|$f", w.toFloat())
            val acc = gradAccum[p]?.get(f) ?: 0.0
            if (acc > 0.01) editor.putFloat("n:$p|$f", acc.toFloat())
        }
    }
    editor.apply()
}

private fun usageGranted(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED
}

/**
 * Reads UsageEvents over the last 7 days, pairs ACTIVITY_RESUMED -> ACTIVITY_PAUSED/STOPPED for dwell duration,
 * and also checks whether the user just unlocked the screen (KEYGUARD_HIDDEN / SCREEN_INTERACTIVE) with no app opened yet.
 */
private fun readUsageSnapshot(ctx: Context, nowMs: Long): Pair<List<LaunchEvent>?, Boolean> {
    if (!usageGranted(ctx)) return null to false
    val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null to false
    val events = usm.queryEvents(nowMs - USE_WINDOW_MS, nowMs) ?: return null to false
    val out = ArrayList<LaunchEvent>(256)
    val openAt = HashMap<String, Long>()
    var lastUnlockMs = 0L
    var lastNonSelfLaunchMs = 0L
    val selfPkg = ctx.packageName
    val e = UsageEvents.Event()
    while (events.getNextEvent(e)) {
        val pkg = e.packageName
        when (e.eventType) {
            UsageEvents.Event.KEYGUARD_HIDDEN, UsageEvents.Event.SCREEN_INTERACTIVE -> {
                if (e.timeStamp > lastUnlockMs) lastUnlockMs = e.timeStamp
            }
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                if (!pkg.isNullOrEmpty()) {
                    openAt[pkg] = e.timeStamp
                    if (pkg != selfPkg && e.timeStamp > lastNonSelfLaunchMs) lastNonSelfLaunchMs = e.timeStamp
                }
            }
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                if (!pkg.isNullOrEmpty()) {
                    val start = openAt.remove(pkg)
                    if (start != null) {
                        val dwell = (e.timeStamp - start).coerceIn(500L, 4 * 3600_000L)
                        out.add(LaunchEvent(pkg, start, dwell))
                    }
                }
            }
        }
    }
    for ((pkg, start) in openAt) {
        val dwell = (nowMs - start).coerceIn(15_000L, 4 * 3600_000L)
        out.add(LaunchEvent(pkg, start, dwell))
    }
    val justUnlocked = lastUnlockMs > 0L && (nowMs - lastUnlockMs) <= 90_000L && lastNonSelfLaunchMs < lastUnlockMs
    return out to justUnlocked
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

private fun readOnlineAccum(ctx: Context): Map<String, Map<String, Double>> {
    val all = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).all
    val out = HashMap<String, MutableMap<String, Double>>()
    for ((k, v) in all) {
        if (!k.startsWith("n:")) continue
        val n = (v as? Number)?.toDouble() ?: continue
        val sep = k.indexOf('|', 2)
        if (sep <= 2) continue
        val pkg = k.substring(2, sep)
        val feat = k.substring(sep + 1)
        out.getOrPut(pkg) { HashMap() }[feat] = n
    }
    return out
}

private fun readBanditStats(ctx: Context): Map<String, BanditStats> {
    val all = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).all
    val imps = HashMap<String, Int>()
    val taps = HashMap<String, Int>()
    for ((k, v) in all) {
        val n = v as? Int ?: continue
        if (n <= 0) continue
        when {
            k.startsWith("bi:") -> imps[k.substring(3)] = n
            k.startsWith("bt:") -> taps[k.substring(3)] = n
        }
    }
    val pkgs = imps.keys + taps.keys
    return pkgs.associateWith { p -> BanditStats(impressions = imps[p] ?: 0, taps = taps[p] ?: 0) }
}

private fun readNetworkProfiles(ctx: Context): Map<String, NetworkDwellProfile> {
    val all = ctx.getSharedPreferences(CONTEXT_PREFS, Context.MODE_PRIVATE).all
    val out = HashMap<String, NetworkDwellProfile>()
    for ((k, v) in all) {
        if (!k.startsWith("np:")) continue
        val raw = v as? String ?: continue
        val parts = raw.split(',')
        if (parts.size < 5) continue
        val total = parts[0].toDoubleOrNull() ?: continue
        val night = parts[1].toDoubleOrNull() ?: continue
        val work = parts[2].toDoubleOrNull() ?: continue
        val chg = parts[3].toDoubleOrNull() ?: continue
        val visits = parts[4].toIntOrNull() ?: 1
        out[k.substring(3)] = NetworkDwellProfile(total, night, work, chg, visits)
    }
    return out
}

private fun writeNetworkProfile(editor: android.content.SharedPreferences.Editor, netId: String, p: NetworkDwellProfile) {
    editor.putString("np:$netId", "${p.totalMin},${p.nightMin},${p.workDayMin},${p.chargingMin},${p.visits}")
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
    val (usage, justUnlocked) = readUsageSnapshot(ctx, now)
    val events = if (!usage.isNullOrEmpty()) usage else readLocalHistory(ctx)
    val fallback = ctx.getSharedPreferences(COUNTS, Context.MODE_PRIVATE).all
        .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }
        .toMap()
    val contextCounts = readContextCounts(ctx)
    val devCtx = readDeviceContext(ctx).copy(justUnlocked = justUnlocked)
    val notifs = readActiveNotifSignals(ctx)
    val weights = readOnlineWeights(ctx)
    val fatigue = readFatigue(ctx)
    val bandit = readBanditStats(ctx)
    val installedPkgs = AppCache.apps.value.orEmpty()
        .map { it.component.packageName }
        .filter { !isInternal(it) }
        .distinct()
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
        installedPackages = installedPkgs,
        banditStats = bandit,
    )
}

/** Packages ranked most relevant right now (backwards-compatible entry point). */
fun usedPackages(ctx: Context): List<String> = contextualPackages(ctx).ordered
