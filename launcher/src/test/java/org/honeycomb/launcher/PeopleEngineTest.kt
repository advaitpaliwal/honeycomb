package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleEngineTest {
    @Test fun rejectsShortCodesBotsAndActionShortcutsWhileKeepingRealPeople() {
        assertTrue(isLikelyRealPerson("Mom"))
        assertTrue(isLikelyRealPerson("Alex Chen"))
        assertTrue(isLikelyRealPerson("+1 (415) 555-0192"))
        assertTrue(isLikelyRealPerson("NYC Dinner Group"))

        assertFalse(isLikelyRealPerson("22395"))
        assertFalse(isLikelyRealPerson("732873"))
        assertFalse(isLikelyRealPerson("1-800-555-0199"))
        assertFalse(isLikelyRealPerson("New message"))
        assertFalse(isLikelyRealPerson("Start chat"))
        assertFalse(isLikelyRealPerson("Chase Bank"))
        assertFalse(isLikelyRealPerson("Verification Code"))
        assertFalse(isLikelyRealPerson("DoorDash"))
        assertFalse(isLikelyRealPerson("noreply@company.com"))
    }

    @Test fun ranksActiveConversationsUnreadPingsAndCloseHabitsAboveColdShortcuts() {
        val now = 1_750_000_000_000L
        val candidates = listOf(
            PersonCandidate("com.whatsapp|sc1", "com.whatsapp", "sc1", "Cold Acquaintance", "WhatsApp", shortcutLastChangedMs = now - 5 * 24 * 3600_000L, shortcutRank = 4),
            PersonCandidate("com.whatsapp|sc2", "com.whatsapp", "sc2", "Alex Chen", "WhatsApp", shortcutLastChangedMs = now - 10 * 60_000L, shortcutRank = 2),
            PersonCandidate("com.google.android.apps.messaging|sc3", "com.google.android.apps.messaging", "sc3", "Mom", "Messages", shortcutLastChangedMs = now - 24 * 3600_000L, shortcutRank = 0),
            PersonCandidate("com.google.android.apps.messaging|sc4", "com.google.android.apps.messaging", "sc4", "22395", "Messages", shortcutLastChangedMs = now - 60_000L, shortcutRank = 0),
        )
        val interactions = listOf(
            // Active burst with Alex right now (4 messages + 1 outbound tap)
            PersonInteraction("com.whatsapp", "sc2", "alex chen", now - 20 * 60_000L, 1.0),
            PersonInteraction("com.whatsapp", "sc2", "alex chen", now - 12 * 60_000L, 2.0),
            PersonInteraction("com.whatsapp", "sc2", "alex chen", now - 4 * 60_000L, 1.0),
            // Steady weekly habit with Mom
            PersonInteraction("com.google.android.apps.messaging", "sc3", "mom", now - 1 * 24 * 3600_000L, 2.0),
            PersonInteraction("com.google.android.apps.messaging", "sc3", "mom", now - 3 * 24 * 3600_000L, 2.0),
        )
        val unread = listOf(
            UnreadPersonPing("com.whatsapp", "sc2", "alex chen", now - 90_000L),
        )

        val ranked = rankRecommendedPeople(candidates, interactions, unread, now)
        assertEquals("Short-code bot 22395 must be filtered out", 3, ranked.size)
        assertEquals("Alex Chen (active burst + unread) should rank #1", "Alex Chen", ranked[0].candidate.name)
        assertTrue(ranked[0].hasUnread)
        assertEquals("Mom (close weekly habit) should rank #2", "Mom", ranked[1].candidate.name)
        assertEquals("Cold Acquaintance", ranked[2].candidate.name)
    }

    @Test fun deduplicatesSamePersonAcrossMessagingAppsKeepingStrongestChannel() {
        val now = 1_750_000_000_000L
        val candidates = listOf(
            PersonCandidate("com.google.android.apps.messaging|sms1", "com.google.android.apps.messaging", "sms1", "Sam Patel", "Messages", shortcutLastChangedMs = now - 4 * 24 * 3600_000L),
            PersonCandidate("com.whatsapp|wa1", "com.whatsapp", "wa1", "Sam Patel", "WhatsApp", shortcutLastChangedMs = now - 5 * 60_000L),
        )
        val interactions = listOf(
            PersonInteraction("com.whatsapp", "wa1", "sam patel", now - 3 * 60_000L, 2.0),
        )
        val ranked = rankRecommendedPeople(candidates, interactions, emptyList(), now)
        assertEquals(1, ranked.size)
        assertEquals("com.whatsapp", ranked[0].candidate.pkg)
    }

    @Test fun filterPeopleRanksPrefixThenWordStartThenSubstring() {
        fun person(name: String, app: String = "WhatsApp") = RecommendedPerson(
            id = name, name = name, shortName = shortPersonName(name), pkg = "com.whatsapp", appLabel = app,
            shortcutId = name, userHandle = null, avatar = null, appBadge = null, hasUnread = false, score = 1.0,
        )
        val list = listOf(person("Priya Sharma"), person("Samir"), person("Alex Sam"), person("Liam"))
        val hits = filterPeople(list, "sam").map { it.name }
        assertEquals(listOf("Samir", "Alex Sam"), hits)
    }
}
