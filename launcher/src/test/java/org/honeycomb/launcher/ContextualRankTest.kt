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
}
