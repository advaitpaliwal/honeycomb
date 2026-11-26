package org.honeycomb.launcher

// Feed screen rules. The detector (view-id hints, Back escalating to Home, tab redirects) and CheckTimer are copied
// from Feedcut, https://github.com/Yxonyx/feedcut at 046ff157e1e4d7df4ed45cc51b5d4cfd7b46d9c3,
// app/src/main/kotlin/io/github/yxonyx/feedcut/Rules.kt and CheckTimer.kt, Apache License 2.0
// (licenses/Apache-2.0.txt, NOTICE). Changes: daily-minute limits, rule merging and health checks are left out;
// a tab can be named by its view id as well as its label, the allowed tab is picked in the order the rule lists it;
// and the defaults below are Honeycomb's (messages stay, feeds go), not Feedcut's rules.json.

/** Decisions without the view tree. The service passes only visible view ids, never text. */
data class SeenNode(val viewId: String?, val visible: Boolean)

enum class GateAction { BACK, HOME }

/** One individually toggleable distracting surface inside an app (e.g. Reels, Explore, Shorts, Home feed). */
data class FeatureRule(
    val id: String,
    val label: String,
    val description: String,
    val hints: List<String> = emptyList(),
    val blockTabs: List<String> = emptyList(),
    val defaultBlocked: Boolean = true,
)

data class AppRule(
    val packageName: String,
    val label: String,
    /** What stays open, in a few words for the Feeds screen ("Messages only"). */
    val note: String,
    /** "back" or "home". */
    val action: String,
    /** View ids of feed screens; "*" blocks the whole app. */
    val hints: List<String>,
    /** Tabs that stay open, by label or view id, in the order a redirect prefers them. */
    val allowTabs: List<String> = emptyList(),
    /** Tabs that are cut, by label, view id or [FIRST_TAB]. Empty: every tab outside allowTabs is cut. */
    val blockTabs: List<String> = emptyList(),
    /** View id of the bottom bar whose clickable children are the tabs, for apps whose tabs have no id or class of their own. */
    val tabBar: String? = null,
    /** Additional tabs allowed as redirect targets when not individually blocked (e.g. Home/Profile when only Reels is blocked). */
    val fallbackAllowTabs: List<String> = emptyList(),
    /** Individual per-feature toggles shown in Block Reels & Feeds settings. */
    val features: List<FeatureRule> = emptyList(),
    /** Variant / Lite / modded package names sharing the same view hierarchy and settings. */
    val packageAliases: List<String> = emptyList(),
)

/** A tab from the bottom bar: the first part of its label ("Marketplace"), its view id, and whether it is selected. */
data class SeenTab(val label: String, val selected: Boolean, val viewId: String? = null)

/** Honeycomb never acts on these: itself, Settings, the system UI and the package installer. */
val PROTECTED_PACKAGES = setOf(
    "org.honeycomb.launcher",
    "com.android.settings",
    "com.android.systemui",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
)

/** Major Android browsers supported for zero-network website & short-form video URL blocking. */
val BROWSER_PACKAGES = setOf(
    "com.android.chrome",
    "com.chrome.beta",
    "com.chrome.dev",
    "com.brave.browser",
    "org.mozilla.firefox",
    "org.mozilla.fenix",
    "org.mozilla.focus",
    "com.sec.android.app.sbrowser",
    "com.microsoft.emmx",
    "com.opera.browser",
    "com.opera.gx",
    "com.duckduckgo.mobile.android",
    "com.vivaldi.browser",
    "app.vanadium.browser",
    "org.cromite.cromite",
    "com.kiwibrowser.browser",
    "org.bromite.bromite",
    "mark.via.gp",
    "com.lemurbrowser.exts",
    "com.naver.whale",
    "com.mi.globalbrowser",
)

/** One individually toggleable short-form video source inside the `Shorts & Reels` website category. */
data class ShortVideoSource(val id: String, val label: String, val urlHint: String)

val SHORT_VIDEO_SOURCES = listOf(
    ShortVideoSource("youtube_shorts", "YouTube Shorts", "youtube.com/shorts"),
    ShortVideoSource("instagram_reels", "Instagram Reels", "instagram.com/reels"),
    ShortVideoSource("tiktok", "TikTok", "tiktok.com"),
    ShortVideoSource("facebook_reels", "Facebook Reels", "facebook.com/reel"),
    ShortVideoSource("snapchat_spotlight", "Snapchat Spotlight", "snapchat.com/spotlight"),
)

