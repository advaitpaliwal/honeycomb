package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxScope
import org.honeycomb.launcher.design.Space

import android.view.HapticFeedbackConstants
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.alpha
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The pause before a feed app opens, after one sec (Grüning et al., PNAS 2023, doi:10.1073/pnas.2213114120):
 * one breath, then the choice. "Open" lets the app through until the owner leaves it; "Not now" goes home.
 * There is no way to switch the limit off from here.
 */
class FeedPauseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeBars()
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val icon = runCatching {
            packageManager.getApplicationIcon(pkg).toBitmap(192, 192).asImageBitmap()
        }.getOrNull()
        onBackPressedDispatcher.addCallback(this) { if (preview) finish() else notNow() }
        setContent {
            ProvideScale {
                PauseScreen(
                    label = label,
                    icon = icon,
                    onOpen = {
                        if (!preview) {
                            FeedPass.grant(pkg)
                            // Back to the app's own task: finishing alone returns to whatever task was under the pause.
                            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                        }
                        finish()
                    },
                    onNotNow = { if (preview) finish() else notNow() },
                )
            }
        }
    }

    private fun notNow() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_PREVIEW = "preview"
        fun intent(ctx: Context, pkg: String, label: String, preview: Boolean = false) = Intent(ctx, FeedPauseActivity::class.java)
            .putExtra(EXTRA_PKG, pkg).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_PREVIEW, preview)
            .addFlags(if (preview) 0 else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

/** Opal-inspired mindful pause: whisper-soft concentric breathing rings + smooth progress arc + calm prompt. */
@Composable
private fun PauseScreen(label: String, icon: ImageBitmap?, onOpen: () -> Unit, onNotNow: () -> Unit) {
    val reduce = HoneycombTheme.reduceMotion
    val view = LocalView.current
    val progress = remember { Animatable(0f) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduce) {
            delay(Motion.BREATH_MS)
            progress.snapTo(1f)
        } else {
            val half = (Motion.BREATH_MS / 2).toInt()
            progress.animateTo(0.5f, tween(durationMillis = half, easing = LinearEasing))
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            progress.animateTo(1f, tween(durationMillis = half, easing = LinearEasing))
        }
        ready = true
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    val breath = rememberInfiniteTransition(label = "breath")
    val breathPhase by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = (Motion.BREATH_MS / 2).toInt(), easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath-phase",
    )
    val b = if (reduce) 0.5f else breathPhase
    val secsLeft = kotlin.math.ceil(((1f - progress.value) * Motion.BREATH_MS) / 1000.0).toInt().coerceAtLeast(1)
    val phaseTitle = when {
        ready -> "Open $label?"
        progress.value < 0.5f -> "Take a deep breath"
        else -> "And let it out"
    }
    val phaseSubtitle = when {
        ready -> "Still worth your time?"
        progress.value < 0.5f -> "Inhale slowly · ${secsLeft}s"
        else -> "Exhale slowly · ${secsLeft}s"
    }

    ScreenFrame(Modifier.testTag("feed-pause")) {
        Column(
            Modifier.fillMaxSize().padding(bottom = Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MindfulBreathOrb(
                    progress = progress.value,
                    breath = b,
                    accentColors = BreathPalette.gradient,
                    appIcon = icon,
                    label = label,
                    phaseTitle = phaseTitle,
                    phaseSubtitle = phaseSubtitle,
                )
            }

            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ButtonSize.stackGap),
            ) {
                // Always allow bailing out immediately (just like Opal)
                CapsuleButton(
                    text = "Not now",
                    onClick = onNotNow,
                    modifier = Modifier.fillMaxWidth().testTag("feed-Not now"),
                    height = ButtonSize.large,
                    filled = true,
                )
                if (ready) {
                    CapsuleButton(
                        text = "Open $label",
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth().testTag("feed-Open"),
                        height = ButtonSize.large,
                    )
                } else {
                    CapsuleButton(
                        text = "Wait ${secsLeft}s",
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().alpha(0.45f),
                        height = ButtonSize.large,
                    )
                }
            }
        }
    }
}

private data class BlockableApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val androidCategory: Int = -1,
)

/**
 * Honeycomb Block: clean Apple Screen Time / Opal-style distraction blocker organized into three focused views:
 *   1. Feeds — granular in-app feature switches (Reels, Shorts, Explore, Watch, Home feeds) with real app icons.
 *   2. Apps — full installed app list with real circular icons & search to block any app completely.
 *   3. Websites — zero-network browser blocker for custom domains and categories (Short-Form Video, Social, Adult, News, Streaming).
 */
