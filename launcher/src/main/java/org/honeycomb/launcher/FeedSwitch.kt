package org.honeycomb.launcher

import android.content.Context

/**
 * One app's block. Switching it on is instant; switching it off starts a 24-hour wait that can be cancelled,
 * because narrow blocks with friction are kept far longer than ones that can be dismissed on the spot.
 * [offAt] is when a requested switch-off takes effect, or null when none is waiting.
 */
data class FeedSwitch(val on: Boolean = true, val offAt: Long? = null) {
    /** Whether the block applies at [now]: it stays on until the wait is over. */
    fun blocking(now: Long): Boolean = on && (offAt == null || now < offAt)

    /** Asks to switch off. Starts the wait once; asking again does not restart it. */
    fun requestOff(now: Long): FeedSwitch = if (!blocking(now) || offAt != null) settle(now) else copy(offAt = now + OFF_DELAY_MS)

    fun cancelOff(now: Long): FeedSwitch = settle(now).let { if (it.on) it.copy(offAt = null) else it }

    fun switchOn(): FeedSwitch = FeedSwitch(on = true, offAt = null)

    /** A wait that has run out becomes a plain off. */
    fun settle(now: Long): FeedSwitch = if (on && offAt != null && now >= offAt) FeedSwitch(on = false) else this

    /** Milliseconds left before the switch-off takes effect, or null when none is waiting. */
    fun waitLeft(now: Long): Long? = offAt?.let { (it - now).takeIf { left -> left > 0 } }