/** One toggleable category of websites in the Block -> Websites screen. */
data class WebsiteCategory(
    val id: String,
    val label: String,
    val description: String,
    val domains: Set<String> = emptySet(),
    val defaultBlocked: Boolean = false,
)

val WEBSITE_CATEGORIES = listOf(
    WebsiteCategory(
        id = "shorts",
        label = "Short-Form Video Links",
        description = "YouTube Shorts, Instagram Reels, TikTok, Facebook Reels & Spotlight",
        defaultBlocked = true,
    ),
    WebsiteCategory(
        id = "social",
        label = "Social Media Web",
        description = "instagram.com, x.com, reddit.com, facebook.com, threads.net, tiktok.com",
        domains = setOf(
            "instagram.com", "x.com", "twitter.com", "reddit.com", "facebook.com",
            "threads.net", "tiktok.com", "linkedin.com", "pinterest.com", "tumblr.com", "bsky.app",
        ),
        defaultBlocked = false,
    ),
    WebsiteCategory(
        id = "adult",
        label = "Adult Content",
        description = "Blocks adult websites and .xxx / .adult / .porn domains across all browsers",
        domains = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "onlyfans.com", "chaturbate.com",
            "redtube.com", "youporn.com", "xhamster.com", "spankbang.com", "stripchat.com",
            "rule34.xxx", "nhentai.net", "e621.net", "eporner.com", "motherless.com",
        ),
        defaultBlocked = false,
    ),
    WebsiteCategory(
        id = "news",
        label = "News & Doomscrolling",
        description = "Hacker News, CNN, NYTimes, BBC, Fox News, Daily Mail, Guardian, BuzzFeed",
        domains = setOf(
            "news.ycombinator.com", "cnn.com", "foxnews.com", "nytimes.com", "bbc.com", "bbc.co.uk",
            "dailymail.co.uk", "theguardian.com", "washingtonpost.com", "wsj.com", "buzzfeed.com",
            "drudgereport.com", "tmz.com",
        ),
        defaultBlocked = false,
    ),
    WebsiteCategory(
        id = "dating",
        label = "Dating",
        description = "Tinder, Bumble, Hinge, Grindr, Feeld, OkCupid, Match, Badoo, POF",
        domains = setOf(
            "tinder.com", "bumble.com", "hinge.co", "grindr.com", "feeld.co",
            "okcupid.com", "match.com", "badoo.com", "pof.com", "coffeemeetsbagel.com",
            "boo.world", "happn.com",
        ),
        defaultBlocked = false,
    ),
    WebsiteCategory(
        id = "shopping",
        label = "Shopping",
        description = "Amazon, Temu, Shein, eBay, AliExpress, Etsy, Depop, StockX",
        domains = setOf(
            "amazon.com", "temu.com", "shein.com", "ebay.com", "aliexpress.com",
            "etsy.com", "depop.com", "stockx.com", "poshmark.com",
        ),
        defaultBlocked = false,
    ),
    WebsiteCategory(
        id = "streaming",
        label = "Streaming & Entertainment",
        description = "Twitch, Kick, Netflix, 9GAG, Imgur",
        domains = setOf("twitch.tv", "kick.com", "netflix.com", "9gag.com", "imgur.com"),
        defaultBlocked = false,
    ),
)

/**
 * One pre-created app category in Block -> Blocked apps (e.g. Dating, Social Media, Games, Shopping).
 * Matches both curated [packages] (so newly downloaded apps in the category are blocked automatically)
 * and Android's OS-level [androidCategory] (`ApplicationInfo.CATEGORY_*`) when set.
 */
data class AppCategory(
    val id: String,
    val label: String,
    val description: String,
    /** Curated `packageName -> human label` covered by this category even before install. */
    val packages: Map<String, String>,
    /** Android `ApplicationInfo.CATEGORY_*` value (0=GAME, 2=VIDEO, 4=SOCIAL, 5=NEWS), or -1 if curated-only. */
    val androidCategory: Int = -1,
)

