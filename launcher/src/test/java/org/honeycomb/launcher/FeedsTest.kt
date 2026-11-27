package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSwitchTest {
    private val t0 = 1_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test fun everyAppStartsBlocked() = assertTrue(FeedSwitch().blocking(t0))

    @Test fun switchingOffWaitsTwentyFourHours() {
        val s = FeedSwitch().requestOff(t0)
        assertTrue(s.blocking(t0))
        assertTrue(s.blocking(t0 + day - 1))
        assertFalse(s.blocking(t0 + day))
        assertEquals(FeedSwitch(on = false), s.settle(t0 + day))
    }

    @Test fun askingAgainDoesNotRestartTheWait() {
        val s = FeedSwitch().requestOff(t0).requestOff(t0 + day / 2)
        assertFalse(s.blocking(t0 + day))
    }

    @Test fun cancelKeepsTheBlock() {
        val s = FeedSwitch().requestOff(t0).cancelOff(t0 + 60_000)
        assertTrue(s.blocking(t0 + 2 * day))
        assertNull(s.offAt)
    }

    @Test fun cancelAfterTheWaitIsTooLate() {
        val s = FeedSwitch().requestOff(t0).cancelOff(t0 + day)
        assertFalse(s.blocking(t0 + day))
    }

    @Test fun switchingOnIsInstant() {
        val s = FeedSwitch().requestOff(t0).settle(t0 + day).switchOn()
        assertTrue(s.blocking(t0 + day))
    }

    @Test fun waitLeftCountsDown() {
        val s = FeedSwitch().requestOff(t0)
        assertEquals(day, s.waitLeft(t0))
        assertEquals(24L, hoursLeft(s.waitLeft(t0)!!))
        assertEquals(1L, hoursLeft(s.waitLeft(t0 + day - 1)!!))
        assertNull(s.waitLeft(t0 + day))
    }
}

class FeedRulesTest {
    private fun rule(pkg: String) = ruleFor(pkg)!!
    private fun tabs(vararg t: Pair<String, Boolean>) = t.map { (id, sel) -> SeenTab("", sel, "com.instagram.android:id/$id") }

    @Test fun instagramKeepsOnlyMessages() {
        val ig = rule("com.instagram.android")
        val bar = listOf("feed_tab", "search_tab", "clips_tab", "direct_tab", "profile_tab")
        for (cut in listOf("feed_tab", "search_tab", "clips_tab")) {
            val seen = tabs(*bar.map { it to (it == cut) }.toTypedArray())
            assertEquals(cut, 3, tabRedirect(ig.allowTabs, seen, ig.blockTabs))
        }
        assertNull(tabRedirect(ig.allowTabs, tabs(*bar.map { it to (it == "direct_tab") }.toTypedArray()), ig.blockTabs))
        assertEquals(GateAction.BACK, chooseAction(ig, false, listOf(SeenNode("com.instagram.android:id/clips_viewer_view_pager", true))))
        assertNull(chooseAction(ig, false, listOf(SeenNode("com.instagram.android:id/direct_thread", true))))
    }

    @Test fun youtubeKeepsSearchAndWatch() {
        val yt = rule("com.google.android.youtube")
        val bar = listOf("Home", "Shorts", "Subscriptions", "You")
        fun on(sel: String) = bar.map { SeenTab(it, it == sel) }
        assertEquals(3, tabRedirect(yt.allowTabs, on("Home"), yt.blockTabs))
        assertEquals(3, tabRedirect(yt.allowTabs, on("Shorts"), yt.blockTabs))
        assertNull(tabRedirect(yt.allowTabs, on("Subscriptions"), yt.blockTabs))
        assertNull(tabRedirect(yt.allowTabs, on("You"), yt.blockTabs))
        assertEquals(GateAction.BACK, chooseAction(yt, false, listOf(SeenNode("com.google.android.youtube:id/reel_watch_player", true))))
        assertNull(chooseAction(yt, false, listOf(SeenNode("com.google.android.youtube:id/watch_player", true))))
        assertNull(chooseAction(yt, false, listOf(SeenNode("com.google.android.youtube:id/search_edit_text", true))))
    }