    companion object {
        const val OFF_DELAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** The switches, one per feed app, in SharedPreferences. Every app starts blocked. */
object FeedSwitches {
    private const val PREFS = "feeds"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): FeedSwitch {
        val p = prefs(ctx)
        val off = p.getLong("$pkg.offAt", -1L)
        return FeedSwitch(on = p.getBoolean("$pkg.on", true), offAt = off.takeIf { it >= 0 }).settle(now)
    }

    fun put(ctx: Context, pkg: String, s: FeedSwitch) {
        prefs(ctx).edit().putBoolean("$pkg.on", s.on).putLong("$pkg.offAt", s.offAt ?: -1L).apply()
    }

    fun blocking(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): Boolean = get(ctx, pkg, now).blocking(now)

    /** Canonical primary package name in [FEED_RULES] so variants (Lite, ReVanced, regional) share toggles cleanly. */
    private fun canonicalPkg(pkg: String): String =
        FEED_RULES.firstOrNull {
            it.packageName == pkg || pkg in it.packageAliases || (it.packageName == "com.google.android.youtube" && isModdedYoutubePackage(pkg))
        }?.packageName ?: pkg

    /** Whether an individual feature (e.g. "reels", "shorts", "explore") is blocked for [pkg]. */
    fun featureBlocked(ctx: Context, pkg: String, feature: FeatureRule): Boolean =
        prefs(ctx).getBoolean("${canonicalPkg(pkg)}.feat.${feature.id}", feature.defaultBlocked)

    /** Toggles an individual feature instantly across the canonical package and any aliases. */
    fun setFeature(ctx: Context, pkg: String, featureId: String, blocked: Boolean) {
        val primary = canonicalPkg(pkg)
        val rule = FEED_RULES.firstOrNull { it.packageName == primary }
        val targets = if (rule != null) listOf(rule.packageName) + rule.packageAliases else listOf(pkg)
        val ed = prefs(ctx).edit()
        targets.forEach { ed.putBoolean("$it.feat.$featureId", blocked) }
        ed.apply()
    }

    /** Whether short-form video URLs (Shorts, Reels, TikTok) are blocked inside web browsers (on by default). */
    fun browserShortsBlocked(ctx: Context): Boolean = prefs(ctx).getBoolean("browser.shorts", true)
    fun setBrowserShortsBlocked(ctx: Context, blocked: Boolean) = prefs(ctx).edit()
        .putBoolean("browser.shorts", blocked)
        .putBoolean("web.cat.shorts", blocked)
        .apply()

    /** Website category toggles (Short-Form Video, Social Web, Adult, News, Streaming). */
    fun websiteCategoryBlocked(ctx: Context, category: WebsiteCategory): Boolean =
        if (category.id == "shorts") browserShortsBlocked(ctx)
        else prefs(ctx).getBoolean("web.cat.${category.id}", category.defaultBlocked)

    fun setWebsiteCategoryBlocked(ctx: Context, categoryId: String, blocked: Boolean) {
        if (categoryId == "shorts") {
            setBrowserShortsBlocked(ctx, blocked)
        } else {
            prefs(ctx).edit().putBoolean("web.cat.$categoryId", blocked).apply()
        }
    }

    fun enabledWebsiteCategoryIds(ctx: Context): Set<String> =
        WEBSITE_CATEGORIES.filter { websiteCategoryBlocked(ctx, it) }.map { it.id }.toSet()

    /** Built-in sites (or short-video source IDs) individually excluded from a category. */
    fun categoryExcludedSites(ctx: Context, categoryId: String): Set<String> =
        prefs(ctx).getStringSet("web.cat.$categoryId.excluded", emptySet())?.toSet().orEmpty()

    fun setCategorySiteExcluded(ctx: Context, categoryId: String, siteKey: String, excluded: Boolean) {
        val next = HashSet(categoryExcludedSites(ctx, categoryId))
        if (excluded) next.add(siteKey) else next.remove(siteKey)
        prefs(ctx).edit().putStringSet("web.cat.$categoryId.excluded", next).apply()
    }

    /** User-added custom sites belonging to a specific website category. */
    fun categoryAddedSites(ctx: Context, categoryId: String): Set<String> =
        prefs(ctx).getStringSet("web.cat.$categoryId.added", emptySet())?.toSet().orEmpty()

    fun addCategorySite(ctx: Context, categoryId: String, raw: String): String? {
        val domain = normalizeDomain(raw) ?: return null
        val cat = WEBSITE_CATEGORIES.firstOrNull { it.id == categoryId }
        // If it's already a built-in site in this category, un-exclude it cleanly instead of duplicating it.
        if (cat != null && domain in cat.domains) {
            setCategorySiteExcluded(ctx, categoryId, domain, excluded = false)
            return domain
        }
        val next = HashSet(categoryAddedSites(ctx, categoryId)).apply { add(domain) }
        prefs(ctx).edit().putStringSet("web.cat.$categoryId.added", next).apply()
        return domain
    }

    fun removeCategorySite(ctx: Context, categoryId: String, domain: String) {
        val next = HashSet(categoryAddedSites(ctx, categoryId)).apply { remove(domain) }
        prefs(ctx).edit().putStringSet("web.cat.$categoryId.added", next).apply()
    }

    fun allCategoryExcluded(ctx: Context): Map<String, Set<String>> =
        WEBSITE_CATEGORIES.associate { it.id to categoryExcludedSites(ctx, it.id) }

    fun allCategoryAdded(ctx: Context): Map<String, Set<String>> =
        WEBSITE_CATEGORIES.associate { it.id to categoryAddedSites(ctx, it.id) }

    /** Counts `(enabledSites, totalSites)` configured inside a category (built-in + custom added). */
    fun categorySiteCounts(ctx: Context, category: WebsiteCategory): Pair<Int, Int> {
        val excluded = categoryExcludedSites(ctx, category.id)
        val added = categoryAddedSites(ctx, category.id)
        val builtInTotal = if (category.id == "shorts") SHORT_VIDEO_SOURCES.size else category.domains.size
        val builtInEnabled = if (category.id == "shorts") {
            SHORT_VIDEO_SOURCES.count { it.id !in excluded }
        } else {
            category.domains.count { it !in excluded }
        }
        return (builtInEnabled + added.size) to (builtInTotal + added.size)
    }

    /** Custom blocked website domains (normalized, e.g. `reddit.com`). */
    fun customBlockedDomains(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("web.custom", emptySet())?.toSet().orEmpty()

    fun addCustomDomain(ctx: Context, raw: String): String? {
        val domain = normalizeDomain(raw) ?: return null
        val next = HashSet(customBlockedDomains(ctx)).apply { add(domain) }
        prefs(ctx).edit().putStringSet("web.custom", next).apply()
        return domain
    }

    fun removeCustomDomain(ctx: Context, domain: String) {
        val next = HashSet(customBlockedDomains(ctx)).apply { remove(domain) }
        prefs(ctx).edit().putStringSet("web.custom", next).apply()
    }

    fun hasActiveBrowserRules(ctx: Context): Boolean =
        browserShortsBlocked(ctx) || enabledWebsiteCategoryIds(ctx).isNotEmpty() || customBlockedDomains(ctx).isNotEmpty()

    /** User-chosen custom apps blocked completely ("Block Any App"). */
    fun customBlockedApps(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("custom.blocked", emptySet())?.toSet().orEmpty()

    fun isCustomAppBlocked(ctx: Context, pkg: String): Boolean = pkg in customBlockedApps(ctx)

    fun setCustomAppBlocked(ctx: Context, pkg: String, blocked: Boolean) {
        if (pkg in PROTECTED_PACKAGES || pkg in MESSAGING_PACKAGES) return
        val next = HashSet(customBlockedApps(ctx))
        if (blocked) next.add(pkg) else next.remove(pkg)
        prefs(ctx).edit().putStringSet("custom.blocked", next).apply()
    }

    /** Optional 5-second breathing pause before opening social apps (off by default so blocking stays seamless). */
    fun pauseEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("global.pause", false)
    fun setPauseEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean("global.pause", enabled).apply()

    /**
     * Strict Mode (Opal Deep Focus / Jomo Strict Mode): turning it on is instant and locks active blocks so they cannot
     * be switched off on impulse; turning Strict Mode off starts a 24-hour unlock cooldown via [FeedSwitch].
     */
    fun strictSwitch(ctx: Context, now: Long = System.currentTimeMillis()): FeedSwitch {
        val p = prefs(ctx)
        if (!p.contains("global.strict.on")) return FeedSwitch(on = false)
        val off = p.getLong("global.strict.offAt", -1L)
        return FeedSwitch(on = p.getBoolean("global.strict.on", false), offAt = off.takeIf { it >= 0 }).settle(now)
    }

    fun strictActive(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = strictSwitch(ctx, now).blocking(now)

    fun toggleStrict(ctx: Context, now: Long = System.currentTimeMillis()) {
        val cur = strictSwitch(ctx, now)
        val next = when {
            !cur.blocking(now) -> FeedSwitch(on = true, offAt = null)
            cur.offAt == null -> cur.requestOff(now)
            else -> cur.cancelOff(now)
        }
        prefs(ctx).edit()
            .putBoolean("global.strict.on", next.on)
            .putLong("global.strict.offAt", next.offAt ?: -1L)
            .apply()
    }

    /** The live rule for [pkg] built from custom app blocks or per-feature toggles, or null if nothing is blocked. */
    fun activeRule(ctx: Context, pkg: String): AppRule? {
        if (pkg in PROTECTED_PACKAGES || pkg in MESSAGING_PACKAGES) return null
        if (isCustomAppBlocked(ctx, pkg)) {
            return AppRule(pkg, pkg, "Blocked", "home", listOf("*"))
        }
        val base = ruleFor(pkg) ?: return null
        if (base.features.isEmpty()) return base.takeIf { blocking(ctx, base.packageName) }
        val on = base.features.filter { featureBlocked(ctx, base.packageName, it) }.map { it.id }.toSet()
        return effectiveRule(base, on)
    }
}