val APP_CATEGORIES = listOf(
    AppCategory(
        id = "dating",
        label = "Dating",
        description = "Tinder, Bumble, Hinge, Grindr, Feeld, OkCupid, Badoo, Match & more",
        packages = linkedMapOf(
            "com.tinder" to "Tinder",
            "com.bumble.app" to "Bumble",
            "co.hinge.app" to "Hinge",
            "com.grindrapp.android" to "Grindr",
            "co.feeld" to "Feeld",
            "com.okcupid.okcupid" to "OkCupid",
            "com.badoo.mobile" to "Badoo",
            "com.coffeemeetsbagel" to "Coffee Meets Bagel",
            "com.match.android.matchmobile" to "Match",
            "com.pof.android" to "Plenty of Fish",
            "enterprises.dating.boo" to "Boo",
            "com.dilmil.app" to "Dil Mil",
            "com.ftw_and_co.happn" to "Happn",
            "com.chispa.app" to "Chispa",
            "com.blk.app" to "BLK",
            "com.weareher.her" to "HER",
        ),
    ),
    AppCategory(
        id = "social",
        label = "Social Media",
        description = "Instagram, TikTok, X, Snapchat, Reddit, Facebook, Threads, Bluesky",
        packages = linkedMapOf(
            "com.instagram.android" to "Instagram",
            "com.instagram.lite" to "Instagram Lite",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok",
            "com.twitter.android" to "X",
            "com.snapchat.android" to "Snapchat",
            "com.reddit.frontpage" to "Reddit",
            "com.facebook.katana" to "Facebook",
            "com.facebook.lite" to "Facebook Lite",
            "com.instagram.barcelona" to "Threads",
            "xyz.blueskyweb.app" to "Bluesky",
            "com.linkedin.android" to "LinkedIn",
            "com.pinterest" to "Pinterest",
            "com.tumblr" to "Tumblr",
            "com.bereal.ft" to "BeReal",
            "com.bd.nproject" to "Lemon8",
        ),
        androidCategory = 4, // ApplicationInfo.CATEGORY_SOCIAL
    ),
    AppCategory(
        id = "games",
        label = "Games",
        description = "All installed Android games plus Roblox, Chess, Clash Royale & more",
        packages = linkedMapOf(
            "com.roblox.client" to "Roblox",
            "com.mojang.minecraftpe" to "Minecraft",
            "com.supercell.clashroyale" to "Clash Royale",
            "com.supercell.brawlstars" to "Brawl Stars",
            "com.supercell.clashofclans" to "Clash of Clans",
            "com.miHoYo.GenshinImpact" to "Genshin Impact",
            "com.nianticlabs.pokemongo" to "Pokémon GO",
            "com.chess" to "Chess.com",
            "com.nytimes.crossword" to "NYT Games",
            "com.kiloo.subwaysurf" to "Subway Surfers",
            "com.king.candycrushsaga" to "Candy Crush",
            "com.innersloth.spacemafia" to "Among Us",
        ),
        androidCategory = 0, // ApplicationInfo.CATEGORY_GAME
    ),
    AppCategory(
        id = "video",
        label = "Video & Streaming",
        description = "YouTube, Netflix, Twitch, Disney+, Prime Video, Hulu, Max, Kick",
        packages = linkedMapOf(
            "com.google.android.youtube" to "YouTube",
            "com.netflix.mediaclient" to "Netflix",
            "tv.twitch.android.app" to "Twitch",
            "com.amazon.avod.thirdpartyclient" to "Prime Video",
            "com.disney.disneyplus" to "Disney+",
            "com.hulu.plus" to "Hulu",
            "com.wbd.stream" to "Max",
            "com.peacocktv.peacockandroid" to "Peacock",
            "com.kick.mobile" to "Kick",
            "com.crunchyroll.crunchyroid" to "Crunchyroll",
            "com.tubitv" to "Tubi",
        ),
        androidCategory = 2, // ApplicationInfo.CATEGORY_VIDEO
    ),
    AppCategory(
        id = "shopping",
        label = "Shopping",
        description = "Amazon, Temu, Shein, eBay, AliExpress, Etsy, Depop, DoorDash",
        packages = linkedMapOf(
            "com.amazon.mShop.android.shopping" to "Amazon Shopping",
            "com.einnovation.temu" to "Temu",
            "com.zzkko" to "SHEIN",
            "com.ebay.mobile" to "eBay",
            "com.alibaba.aliexpresshd" to "AliExpress",
            "com.etsy.android" to "Etsy",
            "com.target.ui" to "Target",
            "com.walmart.android" to "Walmart",
            "com.depop" to "Depop",
            "com.stockx.stockx" to "StockX",
            "com.poshmark.app" to "Poshmark",
            "com.dd.doordash" to "DoorDash",
            "com.ubercab.eats" to "Uber Eats",
        ),
    ),
    AppCategory(
        id = "news",
        label = "News & Sports",
        description = "Substack, NYTimes, ESPN, Flipboard, BBC, CNN, Bleacher Report",
        packages = linkedMapOf(
            "com.substack.app" to "Substack",
            "com.nytimes.android" to "NYTimes",
            "com.espn.score_center" to "ESPN",
            "com.bleacherreport.android.teamstream" to "Bleacher Report",
            "flipboard.app" to "Flipboard",
            "bbc.mobile.news.ww" to "BBC News",
            "com.cnn.mobile.android.phone" to "CNN",
            "com.devhd.feedly" to "Feedly",
        ),
        androidCategory = 5, // ApplicationInfo.CATEGORY_NEWS
    ),
)