    @Test fun tiktokIsBlockedEntirely() {
        for (pkg in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")) {
            val tt = rule(pkg)
            assertTrue(blocksWholeApp(tt))
            assertEquals(GateAction.HOME, chooseAction(tt, true, emptyList()))
        }
    }

    @Test fun facebookAndXFeedsAreCut() {
        val fb = rule("com.facebook.katana")
        assertEquals(1, tabRedirect(fb.allowTabs, listOf(SeenTab("Home", true), SeenTab("Marketplace", false)), fb.blockTabs))
        val x = rule("com.twitter.android")
        val bar = listOf("Home", "Search and Explore", "Notifications", "Messages")
        assertEquals(3, tabRedirect(x.allowTabs, bar.map { SeenTab(it, it == "Home") }, x.blockTabs))
        assertNull(tabRedirect(x.allowTabs, bar.map { SeenTab(it, it == "Messages") }, x.blockTabs))
    }

    @Test fun messagingAppsAreNeverTouched() {
        for (pkg in MESSAGING_PACKAGES) assertNull(pkg, ruleFor(pkg))
    }

    @Test fun perFeatureTogglesBuildTargetedRules() {
        val ig = rule("com.instagram.android")
        val reelsOnly = effectiveRule(ig, setOf("reels"))!!
        assertEquals(listOf("clips_tab", "Reels"), reelsOnly.blockTabs)
        assertTrue(reelsOnly.hints.contains("clips_viewer_view_pager"))
        // Home feed stays allowed & usable as redirect target when only Reels is toggled on
        assertTrue("feed_tab" in reelsOnly.allowTabs)
        val bar = listOf("feed_tab", "search_tab", "clips_tab", "profile_tab")
        assertNull(tabRedirect(reelsOnly.allowTabs, tabs(*bar.map { it to (it == "feed_tab") }.toTypedArray()), reelsOnly.blockTabs))
        assertEquals(0, tabRedirect(reelsOnly.allowTabs, tabs(*bar.map { it to (it == "clips_tab") }.toTypedArray()), reelsOnly.blockTabs))
        // Tab mask matching works by both viewId and label
        assertTrue(tabMatchesAny(SeenTab("Reels", false, null), reelsOnly.blockTabs))
        assertTrue(tabMatchesAny(SeenTab("", false, "com.instagram.android:id/clips_tab"), reelsOnly.blockTabs))
        assertFalse(tabMatchesAny(SeenTab("Home", false, "com.instagram.android:id/feed_tab"), reelsOnly.blockTabs))
        // When Home feed is also blocked (the default), feed_tab is removed from allowTabs and redirects to DMs
        assertTrue(ig.features.first { it.id == "feed" }.defaultBlocked)
        val allBlocked = effectiveRule(ig, setOf("reels", "explore", "feed"))!!
        assertFalse("feed_tab" in allBlocked.allowTabs)
        assertEquals("Messages", tabLabel("No unread messages"))
        assertEquals("Messages", tabLabel("3 unread messages, double tap"))
        val topBarDm = listOf(
            SeenTab("Home", true, "com.instagram.android:id/feed_tab"),
            SeenTab(tabLabel("No unread messages"), false, null),
        )
        assertEquals(1, tabRedirect(allBlocked.allowTabs, topBarDm, allBlocked.blockTabs))
        // Toggling everything off disables blocking for that app
        assertNull(effectiveRule(ig, emptySet()))
    }

