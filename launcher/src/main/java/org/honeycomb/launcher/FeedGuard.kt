package org.honeycomb.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * Keeps blocked feeds and short-form video tabs closed using Android's AccessibilityService:
 *  1. Redirects directly in one hop to an allowed tab (e.g. Instagram Messages/Direct or Profile) whenever a blocked
 *     tab (Reels, Explore, Home feed) becomes active — with zero ugly overlay boxes over app chrome.
 *  2. Backs out immediately if a blocked full-screen viewer opens (e.g. a Reel tapped from a DM or link),
 *     verifying non-zero bounds to avoid zero-size hidden-node false positives.
 * Reads only view IDs and tab labels, never messages or content, and sends nothing over the network.
 */
class FeedGuard : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val timer = CheckTimer(SETTLE_MS, DEBOUNCE_MS, FOLLOW_UP_MS, FOLLOW_UPS)
    private var pendingPackage: String? = null
    private var lastActPackage: String? = null
    private var lastActAt = 0L
    private var backsInARow = 0
    private var foreground: String? = null
    private val keyboards by lazy {
        getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }.toSet()
    }

    private var pendingBrowserPkg: String? = null
    private var lastBrowserBackAt = 0L
    private var browserBacksInARow = 0

    private val browserCheck: Runnable = Runnable {
        val pkg = pendingBrowserPkg ?: return@Runnable
        pendingBrowserPkg = null
        runCatching { checkBrowserWebsites(pkg) }
    }

    private val check: Runnable = Runnable {
        timer.onFire(SystemClock.elapsedRealtime())?.let { wait ->
            handler.postDelayed(check, wait)
            return@Runnable
        }
        val target = pendingPackage ?: return@Runnable
        pendingPackage = null
        try {
            val rule = FeedSwitches.activeRule(this, target) ?: return@Runnable
            val root = activeRootFor(rule.packageName)
            if (root == null) {
                if (blocksWholeApp(rule) && foreground == rule.packageName) {
                    act(rule)
                } else {
                    timer.foundNothing()?.let { delay ->
                        pendingPackage = target
                        handler.postDelayed(check, delay)
                    }
                }
                return@Runnable
            }
            // Prefer 1-hop tab redirect over Back when a blocked tab (e.g. Reels/Explore/Home) is selected
            // so we jump straight to Messages/Profile instead of bouncing through Home.
            when {
                blocksWholeApp(rule) -> act(rule)
                redirectTabs(rule, root) -> Unit
                seesHint(rule, root) -> act(rule)
                seesBlockedRedditFeed(rule, root) -> act(rule)
                else -> timer.foundNothing()?.let { delay ->
                    pendingPackage = target
                    handler.postDelayed(check, delay)
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "check failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (windowChanged) arrived(pkg, event.className?.toString(), event)
        if (pkg in PROTECTED_PACKAGES) return
        if (pkg in BROWSER_PACKAGES && FeedSwitches.hasActiveBrowserRules(this) &&
            !FeedSwitches.isCustomAppBlocked(this, pkg) && !FeedSwitches.isAppBlockedByCategory(this, pkg)
        ) {
            if (windowChanged ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                pendingBrowserPkg = pkg
                handler.removeCallbacks(browserCheck)
                handler.postDelayed(browserCheck, if (windowChanged) 60L else BROWSER_SETTLE_MS)
            }
            return
        }
        val rule = FeedSwitches.activeRule(this, pkg) ?: return
        // Never skip non-windowChanged events for whole-app blocks: a missed WINDOW_STATE_CHANGED
        // (e.g. cold-launch race or Recents switch) must never leave a blocked app scrollable.
        schedule(pkg, windowChanged)
    }

    private fun arrived(pkg: String, cls: String?, event: AccessibilityEvent? = null) {
        if (pkg == packageName && cls != LauncherActivity::class.java.name) return
        if (pkg == "com.android.systemui" || pkg in keyboards || pkg == foreground) return
        if (ruleFor(pkg) == null && !FeedSwitches.isCustomAppBlocked(this, pkg) && !FeedSwitches.isAppBlockedByCategory(this, pkg) &&
            packageManager.getLaunchIntentForPackage(pkg) == null && !isHome(pkg)
        ) return
        foreground = pkg
        if (FeedPass.pkg != pkg && SystemClock.uptimeMillis() - FeedPass.at > PASS_GRACE_MS) FeedPass.pkg = null
        val rule = FeedSwitches.activeRule(this, pkg) ?: return
        if (!FeedSwitches.pauseEnabled(this) || blocksWholeApp(rule) || FeedPass.pkg == pkg) return
        if (activeRootFor(pkg, event) == null) { foreground = null; return }
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(rule.label)
        startActivity(FeedPauseActivity.intent(this, pkg, appLabel))
    }

    /**
     * Resolves the root AccessibilityNodeInfo for [pkg] reliably even when Android's `rootInActiveWindow` transiently
     * returns null during cold-launch animations or multi-window transitions (AOSP issue #223809542).
     */
    private fun activeRootFor(pkg: String, event: AccessibilityEvent? = null): AccessibilityNodeInfo? {
        if (event != null && event.packageName?.toString() == pkg) {
            var cur = runCatching { event.source }.getOrNull()
            var depth = 0
            while (cur != null && depth++ < 40) {
                val parent = cur.parent ?: break
                cur = parent
            }
            if (cur != null && cur.packageName?.toString() == pkg) return cur
        }
        rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.let { return it }
        return runCatching {
            windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg
            }?.root
        }.getOrNull()
    }

    /** Closes blocked websites, categories, and short-form video URLs opened inside any mobile web browser. */
    private fun checkBrowserWebsites(pkg: String) {
        if (!FeedSwitches.hasActiveBrowserRules(this)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBrowserBackAt < DEBOUNCE_MS) return
        val root = activeRootFor(pkg) ?: return
        val shorts = FeedSwitches.browserShortsBlocked(this)
        val categories = FeedSwitches.enabledWebsiteCategoryIds(this)
        val domains = FeedSwitches.customBlockedDomains(this)
        val excluded = FeedSwitches.allCategoryExcluded(this)
        val added = FeedSwitches.allCategoryAdded(this)
        var hit = false
        for (id in exactIds(pkg, BROWSER_URL_IDS)) {
            val found = root.findAccessibilityNodeInfosByViewId(id)
            // Chrome hides url_bar while scrolling down (isVisibleToUser=false) while keeping the active tab's URL
            // in url_bar.text, so match unfocused URL-bar nodes regardless of toolbar collapse.
            if (found.any { !it.isFocused && isWebsiteBlocked(it.text?.toString(), shorts, categories, domains, excluded, added) }) {
                hit = true
                break
            }
        }
        if (!hit) {
            val screenH = resources.displayMetrics.heightPixels
            val topLimit = (screenH * 0.22f).toInt()
            val bottomStart = (screenH * 0.78f).toInt()
            hit = walkBrowserUrlBar(root, topLimit, bottomStart, shorts, categories, domains, excluded, added, intArrayOf(180))
        }
        if (!hit) return
        if (now - lastBrowserBackAt >= ESCALATE_MS) browserBacksInARow = 0
        // If Back could not leave a fresh browser tab with no history after 2 attempts, escalate to Home.
        val goHome = browserBacksInARow >= 2
        val performed = if (goHome) performGlobalAction(GLOBAL_ACTION_HOME) else performGlobalAction(GLOBAL_ACTION_BACK)
        if (performed) {
            lastBrowserBackAt = now
            browserBacksInARow = if (goHome) 0 else browserBacksInARow + 1
            if (!goHome) {
                pendingBrowserPkg = pkg
                handler.removeCallbacks(browserCheck)
                handler.postDelayed(browserCheck, DEBOUNCE_MS + 100L)
            }
        }
    }

    private fun walkBrowserUrlBar(
        node: AccessibilityNodeInfo,
        topLimit: Int,
        bottomStart: Int,
        shorts: Boolean,
        categories: Set<String>,
        domains: Set<String>,
        excluded: Map<String, Set<String>>,
        added: Map<String, Set<String>>,
        budget: IntArray,
    ): Boolean {
        if (budget[0] <= 0 || !node.isVisibleToUser) return false
        budget[0]--
        if (!node.isFocused && !node.text.isNullOrBlank()) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val inBarBand = (r.top in 0..topLimit) || (r.bottom >= bottomStart && r.height() in 1..(bottomStart / 3))
            if (inBarBand && isWebsiteBlocked(node.text?.toString(), shorts, categories, domains, excluded, added)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (walkBrowserUrlBar(child, topLimit, bottomStart, shorts, categories, domains, excluded, added, budget)) return true
        }
        return false
    }

    private fun isHome(pkg: String): Boolean = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0,
    ).any { it.activityInfo.packageName == pkg }

    private fun schedule(packageName: String, windowChanged: Boolean = false) {
        pendingPackage = packageName
        timer.onEvent(windowChanged)?.let { delay ->
            handler.removeCallbacks(check)
            handler.postDelayed(check, delay)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(check)
        handler.removeCallbacks(browserCheck)
        timer.cancel()
        super.onDestroy()
    }

    // ---- Blocking & tab redirect -------------------------------------------------------------------------------------

    private fun act(rule: AppRule, tab: AccessibilityNodeInfo? = null) {
        val now = SystemClock.elapsedRealtime()
        if (!timer.canAct(now)) return
        if (rule.packageName != lastActPackage || now - lastActAt >= ESCALATE_MS) backsInARow = 0
        val goHome = tab == null && escalate(actionOf(rule), backsInARow) == GateAction.HOME
        val performed = when {
            tab != null -> tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            goHome -> performGlobalAction(GLOBAL_ACTION_HOME)
            else -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (performed) {
            timer.acted(now)
            backsInARow = if (goHome) 0 else backsInARow + 1
            lastActPackage = rule.packageName
            lastActAt = now
            schedule(rule.packageName)
        }
    }

    /** True only when a blocked viewer node is both visible AND has real non-zero on-screen bounds. */
    private fun hasVisibleSize(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.width() > 16 && r.height() > 16
    }

    private fun seesHint(rule: AppRule, root: AccessibilityNodeInfo): Boolean {
        val hints = activeHints(rule).filter { it != "*" }
        if (hints.isEmpty()) return false
        for (id in exactIds(rule.packageName, hints)) {
            if (root.findAccessibilityNodeInfosByViewId(id).any { hasVisibleSize(it) }) return true
        }
        return walk(root, hints, intArrayOf(MAX_NODES))
    }

    /** Collects candidate nav/action tab nodes via exact view IDs, tabBar container, ActionBar$Tab, bottom-bar and top-bar buttons. */
    private fun collectTabNodes(root: AccessibilityNodeInfo, rule: AppRule): List<AccessibilityNodeInfo> {
        val tabs = ArrayList<AccessibilityNodeInfo>()
        val seenBounds = HashSet<Rect>()
        fun addUnique(node: AccessibilityNodeInfo) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return
            if (seenBounds.add(r)) tabs.add(node)
        }
        for (id in exactIds(rule.packageName, (rule.allowTabs + rule.blockTabs).filter { '_' in it })) {
            root.findAccessibilityNodeInfosByViewId(id).forEach(::addUnique)
        }
        rule.tabBar?.let { bar ->
            root.findAccessibilityNodeInfosByViewId(exactIds(rule.packageName, listOf(bar)).first())
                .firstOrNull()?.let {
                    val barTabs = ArrayList<AccessibilityNodeInfo>()
                    collectClickable(it, barTabs)
                    barTabs.forEach(::addUnique)
                }
        }
        val actionTabs = ArrayList<AccessibilityNodeInfo>()
        collectTabs(root, actionTabs, intArrayOf(MAX_NODES))
        actionTabs.forEach(::addUnique)
        // Always scan the bottom navigation strip so renamed tab IDs (e.g. Instagram's Messages/Direct tab) are never missed.
        val screenH = resources.displayMetrics.heightPixels
        val navTop = (screenH * 0.80f).toInt()
        val bottomButtons = ArrayList<AccessibilityNodeInfo>()
        collectBottomButtons(root, navTop, bottomButtons, intArrayOf(MAX_NODES))
        bottomButtons.forEach(::addUnique)
        // Also find top-bar allowed buttons (e.g. Instagram's top-right Direct / Messages icon when not in the bottom bar).
        if (rule.allowTabs.isNotEmpty()) {
            val headerBottom = (screenH * 0.20f).toInt()
            collectHeaderAllowedButtons(root, headerBottom, rule.allowTabs, tabs, intArrayOf(MAX_NODES))
        }
        return tabs
    }

    private fun redirectTabs(rule: AppRule, root: AccessibilityNodeInfo): Boolean {
        if (rule.allowTabs.isEmpty()) return false
        val tabs = collectTabNodes(root, rule)
        val seen = tabs.map { SeenTab(tabLabel(nodeLabel(it)), isNodeSelected(it), it.viewIdResourceName) }
        val target = tabRedirect(rule.allowTabs, seen, rule.blockTabs)
            ?: fallbackInstagramHomeTarget(root, rule, seen)
            ?: fallbackRedditFeedTarget(root, rule, seen)
            ?: return false
        act(rule, tabs[target])
        return true
    }

    /**
     * Modern Reddit Android renders its feed selector ("Home", "Popular", "Watch", "Latest", "News") in the top header
     * and often uses Compose bottom tabs without setting View.isSelected=true. When a blocked Reddit feed header is
     * visible and no allowed tab is selected, redirect straight to Inbox/Chat/Communities.
     */
    private fun fallbackRedditFeedTarget(root: AccessibilityNodeInfo, rule: AppRule, seen: List<SeenTab>): Int? {
        if (rule.packageName != "com.reddit.frontpage") return null
        if (seen.any { it.selected && tabMatchesAny(it, rule.allowTabs) }) return null
        val blockedHeaders = rule.blockTabs
            .filter { '_' !in it && it != FIRST_TAB }
            .map { it.lowercase() }
            .toSet()
        if (blockedHeaders.isEmpty()) return null
        val headerBottom = (resources.displayMetrics.heightPixels * 0.18f).toInt()
        if (!hasHeaderFeedLabel(root, headerBottom, blockedHeaders, intArrayOf(180))) return null
        return rule.allowTabs.firstNotNullOfOrNull { name ->
            seen.indexOfFirst { tabMatchesAny(it, listOf(name)) }.takeIf { it >= 0 }
        }
    }

    /**
     * When Reddit collapses its bottom bar during scrolling, [redirectTabs] has no tab to tap; if a blocked feed header
     * ("Home", "Popular", "Watch", "Latest", "News") is still visible at the top, back out instead of letting scrolling continue.
     */
    private fun seesBlockedRedditFeed(rule: AppRule, root: AccessibilityNodeInfo): Boolean {
        if (rule.packageName != "com.reddit.frontpage") return false
        val blockedHeaders = rule.blockTabs
            .filter { '_' !in it && it != FIRST_TAB }
            .map { it.lowercase() }
            .toSet()
        if (blockedHeaders.isEmpty()) return false
        val headerBottom = (resources.displayMetrics.heightPixels * 0.18f).toInt()
        return hasHeaderFeedLabel(root, headerBottom, blockedHeaders, intArrayOf(180))
    }

    private fun hasHeaderFeedLabel(
        node: AccessibilityNodeInfo,
        headerBottom: Int,
        blockedHeaders: Set<String>,
        budget: IntArray,
    ): Boolean {
        if (budget[0] <= 0 || !node.isVisibleToUser) return false
        budget[0]--
        if (!node.isFocused && !node.isEditable) {
            val text = (node.text ?: node.contentDescription)?.toString()?.trim()?.lowercase()
            if (text != null && text in blockedHeaders) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top in 0..headerBottom && r.width() > 20 && r.height() > 16) return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasHeaderFeedLabel(child, headerBottom, blockedHeaders, budget)) return true
        }
        return false
    }

    /**
     * Instagram sometimes opens onto the Home feed without setting `isSelected=true` on `feed_tab` (or sets it on a
     * non-accessibility drawable). When Home feed is blocked and we see Instagram's Home header/tray while NOT already
     * inside Direct messages or Profile, redirect immediately to the preferred allowed tab (DMs first, then Profile).
     */
    private fun fallbackInstagramHomeTarget(root: AccessibilityNodeInfo, rule: AppRule, seen: List<SeenTab>): Int? {
        if (rule.packageName != "com.instagram.android" && rule.packageName != "com.instagram.lite") return null
        if ("feed_tab" !in rule.blockTabs && "Home" !in rule.blockTabs) return null
        // Never interrupt if an allowed tab is already selected or if we are inside DMs inbox/thread.
        if (seen.any { it.selected && tabMatchesAny(it, rule.allowTabs) }) return null
        val dmIndicators = listOf("inbox_recyclerview", "row_thread_composer", "thread_title", "direct_inbox_recycler_view")
        if (exactIds(rule.packageName, dmIndicators).any { root.findAccessibilityNodeInfosByViewId(it).any { n -> n.isVisibleToUser } }) {
            return null
        }
        val homeIndicators = listOf("action_bar_inbox_button", "title_logo", "reel_tray", "stories_tray", "feed_recycler_view", "main_feed_recycler_view")
        val onHome = exactIds(rule.packageName, homeIndicators).any {
            root.findAccessibilityNodeInfosByViewId(it).any { n -> n.isVisibleToUser }
        }
        if (!onHome) return null
        return rule.allowTabs.firstNotNullOfOrNull { name ->
            seen.indexOfFirst { tabMatchesAny(it, listOf(name)) }.takeIf { it >= 0 }
        }
    }

    /** Reads contentDescription or text from [node] or its descendant label (handles Compose NavigationBarItem children). */
    private fun nodeLabel(node: AccessibilityNodeInfo, depth: Int = 3): CharSequence? {
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { return it }
        node.text?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            nodeLabel(c, depth - 1)?.let { return it }
        }
        return null
    }

    /** True when [node] or a descendant icon/tab reports selected, checked, or Compose "Selected" stateDescription. */
    private fun isNodeSelected(node: AccessibilityNodeInfo, depth: Int = 3): Boolean {
        if (node.isSelected || node.isChecked) return true
        if (node.stateDescription?.toString()?.equals("selected", ignoreCase = true) == true) return true
        if (depth <= 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isNodeSelected(child, depth - 1)) return true
        }
        return false
    }

    private fun collectHeaderAllowedButtons(
        node: AccessibilityNodeInfo,
        headerBottom: Int,
        allowTabs: List<String>,
        out: MutableList<AccessibilityNodeInfo>,
        budget: IntArray,
    ) {
        if (budget[0] <= 0 || !node.isVisibleToUser) return
        budget[0]--
        if (node.isClickable) {
            val label = tabLabel(nodeLabel(node))
            val seen = SeenTab(label, isNodeSelected(node), node.viewIdResourceName)
            if (tabMatchesAny(seen, allowTabs)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.top in 0..headerBottom && r.width() > 24 && r.height() > 24) {
                    if (out.none { it.viewIdResourceName != null && it.viewIdResourceName == node.viewIdResourceName }) {
                        out.add(node)
                    }
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectHeaderAllowedButtons(it, headerBottom, allowTabs, out, budget) }
        }
    }

    private fun collectClickable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable) out.add(child) else collectClickable(child, out)
        }
    }

    private fun collectTabs(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, budget: IntArray) {
        if (budget[0] <= 0) return
        budget[0]--
        if (node.className?.toString() == TAB_CLASS) {
            out.add(node)
            return
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTabs(it, out, budget) }
    }

    private fun collectBottomButtons(
        node: AccessibilityNodeInfo,
        navTop: Int,
        out: MutableList<AccessibilityNodeInfo>,
        budget: IntArray,
    ) {
        if (budget[0] <= 0 || !node.isVisibleToUser) return
        budget[0]--
        if (node.isClickable && nodeLabel(node) != null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top >= navTop && r.width() > 32 && r.height() > 32) {
                out.add(node)
                return
            }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectBottomButtons(it, navTop, out, budget) }
    }

    private fun walk(node: AccessibilityNodeInfo, hints: List<String>, budget: IntArray): Boolean {
        if (budget[0] <= 0 || !node.isVisibleToUser) return false
        budget[0]--
        if (idMatches(hints, node.viewIdResourceName) && hasVisibleSize(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (walk(child, hints, budget)) return true
        }
        return false
    }

    companion object {
        private const val TAG = "FeedGuard"
        private const val DEBOUNCE_MS = 350L
        private const val SETTLE_MS = 80L
        private const val BROWSER_SETTLE_MS = 150L
        private const val FOLLOW_UP_MS = 220L
        private const val FOLLOW_UPS = 6
        private const val ESCALATE_MS = 2500L
        private const val MAX_NODES = 1500
        private const val PASS_GRACE_MS = 1500L
        private const val TAB_CLASS = "android.app.ActionBar\$Tab"

        /** Whether Android is running Honeycomb's feed service (switched on in Accessibility settings). */
        fun enabled(ctx: android.content.Context): Boolean {
            val me = ComponentName(ctx, FeedGuard::class.java)
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** The app the owner chose to open from the pause, until they leave it. In memory: a restart asks again. */
object FeedPass {
    @Volatile var pkg: String? = null
    @Volatile var at = 0L

    fun grant(pkg: String) { this.pkg = pkg; at = SystemClock.uptimeMillis() }
}