/**
 * Pure matcher for whether [pkg] (with optional Android `ApplicationInfo.category` [androidCat]) is covered by
 * [category], respecting per-category user [excluded] and [added] overrides and never matching [PROTECTED_PACKAGES].
 */
fun matchesAppCategory(
    pkg: String,
    androidCat: Int,
    category: AppCategory,
    excluded: Set<String> = emptySet(),
    added: Set<String> = emptySet(),
): Boolean {
    if (pkg in PROTECTED_PACKAGES) return false
    if (pkg in excluded) return false
    if (pkg in added) return true
    if (pkg in category.packages) return true
    if (category.id == "video" && isModdedYoutubePackage(pkg)) return true
    if (category.androidCategory >= 0 && androidCat == category.androidCategory && pkg !in MESSAGING_PACKAGES) return true
    return false
}

/** Omnibox / toolbar URL text view IDs across Chromium, Gecko/Firefox, Samsung Internet, DuckDuckGo, and Brave. */
val BROWSER_URL_IDS = listOf(
    "url_bar",
    "location_bar_edit_text",
    "mozac_browser_toolbar_url_view",
    "mozac_browser_toolbar_edit_url_view",
    "omnibox_text_field",
    "url_field",
    "omniboxTextInput",
)

/**
 * The feed apps and what each keeps. Messaging apps (WhatsApp, Signal, Telegram, Messenger, Messages) are not here,
 * so they are never touched. View ids: Instagram's Reels and YouTube's Shorts are Feedcut's; YouTube's tab bar was
 * read from the app's own view tree (uiautomator dump, YouTube 21.x).
 */