    @Test fun packageAliasesAndExpandedPlatformsResolveCleanly() {
        // ReVanced / RVX YouTube shares YouTube's Shorts rule with its own package name for view-id lookup, while YouTube Music is untouched
        val revanced = rule("app.revanced.android.youtube")
        assertEquals("app.revanced.android.youtube", revanced.packageName)
        assertEquals("YouTube", revanced.label)
        assertEquals(GateAction.BACK, chooseAction(revanced, false, listOf(SeenNode("app.revanced.android.youtube:id/reel_watch_player", true))))
        assertNull(ruleFor("app.revanced.android.apps.youtube.music"))
        assertNull(ruleFor("com.google.android.apps.youtube.music"))

        // Instagram Lite & Facebook Lite resolve to their parent rules
        assertEquals("com.instagram.lite", rule("com.instagram.lite").packageName)
        assertEquals("Instagram", rule("com.instagram.lite").label)
        assertEquals("Facebook", rule("com.facebook.lite").label)

        // Reddit, Threads, and LinkedIn rules
        val reddit = rule("com.reddit.frontpage")
        assertEquals(GateAction.BACK, chooseAction(reddit, false, listOf(SeenNode("com.reddit.frontpage:id/video_player_view", true))))
        // Reddit Home feed is blocked by default, and tabLabel("Inbox") -> "Messages" must still match Reddit's allowTabs
        assertTrue(reddit.features.first { it.id == "home" }.defaultBlocked)
        val redditDefault = effectiveRule(reddit, reddit.features.filter { it.defaultBlocked }.map { it.id }.toSet())!!
        val redditTabs = listOf(
            SeenTab(tabLabel("Home"), true),
            SeenTab(tabLabel("Communities"), false),
            SeenTab(tabLabel("Chat"), false),
            SeenTab(tabLabel("Inbox"), false),
        )
        assertEquals(3, tabRedirect(redditDefault.allowTabs, redditTabs, redditDefault.blockTabs))
        assertNull(tabRedirect(redditDefault.allowTabs, redditTabs.map { it.copy(selected = it.label == "Messages") }, redditDefault.blockTabs))
        assertTrue("com.google.android.googlequicksearchbox" in BROWSER_PACKAGES)
        val threads = rule("com.instagram.barcelona")
        assertTrue(blocksWholeApp(threads))
        val linkedin = rule("com.linkedin.android")
        assertEquals(GateAction.BACK, chooseAction(linkedin, false, listOf(SeenNode("com.linkedin.android:id/video_viewer", true))))
    }