class FeedsActivity : ComponentActivity() {
    private var access by mutableStateOf(true)
    private var tick by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeBars()
        AppCache.start(this)
        setContent {
            ProvideScale {
                val cachedApps by AppCache.apps.collectAsState()
                val iconByPkg = remember(cachedApps) {
                    cachedApps.orEmpty().associate { it.component.packageName to it.icon }
                }
                val candidates = remember(cachedApps, tick) { customAppCandidates(cachedApps.orEmpty()) }
                BlockScreen(
                    tick = tick,
                    accessEnabled = access,
                    rows = remember(tick) { rows() },
                    iconByPkg = iconByPkg,
                    candidates = candidates,
                    onEnableAccess = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onAppInfo = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(android.net.Uri.fromParts("package", packageName, null)),
                        )
                    },
                    onToggleFeature = { pkg, featId, blocked ->
                        FeedSwitches.setFeature(this@FeedsActivity, pkg, featId, blocked)
                        tick++
                    },
                    onToggleCustomApp = { pkg, on ->
                        FeedSwitches.setCustomAppBlocked(this@FeedsActivity, pkg, on)
                        tick++
                    },
                    onToggleAppCategory = { catId, on ->
                        FeedSwitches.setAppCategoryBlocked(this@FeedsActivity, catId, on)
                        tick++
                    },
                    onToggleAppCategoryExcluded = { catId, pkg, excluded ->
                        FeedSwitches.setAppCategoryExcluded(this@FeedsActivity, catId, pkg, excluded)
                        tick++
                    },
                    onAddAppToCategory = { catId, pkg ->
                        FeedSwitches.addAppToCategory(this@FeedsActivity, catId, pkg)
                        tick++
                    },
                    onRemoveAppFromCategory = { catId, pkg ->
                        FeedSwitches.removeAppFromCategory(this@FeedsActivity, catId, pkg)
                        tick++
                    },
                    onToggleWebCategory = { catId, on ->
                        FeedSwitches.setWebsiteCategoryBlocked(this@FeedsActivity, catId, on)
                        tick++
                    },
                    onToggleCategorySite = { catId, siteKey, excluded ->
                        FeedSwitches.setCategorySiteExcluded(this@FeedsActivity, catId, siteKey, excluded)
                        tick++
                    },
                    onAddCategorySite = { catId, raw ->
                        val added = FeedSwitches.addCategorySite(this@FeedsActivity, catId, raw) != null
                        if (added) tick++
                        added
                    },
                    onRemoveCategorySite = { catId, domain ->
                        FeedSwitches.removeCategorySite(this@FeedsActivity, catId, domain)
                        tick++
                    },
                    onAddDomain = { raw ->
                        val added = FeedSwitches.addCustomDomain(this@FeedsActivity, raw) != null
                        if (added) tick++
                        added
                    },
                    onRemoveDomain = { domain ->
                        FeedSwitches.removeCustomDomain(this@FeedsActivity, domain)
                        tick++
                    },
                    onTogglePause = { on ->
                        FeedSwitches.setPauseEnabled(this@FeedsActivity, on)
                        tick++
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        access = FeedGuard.enabled(this)
        tick++
    }

    private fun installed(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Loads a circular bitmap icon if an installed social app wasn't already in [AppCache]. */
    private fun loadFallbackIcon(pkg: String): ImageBitmap? = runCatching {
        val drawable = packageManager.getApplicationIcon(pkg)
        circleIcon(drawable, 128).asImageBitmap()
    }.getOrNull()

    /** One group per social platform, resolving any installed package alias (e.g. Lite / ReVanced) first. */
    private fun rows(): List<Pair<AppRule, Boolean>> = FEED_RULES
        .map { rule ->
            val allPackages = listOf(rule.packageName) + rule.packageAliases
            val foundPkg = allPackages.firstOrNull { installed(it) }
            val resolved = if (foundPkg != null) rule.copy(packageName = foundPkg) else rule
            resolved to (foundPkg != null)
        }
        .sortedByDescending { it.second }

    private fun resolveAndroidCategory(pkg: String): Int = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        @Suppress("DEPRECATION")
        val legacyGame = (info.flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0
        if (legacyGame && info.category == android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED) 0 else info.category
    }.getOrDefault(-1)

    /**
     * Every launchable app on the phone (across AppCache, all LauncherApps profiles, and PackageManager launcher
     * queries), excluding ONLY system-protected packages so no installed app ever goes missing from Block.
     */
    private fun customAppCandidates(cached: List<App>): List<BlockableApp> {
        val map = LinkedHashMap<String, BlockableApp>()
        for (app in cached) {
            val pkg = app.component.packageName
            if (pkg == packageName || pkg in PROTECTED_PACKAGES) continue
            map.putIfAbsent(pkg, BlockableApp(pkg, app.label, app.icon, resolveAndroidCategory(pkg)))
        }
        runCatching {
            val la = getSystemService(LauncherApps::class.java)
            if (la != null) {
                for (profile in la.profiles) {
                    for (act in la.getActivityList(null, profile)) {
                        val pkg = act.applicationInfo.packageName
                        if (pkg == packageName || pkg in PROTECTED_PACKAGES || pkg in map) continue
                        map[pkg] = BlockableApp(pkg, act.label.toString(), loadFallbackIcon(pkg), resolveAndroidCategory(pkg))
                    }
                }
            }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in packageManager.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == packageName || pkg in PROTECTED_PACKAGES || pkg in map) continue
                val label = runCatching { ri.loadLabel(packageManager).toString() }.getOrDefault(pkg)
                map[pkg] = BlockableApp(pkg, label, loadFallbackIcon(pkg), resolveAndroidCategory(pkg))
            }
        }
        return map.values.sortedWith(compareBy({ it.label.trim().lowercase() }, { it.packageName }))
    }

private sealed interface BlockRoute {
    data object Main : BlockRoute
    data class AppDetail(val label: String, val fromMore: Boolean = false) : BlockRoute
    data object MoreFeeds : BlockRoute
    data object Apps : BlockRoute
    data class AppCategoryDetail(val categoryId: String) : BlockRoute
    data object Websites : BlockRoute
    data class WebsiteCategoryDetail(val categoryId: String) : BlockRoute
}

private fun blockRouteDepth(route: BlockRoute): Int = when (route) {
    BlockRoute.Main -> 0
    is BlockRoute.AppDetail -> if (route.fromMore) 2 else 1
    BlockRoute.MoreFeeds, BlockRoute.Apps, BlockRoute.Websites -> 1
    is BlockRoute.AppCategoryDetail, is BlockRoute.WebsiteCategoryDetail -> 2
}

private fun shortFeatureLabel(ruleLabel: String, rawLabel: String): String =
    rawLabel
        .removePrefix("Block ")
        .replace("Home feed", "Home Feed")
        .replace("Home / For You", "Home Feed")
        .replace("Reels & Video", "Reels")
        .replace("Watch & Popular", "Watch")
        .replace("Discover & Stories", "Stories")
        .replace("Video feed", "Videos")
        .let { if (it.equals(ruleLabel, ignoreCase = true)) "Block App" else it }

private fun shortCategoryTitle(category: WebsiteCategory): String = when (category.id) {
    "shorts" -> "Shorts & Reels"
    "social" -> "Social Media"
    "adult" -> "Adult Websites"
    "news" -> "News"
    "dating" -> "Dating"
    "shopping" -> "Shopping"
    "streaming" -> "Streaming"
    else -> category.label
}