val FEED_RULES = listOf(
    AppRule(
        "com.instagram.android", "Instagram", "Messages only", "back",
        hints = listOf(
            "clips_viewer_view_pager", "clips_viewer", "clips_swipe_refresh",
            "clips_viewer_pager", "clips_media_component", "clips_video_container",
            "clips_ufi_component", "clips_author_info_component", "clips_viewer_root",
            "clips_tab_container", "clips_viewer_video_player",
            "explore_recycler_view", "discovery_recycler_view", "explore_grid",
            "explore_context_line", "explore_action_bar", "explore_fragment_container",
        ),
        allowTabs = listOf("direct_tab", "direct_inbox_tab", "messages_tab", "action_bar_inbox_button", "Messages", "Direct"),
        blockTabs = listOf("feed_tab", "search_tab", "clips_tab"),
        fallbackAllowTabs = listOf("feed_tab", "profile_tab", "Home", "Profile"),
        packageAliases = listOf("com.instagram.lite"),
        features = listOf(
            FeatureRule(
                "reels", "Block Reels", "Blocks the Reels tab and Reels player",
                hints = listOf(
                    "clips_viewer_view_pager", "clips_viewer", "clips_swipe_refresh",
                    "clips_viewer_pager", "clips_media_component", "clips_video_container",
                    "clips_ufi_component", "clips_author_info_component", "clips_viewer_root",
                    "clips_tab_container", "clips_viewer_video_player",
                ),
                blockTabs = listOf("clips_tab", "Reels"),
                defaultBlocked = true,
            ),
            FeatureRule(
                "explore", "Block Explore", "Blocks the Explore tab and grid",
                hints = listOf(
                    "explore_recycler_view", "discovery_recycler_view", "explore_grid",
                    "explore_context_line", "explore_action_bar", "explore_fragment_container",
                ),
                blockTabs = listOf("search_tab", "Search and explore", "Explore", "Search"),
                defaultBlocked = true,
            ),
            FeatureRule(
                "feed", "Block Home feed", "Redirects Home straight to Messages",
                blockTabs = listOf("feed_tab", "Home"),
                defaultBlocked = true,
            ),
        ),
    ),
    AppRule(
        "com.google.android.youtube", "YouTube", "Search and watch", "back",
        hints = listOf("reel_watch_player", "reel_player_page_container", "reel_recycler", "shorts_video_pager"),
        allowTabs = listOf("You", "Library"),
        blockTabs = listOf("Home", "Shorts"),
        // YouTube 21.x: the tabs are id-less Buttons labelled Home, Shorts, Subscriptions, You inside pivot_bar
        tabBar = "pivot_bar",
        fallbackAllowTabs = listOf("Subscriptions", "Home"),
        packageAliases = listOf("app.revanced.android.youtube", "app.rvx.android.youtube"),
        features = listOf(
            FeatureRule(
                "shorts", "Block Shorts", "Hides & blocks the Shorts tab and player",
                hints = listOf("reel_watch_player", "reel_player_page_container", "reel_recycler", "shorts_video_pager"),
                blockTabs = listOf("Shorts"),
                defaultBlocked = true,
            ),
            FeatureRule(
                "home", "Block Home feed", "Hides Home; keeps Search & Subscriptions",
                blockTabs = listOf("Home"),
                defaultBlocked = false,
            ),
        ),
    ),
    AppRule(
        "com.zhiliaoapp.musically", "TikTok", "Blocked", "home", hints = listOf("*"),
        packageAliases = listOf("com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"),
        features = listOf(FeatureRule("app", "Block TikTok", "Returns Home immediately when opened", hints = listOf("*"), defaultBlocked = true)),
    ),
    AppRule(
        "com.facebook.katana", "Facebook", "Marketplace only", "back", hints = emptyList(),
        allowTabs = listOf("Marketplace"),
        blockTabs = listOf(FIRST_TAB, "Reels", "Video", "Videos", "Watch"),
        fallbackAllowTabs = listOf("Notifications", "Menu", "Profile"),
        packageAliases = listOf("com.facebook.lite"),
        features = listOf(
            FeatureRule("reels", "Block Reels & Video", "Closes Reels and Watch tabs", blockTabs = listOf("Reels", "Video", "Videos", "Watch"), defaultBlocked = true),
            FeatureRule("feed", "Block Home feed", "Redirects Home to Marketplace", blockTabs = listOf(FIRST_TAB), defaultBlocked = false),
        ),
    ),
    AppRule(
        "com.twitter.android", "X", "Messages and notifications", "back", hints = emptyList(),
        allowTabs = listOf("Messages", "Notifications"),
        blockTabs = listOf(FIRST_TAB, "Home", "Search and Explore"),
        packageAliases = listOf("com.twitter.android.lite"),
        features = listOf(
            FeatureRule("home", "Block Home / For You", "Redirects Home to Messages", blockTabs = listOf(FIRST_TAB, "Home"), defaultBlocked = true),
            FeatureRule("explore", "Block Explore", "Redirects away from trending search", blockTabs = listOf("Search and Explore"), defaultBlocked = true),
        ),
    ),
    AppRule(
        "com.snapchat.android", "Snapchat", "Chat & Camera", "back",
        hints = listOf("spotlight_view_pager"),
        allowTabs = listOf("Chat", "Camera"),
        blockTabs = listOf("Spotlight", "Stories", "Discover"),
        features = listOf(
            FeatureRule("spotlight", "Block Spotlight", "Closes Spotlight short videos", hints = listOf("spotlight_view_pager"), blockTabs = listOf("Spotlight"), defaultBlocked = true),
            FeatureRule("discover", "Block Discover & Stories", "Keeps Chat & Camera only", blockTabs = listOf("Stories", "Discover"), defaultBlocked = false),
        ),
    ),
    AppRule(
        "com.reddit.frontpage", "Reddit", "Search and inbox", "back",
        hints = listOf("video_player_view", "media_pager"),
        allowTabs = listOf("Inbox", "Chat", "Communities"),
        blockTabs = listOf("Home", "Popular", "Watch"),
        fallbackAllowTabs = listOf("Communities", "Home"),
        features = listOf(
            FeatureRule(
                "watch", "Block Watch & Popular", "Closes vertical video player and Popular feed",
                hints = listOf("video_player_view", "media_pager"),
                blockTabs = listOf("Popular", "Watch"),
                defaultBlocked = true,
            ),
            FeatureRule(
                "home", "Block Home feed", "Redirects Home to Inbox & Communities",
                blockTabs = listOf("Home"),
                defaultBlocked = false,
            ),
        ),
    ),
    AppRule(
        "com.instagram.barcelona", "Threads", "Blocked", "home", hints = listOf("*"),
        features = listOf(FeatureRule("app", "Block Threads", "Returns Home immediately when opened", hints = listOf("*"), defaultBlocked = true)),
    ),
    AppRule(
        "com.linkedin.android", "LinkedIn", "Messages and jobs", "back",
        hints = listOf("video_viewer", "media_viewer_pager"),
        allowTabs = listOf("Messaging", "Jobs", "Notifications", "My Network"),
        blockTabs = listOf("Home", "Video"),
        fallbackAllowTabs = listOf("Home"),
        features = listOf(
            FeatureRule(
                "video", "Block Video feed", "Hides & closes short-form videos",
                hints = listOf("video_viewer", "media_viewer_pager"),
                blockTabs = listOf("Video"),
                defaultBlocked = true,
            ),
            FeatureRule(
                "feed", "Block Home feed", "Redirects Home to Messaging & Jobs",
                blockTabs = listOf("Home"),
                defaultBlocked = false,
            ),
        ),
    ),
)