    @Test fun browserShortVideoUrlsMatchOnlyShortFormFeeds() {
        // Short-form video feeds across mobile browser URL formats (with/without scheme, www, m.)
        assertTrue(isShortVideoUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(isShortVideoUrl("m.youtube.com/shorts/abc123"))
        assertTrue(isShortVideoUrl("youtube.com/shorts"))
        assertTrue(isShortVideoUrl("https://www.instagram.com/reel/C9xyz123/"))
        assertTrue(isShortVideoUrl("instagram.com/reels/audio/123/"))
        assertTrue(isShortVideoUrl("https://www.tiktok.com/@creator/video/7123456"))
        assertTrue(isShortVideoUrl("vm.tiktok.com/ZM12345/"))
        assertTrue(isShortVideoUrl("m.facebook.com/reel/987654321"))
        assertTrue(isShortVideoUrl("fb.watch/xyz123/"))

        // Normal long-form videos, DMs, search queries, and omnibox typing must NEVER be blocked
        assertFalse(isShortVideoUrl("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(isShortVideoUrl("youtube.com/@channel/videos"))
        assertFalse(isShortVideoUrl("instagram.com/direct/inbox/"))
        assertFalse(isShortVideoUrl("facebook.com/marketplace/"))
        assertFalse(isShortVideoUrl("https://www.google.com/search?q=youtube.com/shorts/abc"))
        assertFalse(isShortVideoUrl("youtube.com/shorts how to block"))
        assertFalse(isShortVideoUrl(null))
        assertFalse(isShortVideoUrl(""))
    }

    @Test fun websiteDomainAndCategoryBlockerMatchesSubdomainsCleanly() {
        assertEquals("reddit.com", normalizeDomain("  https://www.Reddit.com/r/all?sort=hot "))
        assertEquals("news.ycombinator.com", normalizeDomain("news.ycombinator.com/newest"))
        assertNull(normalizeDomain("how to focus better"))
        assertNull(normalizeDomain(""))

        // Custom domains match exact host and subdomains, never unrelated lookalike domains
        val custom = setOf("reddit.com", "espn.com")
        assertTrue(isWebsiteBlocked("https://old.reddit.com/r/android/", false, emptySet(), custom))
        assertTrue(isWebsiteBlocked("m.espn.com/nba/", false, emptySet(), custom))
        assertFalse(isWebsiteBlocked("https://notreddit.com/page", false, emptySet(), custom))

        // Category presets (Social, Adult TLDs, News)
        assertTrue(isWebsiteBlocked("https://x.com/home", false, setOf("social"), emptySet()))
        assertTrue(isWebsiteBlocked("example.xxx/video", false, setOf("adult"), emptySet()))
        assertTrue(isWebsiteBlocked("https://pornhub.com/view", false, setOf("adult"), emptySet()))
        assertTrue(isWebsiteBlocked("news.ycombinator.com", false, setOf("news"), emptySet()))
        assertFalse(isWebsiteBlocked("wikipedia.org/wiki/Watch", false, setOf("social", "adult", "news"), custom))

        // Fine-grain per-category excludes & custom additions
        assertFalse(
            isWebsiteBlocked(
                "https://linkedin.com/jobs",
                shortsBlocked = false,
                enabledCategoryIds = setOf("social"),
                customDomains = emptySet(),
                excludedByCategory = mapOf("social" to setOf("linkedin.com")),
            ),
        )
        assertTrue(
            isWebsiteBlocked(
                "https://threads.net/@user",
                shortsBlocked = false,
                enabledCategoryIds = setOf("social"),
                customDomains = emptySet(),
                excludedByCategory = mapOf("social" to setOf("linkedin.com")),
            ),
        )
        assertTrue(
            isWebsiteBlocked(
                "https://4chan.org/g/",
                shortsBlocked = false,
                enabledCategoryIds = setOf("social"),
                customDomains = emptySet(),
                addedByCategory = mapOf("social" to setOf("4chan.org")),
            ),
        )
        assertFalse(
            isWebsiteBlocked(
                "https://www.youtube.com/shorts/abc123",
                shortsBlocked = true,
                enabledCategoryIds = setOf("shorts"),
                customDomains = emptySet(),
                excludedByCategory = mapOf("shorts" to setOf("youtube_shorts")),
            ),
        )
        assertTrue(
            isWebsiteBlocked(
                "https://www.instagram.com/reels/abc123",
                shortsBlocked = true,
                enabledCategoryIds = setOf("shorts"),
                customDomains = emptySet(),
                excludedByCategory = mapOf("shorts" to setOf("youtube_shorts")),
            ),
        )
    }

    @Test fun sectionLettersBucketingSupportsWatchListViewScrubber() {
        assertEquals('A', sectionLetter("Apple Music"))
        assertEquals('Z', sectionLetter("  zoom "))
        assertEquals('#', sectionLetter("1Password"))
        assertEquals('#', sectionLetter(""))
    }

    @Test fun preCreatedAppAndWebsiteCategoriesCoverDatingGamesAndCustomOverrides() {
        val dating = APP_CATEGORIES.first { it.id == "dating" }
        assertTrue(matchesAppCategory("co.hinge.app", -1, dating))
        assertTrue(matchesAppCategory("com.tinder", -1, dating))
        assertTrue(matchesAppCategory("com.bumble.app", -1, dating))
        assertFalse(matchesAppCategory("co.hinge.app", -1, dating, excluded = setOf("co.hinge.app")))
        assertTrue(matchesAppCategory("com.custom.dating", -1, dating, added = setOf("com.custom.dating")))
        assertFalse(matchesAppCategory("org.honeycomb.launcher", -1, dating, added = setOf("org.honeycomb.launcher")))

        // Games category automatically classifies any Android CATEGORY_GAME (0) app
        val games = APP_CATEGORIES.first { it.id == "games" }
        assertTrue(matchesAppCategory("com.any.indie.game", androidCat = 0, games))
        assertFalse(matchesAppCategory("com.any.indie.game", androidCat = 0, games, excluded = setOf("com.any.indie.game")))

        // Dating & Shopping website categories
        assertTrue(isWebsiteBlocked("https://tinder.com/app/recs", false, setOf("dating"), emptySet()))
        assertTrue(isWebsiteBlocked("https://hinge.co/", false, setOf("dating"), emptySet()))
        assertTrue(isWebsiteBlocked("https://www.temu.com/deals", false, setOf("shopping"), emptySet()))
        assertFalse(isWebsiteBlocked("https://hinge.co/", false, setOf("shopping"), emptySet()))
    }
}
