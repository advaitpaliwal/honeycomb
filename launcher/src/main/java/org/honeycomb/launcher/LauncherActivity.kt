package org.honeycomb.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home app: Face <-> Dynamic Grid (7 apps) <-> All Apps (Honeycomb grid / List View),
 * Smart Stack rising over the Face, and Notification / Control sheets pulled from the top edge.
 */
class LauncherActivity : ComponentActivity() {
    private var homeDepth by mutableStateOf(Depth.Face)
    private var space by mutableStateOf(Space(depth = Depth.Face))

    private var launched: ComponentName? = null
    private var editing by mutableStateOf(false)
    private var pickFor by mutableStateOf<Int?>(null)
    private var searchFocusKey by androidx.compose.runtime.mutableIntStateOf(0)
    private val rowSteps = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 16)

    /** Immediately dismisses the soft keyboard so it never lags behind layer transitions. */
    private fun hideKeyboard() {
        searchFocusKey = 0
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        runCatching {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        }
    }

    /** Every input goes through the table. */
    fun input(i: Input) {
        launched = null
        val intent = intentFor(space, i)
        if (intent == Intent.Home || intent == Intent.Out) { editing = false; pickFor = null }
        val next = when {
            // Pressing Home again while already on a non-Face Home (7-App Hub / Grid) lets you rest on the Watch Face;
            // pressing Home again from Watch Face jumps straight back to your configured Home depth.
            i == Input.HomeAgain && homeDepth != Depth.Face && !space.stack && space.edge == null && !space.picking && space.depth == homeDepth ->
                Space(depth = Depth.Face, sheetEnd = space.sheetEnd)
            i == Input.HomeAgain && homeDepth != Depth.Face && !space.stack && space.edge == null && !space.picking && space.depth == Depth.Face ->
                Space(depth = homeDepth, sheetEnd = space.sheetEnd)
            else -> reduce(space, intent, homeDepth)
        }
        if (next.depth != space.depth) editing = false
        if (intent == Intent.Search) {
            searchFocusKey++
        } else if (next.depth != Depth.Apps || next.edge != null) {
            hideKeyboard()
        }
        space = next
    }

    private fun tall() = resources.displayMetrics.let { tallScreen(it.widthPixels, it.heightPixels) }

    private var waitingForUnlock = false
    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) = recreate()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (IconTheme.themed(this)) AppCache.refresh()
    }

    override fun onDestroy() {
        if (waitingForUnlock) unregisterReceiver(unlocked)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val users = getSystemService(android.os.UserManager::class.java)
        if (!users.isUserUnlocked) {
            waitingForUnlock = true
            registerReceiver(unlocked, android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED), RECEIVER_NOT_EXPORTED)
            if (users.isUserUnlocked) recreate()
            return
        }
        AppCache.start(this)
        homeDepth = AppOrder.savedHomeStart(this).depth
        WindowCompat.setDecorFitsSystemWindows(window, false)
        launched = savedInstanceState?.getParcelable("launched", ComponentName::class.java)
        if (savedInstanceState != null && launched != null) {
            val depth = savedInstanceState.getString("depth")?.let { runCatching { Depth.valueOf(it) }.getOrNull() } ?: homeDepth
            space = Space(depth = depth)
        } else {
            space = Space(depth = homeDepth)
        }
        route(intent)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
        }
        window.isNavigationBarContrastEnforced = false
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            org.honeycomb.launcher.design.Native.scheme(this).let {
                org.honeycomb.launcher.design.Native.ground(it, org.honeycomb.launcher.design.Native.isDark(this))
            }.toArgb(),
        ))
        Setup.firstRun(this)
        onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent {
            org.honeycomb.launcher.design.ProvideScale {
                FrameSize {
                    LaunchedEffect(Unit) {
                        androidx.compose.runtime.snapshotFlow { org.honeycomb.launcher.design.BarsUnder.front }.collect { under ->
                            val ground = org.honeycomb.launcher.design.Native.let {
                                it.ground(it.scheme(this@LauncherActivity), it.isDark(this@LauncherActivity))
                            }
                            WindowInsetsControllerCompat(window, window.decorView).apply {
                                isAppearanceLightStatusBars = (under?.first ?: ground).luminance() > 0.5f
                                isAppearanceLightNavigationBars = (under?.second ?: ground).luminance() > 0.5f
                            }
                        }
                    }
                    BackHandler(enabled = editing || pickFor != null) {
                        if (editing) editing = false else { pickFor = null; input(Input.Back) }
                    }
                    BackHandler(enabled = !editing && pickFor == null && intentFor(space, Input.Back) != Intent.None) { input(Input.Back) }

                    val apps by AppCache.apps.collectAsState()
                    val all = apps.orEmpty()
                    val keys = remember(all) { all.map { it.component.flattenToString() } }
                    var pins by remember { mutableStateOf(AppOrder.savedPins(this)) }
                    var hidden by remember { mutableStateOf(AppOrder.savedHidden(this)) }
                    var order by remember { mutableStateOf(AppOrder.savedList(this)) }
                    val labels = remember(all) { all.associate { it.component.flattenToString() to it.label } }
                    val shownOrder = remember(order, keys) { AppOrder.list(order, keys) { labels[it].orEmpty() } }
                    // watchOS List View is strictly alphabetical (A -> Z, '#' last) so the right-edge A-Z scrubber maps 1:1.
                    val alphabeticalOrder = remember(all) { alphabeticalApps(all).map { it.component.flattenToString() } }
                    var grid by remember { mutableStateOf(AppOrder.savedGrid(this, tall())) }
                    var ranking by remember { mutableStateOf(ContextualRanking(emptyList(), emptyMap())) }
                    val resumeTick = FaceTick.resumes.value
                    val notifTick by MediaListener.Defaults.posted.collectAsState()
                    LaunchedEffect(space.depth, space.stack, resumeTick, notifTick) {
                        if (space.depth == Depth.Favourites || space.depth == Depth.Face || space.stack || ranking.ordered.isEmpty()) {
                            ranking = withContext(Dispatchers.IO) { contextualPackages(this@LauncherActivity) }
                        }
                    }
                    val used = ranking.ordered
                    val prevShownRef = remember { arrayOf(emptyList<String>()) }
                    val shownPins = remember(pins, keys, ranking, hidden) {
                        AppOrder.favouritesStable(pins, keys, ranking, prevShownRef[0], { labels[it].orEmpty() }, hidden)
                            .also {
                                prevShownRef[0] = it
                                // Track currently shown unpinned dynamic packages for passive-negative impression learning
                                lastShownDynamicPkgs = it.mapIndexedNotNull { idx, key ->
                                    if (key.isEmpty() || pins.getOrNull(idx).orEmpty().isNotEmpty()) null
                                    else ComponentName.unflattenFromString(key)?.packageName
                                }
                            }
                    }
                    fun savePins(next: List<String>) {
                        pins = AppOrder.pinned(pins, shownPins, next); AppOrder.savePins(this@LauncherActivity, pins)
                    }
                    fun dontRecommendSlot(slot: Int, key: String) {
                        hidden = hidden + key; AppOrder.saveHidden(this@LauncherActivity, hidden)
                        pins = AppOrder.unpin(AppOrder.unpinKey(pins, key), slot); AppOrder.savePins(this@LauncherActivity, pins)
                    }
                    fun unpinSlot(slot: Int) {
                        pins = AppOrder.unpin(pins, slot); AppOrder.savePins(this@LauncherActivity, pins)
                    }
                    val gridOrder = remember(order, keys, used, hidden, shownOrder) {
                        if (order.isEmpty()) AppOrder.byUse(used, keys, hidden) { labels[it].orEmpty() }
                        else shownOrder.filter { it !in hidden } + shownOrder.filter { it in hidden }
                    }
                    fun view(g: Boolean) { grid = g; AppOrder.saveGrid(this@LauncherActivity, g) }
                    fun moveToTop(key: String) {
                        if (key in hidden) { hidden = hidden - key; AppOrder.saveHidden(this@LauncherActivity, hidden) }
                        order = listOf(key) + (gridOrder - key)
                        AppOrder.saveList(this@LauncherActivity, order)
                    }
                    fun moveGridApp(from: Int, to: Int) {
                        val moved = AppOrder.move(gridOrder, from, to)
                        if (moved != gridOrder) {
                            order = moved
                            AppOrder.saveList(this@LauncherActivity, order)
                        }
                    }
                    fun dontRecommendGrid(key: String) {
                        hidden = hidden + key; AppOrder.saveHidden(this@LauncherActivity, hidden)
                        pins = AppOrder.unpinKey(pins, key); AppOrder.savePins(this@LauncherActivity, pins)
                        if (order.isNotEmpty()) {
                            order = order - key
                            AppOrder.saveList(this@LauncherActivity, order)
                        }
                    }
                    fun recommend(key: String) {
                        hidden = hidden - key; AppOrder.saveHidden(this@LauncherActivity, hidden)
                    }

                    val s = space
                    val depthController = remember { DepthController(space.depth) }
                    val rise = remember { RiseState() }
                    val screenH = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
                    val notifSheet = remember { SheetController(screenH) }
                    val controlSheet = remember { SheetController(screenH) }
                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    LaunchedEffect(s.depth, s.edge) {
                        if (s.depth != Depth.Apps || s.edge != null) {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            hideKeyboard()
                        }
                    }
                    val inputSpace = { if (editing || pickFor != null) space.copy(edge = null, picking = true) else space }
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (SideSwipe.claims(inputSpace())) Modifier.systemGestureExclusion { c ->
                                SideSwipe.band(c.size.width.toFloat(), c.size.height.toFloat(), android.content.res.Resources.getSystem().displayMetrics.density)
                            } else Modifier)
                            .spaceInput(
                                space = inputSpace,
                                onInput = { input(it) },
                                onDrag = { i, px ->
                                    val cur = space
                                    val dragIntent = intentFor(cur, i)
                                    if (cur.depth == Depth.Apps && (dragIntent == Intent.Out || dragIntent == Intent.Notifications || dragIntent == Intent.Control)) {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        hideKeyboard()
                                    }
                                    when (dragIntent) {
                                        Intent.RaiseStack -> rise.drag(px)
                                        Intent.LowerStack -> rise.drag(-px)
                                        Intent.Notifications -> notifSheet.drag(px)
                                        Intent.Control -> controlSheet.drag(px)
                                        Intent.CloseEdge -> {
                                            if (cur.edge == Edge.Notifications) notifSheet.drag(-px)
                                            else if (cur.edge == Edge.Control) controlSheet.drag(-px)
                                        }
                                        Intent.In, Intent.Out -> {
                                            val span = when {
                                                i == Input.Right || i == Input.Left -> resources.displayMetrics.widthPixels * SideSwipe.TRAVEL
                                                (i == Input.Up || i == Input.Down) && cur.depth == Depth.Favourites && cur.edge == null -> resources.displayMetrics.heightPixels * 0.35f
                                                else -> 0f
                                            }
                                            if (span > 0f) {
                                                val from = cur.depth.ordinal.toFloat()
                                                val to = reduce(cur, dragIntent).depth.ordinal.toFloat()
                                                if (to != from) depthController.drag((to - from) * (px / span))
                                            }
                                        }
                                        else -> Unit
                                    }
                                },
                                onRelease = { i, velocity, _ ->
                                    val cur = space
                                    val dragIntent = intentFor(cur, i)
                                    if (rise.tracking) rise.release(if (i == Input.Up) velocity else -velocity)
                                    if (notifSheet.tracking) notifSheet.release(if (i == Input.Up) -velocity else velocity)
                                    if (controlSheet.tracking) controlSheet.release(if (i == Input.Up) -velocity else velocity)
                                    if (depthController.tracking) {
                                        val span = when {
                                            i == Input.Right || i == Input.Left -> resources.displayMetrics.widthPixels * SideSwipe.TRAVEL
                                            else -> resources.displayMetrics.heightPixels * 0.35f
                                        }.coerceAtLeast(1f)
                                        val from = cur.depth.ordinal.toFloat()
                                        val to = reduce(cur, dragIntent).depth.ordinal.toFloat()
                                        val dir = if (to >= from) 1f else -1f
                                        depthController.release(dir * velocity / span)
                                    }
                                },
                            ),
                    ) {
                        val wideGrid = expandedGridWindow()
                        DepthLayers(
                            depth = s.depth,
                            controller = depthController,
                            fullWidthApps = wideGrid && grid && pickFor == null,
                            face = {
                                Rise(
                                    raised = s.stack,
                                    state = rise,
                                    under = {
                                        Frame {
                                            FacePager(picking = s.picking, onInput = ::input)
                                        }
                                    },
                                    over = {
                                        SmartStack(
                                            onTop = { top -> if (space.stackTop != top) space = space.copy(stackTop = top) },
                                            onOpenMedia = { m -> openMedia(this@LauncherActivity, m) },
                                            ranking = ranking,
                                        )
                                    },
                                )
                            },
                            favourites = {
                                FavouritesScreen(
                                    apps = all,
                                    pins = shownPins,
                                    savedPins = pins,
                                    editing = editing,
                                    onEditing = { editing = it },
                                    onPins = ::savePins,
                                    onPick = { pickFor = it; space = space.copy(depth = Depth.Apps) },
                                    onDontRecommend = ::dontRecommendSlot,
                                    onUnpin = ::unpinSlot,
                                    onAllApps = { input(Input.AllApps) },
                                    onSearch = { searchFocusKey++; input(Input.AllApps) },
                                    onFace = { input(Input.Back) },
                                    onOpen = ::open,
                                )
                            },
                            apps = {
                                androidx.compose.animation.AnimatedContent(
                                    targetState = grid && pickFor == null,
                                    label = "all apps view",
                                    transitionSpec = {
                                        val enter = androidx.compose.animation.fadeIn(Motion.tap.spec()) +
                                            androidx.compose.animation.scaleIn(Motion.tap.spec(), initialScale = 0.96f)
                                        val exit = androidx.compose.animation.fadeOut(Motion.tap.spec()) +
                                            androidx.compose.animation.scaleOut(Motion.tap.spec(), targetScale = 0.96f)
                                        androidx.compose.animation.ContentTransform(enter, exit)
                                    },
                                ) { showGrid ->
                                    if (showGrid) AppGridScreen(
                                        apps = all,
                                        order = gridOrder,
                                        rowSteps = rowSteps,
                                        hidden = hidden,
                                        editing = editing,
                                        onEditing = { editing = it },
                                        onMove = ::moveGridApp,
                                        onMoveToTop = ::moveToTop,
                                        onDontRecommend = ::dontRecommendGrid,
                                        onRecommend = ::recommend,
                                        onListView = { editing = false; view(false) },
                                        onOpen = ::open,
                                        onOpenBlock = {
                                            startActivity(
                                                android.content.Intent(this@LauncherActivity, FeedsActivity::class.java)
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        },
                                        onOpenControl = { input(Input.TopRight) },
                                        onOpenSettings = {
                                            startActivity(
                                                android.content.Intent(this@LauncherActivity, HoneycombSettingsActivity::class.java)
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        },
                                        searchFocusKey = searchFocusKey,
                                        onSearchFocused = { searchFocusKey = 0 },
                                    ) else AppListScreen(
                                        apps = all,
                                        order = alphabeticalOrder,
                                        rowSteps = rowSteps,
                                        editing = false,
                                        onEditing = { editing = it },
                                        onOrder = {},
                                        pickFor = pickFor,
                                        onPicked = { key ->
                                            val slot = pickFor ?: return@AppListScreen
                                            if (key in hidden) { hidden = hidden - key; AppOrder.saveHidden(this@LauncherActivity, hidden) }
                                            savePins(AppOrder.pin(shownPins, slot, key))
                                            pickFor = null; space = space.copy(depth = Depth.Favourites)
                                        },
                                        onOpen = ::open,
                                        onGridView = { view(true) },
                                        onOpenBlock = {
                                            startActivity(
                                                android.content.Intent(this@LauncherActivity, FeedsActivity::class.java)
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        },
                                        onOpenControl = { input(Input.TopRight) },
                                        onOpenSettings = {
                                            startActivity(
                                                android.content.Intent(this@LauncherActivity, HoneycombSettingsActivity::class.java)
                                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        },
                                        retainScroll = { launched != null },
                                        searchFocusKey = searchFocusKey,
                                        onSearchFocused = { searchFocusKey = 0 },
                                    )
                                }
                            },
                        )

                        Box(Modifier.align(Alignment.Center).width(FRAME_W.dp).fillMaxHeight()) {
                            EdgeSheet(open = s.edge == Edge.Notifications, controller = notifSheet) {
                                NotificationCentre(onEnd = { end -> if (space.sheetEnd != end) space = space.copy(sheetEnd = end) })
                            }
                            EdgeSheet(open = s.edge == Edge.Control, controller = controlSheet) {
                                val reduce = org.honeycomb.launcher.design.HoneycombTheme.reduceMotion
                                androidx.compose.animation.AnimatedContent(
                                    targetState = s.quiet,
                                    transitionSpec = {
                                        org.honeycomb.launcher.design.routeTransitionSpec(
                                            forward = targetState,
                                            reduceMotion = reduce,
                                        )
                                    },
                                    label = "control-quiet",
                                ) { showQuiet ->
                                    if (showQuiet) QuietScreen(onBack = { input(Input.Back) })
                                    else ControlCentre(
                                        onHoldQuiet = { input(Input.HoldQuiet) },
                                        onEnd = { end -> if (space.sheetEnd != end) space = space.copy(sheetEnd = end) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (waitingForUnlock) return
        Widgets.host(this).startListening()
        Providers.start(this)
    }

    override fun onStop() {
        inFront = false
        hideKeyboard()
        if (waitingForUnlock) { super.onStop(); return }
        Widgets.host(this).stopListening()
        Providers.stop()
        super.onStop()
    }

    @Deprecated("Widget host configuration uses the platform result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE) WidgetResults.onConfigure?.invoke(resultCode == RESULT_OK)
    }

    override fun onResume() {
        super.onResume()
        FaceTick.resumes.value++
        if (!waitingForUnlock) {
            homeDepth = AppOrder.savedHomeStart(this).depth
            runCatching { Widgets.host(this).startListening() }
            runCatching { Quiet.clearSetupDndOnce(this) }
            runCatching { Quiet.silenceNotificationBeepsOnce(this) }
            runCatching { Quiet.ensureMediaAllowed(this) }
            runCatching { AppUpdater.checkIfDue(this) }
        }
    }

    private var inFront = false
    private var lastShownDynamicPkgs: List<String> = emptyList()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inFront = true
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        route(intent, new = true)
    }

    private fun route(intent: android.content.Intent?, new: Boolean = false) {
        if (intent?.action != android.content.Intent.ACTION_MAIN || !intent.hasCategory(android.content.Intent.CATEGORY_HOME)) return
        val again = new && inFront && (intent.flags and android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0 &&
            !editing && pickFor == null
        input(if (again) Input.HomeAgain else Input.Home)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launched?.let {
            outState.putParcelable("launched", it)
            outState.putString("depth", space.depth.name)
        }
    }

    private fun open(component: ComponentName, from: Rect?) {
        hideKeyboard()
        val bounds = from?.takeUnless { it.isEmpty }
        val options = bounds?.let {
            runCatching { ActivityOptions.makeScaleUpAnimation(window.decorView, it.left, it.top, it.width(), it.height()) }.getOrNull()
        }
        val app = android.content.Intent.makeMainActivity(component).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            sourceBounds = bounds
        }
        inFront = false
        val started = runCatching { startActivity(app, options?.toBundle()) }.isSuccess ||
            runCatching { startActivity(app) }.isSuccess
        if (started) {
            launched = component
            recordLaunch(this, component, lastShownDynamicPkgs)
        }
    }
}