/** Builds the live [AppRule] from only the user's currently enabled feature IDs, or null if all toggles are off. */
fun effectiveRule(rule: AppRule, enabledFeatureIds: Set<String>): AppRule? {
    if (rule.features.isEmpty()) return rule
    val active = rule.features.filter { it.id in enabledFeatureIds }
    if (active.isEmpty()) return null
    val hints = active.flatMap { it.hints }.distinct()
    val tabs = active.flatMap { it.blockTabs }.distinct()
    val blockedLower = tabs.map { it.lowercase() }.toSet()
    val allowed = if (tabs.isEmpty()) emptyList() else (rule.allowTabs + rule.fallbackAllowTabs).filter { it.lowercase() !in blockedLower }
    return rule.copy(
        hints = hints,
        blockTabs = tabs,
        allowTabs = allowed,
    )
}

/** True when a bottom-bar tab matches any entry in [targets] (by view-id suffix or contentDescription/label). */
fun tabMatchesAny(tab: SeenTab, targets: List<String>): Boolean {
    if (targets.isEmpty()) return false
    return targets.any { name ->
        name != FIRST_TAB && (
            name.equals(tab.label, ignoreCase = true) ||
                (tab.viewId != null && tab.viewId.substringAfter(":id/") == name)
            )
    }
}

/** Messaging apps Honeycomb never limits, listed on the Feeds screen so the owner can see they stay. */
val MESSAGING_PACKAGES = listOf(
    "com.whatsapp", "org.thoughtcrime.securesms", "org.telegram.messenger", "com.facebook.orca",
    "com.google.android.apps.messaging", "com.android.mms",
)

/** Matches ReVanced / RVX / custom-package YouTube builds while excluding YouTube Music (`youtube.music`). */
fun isModdedYoutubePackage(packageName: String): Boolean {
    val lower = packageName.lowercase()
    if ("music" in lower || "kids" in lower || "tv" in lower || "studio" in lower) return false
    return (lower.startsWith("app.revanced.") || lower.startsWith("app.rvx.")) && lower.endsWith(".youtube")
}

fun ruleFor(packageName: String): AppRule? {
    FEED_RULES.firstOrNull { it.packageName == packageName }?.let { return it }
    FEED_RULES.firstOrNull { packageName in it.packageAliases }?.let { return it.copy(packageName = packageName) }
    if (isModdedYoutubePackage(packageName)) {
        return FEED_RULES.firstOrNull { it.packageName == "com.google.android.youtube" }?.copy(packageName = packageName)
    }
    return null
}

