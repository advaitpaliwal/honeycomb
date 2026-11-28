package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ContextualRankTest {
    private val utc = ZoneId.of("UTC")
    private fun ts(year: Int, month: Int, day: Int, hour: Int, min: Int): Long =
        ZonedDateTime.of(year, month, day, hour, min, 0, 0, utc).toInstant().toEpochMilli()

    @Test fun partyBurstPromotesPartifulImmediatelyAndFadesTwoHoursLater() {
        val now = ts(2026, 11, 28, 22, 0) // Saturday 10:00 PM
        val events = buildList {
            // Steady everyday habit: Chrome opened 3x/day across 6 days (18 total launches)
            for (d in 1..6) {
                add(LaunchEvent("com.android.chrome", now - d * 24 * 3600_000L))
                add(LaunchEvent("com.android.chrome", now - d * 24 * 3600_000L + 3 * 3600_000L))
                add(LaunchEvent("com.android.chrome", now - d * 24 * 3600_000L + 6 * 3600_000L))
            }
            // Sudden party burst: Partiful opened 4 times in the last 18 minutes
            add(LaunchEvent("com.partiful.app", now - 18 * 60_000L))
            add(LaunchEvent("com.partiful.app", now - 12 * 60_000L))
            add(LaunchEvent("com.partiful.app", now - 5 * 60_000L))
            add(LaunchEvent("com.partiful.app", now - 2 * 60_000L))
        }

        val duringParty = rankContextualPackages(
            events = events,
            fallbackCounts = emptyMap(),
            contextCounts = emptyMap(),
            nowMs = now,
            ctx = DeviceContext(onWifi = false),
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        assertEquals("Partiful should outrank steady weekly Chrome during an active party burst",
            "com.partiful.app", duringParty.ordered.first())

        // 3 hours later, with Chrome opened once casually in the meantime, Partiful fades back below Chrome
        val threeHoursLater = now + 3 * 3600_000L
        val afterParty = rankContextualPackages(
            events = events + LaunchEvent("com.android.chrome", threeHoursLater - 10 * 60_000L),
            fallbackCounts = emptyMap(),
            contextCounts = emptyMap(),
            nowMs = threeHoursLater,
            ctx = DeviceContext(onWifi = true),
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        assertEquals("Chrome should reclaim #1 once the party burst decays",
            "com.android.chrome", afterParty.ordered.first())
    }

    @Test fun markovChainBoostsNextAppInSequence() {
        val now = ts(2026, 11, 28, 22, 30)
        val events = buildList {
            // Historical pattern: Partiful -> X -> Messages repeated 4 times earlier in the evening
            for (k in 4 downTo 1) {
                val base = now - k * 30 * 60_000L
                add(LaunchEvent("com.partiful.app", base))
                add(LaunchEvent("com.twitter.android", base + 40_000L))
                add(LaunchEvent("com.google.android.apps.messaging", base + 90_000L))
            }
            // Just closed Partiful 20 seconds ago!
            add(LaunchEvent("com.partiful.app", now - 20_000L))
        }
        val ranked = rankContextualPackages(
            events = events,
            fallbackCounts = emptyMap(),
            contextCounts = emptyMap(),
            nowMs = now,
            ctx = DeviceContext(),
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        val xScore = ranked.scores["com.twitter.android"] ?: 0.0
        val msgScore = ranked.scores["com.google.android.apps.messaging"] ?: 0.0
        assertTrue("Markov chain P(X | Partiful) should boost X above Messages right after Partiful ($xScore vs $msgScore)",
            xScore > msgScore)
    }

    @Test fun circularTimeOfDayWrapsSmoothlyAcrossMidnight() {
        // User opens Kindle around 11:40 PM every night; test at 12:10 AM (only 30 min circular distance!)
        val now = ts(2026, 11, 25, 0, 10) // Wednesday 00:10
        val events = listOf(
            LaunchEvent("com.amazon.kindle", ts(2026, 11, 24, 23, 40)),
            LaunchEvent("com.amazon.kindle", ts(2026, 11, 23, 23, 45)),
            LaunchEvent("com.slack.app", ts(2026, 11, 24, 14, 0)),
            LaunchEvent("com.slack.app", ts(2026, 11, 23, 14, 0)),
        )
        val ranked = rankContextualPackages(
            events = events,
            fallbackCounts = emptyMap(),
            contextCounts = emptyMap(),
            nowMs = now,
            ctx = DeviceContext(),
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        assertEquals("Circular Gaussian kernel across midnight should rank Kindle over afternoon Slack",
            "com.amazon.kindle", ranked.ordered.first())
    }

    @Test fun passiveContextAndLiveNotificationSignalsBoostMatchingApps() {
        val now = ts(2026, 11, 25, 18, 0)
        val contextCounts = mapOf(
            "audio_hp" to mapOf("com.spotify.music" to 12, "com.google.android.apps.maps" to 1),
            "net_wifi" to mapOf("com.spotify.music" to 2, "com.google.android.apps.maps" to 10, "com.android.chrome" to 10),
        )
        val withHeadphones = rankContextualPackages(
            events = emptyList(),
            fallbackCounts = mapOf("com.spotify.music" to 14, "com.google.android.apps.maps" to 11),
            contextCounts = contextCounts,
            nowMs = now,
            ctx = DeviceContext(onWifi = false, headphonesConnected = true),
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        assertEquals("Headphones context should surface Spotify first",
            "com.spotify.music", withHeadphones.ordered.first())

        val withFreshNotification = rankContextualPackages(
            events = emptyList(),
            fallbackCounts = mapOf("com.android.chrome" to 10),
            contextCounts = emptyMap(),
            nowMs = now,
            ctx = DeviceContext(),
            activeNotifications = listOf(NotifSignal("com.whatsapp", now - 60_000L)),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        assertEquals("Fresh 1-minute-old notification should surface WhatsApp immediately",
            "com.whatsapp", withFreshNotification.ordered.first())
    }

    @Test fun antiJitterHysteresisPreservesPhysicalSlotsAndResistsSmallFluctuations() {
        val installed = listOf("a/.A", "b/.B", "c/.C", "d/.D", "e/.E", "f/.F", "g/.G", "h/.H")
        val label = { k: String -> k }
        val saved = listOf("a/.A", "", "", "", "", "", "") // slot 0 pinned to a/.A
        val prevShown = listOf("a/.A", "b/.B", "c/.C", "d/.D", "e/.E", "f/.F", "g/.G")

        // Challenger h/.H has score 1.10 vs weakest incumbent g/.G at 1.00 (below 1.20x hysteresis -> NO swap!)
        val mildRanking = ContextualRanking(
            ordered = listOf("a", "b", "c", "d", "e", "f", "h", "g"),
            scores = mapOf("a" to 5.0, "b" to 4.0, "c" to 3.0, "d" to 2.5, "e" to 2.0, "f" to 1.5, "h" to 1.10, "g" to 1.00),
        )
        val stable = AppOrder.favouritesStable(saved, installed, mildRanking, prevShown, label, hysteresis = 1.20)
        assertEquals("Sub-threshold challenger must not jitter existing slots", prevShown, stable)

        // Real burst: challenger h/.H jumps to 3.50 (3.5x > g/.G's 1.00) -> evicts ONLY g/.G at slot 6, keeping slots 0..5 untouched!
        val burstRanking = ContextualRanking(
            ordered = listOf("a", "b", "h", "c", "d", "e", "f", "g"),
            scores = mapOf("a" to 5.0, "b" to 4.0, "h" to 3.50, "c" to 3.0, "d" to 2.5, "e" to 2.0, "f" to 1.5, "g" to 1.00),
        )
        val afterBurst = AppOrder.favouritesStable(saved, installed, burstRanking, prevShown, label, hysteresis = 1.20)
        assertEquals(listOf("a/.A", "b/.B", "c/.C", "d/.D", "e/.E", "f/.F", "h/.H"), afterBurst)
    }

    @Test fun quickPriceShoppingBouncesAndArrivingHomeFlushCompetingRideAppsAutomatically() {
        val now = ts(2026, 11, 28, 6, 50)
        val arrivedHomeAt = now - 3 * 60_000L // stepped onto Home Wi-Fi 3 min ago
        val events = buildList {
            // Steady home habit: Messages & Chrome used daily at home
            for (d in 1..4) {
                add(LaunchEvent("com.google.android.apps.messaging", now - d * 24 * 3600_000L, dwellMs = 90_000L))
                add(LaunchEvent("com.android.chrome", now - d * 24 * 3600_000L, dwellMs = 120_000L))
            }
            // 15 minutes ago outside on cellular: quick 6-8s price-shopping bounces across 3 apps
            add(LaunchEvent("com.ubercab", now - 16 * 60_000L, dwellMs = 6_000L))
            add(LaunchEvent("me.lyft.android", now - 15 * 60_000L, dwellMs = 5_000L))
            add(LaunchEvent("com.waymo.carapp", now - 14 * 60_000L, dwellMs = 7_000L))
            // Committed session in Robotaxi (90s)
            add(LaunchEvent("com.tesla.riders", now - 13 * 60_000L, dwellMs = 90_000L))
        }
        val rankedAtHome = rankContextualPackages(
            events = events,
            fallbackCounts = emptyMap(),
            contextCounts = emptyMap(),
            nowMs = now,
            ctx = DeviceContext(onWifi = true, networkId = "w_home123", networkChangedAtMs = arrivedHomeAt),
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
        )
        // Zero hardcoding: committed Robotaxi outranks bounced Uber/Lyft/Waymo, AND steady home apps beat pre-arrival bounces!
        val msgScore = rankedAtHome.scores["com.google.android.apps.messaging"] ?: 0.0
        val uberScore = rankedAtHome.scores["com.ubercab"] ?: 0.0
        val lyftScore = rankedAtHome.scores["me.lyft.android"] ?: 0.0
        val waymoScore = rankedAtHome.scores["com.waymo.carapp"] ?: 0.0
        val robotaxiScore = rankedAtHome.scores["com.tesla.riders"] ?: 0.0
        assertTrue("Home Messages ($msgScore) should beat pre-arrival bounced Uber ($uberScore)", msgScore > uberScore)
        assertTrue("Home Messages ($msgScore) should beat pre-arrival bounced Lyft ($lyftScore)", msgScore > lyftScore)
        assertTrue("Home Messages ($msgScore) should beat pre-arrival bounced Waymo ($waymoScore)", msgScore > waymoScore)
        assertTrue("Committed Robotaxi ($robotaxiScore) should outrank bounced Uber ($uberScore)", robotaxiScore > uberScore)
    }

    @Test fun onlineMomentModelAndPassiveNegativeFatigueLearnWithoutHardcoding() {
        val now = ts(2026, 11, 28, 0, 30) // 12:30 AM, dark room, charging, muted
        val bedCtx = DeviceContext(
            onWifi = true,
            networkId = "w_home",
            charging = true,
            brightnessBucket = 0,
            volumeBucket = 0,
            darkMode = true,
            idleGapBucket = 2,
        )
        val buckets = bedCtx.activeBuckets(now, utc)
        assertTrue("Should synthesize cross-feature x_bed", "x_bed" in buckets)
        assertTrue("Should synthesize network-specific id", "netid_w_home" in buckets)
        assertTrue("Should synthesize date-of-month end band", "dom_end" in buckets)

        val weights = HashMap<String, MutableMap<String, Double>>()
        // User repeatedly taps Kindle in bed while skipping Uber shown in the grid
        repeat(4) {
            updateOnlineWeights(weights, "com.amazon.kindle", buckets, positive = true)
            updateOnlineWeights(weights, "com.ubercab", buckets, positive = false)
        }
        val ranked = rankContextualPackages(
            events = emptyList(),
            fallbackCounts = mapOf("com.amazon.kindle" to 5, "com.ubercab" to 5),
            contextCounts = emptyMap(),
            nowMs = now,
            ctx = bedCtx,
            activeNotifications = emptyList(),
            selfPkg = "org.honeycomb.launcher",
            zoneId = utc,
            onlineWeights = weights,
            fatigue = mapOf("com.ubercab" to FatigueState(count = 3.0, updatedAtMs = now - 60_000L)),
        )
        assertEquals("Online model + skip fatigue should put Kindle #1 over skipped Uber",
            "com.amazon.kindle", ranked.ordered.first())
    }

    @Test fun learnedNotificationUtilityDownranksRepeatedlyDismissedSpam() {
        assertTrue(notifUtility(opened = 10, dismissed = 1) > notifUtility(opened = 0, dismissed = 12) * 2.5)
    }

    @Test fun unsupervisedWifiDwellInfersHomeWorkRegularAndTransientPlaces() {
        val profiles = mapOf(
            "w_apt" to NetworkDwellProfile(totalMin = 900.0, nightMin = 480.0, workDayMin = 60.0, chargingMin = 360.0, visits = 6),
            "w_office" to NetworkDwellProfile(totalMin = 600.0, nightMin = 0.0, workDayMin = 540.0, chargingMin = 90.0, visits = 5),
            "w_cafe" to NetworkDwellProfile(totalMin = 180.0, nightMin = 0.0, workDayMin = 40.0, chargingMin = 0.0, visits = 3),
            "w_airport" to NetworkDwellProfile(totalMin = 25.0, nightMin = 0.0, workDayMin = 0.0, chargingMin = 0.0, visits = 1),
        )
        assertEquals("home", inferPlaceRole("w_apt", onWifi = true, profiles))
        assertEquals("work", inferPlaceRole("w_office", onWifi = true, profiles))
        assertEquals("regular", inferPlaceRole("w_cafe", onWifi = true, profiles))
        assertEquals("transient", inferPlaceRole("w_airport", onWifi = true, profiles))
        assertEquals("away", inferPlaceRole("cell", onWifi = false, profiles))
    }

    @Test fun smartStackReordersUserWidgetsByLiveContextWhilePreservingUnscoredOrder() {
        val pkgs = mapOf(
            10 to "com.google.android.calendar",
            20 to "com.amazon.kindle",
            30 to "com.weather.app",
            40 to "com.notes.app",
        )
        val morningWork = ContextualRanking(
            ordered = listOf("com.google.android.calendar", "com.amazon.kindle"),
            scores = mapOf("com.google.android.calendar" to 9.5, "com.amazon.kindle" to 1.2),
        )
        val nightHome = ContextualRanking(
            ordered = listOf("com.amazon.kindle", "com.google.android.calendar"),
            scores = mapOf("com.amazon.kindle" to 10.4, "com.google.android.calendar" to 0.8),
        )
        // Saved order: 30 (weather), 20 (kindle), 10 (calendar), 40 (notes)
        val saved = listOf(30, 20, 10, 40)
        assertEquals(
            "At work in the morning, Calendar surfaces to #1 while unscored Weather stays ahead of unscored Notes",
            listOf(10, 20, 30, 40),
            rankStackWidgets(saved, { pkgs[it] }, morningWork),
        )
        assertEquals(
            "At home at night, Kindle surfaces to #1 while unscored widgets preserve their saved relative order",
            listOf(20, 10, 30, 40),
            rankStackWidgets(saved, { pkgs[it] }, nightHome),
        )
    }
}