    @Composable
    private fun BlockScreen(
        tick: Long,
        accessEnabled: Boolean,
        rows: List<Pair<AppRule, Boolean>>,
        iconByPkg: Map<String, ImageBitmap>,
        candidates: List<BlockableApp>,
        onEnableAccess: () -> Unit,
        @Suppress("UNUSED_PARAMETER") onAppInfo: () -> Unit,
        onToggleFeature: (String, String, Boolean) -> Unit,
        onToggleCustomApp: (String, Boolean) -> Unit,
        onToggleAppCategory: (String, Boolean) -> Unit,
        onToggleAppCategoryExcluded: (String, String, Boolean) -> Unit,
        onAddAppToCategory: (String, String) -> Unit,
        onRemoveAppFromCategory: (String, String) -> Unit,
        onToggleWebCategory: (String, Boolean) -> Unit,
        onToggleCategorySite: (String, String, Boolean) -> Unit,
        onAddCategorySite: (String, String) -> Boolean,
        onRemoveCategorySite: (String, String) -> Unit,
        onAddDomain: (String) -> Boolean,
        onRemoveDomain: (String) -> Unit,
        onTogglePause: (Boolean) -> Unit,
    ) {
        var route by remember { mutableStateOf<BlockRoute>(BlockRoute.Main) }
        androidx.activity.compose.BackHandler(enabled = route != BlockRoute.Main) {
            route = when (val cur = route) {
                is BlockRoute.AppDetail -> if (cur.fromMore) BlockRoute.MoreFeeds else BlockRoute.Main
                is BlockRoute.AppCategoryDetail -> BlockRoute.Apps
                is BlockRoute.WebsiteCategoryDetail -> BlockRoute.Websites
                else -> BlockRoute.Main
            }
        }

        val now = remember(tick) { System.currentTimeMillis() }
        val strict = remember(tick) { FeedSwitches.strictSwitch(this@FeedsActivity, now) }
        val locked = strict.blocking(now)
        val strictUnlockHours = strict.waitLeft(now)?.let { "Unlocks in ${hoursLeft(it)}h" }

        val pauseOn = remember(tick) { FeedSwitches.pauseEnabled(this@FeedsActivity) }
        val customBlocked = remember(tick) { FeedSwitches.customBlockedApps(this@FeedsActivity) }
        val enabledAppCats = remember(tick) { FeedSwitches.enabledAppCategoryIds(this@FeedsActivity) }
        val customDomains = remember(tick) { FeedSwitches.customBlockedDomains(this@FeedsActivity).sorted() }
        val enabledWebCats = remember(tick) { FeedSwitches.enabledWebsiteCategoryIds(this@FeedsActivity) }

        val installedRows = remember(rows) { rows.filter { it.second } }
        val uninstalledRows = remember(rows) { rows.filterNot { it.second } }

        var appQuery by remember { mutableStateOf("") }
        var catAppQuery by remember { mutableStateOf("") }
        var domainInput by remember { mutableStateOf("") }
        var categorySiteInput by remember { mutableStateOf("") }

        val blockedAppItems = remember(candidates, customBlocked) {
            candidates.filter { it.packageName in customBlocked }
        }
        val appsSummary = remember(enabledAppCats, blockedAppItems) {
            when {
                enabledAppCats.isEmpty() && blockedAppItems.isEmpty() -> "None"
                enabledAppCats.size == 1 && blockedAppItems.isEmpty() ->
                    APP_CATEGORIES.firstOrNull { it.id in enabledAppCats }?.label ?: "1 category"
                enabledAppCats.isNotEmpty() && blockedAppItems.isEmpty() -> "${enabledAppCats.size} categories"
                enabledAppCats.isEmpty() && blockedAppItems.size == 1 -> "1 app"
                enabledAppCats.isEmpty() -> "${blockedAppItems.size} apps"
                else -> "${enabledAppCats.size} cat · ${blockedAppItems.size} apps"
            }
        }
        val sortedCandidates = remember(candidates, customBlocked, appQuery) {
            val q = appQuery.trim().lowercase()
            val filtered = if (q.isEmpty()) candidates
            else candidates.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
            if (q.isEmpty()) filtered.sortedByDescending { it.packageName in customBlocked } else filtered
        }

        val websitesSummary = remember(enabledWebCats, customDomains) {
            val total = enabledWebCats.size + customDomains.size
            when {
                total == 0 -> "Off"
                total == 1 && "shorts" in enabledWebCats && customDomains.isEmpty() -> "Shorts & Reels"
                else -> "$total rules active"
            }
        }

        fun feedSummary(rule: AppRule): String {
            val activeNames = rule.features
                .filter { FeedSwitches.featureBlocked(this@FeedsActivity, rule.packageName, it) }
                .map { shortFeatureLabel(rule.label, it.label).replace("Home Feed", "Home") }
            if (activeNames.isEmpty()) return "Off"
            if (activeNames.size == 1 && activeNames[0] == "Block App") return "Blocked"
            return activeNames.joinToString(", ")
        }

        val reduce = HoneycombTheme.reduceMotion
        androidx.compose.animation.AnimatedContent(
            targetState = route,
            modifier = Modifier.fillMaxSize().background(HoneycombColors.background),
            transitionSpec = {
                routeTransitionSpec(
                    forward = blockRouteDepth(targetState) >= blockRouteDepth(initialState),
                    reduceMotion = reduce,
                )
            },
            label = "block-route",
        ) { curRoute ->
        when (curRoute) {
            BlockRoute.Main -> {
                val shownMain = installedRows.ifEmpty { uninstalledRows.take(3) }
                val remainingCount = if (installedRows.isNotEmpty()) uninstalledRows.size else (uninstalledRows.size - 3).coerceAtLeast(0)
                val feedGroupSize = shownMain.size + if (remainingCount > 0) 1 else 0
                LazyColumn(
                    Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("feeds"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "header") {
                        MinimalScreenHeader(
                            title = "Block",
                            actionLabel = if (accessEnabled) null else "Turn On",
                            onAction = if (accessEnabled) null else onEnableAccess,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (!accessEnabled) {
                        item(key = "access-banner") {
                            NavSummaryRow(
                                title = "Accessibility is off",
                                valueText = "Turn on to block",
                                index = 0,
                                count = 1,
                                glyph = Symbols.block,
                                accentActive = true,
                                onClick = onEnableAccess,
                                modifier = Modifier.padding(bottom = 8.dp).testTag("block-access-banner"),
                            )
                        }
                    }

                    // Group 1: In-App Feeds (Android 16 Material 3 segmented list)
                    item(key = "feeds-kicker") { SectionKicker("In-app feeds") }
                    shownMain.forEachIndexed { idx, (rule, isInstalled) ->
                        item(key = "feed-${rule.packageName}") {
                            val icon = if (isInstalled) {
                                iconByPkg[rule.packageName] ?: remember(rule.packageName) { loadFallbackIcon(rule.packageName) }
                            } else null
                            val summary = remember(tick, rule.packageName) { feedSummary(rule) }
                            if (rule.features.size == 1 && blocksWholeApp(rule)) {
                                val feat = rule.features[0]
                                val isBlocked = remember(tick, rule.packageName, feat.id) {
                                    FeedSwitches.featureBlocked(this@FeedsActivity, rule.packageName, feat)
                                }
                                CleanSwitchRow(
                                    title = rule.label,
                                    subtitle = if (isBlocked) "Blocked" else "Allowed",
                                    checked = isBlocked,
                                    onCheckedChange = { next -> if (!locked || next) onToggleFeature(rule.packageName, feat.id, next) },
                                    index = idx,
                                    count = feedGroupSize,
                                    appIcon = icon,
                                    monogram = if (icon == null) rule.label.take(1).uppercase() else null,
                                    modifier = Modifier.testTag("feed-row-${rule.label}"),
                                )
                            } else {
                                NavSummaryRow(
                                    title = rule.label,
                                    valueText = summary,
                                    index = idx,
                                    count = feedGroupSize,
                                    appIcon = icon,
                                    monogram = if (icon == null) rule.label.take(1).uppercase() else null,
                                    accentActive = summary != "Off",
                                    onClick = { route = BlockRoute.AppDetail(rule.label, fromMore = false) },
                                    modifier = Modifier.testTag("feed-row-${rule.label}"),
                                )
                            }
                        }
                    }
                    if (remainingCount > 0) {
                        item(key = "feeds-more-apps") {
                            NavSummaryRow(
                                title = "Other apps",
                                valueText = "$remainingCount more",
                                index = feedGroupSize - 1,
                                count = feedGroupSize,
                                glyph = Symbols.apps,
                                onClick = { route = BlockRoute.MoreFeeds },
                                modifier = Modifier.testTag("feeds-more-apps"),
                            )
                        }
                    }

                    // Group 2: Apps & Websites
                    item(key = "targets-kicker") { SectionKicker("Apps & websites") }
                    item(key = "nav-apps") {
                        NavSummaryRow(
                            title = "Blocked apps",
                            valueText = appsSummary,
                            index = 0,
                            count = 2,
                            previewIcons = blockedAppItems.mapNotNull { it.icon },
                            glyph = Symbols.apps,
                            accentActive = appsSummary != "None",
                            onClick = { appQuery = ""; route = BlockRoute.Apps },
                            modifier = Modifier.testTag("block-nav-apps"),
                        )
                    }
                    item(key = "nav-websites") {
                        NavSummaryRow(
                            title = "Websites",
                            valueText = websitesSummary,
                            index = 1,
                            count = 2,
                            glyph = Symbols.globe,
                            accentActive = websitesSummary != "Off",
                            onClick = { route = BlockRoute.Websites },
                            modifier = Modifier.testTag("block-nav-websites"),
                        )
                    }

                    // Group 3: Options
                    item(key = "protection-kicker") { SectionKicker("Options") }
                    item(key = "opt-pause") {
                        CleanSwitchRow(
                            title = "Pause before opening",
                            subtitle = if (pauseOn) "5-second mindful breath" else "Off",
                            checked = pauseOn,
                            onCheckedChange = { next -> if (!locked || next) onTogglePause(next) },
                            index = 0,
                            count = 3,
                            glyph = Symbols.timer,
                            modifier = Modifier.testTag("feed-toggle-pause"),
                        )
                    }
                    item(key = "opt-pause-preview") {
                        NavSummaryRow(
                            title = "Preview pause screen",
                            valueText = "5s",
                            index = 1,
                            count = 3,
                            glyph = Symbols.sun,
                            onClick = {
                                val sample = installedRows.firstOrNull()?.first?.let { it.packageName to it.label }
                                    ?: (packageName to "Instagram")
                                startActivity(FeedPauseActivity.intent(this@FeedsActivity, sample.first, sample.second, preview = true))
                            },
                            modifier = Modifier.testTag("feed-preview-pause"),
                        )
                    }
                    item(key = "opt-strict") {
                        CleanSwitchRow(
                            title = "Strict mode",
                            subtitle = strictUnlockHours ?: if (locked) "Active blocks locked" else "24-hour unlock cooldown",
                            checked = locked && strictUnlockHours == null,
                            onCheckedChange = {
                                FeedSwitches.toggleStrict(this@FeedsActivity)
                                this@FeedsActivity.tick++
                            },
                            index = 2,
                            count = 3,
                            glyph = Symbols.lock,
                            modifier = Modifier.testTag("block-toggle-strict"),
                        )
                    }
                }
            }

            is BlockRoute.AppDetail -> {
                val targetPair = rows.firstOrNull { it.first.label == curRoute.label }
                if (targetPair == null) {
                    route = BlockRoute.Main
                } else {
                    val (rule, _) = targetPair
                    LazyColumn(
                        Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("block-app-detail"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "header") {
                            MinimalScreenHeader(
                                title = rule.label,
                                onBack = { route = if (curRoute.fromMore) BlockRoute.MoreFeeds else BlockRoute.Main },
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                        rule.features.forEachIndexed { idx, feat ->
                            item(key = "feat-${feat.id}") {
                                val isBlocked = remember(tick, rule.packageName, feat.id) {
                                    FeedSwitches.featureBlocked(this@FeedsActivity, rule.packageName, feat)
                                }
                                CleanSwitchRow(
                                    title = shortFeatureLabel(rule.label, feat.label),
                                    checked = isBlocked,
                                    onCheckedChange = { next ->
                                        if (!locked || next) onToggleFeature(rule.packageName, feat.id, next)
                                    },
                                    index = idx,
                                    count = rule.features.size,
                                    modifier = Modifier.testTag("feed-toggle-${rule.label}-${feat.id}"),
                                )
                            }
                        }
                    }
                }
            }

            BlockRoute.MoreFeeds -> {
                LazyColumn(
                    Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("block-more-feeds"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "header") {
                        MinimalScreenHeader(
                            title = "Other apps",
                            onBack = { route = BlockRoute.Main },
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    uninstalledRows.forEachIndexed { idx, (rule, _) ->
                        item(key = "more-${rule.packageName}") {
                            if (rule.features.size == 1 && blocksWholeApp(rule)) {
                                val feat = rule.features[0]
                                val isBlocked = remember(tick, rule.packageName, feat.id) {
                                    FeedSwitches.featureBlocked(this@FeedsActivity, rule.packageName, feat)
                                }
                                CleanSwitchRow(
                                    title = rule.label,
                                    subtitle = if (isBlocked) "Blocked" else "Allowed",
                                    checked = isBlocked,
                                    onCheckedChange = { next -> if (!locked || next) onToggleFeature(rule.packageName, feat.id, next) },
                                    index = idx,
                                    count = uninstalledRows.size,
                                    monogram = rule.label.take(1).uppercase(),
                                    modifier = Modifier.testTag("feed-row-${rule.label}"),
                                )
                            } else {
                                val summary = remember(tick, rule.packageName) { feedSummary(rule) }
                                NavSummaryRow(
                                    title = rule.label,
                                    valueText = summary,
                                    index = idx,
                                    count = uninstalledRows.size,
                                    monogram = rule.label.take(1).uppercase(),
                                    accentActive = summary != "Off",
                                    onClick = { route = BlockRoute.AppDetail(rule.label, fromMore = true) },
                                    modifier = Modifier.testTag("feed-row-${rule.label}"),
                                )
                            }
                        }
                    }
                }
            }

            BlockRoute.Apps -> {
                val searching = appQuery.trim().isNotEmpty()
                LazyColumn(
                    Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("block-apps-sheet"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "header") {
                        MinimalScreenHeader(
                            title = "Blocked apps",
                            onBack = { route = BlockRoute.Main },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    item(key = "search") {
                        AppSearchBar(
                            query = appQuery,
                            onQueryChange = { appQuery = it },
                            onSubmit = {},
                            placeholder = "Search installed apps",
                            modifier = Modifier.padding(bottom = 10.dp).testTag("feeds-app-search"),
                        )
                    }
                    if (!searching) {
                        item(key = "app-cats-kicker") { SectionKicker("Categories") }
                        APP_CATEGORIES.forEachIndexed { idx, cat ->
                            item(key = "app-cat-${cat.id}") {
                                val catOn = cat.id in enabledAppCats
                                val excluded = remember(tick, cat.id) { FeedSwitches.appCategoryExcluded(this@FeedsActivity, cat.id) }
                                val added = remember(tick, cat.id) { FeedSwitches.appCategoryAdded(this@FeedsActivity, cat.id) }
                                val installedInCat = remember(candidates, cat.id, excluded, added) {
                                    candidates.filter { matchesAppCategory(it.packageName, it.androidCategory, cat, emptySet(), added) }
                                }
                                val subtitle = when {
                                    installedInCat.isNotEmpty() -> "${installedInCat.size} installed · ${cat.packages.size} covered"
                                    else -> "${cat.packages.size} apps covered"
                                }
                                TwoTargetSwitchRow(
                                    title = cat.label,
                                    subtitle = subtitle,
                                    checked = catOn,
                                    onClick = {
                                        catAppQuery = ""
                                        route = BlockRoute.AppCategoryDetail(cat.id)
                                    },
                                    onCheckedChange = { next ->
                                        if (!locked || next) onToggleAppCategory(cat.id, next)
                                    },
                                    index = idx,
                                    count = APP_CATEGORIES.size,
                                    glyph = Symbols.apps,
                                    modifier = Modifier.testTag("app-cat-${cat.id}"),
                                )
                            }
                        }
                        item(key = "all-apps-kicker") { SectionKicker("All installed apps (${sortedCandidates.size})") }
                    }
                    if (sortedCandidates.isEmpty()) {
                        item(key = "empty-apps") {
                            SettingsNote(
                                text = "No apps found",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    } else {
                        sortedCandidates.forEachIndexed { idx, app ->
                            item(key = "app-${app.packageName}") {
                                val isCustom = app.packageName in customBlocked
                                val byCategory = remember(tick, app.packageName, enabledAppCats) {
                                    FeedSwitches.isAppBlockedByCategory(this@FeedsActivity, app.packageName)
                                }
                                CleanSwitchRow(
                                    title = app.label,
                                    subtitle = if (!isCustom && byCategory) "Blocked by category" else null,
                                    checked = isCustom || byCategory,
                                    onCheckedChange = { next ->
                                        if (!locked || next) {
                                            if (!next && byCategory && !isCustom) {
                                                enabledAppCats.forEach { catId ->
                                                    onToggleAppCategoryExcluded(catId, app.packageName, true)
                                                }
                                            } else {
                                                onToggleCustomApp(app.packageName, next)
                                            }
                                        }
                                    },
                                    index = idx,
                                    count = sortedCandidates.size,
                                    appIcon = app.icon,
                                    modifier = Modifier.testTag("feed-custom-${app.packageName}"),
                                )
                            }
                        }
                    }
                }
            }

            is BlockRoute.AppCategoryDetail -> {
                val category = APP_CATEGORIES.firstOrNull { it.id == curRoute.categoryId }
                if (category == null) {
                    route = BlockRoute.Apps
                } else {
                    val catOn = category.id in enabledAppCats
                    val excluded = remember(tick, category.id) { FeedSwitches.appCategoryExcluded(this@FeedsActivity, category.id) }
                    val added = remember(tick, category.id) { FeedSwitches.appCategoryAdded(this@FeedsActivity, category.id) }
                    val installedInCat = remember(candidates, category.id, added) {
                        candidates.filter { matchesAppCategory(it.packageName, it.androidCategory, category, emptySet(), added) }
                    }
                    val installedPkgs = remember(installedInCat) { installedInCat.map { it.packageName }.toSet() }
                    val builtInUninstalled = remember(category.id, installedPkgs) {
                        category.packages.entries.filter { it.key !in installedPkgs }
                    }
                    val addableApps = remember(candidates, installedPkgs, catAppQuery) {
                        val q = catAppQuery.trim().lowercase()
                        if (q.isEmpty()) emptyList()
                        else candidates.filter {
                            it.packageName !in installedPkgs &&
                                (it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q))
                        }.take(12)
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("block-app-category-detail"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "header") {
                            MinimalScreenHeader(
                                title = category.label,
                                onBack = { route = BlockRoute.Apps },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        item(key = "master") {
                            CleanSwitchRow(
                                title = "Block ${category.label}",
                                subtitle = if (catOn) "Active (also blocks newly installed)" else "Off",
                                checked = catOn,
                                onCheckedChange = { next -> if (!locked || next) onToggleAppCategory(category.id, next) },
                                index = 0,
                                count = 1,
                                modifier = Modifier.padding(bottom = 10.dp).testTag("app-cat-master-${category.id}"),
                            )
                        }
                        item(key = "add-search") {
                            AppSearchBar(
                                query = catAppQuery,
                                onQueryChange = { catAppQuery = it },
                                onSubmit = {},
                                placeholder = "Add any installed app to ${category.label}",
                                modifier = Modifier.padding(bottom = 6.dp).testTag("app-cat-add-search"),
                            )
                        }
                        if (addableApps.isNotEmpty()) {
                            item(key = "add-kicker") { SectionKicker("Tap to add to ${category.label}") }
                            addableApps.forEachIndexed { idx, app ->
                                item(key = "add-cand-${app.packageName}") {
                                    NavSummaryRow(
                                        title = app.label,
                                        valueText = "Add",
                                        index = idx,
                                        count = addableApps.size,
                                        appIcon = app.icon,
                                        accentActive = true,
                                        onClick = {
                                            onAddAppToCategory(category.id, app.packageName)
                                            catAppQuery = ""
                                        },
                                        modifier = Modifier.testTag("app-cat-add-${app.packageName}"),
                                    )
                                }
                            }
                        }
                        if (installedInCat.isNotEmpty()) {
                            item(key = "inst-kicker") { SectionKicker("Installed (${installedInCat.size})") }
                            installedInCat.forEachIndexed { idx, app ->
                                val isAdded = app.packageName in added
                                val included = app.packageName !in excluded
                                item(key = "inst-${app.packageName}") {
                                    CleanSwitchRow(
                                        title = app.label,
                                        subtitle = if (isAdded) "Added by you" else null,
                                        checked = included,
                                        onCheckedChange = { next ->
                                            if (!locked || next) {
                                                if (!next && isAdded) onRemoveAppFromCategory(category.id, app.packageName)
                                                else onToggleAppCategoryExcluded(category.id, app.packageName, !next)
                                            }
                                        },
                                        index = idx,
                                        count = installedInCat.size,
                                        appIcon = app.icon,
                                        modifier = Modifier.testTag("app-cat-inst-${app.packageName}"),
                                    )
                                }
                            }
                        }
                        if (builtInUninstalled.isNotEmpty()) {
                            item(key = "covered-kicker") { SectionKicker("Covered if installed (${builtInUninstalled.size})") }
                            builtInUninstalled.forEachIndexed { idx, (pkg, label) ->
                                val included = pkg !in excluded
                                item(key = "cov-$pkg") {
                                    CleanSwitchRow(
                                        title = label,
                                        checked = included,
                                        onCheckedChange = { next ->
                                            if (!locked || next) onToggleAppCategoryExcluded(category.id, pkg, !next)
                                        },
                                        index = idx,
                                        count = builtInUninstalled.size,
                                        monogram = label.take(1).uppercase(),
                                        modifier = Modifier.testTag("app-cat-cov-$pkg"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BlockRoute.Websites -> {
                LazyColumn(
                    Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("block-websites-sheet"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "header") {
                        MinimalScreenHeader(
                            title = "Websites",
                            onBack = { route = BlockRoute.Main },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    item(key = "website-input") {
                        WebsiteInputBar(
                            value = domainInput,
                            onValueChange = { domainInput = it },
                            onAdd = {
                                if (onAddDomain(domainInput)) domainInput = ""
                            },
                            modifier = Modifier.padding(bottom = 8.dp).testTag("block-website-input"),
                        )
                    }
                    if (customDomains.isNotEmpty()) {
                        item(key = "custom-kicker") { SectionKicker("Custom sites") }
                        customDomains.forEachIndexed { idx, domain ->
                            item(key = "custom-domain-$domain") {
                                BlockedDomainRow(
                                    domain = domain,
                                    onRemove = { if (!locked) onRemoveDomain(domain) },
                                    index = idx,
                                    count = customDomains.size,
                                    modifier = Modifier.testTag("blocked-domain-$domain"),
                                )
                            }
                        }
                    }
                    item(key = "categories-kicker") { SectionKicker("Categories") }
                    WEBSITE_CATEGORIES.forEachIndexed { idx, category ->
                        item(key = "web-cat-row-${category.id}") {
                            val isBlocked = remember(tick, category.id) {
                                FeedSwitches.websiteCategoryBlocked(this@FeedsActivity, category)
                            }
                            val (enabledSites, totalSites) = remember(tick, category.id) {
                                FeedSwitches.categorySiteCounts(this@FeedsActivity, category)
                            }
                            val siteSummary = if (enabledSites == totalSites) "$totalSites sites" else "$enabledSites of $totalSites sites"
                            TwoTargetSwitchRow(
                                title = shortCategoryTitle(category),
                                subtitle = siteSummary,
                                checked = isBlocked,
                                onClick = {
                                    categorySiteInput = ""
                                    route = BlockRoute.WebsiteCategoryDetail(category.id)
                                },
                                onCheckedChange = { next ->
                                    if (!locked || next) onToggleWebCategory(category.id, next)
                                },
                                index = idx,
                                count = WEBSITE_CATEGORIES.size,
                                glyph = if (category.id == "shorts") Symbols.block else Symbols.globe,
                                modifier = Modifier.testTag("web-cat-${category.id}"),
                            )
                        }
                    }
                }
            }

            is BlockRoute.WebsiteCategoryDetail -> {
                val category = WEBSITE_CATEGORIES.firstOrNull { it.id == curRoute.categoryId }
                if (category == null) {
                    route = BlockRoute.Websites
                } else {
                    val catTitle = shortCategoryTitle(category)
                    val catEnabled = remember(tick, category.id) {
                        FeedSwitches.websiteCategoryBlocked(this@FeedsActivity, category)
                    }
                    val excludedSites = remember(tick, category.id) {
                        FeedSwitches.categoryExcludedSites(this@FeedsActivity, category.id)
                    }
                    val addedSites = remember(tick, category.id) {
                        FeedSwitches.categoryAddedSites(this@FeedsActivity, category.id).sorted()
                    }
                    val builtInDomains = remember(category.id) { category.domains.sorted() }
                    val (activeCount, totalCount) = remember(tick, category.id) {
                        FeedSwitches.categorySiteCounts(this@FeedsActivity, category)
                    }
                    val totalBuiltInRows = if (category.id == "shorts") SHORT_VIDEO_SOURCES.size else builtInDomains.size
                    val totalSiteRows = addedSites.size + totalBuiltInRows

                    LazyColumn(
                        Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("block-category-detail"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "header") {
                            MinimalScreenHeader(
                                title = catTitle,
                                onBack = { route = BlockRoute.Websites },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        item(key = "master-toggle") {
                            CleanSwitchRow(
                                title = "Block $catTitle",
                                subtitle = if (catEnabled) "$activeCount of $totalCount sites blocked" else "Off",
                                checked = catEnabled,
                                onCheckedChange = { next ->
                                    if (!locked || next) onToggleWebCategory(category.id, next)
                                },
                                index = 0,
                                count = 1,
                                modifier = Modifier.padding(bottom = 10.dp).testTag("cat-master-${category.id}"),
                            )
                        }
                        item(key = "cat-site-input") {
                            WebsiteInputBar(
                                value = categorySiteInput,
                                onValueChange = { categorySiteInput = it },
                                onAdd = {
                                    if (onAddCategorySite(category.id, categorySiteInput)) {
                                        categorySiteInput = ""
                                    }
                                },
                                placeholder = "Add site to $catTitle",
                                modifier = Modifier.padding(bottom = 6.dp).testTag("cat-add-site-input"),
                            )
                        }
                        item(key = "sites-kicker") { SectionKicker("Sites ($activeCount of $totalCount)") }

                        addedSites.forEachIndexed { idx, domain ->
                            item(key = "cat-added-$domain") {
                                BlockedDomainRow(
                                    domain = domain,
                                    subtitle = "Added",
                                    onRemove = { if (!locked) onRemoveCategorySite(category.id, domain) },
                                    index = idx,
                                    count = totalSiteRows,
                                    modifier = Modifier.testTag("cat-added-$domain"),
                                )
                            }
                        }

                        if (category.id == "shorts") {
                            SHORT_VIDEO_SOURCES.forEachIndexed { idx, src ->
                                val rowIdx = addedSites.size + idx
                                val enabled = src.id !in excludedSites
                                item(key = "cat-short-${src.id}") {
                                    CleanSwitchRow(
                                        title = src.label,
                                        subtitle = src.urlHint,
                                        checked = enabled,
                                        onCheckedChange = { next ->
                                            if (!locked || next) onToggleCategorySite(category.id, src.id, !next)
                                        },
                                        index = rowIdx,
                                        count = totalSiteRows,
                                        modifier = Modifier.testTag("cat-site-${src.id}"),
                                    )
                                }
                            }
                        } else {
                            builtInDomains.forEachIndexed { idx, domain ->
                                val rowIdx = addedSites.size + idx
                                val enabled = domain !in excludedSites
                                item(key = "cat-site-$domain") {
                                    CleanSwitchRow(
                                        title = domain,
                                        checked = enabled,
                                        onCheckedChange = { next ->
                                            if (!locked || next) onToggleCategorySite(category.id, domain, !next)
                                        },
                                        index = rowIdx,
                                        count = totalSiteRows,
                                        modifier = Modifier.testTag("cat-site-$domain"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

fun hoursLeft(ms: Long): Long = (ms + 3_599_999L) / 3_600_000L

/** The first-run-style request for the accessibility service: a title, a line, one button. */
@Composable
private fun AccessScreen(onButton: () -> Unit) =
    OnboardingPage("Keep distractions\nblocked",
        "Turn on Honeycomb Block in Accessibility. Honeycomb checks only which screen or URL is open locally on your phone, so calls and messages stay untouched.",
        Modifier.testTag("feeds-access")) {
        CapsuleButton("Turn on", onButton, Modifier.fillMaxWidth(), height = ButtonSize.large, filled = true)
    }

/** Quick Settings tile so Block stays one tap away from Android's system Quick Settings panel. */
class BlockTileService : android.service.quicksettings.TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "Block"
            state = if (FeedGuard.enabled(this@BlockTileService)) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, FeedsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }
}

/** Honeycomb's settings entry in App info (APPLICATION_PREFERENCES): Setup, Block, Keyboard, and Updates. */
class HoneycombSettingsActivity : ComponentActivity() {
    private var pendingInstallPath: String? = null

    override fun onResume() {
        super.onResume()
        AppUpdater.loadCached(this)
        val pending = pendingInstallPath
        if (pending != null && packageManager.canRequestPackageInstalls()) {
            pendingInstallPath = null
            AppUpdater.launchInstaller(this, pending)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeBars()
        AppUpdater.loadCached(this)
        val autoStart = intent.getBooleanExtra(AppUpdater.EXTRA_START_UPDATE, false)
        // SetupActivity reads this action as a re-run: it shows "You're all set" even when nothing is missing
        val setup = Intent(this, SetupActivity::class.java).setAction(Intent.ACTION_APPLICATION_PREFERENCES)
        setContent {
            ProvideScale {
                val updateState by AppUpdater.state.collectAsState()
                val version = remember { AppUpdater.installedVersion(this@HoneycombSettingsActivity) }
                val scope = androidx.compose.runtime.rememberCoroutineScope()

                LaunchedEffect(autoStart) {
                    if (autoStart) {
                        when (val s = AppUpdater.state.value) {
                            is UpdateState.Available -> {
                                if (AppUpdater.downloadAndInstall(this@HoneycombSettingsActivity, s.release)) {
                                    val ready = AppUpdater.state.value as? UpdateState.ReadyToInstall
                                    if (ready != null && !packageManager.canRequestPackageInstalls()) {
                                        pendingInstallPath = ready.apkPath
                                    }
                                }
                            }
                            is UpdateState.ReadyToInstall -> {
                                if (!AppUpdater.launchInstaller(this@HoneycombSettingsActivity, s.apkPath)) {
                                    pendingInstallPath = s.apkPath
                                }
                            }
                            else -> AppUpdater.checkNow(this@HoneycombSettingsActivity)
                        }
                    }
                }

                val (updateTitle, updateSubtitle, updateActive) = when (val s = updateState) {
                    UpdateState.Idle -> Triple("Updates", "v$version · Check for updates", false)
                    UpdateState.Checking -> Triple("Updates", "v$version · Checking…", false)
                    is UpdateState.UpToDate -> Triple("Updates", "v${s.currentVersion} · Up to date", false)
                    is UpdateState.Available -> Triple("Update available", "${s.release.tag} · Tap to download", true)
                    is UpdateState.Downloading -> Triple(
                        "Downloading ${s.release.tag}",
                        if (s.percent > 0) "${s.percent}%" else "Starting…",
                        true,
                    )
                    is UpdateState.Verifying -> Triple("Verifying ${s.release.tag}", "Checking SHA-256…", true)
                    is UpdateState.ReadyToInstall -> Triple("Install ${s.release.tag}", "Downloaded · Tap to install", true)
                    is UpdateState.Error -> Triple("Updates", s.message, false)
                }

                var homeStart by remember { mutableStateOf(AppOrder.savedHomeStart(this@HoneycombSettingsActivity)) }
                var quietMode by remember { mutableStateOf(Quiet.callMode(this@HoneycombSettingsActivity)) }
                val quietRepeat = remember(quietMode) { Quiet.repeatCallers(this@HoneycombSettingsActivity) }
                val view = androidx.compose.ui.platform.LocalView.current

                LazyColumn(
                    Modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding().testTag("honeycomb-settings"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "header") {
                        MinimalScreenHeader(title = "Settings", modifier = Modifier.padding(bottom = 12.dp))
                    }
                    item(key = "home-start") {
                        NavSummaryRow(
                            title = "Home screen",
                            valueText = "${homeStart.label} · ${homeStart.subtitle}",
                            index = 0,
                            count = 5,
                            glyph = Symbols.apps,
                            showChevron = false,
                            onClick = {
                                val modes = HomeStartMode.entries
                                val next = modes[(modes.indexOf(homeStart) + 1) % modes.size]
                                homeStart = next
                                AppOrder.saveHomeStart(this@HoneycombSettingsActivity, next)
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            },
                            modifier = Modifier.testTag("settings-home-start"),
                        )
                    }
                    item(key = "quiet-calls") {
                        NavSummaryRow(
                            title = "Quiet calls",
                            valueText = Quiet.summary(quietMode, quietRepeat),
                            index = 1,
                            count = 5,
                            glyph = Symbols.moon,
                            showChevron = false,
                            onClick = {
                                val modes = Quiet.CallMode.entries
                                val next = modes[(modes.indexOf(quietMode) + 1) % modes.size]
                                if (Quiet.setCallMode(this@HoneycombSettingsActivity, next)) {
                                    quietMode = next
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            },
                            modifier = Modifier.testTag("settings-quiet-calls"),
                        )
                    }
                    item(key = "setup") {
                        NavSummaryRow(
                            title = "Setup",
                            valueText = "Permissions & home app",
                            index = 2,
                            count = 5,
                            glyph = Symbols.apps,
                            onClick = { startActivity(setup) },
                            modifier = Modifier.testTag("settings-setup"),
                        )
                    }
                    item(key = "block") {
                        NavSummaryRow(
                            title = "Block",
                            valueText = "Feeds, apps & websites",
                            index = 3,
                            count = 5,
                            glyph = Symbols.block,
                            onClick = { startActivity(Intent(this@HoneycombSettingsActivity, FeedsActivity::class.java)) },
                            modifier = Modifier.testTag("settings-feeds"),
                        )
                    }
                    item(key = "updates") {
                        NavSummaryRow(
                            title = updateTitle,
                            valueText = updateSubtitle,
                            index = 4,
                            count = 5,
                            glyph = Symbols.download,
                            accentActive = updateActive,
                            showChevron = false,
                            onClick = {
                                when (val s = updateState) {
                                    is UpdateState.Available -> scope.launch {
                                        if (AppUpdater.downloadAndInstall(this@HoneycombSettingsActivity, s.release)) {
                                            val ready = AppUpdater.state.value as? UpdateState.ReadyToInstall
                                            if (ready != null && !packageManager.canRequestPackageInstalls()) {
                                                pendingInstallPath = ready.apkPath
                                            }
                                        }
                                    }
                                    is UpdateState.ReadyToInstall -> {
                                        if (!AppUpdater.launchInstaller(this@HoneycombSettingsActivity, s.apkPath)) {
                                            pendingInstallPath = s.apkPath
                                        }
                                    }
                                    is UpdateState.Error -> {
                                        val rel = s.release
                                        scope.launch {
                                            if (rel != null) {
                                                if (AppUpdater.downloadAndInstall(this@HoneycombSettingsActivity, rel)) {
                                                    val ready = AppUpdater.state.value as? UpdateState.ReadyToInstall
                                                    if (ready != null && !packageManager.canRequestPackageInstalls()) {
                                                        pendingInstallPath = ready.apkPath
                                                    }
                                                }
                                            } else {
                                                AppUpdater.checkNow(this@HoneycombSettingsActivity)
                                            }
                                        }
                                    }
                                    UpdateState.Idle, is UpdateState.UpToDate -> scope.launch {
                                        AppUpdater.checkNow(this@HoneycombSettingsActivity)
                                    }
                                    UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Verifying -> Unit
                                }
                            },
                            modifier = Modifier.testTag("settings-updates"),
                        )
                    }
                }
            }
        }
    }
}