/** Parses a browser URL bar string into `(normalizedHost, path)` or null when typing a search query / plain text. */
fun extractBrowserHostAndPath(rawUrl: String?): Pair<String, String>? {
    if (rawUrl.isNullOrBlank()) return null
    val trimmed = rawUrl.trim().lowercase()
    if (' ' in trimmed) return null
    val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
    val slashIdx = withoutScheme.indexOf('/')
    val queryIdx = withoutScheme.indexOf('?')
    val splitIdx = when {
        slashIdx >= 0 && queryIdx >= 0 -> minOf(slashIdx, queryIdx)
        slashIdx >= 0 -> slashIdx
        queryIdx >= 0 -> queryIdx
        else -> -1
    }
    val hostPart = (if (splitIdx >= 0) withoutScheme.substring(0, splitIdx) else withoutScheme)
        .substringBefore(':')
        .removePrefix("www.")
        .removePrefix("m.")
        .trimEnd('.')
    if ('.' !in hostPart || hostPart.startsWith('.') || hostPart.length < 3) return null
    val path = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
    return hostPart to path
}

/** Normalizes user-entered website input (e.g. `https://www.Reddit.com/r/all`) into a clean domain (`reddit.com`). */
fun normalizeDomain(raw: String?): String? = extractBrowserHostAndPath(raw)?.first

/** Exact domain or subdomain match (`old.reddit.com` matches `reddit.com`; `notreddit.com` does not). */
fun hostMatchesDomain(host: String, domain: String): Boolean =
    host == domain || host.endsWith(".$domain")

/**
 * Pure local URL matcher for mobile short-form video feeds inside web browsers.
 * Never matches search queries, omnibox typing with spaces, normal YouTube watch pages (`youtube.com/watch`),
 * or Instagram Direct messages (`instagram.com/direct`).
 */
fun isShortVideoUrl(rawUrl: String?, excludedSourceIds: Set<String> = emptySet()): Boolean {
    val (hostPart, path) = extractBrowserHostAndPath(rawUrl) ?: return false
    return when (hostPart) {
        "youtube.com" -> "youtube_shorts" !in excludedSourceIds && (path.startsWith("/shorts/") || path == "/shorts")
        "instagram.com" -> "instagram_reels" !in excludedSourceIds && (path.startsWith("/reel/") || path.startsWith("/reels/") || path == "/reels")
        "tiktok.com", "vm.tiktok.com", "vt.tiktok.com" -> "tiktok" !in excludedSourceIds
        "facebook.com", "fb.watch" -> "facebook_reels" !in excludedSourceIds && (hostPart == "fb.watch" || path.startsWith("/reel/") || path.startsWith("/reels/"))
        "snapchat.com" -> "snapchat_spotlight" !in excludedSourceIds && path.startsWith("/spotlight")
        else -> false
    }
}

/** Evaluates whether a browser URL matches any active short-form, category, or custom website block rule. */
fun isWebsiteBlocked(
    rawUrl: String?,
    shortsBlocked: Boolean,
    enabledCategoryIds: Set<String>,
    customDomains: Set<String>,
    excludedByCategory: Map<String, Set<String>> = emptyMap(),
    addedByCategory: Map<String, Set<String>> = emptyMap(),
): Boolean {
    val (host, _) = extractBrowserHostAndPath(rawUrl) ?: return false
    val shortsOn = shortsBlocked || "shorts" in enabledCategoryIds
    if (shortsOn && isShortVideoUrl(rawUrl, excludedByCategory["shorts"].orEmpty())) return true
    if (customDomains.any { hostMatchesDomain(host, it) }) return true
    if ("adult" in enabledCategoryIds && (host.endsWith(".xxx") || host.endsWith(".adult") || host.endsWith(".porn") || host.endsWith(".sex"))) {
        return true
    }
    for (cat in WEBSITE_CATEGORIES) {
        val active = if (cat.id == "shorts") shortsOn else (cat.id in enabledCategoryIds)
        if (!active) continue
        val excluded = excludedByCategory[cat.id].orEmpty()
        val effectiveDomains = (cat.domains - excluded) + addedByCategory[cat.id].orEmpty()
        if (effectiveDomains.any { hostMatchesDomain(host, it) }) return true
    }
    return false
}

