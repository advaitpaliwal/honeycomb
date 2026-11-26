package org.honeycomb.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * Keeps blocked feeds and short-form video tabs hidden & closed using Android's AccessibilityService:
 *  1. Covers blocked bottom-bar tab buttons (e.g. Instagram Reels/Explore, YouTube Shorts, Snapchat Spotlight)
 *     with seamless nav-bar-matched TYPE_ACCESSIBILITY_OVERLAY shields so the icons visually disappear and swallow taps.
 *  2. Backs out immediately if a blocked full-screen viewer opens (e.g. a Reel tapped from a DM or feed),
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

    // Active TYPE_ACCESSIBILITY_OVERLAY shields hiding blocked bottom-bar tabs (zero extra permission needed).
    private val tabMasks = ArrayList<View>()
    private var maskedPkg: String? = null
    private var maskedRects: List<Rect> = emptyList()
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
            val rule = FeedSwitches.activeRule(this, target)
            val root = if (rule != null) activeRootFor(rule.packageName) else null
            if (rule == null || root == null) {
                clearMasks()
                return@Runnable
            }
            updateTabMasks(rule, root)
            if (blocksWholeApp(rule) || seesHint(rule, root)) act(rule)
            else if (!redirectTabs(rule, root)) timer.foundNothing()?.let { delay ->
                pendingPackage = target
                handler.postDelayed(check, delay)
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
        if (pkg in PROTECTED_PACKAGES) {
            if (windowChanged && pkg != packageName) clearMasks()
            return
        }
        if (pkg in BROWSER_PACKAGES && FeedSwitches.hasActiveBrowserRules(this) && !FeedSwitches.isCustomAppBlocked(this, pkg)) {
            if (windowChanged) clearMasks()
            if (windowChanged || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                pendingBrowserPkg = pkg
                handler.removeCallbacks(browserCheck)
                handler.postDelayed(browserCheck, if (windowChanged) 60L else BROWSER_SETTLE_MS)
            }
            return
        }
        val rule = FeedSwitches.activeRule(this, pkg)
        if (rule == null) {
            if (pkg in keyboards) clearMasks()
            else if (windowChanged && pkg != "com.android.systemui") clearMasks()
            return
        }
        // Keep masks synced immediately when switching into or scrolling inside a monitored app.
        if (windowChanged ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            runCatching { updateTabMasks(rule, activeRootFor(rule.packageName, event)) }
        }
        if (blocksWholeApp(rule) && chooseAction(rule, windowChanged, emptyList()) == null) return
        schedule(pkg, windowChanged)
    }

    private fun arrived(pkg: String, cls: String?, event: AccessibilityEvent? = null) {
        if (pkg == packageName && cls != LauncherActivity::class.java.name) return
        if (pkg == "com.android.systemui" || pkg in keyboards || pkg == foreground) return
        if (ruleFor(pkg) == null && !FeedSwitches.isCustomAppBlocked(this, pkg) &&
            packageManager.getLaunchIntentForPackage(pkg) == null && !isHome(pkg)
        ) return
        foreground = pkg
        if (maskedPkg != null && maskedPkg != pkg) clearMasks()
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
            if (found.any { !it.isFocused && it.isVisibleToUser && isWebsiteBlocked(it.text?.toString(), shorts, categories, domains, excluded, added) }) {
                hit = true
                break
            }
        }
        if (!hit) {
            val topLimit = (resources.displayMetrics.heightPixels * 0.22f).toInt()
            hit = walkBrowserUrlBar(root, topLimit, shorts, categories, domains, excluded, added, intArrayOf(120))
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
            if (r.top in 0..topLimit && isWebsiteBlocked(node.text?.toString(), shorts, categories, domains, excluded, added)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (walkBrowserUrlBar(child, topLimit, shorts, categories, domains, excluded, added, budget)) return true
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

    override fun onInterrupt() {
        clearMasks()
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        handler.removeCallbacks(browserCheck)
        timer.cancel()
        clearMasks()
        super.onDestroy()
    }

    // ---- Visual tab hiding via TYPE_ACCESSIBILITY_OVERLAY ------------------------------------------------------------

    /**
     * Covers every visible blocked bottom-bar tab button with a solid nav-bar-colored overlay window so the Reels /
     * Explore / Shorts icon visually disappears and taps on that spot are swallowed cleanly.
     */
    private fun updateTabMasks(rule: AppRule, resolvedRoot: AccessibilityNodeInfo? = null) {
        if (rule.blockTabs.isEmpty() || blocksWholeApp(rule)) {
            clearMasks()
            return
        }
        // Never draw over the soft keyboard if it is open over a monitored app.
        if (runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)) {
            clearMasks()
            return
        }
        val root = resolvedRoot ?: activeRootFor(rule.packageName)
        if (root == null || root.packageName?.toString() != rule.packageName) {
            clearMasks()
            return
        }
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val navBarTopMin = (screenH * 0.78f).toInt()
        val nodes = collectTabNodes(root, rule)
        var bottomStripTabs = 0
        val rects = ArrayList<Rect>()
        for ((index, node) in nodes.withIndex()) {
            if (!node.isVisibleToUser) continue
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top >= navBarTopMin && r.width() in 32..(screenW / 2) && r.height() in 32..(screenH / 6)) {
                bottomStripTabs++
            }
            val seen = SeenTab(tabLabel(nodeLabel(node)), isNodeSelected(node), node.viewIdResourceName)
            if (tabMatchesAny(seen, rule.allowTabs)) continue
            val isFirst = index == 0 && FIRST_TAB in rule.blockTabs
            if (!isFirst && !tabMatchesAny(seen, rule.blockTabs)) continue
            // Safety guardrail: only ever cover compact bottom-nav tab tiles, never main feed/chat content.
            if (r.top < navBarTopMin || r.width() !in 32..(screenW / 2) || r.height() !in 32..(screenH / 6)) continue
            rects.add(r)
        }
        // Only render shields when an actual multi-tab bottom navigation bar (>= 3 tabs) is present on screen.
        if (rects.isEmpty() || bottomStripTabs < 3) {
            clearMasks()
            return
        }
        if (maskedPkg == rule.packageName && maskedRects == rects) return
        clearMasks()
        val wm = getSystemService(WindowManager::class.java) ?: return
        val bg = navBarMaskColor(rule.packageName)
        for (r in rects) {
            val shield = View(this).apply {
                setBackgroundColor(bg)
                contentDescription = "Blocked tab"
            }
            val lp = WindowManager.LayoutParams(
                r.width(),
                r.height(),
                r.left,
                r.top,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            runCatching {
                wm.addView(shield, lp)
                tabMasks.add(shield)
            }
        }
        maskedPkg = rule.packageName
        maskedRects = rects
    }

    private fun navBarMaskColor(pkg: String): Int {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (!dark) return Color.WHITE
        return when {
            pkg == "com.google.android.youtube" || isModdedYoutubePackage(pkg) -> Color.rgb(15, 15, 15)
            pkg == "com.facebook.katana" || pkg == "com.facebook.lite" -> Color.rgb(24, 25, 26)
            else -> Color.BLACK
        }
    }

    private fun clearMasks() {
        if (tabMasks.isEmpty()) {
            maskedPkg = null
            maskedRects = emptyList()
            return
        }
        val wm = getSystemService(WindowManager::class.java)
        for (v in tabMasks) {
            runCatching { wm?.removeViewImmediate(v) }
        }
        tabMasks.clear()
        maskedPkg = null
        maskedRects = emptyList()
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
        for (id in exactIds(rule.packageName, (rule.allowTabs + rule.blockTabs).filter { '_' in it })) {
            tabs += root.findAccessibilityNodeInfosByViewId(id)
        }
        rule.tabBar?.let { bar ->
            root.findAccessibilityNodeInfosByViewId(exactIds(rule.packageName, listOf(bar)).first())
                .firstOrNull()?.let { collectClickable(it, tabs) }
        }
        if (tabs.isEmpty()) collectTabs(root, tabs, intArrayOf(MAX_NODES))
        val screenH = resources.displayMetrics.heightPixels
        val navTop = (screenH * 0.80f).toInt()
        if (tabs.size < 2) {
            // Fallback for apps (like newer Instagram/Snapchat builds) whose bottom tabs use plain ViewGroup/FrameLayout with contentDescription.
            collectBottomButtons(root, navTop, tabs, intArrayOf(MAX_NODES))
        }
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
            ?: return false
        act(rule, tabs[target])
        return true
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

    /** Reads contentDescription or text from [node] or its immediate labeled child. */
    private fun nodeLabel(node: AccessibilityNodeInfo): CharSequence? {
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { return it }
        node.text?.takeIf { it.isNotBlank() }?.let { return it }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            c.contentDescription?.takeIf { it.isNotBlank() }?.let { return it }
            c.text?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** True when [node] or a direct/grandchild icon inside it is marked selected (Instagram sets selected on inner icons). */
    private fun isNodeSelected(node: AccessibilityNodeInfo, depth: Int = 2): Boolean {
        if (node.isSelected) return true
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
        if (node.isClickable && (!node.contentDescription.isNullOrBlank() || !node.text.isNullOrBlank())) {
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
        private const val DEBOUNCE_MS = 800L
        private const val SETTLE_MS = 120L
        private const val BROWSER_SETTLE_MS = 180L
        private const val FOLLOW_UP_MS = 300L
        private const val FOLLOW_UPS = 4
        private const val ESCALATE_MS = 2500L
        private const val MAX_NODES = 800
        private const val PASS_GRACE_MS = 1500L
        private const val TAB_CLASS = "android.app.ActionBar\$Tab"

        /** Whether Android is running Honeycomb's feed service (switched on in Accessibility settings). */
        fun enabled(ctx: Context): Boolean {
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