fun blocksWholeApp(rule: AppRule): Boolean = rule.hints.any { it == "*" }

fun activeHints(rule: AppRule): List<String> = rule.hints.map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Home throws the user out of the app, and a user tapping straight back in looks the same
 * as a Back that did nothing. So only several Backs in a row with no effect end in Home.
 */
fun escalate(action: GateAction, backsInARow: Int): GateAction =
    if (backsInARow >= BACKS_BEFORE_HOME) GateAction.HOME else action

fun actionOf(rule: AppRule): GateAction = if (rule.action == "home") GateAction.HOME else GateAction.BACK

fun idMatches(hints: List<String>, viewId: String?): Boolean =
    viewId != null && hints.any { hint -> hint != "*" && viewId.contains(hint, ignoreCase = true) }

/** Full view ids for the fast lookup: the target app resolves them in its own process in one call. */
fun exactIds(packageName: String, hints: List<String>): List<String> =
    hints.filter { it != "*" }.map { if (':' in it) it else "$packageName:id/$it" }

fun chooseAction(rule: AppRule, windowJustChanged: Boolean, nodes: List<SeenNode>): GateAction? {
    val hints = activeHints(rule)
    if (hints.isEmpty()) return null
    if (hints.any { it == "*" }) return if (windowJustChanged) actionOf(rule) else null
    val hit = nodes.any { node -> node.visible && idMatches(hints, node.viewId) }
    return if (hit) actionOf(rule) else null
}

fun tabLabel(contentDescription: CharSequence?): String {
    val raw = contentDescription?.toString()?.substringBefore(',')?.trim().orEmpty()
    val lower = raw.lowercase()
    return when {
        "unread message" in lower || lower == "direct messages" || lower == "messenger" || lower == "inbox" || lower == "chats" -> "Messages"
        lower == "clips" -> "Reels"
        else -> raw
    }
}

private fun SeenTab.named(name: String): Boolean =
    name.equals(label, ignoreCase = true) || (viewId != null && viewId.substringAfter(":id/") == name)

/**
 * Which tab to tap. A tab bar is recognised by containing an allowed tab.
 * Without one (menu, group, ad) the screen is left alone.
 */
fun tabRedirect(allow: List<String>, tabs: List<SeenTab>, block: List<String> = emptyList()): Int? {
    if (allow.isEmpty()) return null
    val allowed = allow.firstNotNullOfOrNull { name -> tabs.indexOfFirst { it.named(name) }.takeIf { it >= 0 } } ?: return null
    val selected = tabs.indexOfFirst { it.selected }
    if (selected < 0) return null
    if (allow.any { tabs[selected].named(it) }) return null
    if (block.isEmpty()) return allowed
    val cut = block.any { if (it == FIRST_TAB) selected == 0 else tabs[selected].named(it) }
    return if (cut) allowed else null
}

const val BACKS_BEFORE_HOME = 3

/** In blockTabs: the first tab of the bar, which needs no label and works in every language. */
const val FIRST_TAB = "#1"

/**
 * When to run the next check (Feedcut's CheckTimer, unchanged). No Android calls, so it can be unit tested.
 *
 * A playing video sends many contentChanged events per second. They are merged into
 * one check. A window change is checked at once. After an action nothing is decided
 * until the debounce ends, but then the screen is checked again, otherwise a quick
 * way back into the blocked screen would be missed.
 */
class CheckTimer(
    private val settleMs: Long,
    private val debounceMs: Long,
    private val followUpMs: Long = 0,
    private val followUps: Int = 0,
) {
    var pending = false
        private set
    private var lastActionAt = -debounceMs
    private var followUpsLeft = 0

    fun onEvent(windowChanged: Boolean): Long? {
        if (windowChanged) followUpsLeft = followUps
        if (!windowChanged && pending) return null
        pending = true
        return if (windowChanged) 0L else settleMs
    }

    fun onFire(now: Long): Long? {
        val wait = debounceMs - (now - lastActionAt)
        if (wait > 0) return wait
        pending = false
        return null
    }

    fun foundNothing(): Long? {
        if (pending || followUpsLeft <= 0) return null
        followUpsLeft--
        pending = true
        return followUpMs
    }

    fun canAct(now: Long): Boolean = now - lastActionAt >= debounceMs

    fun acted(now: Long) { lastActionAt = now }

    fun cancel() { pending = false }
}
