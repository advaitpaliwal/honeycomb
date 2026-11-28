package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateDpAsState

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay

/*
 * Control Centre and the Quiet setting, laid out after iOS's Control Center. Coordinates are layout units in the
 * 360-unit-wide frame, as in Home.kt.
 * iOS's Edit button is left out: the tiles are fixed.
 */

// ---- the device's state -------------------------------------------------------------------------------------------

/** Quiet: Android's Do Not Disturb set to priority only — zero app notification noise while protecting real calls & emergencies. */
object Quiet {
    private const val PREFS = "quiet"
    private const val KEY_CALL_MODE = "call_mode"
    private const val KEY_REPEAT_CALLERS = "repeat_callers"

    enum class CallMode(val label: String) {
        CONTACTS("Contacts"),
        EVERYONE("Everyone"),
        NONE("No one"),
    }

    /** The policy's numbers, kept apart from NotificationManager.Policy so a unit test can read them. */
    data class Rules(val categories: Int, val callSenders: Int, val messageSenders: Int, val suppressed: Int, val conversations: Int)

    fun rules(callMode: CallMode, repeatCallers: Boolean = true) = Rules(
        categories = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA or
            (if (callMode != CallMode.NONE) NotificationManager.Policy.PRIORITY_CATEGORY_CALLS else 0) or
            (if (repeatCallers) NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS else 0),
        callSenders = if (callMode == CallMode.EVERYONE) NotificationManager.Policy.PRIORITY_SENDERS_ANY else NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS,
        messageSenders = NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS, // unused: messages are not a category
        // notifications are off: no sound, no dot, no badge, no peek, nothing in the status bar or the shade
        suppressed = NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE or NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR or
            NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS or
            NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT or
            NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST,
        conversations = NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
    )

    /** Legacy 1-arg helper kept for compatibility with existing unit tests. */
    fun rules(contactsCall: Boolean) = rules(if (contactsCall) CallMode.CONTACTS else CallMode.NONE, repeatCallers = false)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun granted(ctx: Context) = nm(ctx).isNotificationPolicyAccessGranted
    fun isOn(ctx: Context): Boolean {
        val f = nm(ctx).currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun callMode(ctx: Context): CallMode {
        val p = prefs(ctx)
        p.getString(KEY_CALL_MODE, null)?.let { name ->
            CallMode.entries.firstOrNull { it.name == name }?.let { return it }
        }
        return if (p.getBoolean("contacts_call", true)) CallMode.CONTACTS else CallMode.NONE
    }

    /** Same number calling twice within 15 minutes breaks through by default so real emergencies never fail silently. */
    fun repeatCallers(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_REPEAT_CALLERS, true)

    fun contactsCall(ctx: Context) = callMode(ctx) != CallMode.NONE

    /**
     * Setup's Quiet screen was answered with Turn on. Notification access also gives Do Not Disturb access on Android 15
     * and later, so access alone never means the person was told Quiet will be on.
     */
    fun chosen(ctx: Context) = prefs(ctx).getBoolean("chosen", false)

    /** Turns Quiet on as setup's Quiet screen said it would, once access is there; false while it is not. */
    fun choose(ctx: Context): Boolean {
        if (!granted(ctx)) return false
        set(ctx, true)
        prefs(ctx).edit().putBoolean("chosen", true).apply()
        return true
    }

    /** Turns Quiet on or off; without policy access it asks for it instead and returns false. */
    fun set(ctx: Context, on: Boolean): Boolean {
        if (!granted(ctx)) { ask(ctx); return false }
        if (on) applyPolicy(ctx, callMode(ctx), repeatCallers(ctx))
        nm(ctx).setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return true
    }

    /** One-time upgrade fix: clears Android 15/16's implicit Honeycomb DND rule left on by setup so notifications & Live Updates show. */
    fun clearSetupDndOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("migrated_quiet_off_v1", false)) return
        p.edit().putBoolean("migrated_quiet_off_v1", true).apply()
        if (granted(ctx) && isOn(ctx)) {
            nm(ctx).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    /**
     * One-time calm default: silences notification sound (STREAM_NOTIFICATION = 0, leaving calls, alarms,
     * media, and Live Updates untouched) and turns off the notification pulse light when WRITE_SETTINGS is granted.
     */
    fun silenceNotificationBeepsOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("migrated_silent_notifs_v1", false)) return
        p.edit().putBoolean("migrated_silent_notifs_v1", true).apply()
        runCatching {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            am?.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, 0, 0)
        }
        runCatching {
            if (android.provider.Settings.System.canWrite(ctx)) {
                android.provider.Settings.System.putInt(ctx.contentResolver, "notification_light_pulse", 0)
            }
        }
    }

    /** Keeps Android's active DND policy synced (media unmuted + repeat callers + chosen call mode) across upgrades. */
    fun ensureMediaAllowed(ctx: Context) {
        if (!granted(ctx) || !isOn(ctx)) return
        val expected = rules(callMode(ctx), repeatCallers(ctx))
        val cur = nm(ctx).notificationPolicy
        if (cur.priorityCategories != expected.categories || cur.priorityCallSenders != expected.callSenders) {
            applyPolicy(ctx, callMode(ctx), repeatCallers(ctx))
        }
    }

    private fun applyPolicy(ctx: Context, mode: CallMode, repeat: Boolean) {
        val r = rules(mode, repeat)
        nm(ctx).notificationPolicy = NotificationManager.Policy(r.categories, r.callSenders, r.messageSenders, r.suppressed, r.conversations)
    }

    fun setCallMode(ctx: Context, mode: CallMode): Boolean {
        if (!granted(ctx)) { ask(ctx); return false }
        val repeat = repeatCallers(ctx)
        applyPolicy(ctx, mode, repeat)
        prefs(ctx).edit()
            .putString(KEY_CALL_MODE, mode.name)
            .putBoolean("contacts_call", mode != CallMode.NONE)
            .apply()
        return true
    }

    fun setRepeatCallers(ctx: Context, allow: Boolean): Boolean {
        if (!granted(ctx)) { ask(ctx); return false }
        applyPolicy(ctx, callMode(ctx), allow)
        prefs(ctx).edit().putBoolean(KEY_REPEAT_CALLERS, allow).apply()
        return true
    }

    fun setContactsCall(ctx: Context, allow: Boolean): Boolean =
        setCallMode(ctx, if (allow) CallMode.CONTACTS else CallMode.NONE)

    fun ask(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun summary(mode: CallMode, repeat: Boolean): String = when (mode) {
        CallMode.CONTACTS -> if (repeat) "Contacts + repeat callers" else "Contacts only"
        CallMode.EVERYONE -> "All calls ring"
        CallMode.NONE -> if (repeat) "Repeat callers only" else "No calls"
    }

    /** The Quiet screen's two lines for the full call policy. */
    fun words(on: Boolean, mode: CallMode, repeat: Boolean): Pair<String, String> {
        val calls = when (mode) {
            CallMode.EVERYONE -> "every phone call rings through while app notifications stay silent."
            CallMode.CONTACTS -> if (repeat) "your contacts and repeat callers (2x in 15 min) still ring. Other calls show as missed."
                else "your contacts can still call you. Other calls show as missed."
            CallMode.NONE -> if (repeat) "only repeat callers (2x in 15 min) ring through. All other calls show as missed."
                else "every call shows as missed."
        }
        return if (on) "Notifications are off. No sounds, no red dot, no badges on apps." to calls.replaceFirstChar { it.uppercase() }
        else "Quiet is off. Notifications and calls come through as usual." to "When Quiet is on, $calls"
    }

    /** Legacy 2-arg helper kept for SetupFlowTest. */
    fun words(on: Boolean, contactsCall: Boolean): Pair<String, String> =
        words(on, if (contactsCall) CallMode.CONTACTS else CallMode.NONE, repeat = false)
}

/** What each tile shows, read fresh from the system. */
data class Toggles(
    val wifi: Boolean = false, val bluetooth: Boolean = false, val quiet: Boolean = false, val silent: Boolean = false,
    val airplane: Boolean = false, val torch: Boolean = false, val hotspot: Boolean = false, val battery: Int = 0,
    /** Rotation lock: auto-rotate off (Settings.System.ACCELEROMETER_ROTATION 0). */
    val rotationLock: Boolean = false,
    /** Charging (BatteryManager.isCharging): the battery capsule shows the bolt. */
    val charging: Boolean = false,
    /** Battery Saver / Low Power Mode (PowerManager.isPowerSaveMode). */
    val powerSave: Boolean = false,
)

object Device {
    fun read(ctx: Context, torch: Boolean): Toggles {
        val audio = ctx.getSystemService(AudioManager::class.java)
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        return Toggles(
            wifi = runCatching { ctx.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled }.getOrDefault(false),
            bluetooth = runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false),
            quiet = Quiet.isOn(ctx),
            silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL,
            airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            torch = torch,
            // no public API reads the hotspot; WifiManager.isWifiApEnabled is hidden, so this is off where it is blocked
            hotspot = runCatching {
                val wm = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
                wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
            }.getOrDefault(false),
            battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
            rotationLock = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0,
            charging = bm.isCharging,
            powerSave = runCatching { pm.isPowerSaveMode }.getOrDefault(false),
        )
    }

    fun torchId(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()

    fun setTorch(ctx: Context, on: Boolean) {
        val id = torchId(ctx) ?: return
        runCatching { ctx.getSystemService(CameraManager::class.java).setTorchMode(id, on) }
    }

    /** Silent: the ringer off. Changing it from or to silent needs the same policy access as Quiet. */
    fun setSilent(ctx: Context, on: Boolean) {
        if (!Quiet.granted(ctx)) return Quiet.ask(ctx)
        runCatching { ctx.getSystemService(AudioManager::class.java).ringerMode = if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL }
    }

    /**
     * Rotation lock: auto-rotate off. Writing it needs WRITE_SETTINGS (Settings.System.canWrite); without it, Display
     * settings, where auto-rotate is, opens instead.
     */
    fun setRotationLock(ctx: Context, on: Boolean) {
        if (!Settings.System.canWrite(ctx)) return open(ctx, Settings.ACTION_DISPLAY_SETTINGS)
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1) }
    }

    /** Starts [intent] as a new task; false when nothing handles it. */
    fun launch(ctx: Context, intent: Intent): Boolean =
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    /** The timer: the clock app's timers (AlarmClock.ACTION_SHOW_TIMERS), with fallback to ACTION_SET_TIMER. */
    fun openTimer(ctx: Context) =
        launch(ctx, Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)) ||
            launch(ctx, Intent(android.provider.AlarmClock.ACTION_SET_TIMER))

    /** The calculator: whichever app declares CATEGORY_APP_CALCULATOR, found through a selector intent. */
    fun openCalculator(ctx: Context) = launch(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))

    /** The camera, ready for a photo (MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA). */
    fun openCamera(ctx: Context) = launch(ctx, Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

    /** The battery's percentage: Android's battery usage page, where Battery Saver is (the watch's opens Low Power). */
    fun openBattery(ctx: Context) = open(ctx, Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS)

    /** Volume: Android's volume panel (Settings.Panel.ACTION_VOLUME). */
    fun openVolume(ctx: Context) = open(ctx, Settings.Panel.ACTION_VOLUME)

    /** Screen mirroring: Android's cast settings (Settings.ACTION_CAST_SETTINGS). */
    fun openMirroring(ctx: Context) = open(ctx, Settings.ACTION_CAST_SETTINGS)

    /** Apps cannot switch these on Android 10+; each opens its system panel or page. */
    fun open(ctx: Context, vararg actions: String) {
        for (a in actions) if (runCatching { ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

// ---- A5 / A6: Control Centre --------------------------------------------------------------------------------------

/*
 * Control Centre is watchOS 27's Control Center (ControlGrid): the battery capsule centred at the top, then two columns
 * of glyph-only WatchButtons, scrolling as one list. A control that is on fills with its tint, as on the watch. There is
 * no slider, no now-playing module (media lives in the Smart Stack) and no Edit button.
 */

/** Polls the system each second while shown, and the torch through its callback. */
@Composable
fun rememberToggles(nudge: Int = 0): Toggles {
    val ctx = LocalContext.current
    var torch by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torch = enabled }
        }
        runCatching { cm.registerTorchCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())) }
        onDispose { runCatching { cm.unregisterTorchCallback(cb) } }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val resumes by FaceTick.resumes.collectAsState()
    return remember(tick, torch, resumes, nudge) { Device.read(ctx, torch) }
}

/** A switch's spoken state after its name, as the tiles always said it. */
private fun said(label: String, on: Boolean) = label + if (on) ", on" else ", off"

/** The charging bolt in the battery capsule (SF Symbols' bolt.fill, redrawn on the 24-unit grid). */
private val Bolt = Icon("M13.5 2 4.5 13.5h6.2L9.8 22l9.2-12h-6.4L13.5 2z")

@Composable
fun ControlCentre(onHoldQuiet: () -> Unit, onEnd: (Boolean) -> Unit) {
    var nudge by remember { mutableIntStateOf(0) } // re-read right after a tap
    val now = rememberToggles(nudge)
    val ctx = LocalContext.current
    val view = LocalView.current
    var brightness by remember { mutableStateOf(Levels.brightness(ctx)) }
    var showBrightnessSheet by remember { mutableStateOf(false) }
    var lastBrightnessAskMs by remember { mutableStateOf(0L) }
    var activeIds by remember { mutableStateOf(ControlPrefs.load(ctx)) }
    var editing by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val curIds by rememberUpdatedState(activeIds)
    val resumes by FaceTick.resumes.collectAsState()
    LaunchedEffect(resumes) { brightness = Levels.brightness(ctx) }
    DisposableEffect(editing) {
        DragOwners.editingLock = editing
        onDispose {
            DragOwners.editingLock = false
            DragOwners.activeDrag = false
            DragOwners.set("brightness-slider", null)
        }
    }
    val reorderableState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyGridState
        val toKey = to.key as? String ?: return@rememberReorderableLazyGridState
        val fromIdx = curIds.indexOf(fromKey)
        val toIdx = curIds.indexOf(toKey)
        if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
            val moved = ControlPrefs.move(curIds, fromIdx, toIdx)
            activeIds = moved
            ControlPrefs.save(ctx, moved)
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    fun act(f: () -> Unit): () -> Unit = { if (!editing) { f(); nudge++ } }
    HoneycombTheme { Backdrop(Modifier.testTag("control-centre"), sheet = true, ground = HoneycombColors.sheet) {
        // a swipe up closes it once the list is at its end (disabled while editing/reordering tiles)
        LaunchedEffect(gridState, editing) {
            androidx.compose.runtime.snapshotFlow { !editing && !gridState.canScrollForward }.collect(onEnd)
        }
        Box(Modifier.fillMaxSize()) {
            val pt = 1.wp
            val gapX = pt * ControlGrid.GAP_X
            val gapY = pt * ControlGrid.GAP_Y
            val btnH = pt * ControlGrid.BUTTON_H
            val sidePad = pt * ControlGrid.SIDE
            val updateState by AppUpdater.state.collectAsState()
            val availableUpdate = when (val s = updateState) {
                is UpdateState.Available -> s.release
                is UpdateState.ReadyToInstall -> s.release
                else -> null
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(ControlGrid.COLS),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sidePad,
                    end = sidePad,
                    top = statusBarInset + pt * ControlGrid.CAPSULE_TOP,
                    bottom = scrollBottomInset + 28.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(gapX),
                verticalArrangement = Arrangement.spacedBy(gapY),
            ) {
                // Header: watchOS Edit bar (+ / Done) in Edit mode only (phone already has a status bar + Battery tile)
                if (editing) {
                    item(key = "cc-header", span = { GridItemSpan(ControlGrid.COLS) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            CircleButton(
                                icon = Symbols.add,
                                contentDescription = "Add control",
                                onClick = { showAddSheet = true },
                                modifier = Modifier.testTag("control-add"),
                                size = ButtonSize.circleRegular,
                            )
                            CapsuleButton(
                                text = "Done",
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    editing = false
                                },
                                modifier = Modifier.testTag("control-done"),
                                filled = true,
                                height = ButtonSize.compact,
                            )
                        }
                    }
                }

                // 2-column Apple Watch pure Capsule tiles with drag-to-reorder & per-tile remove badge in Edit mode
                items(items = activeIds, key = { it }) { id ->
                    ReorderableItem(reorderableState, key = id) { isDragging ->
                        val scale by animateFloatAsState(
                            targetValue = if (isDragging) 1.07f else 1f,
                            animationSpec = PICKUP_SCALE_SPRING,
                            label = "cc-drag-scale",
                        )
                        val jiggle = rememberGentleJiggle(editing && !isDragging, id.hashCode())
                        val dragMod = if (editing) {
                            Modifier.draggableHandle(
                                enabled = true,
                                onDragStarted = {
                                    DragOwners.activeDrag = true
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                },
                                onDragStopped = {
                                    DragOwners.activeDrag = false
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                },
                            )
                        } else {
                            Modifier
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(btnH)
                                .zIndex(if (isDragging) 40f else 0f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    rotationZ = jiggle
                                },
                        ) {
                            Control(
                                id = id,
                                t = now,
                                m = Modifier
                                    .fillMaxSize()
                                    .then(if (isDragging) Modifier.shadow(14.dp, Capsule, clip = false) else Modifier)
                                    .then(dragMod),
                                pt = pt,
                                onHoldQuiet = { if (!editing) onHoldQuiet() },
                                onOpenBrightness = {
                                    brightness = Levels.brightness(ctx)
                                    showBrightnessSheet = true
                                },
                                act = ::act,
                            )
                            if (editing && activeIds.size > 1) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset(x = (-2).dp, y = (-2).dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(HoneycombColors.error)
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            val next = activeIds - id
                                            activeIds = next
                                            ControlPrefs.save(ctx, next)
                                        }
                                        .testTag("control-remove-$id")
                                        .semantics { contentDescription = "Remove $id" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Glyph(Symbols.remove, HoneycombColors.contentFor(HoneycombColors.error), 16.dp)
                                }
                            }
                        }
                    }
                }

                // Footer: watchOS Edit button (and optional Update button when a newer signed release exists)
                item(key = "cc-footer", span = { GridItemSpan(ControlGrid.COLS) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ButtonSize.stackGap),
                    ) {
                        if (!editing) {
                            CapsuleButton(
                                text = "Edit",
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    editing = true
                                },
                                modifier = Modifier.testTag("control-edit"),
                                height = ButtonSize.regular,
                                minWidth = ButtonSize.minPillWidth,
                            )
                        }
                        if (availableUpdate != null && !editing) {
                            CapsuleButton(
                                text = "Update ${availableUpdate.tag}",
                                onClick = {
                                    ctx.startActivity(
                                        Intent(ctx, HoneycombSettingsActivity::class.java)
                                            .putExtra(AppUpdater.EXTRA_START_UPDATE, true)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                },
                                modifier = Modifier.testTag("control-update"),
                                icon = Symbols.download,
                                filled = true,
                                height = ButtonSize.regular,
                            )
                        }
                    }
                }
            }

            if (showBrightnessSheet) {
                BottomSheet(onDismiss = { showBrightnessSheet = false }) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        BasicText("Brightness", style = HoneycombType.title)
                        ControlCapsuleSlider(
                            value = brightness,
                            onValueChange = { next ->
                                brightness = next
                                if (Levels.canWriteBrightness(ctx)) {
                                    Levels.setBrightness(ctx, next)
                                } else {
                                    val nowMs = android.os.SystemClock.uptimeMillis()
                                    if (nowMs - lastBrightnessAskMs > 1500L) {
                                        lastBrightnessAskMs = nowMs
                                        Device.launch(
                                            ctx,
                                            Intent(
                                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                                android.net.Uri.fromParts("package", ctx.packageName, null),
                                            ),
                                        )
                                    }
                                }
                            },
                            icon = Symbols.sun,
                            contentDescription = "Brightness",
                            height = 56.dp,
                            modifier = Modifier.fillMaxWidth().testTag("brightness-slider"),
                        )
                        Spacer(Modifier.height(scrollBottomInset + 8.dp))
                    }
                }
            }

            if (showAddSheet) {
                val missing = ControlGrid.catalog.filter { (id, _) -> id !in activeIds }
                BottomSheet(onDismiss = { showAddSheet = false }) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicText("Add Controls", style = HoneycombType.title)
                        Spacer(Modifier.height(4.dp))
                        if (missing.isEmpty()) {
                            BasicText(
                                "All controls are already in Control Centre.",
                                style = HoneycombType.body.copy(color = HoneycombColors.secondaryLabel),
                            )
                        } else {
                            missing.forEach { (id, label) ->
                                Row(
                                    title = label,
                                    icon = controlIcon(id),
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        val next = activeIds + id
                                        activeIds = next
                                        ControlPrefs.save(ctx, next)
                                        showAddSheet = false
                                    },
                                )
                            }
                        }
                        if (activeIds != ControlGrid.order) {
                            Spacer(Modifier.height(4.dp))
                            CapsuleButton(
                                text = "Reset to defaults",
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    activeIds = ControlPrefs.reset(ctx)
                                    showAddSheet = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                height = ButtonSize.large,
                            )
                        }
                        Spacer(Modifier.height(scrollBottomInset + 8.dp))
                    }
                }
            }
        }
    } }
}

/** One control: a glyph-only WatchButton, its tint filling it when on. */
@Composable
private fun Control(
    id: String,
    t: Toggles,
    m: Modifier,
    pt: androidx.compose.ui.unit.Dp,
    onHoldQuiet: () -> Unit,
    onOpenBrightness: () -> Unit,
    act: (() -> Unit) -> () -> Unit,
) {
    val ctx = LocalContext.current
    when (id) {
        "bluetooth" -> WatchButton(Symbols.bluetooth, said("Bluetooth", t.bluetooth), act { Device.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) },
            m.testTag("tile-bluetooth"), on = t.bluetooth)
        "wifi" -> WatchButton(Symbols.wifi, said("Wi-Fi", t.wifi), act { Device.open(ctx, Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS) },
            m.testTag("tile-wifi"), on = t.wifi)
        "airplane" -> WatchButton(Symbols.airplane, said("Airplane mode", t.airplane), act { Device.open(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
            m.testTag("tile-airplane"), on = t.airplane)
        "battery" -> WatchButton(
            Symbols.batteryFull,
            "Battery, ${t.battery} percent" + if (t.powerSave) ", Battery Saver on" else "",
            act { Device.openBattery(ctx) },
            m.testTag("tile-battery"),
            on = t.powerSave || t.battery <= 20,
            tint = if (t.battery <= 20) HoneycombColors.error else HoneycombColors.accent,
            label = "${t.battery}%",
        )
        "silent" -> WatchButton(if (t.silent) Symbols.bellSlash else Symbols.bell, said("Silent", t.silent), act { Device.setSilent(ctx, !t.silent) },
            m.testTag("tile-silent"), on = t.silent)
        "flashlight" -> WatchButton(if (t.torch) Symbols.flashlightOn else Symbols.flashlightOff, said("Flashlight", t.torch),
            act { Device.setTorch(ctx, !t.torch) }, m.testTag("tile-flashlight"), on = t.torch)
        "quiet" -> WatchButton(Symbols.moon, said("Quiet", t.quiet), act { Quiet.set(ctx, !t.quiet) }, m.testTag("tile-quiet"),
            on = t.quiet, onLongClick = onHoldQuiet)
        "rotation" -> WatchButton(Symbols.rotationLock, said("Rotation lock", t.rotationLock), act { Device.setRotationLock(ctx, !t.rotationLock) },
            m.testTag("tile-rotation"), on = t.rotationLock)
        "volume" -> WatchButton(Symbols.volume, "Volume", act { Device.openVolume(ctx) }, m.testTag("tile-volume"))
        "output" -> WatchButton(Symbols.output, "Audio output", act { openOutput(ctx) }, m.testTag("tile-output"))
        "brightness" -> WatchButton(Symbols.sun, "Brightness", act { onOpenBrightness() }, m.testTag("tile-brightness"))
        "timer" -> WatchButton(Symbols.timer, "Timer", act { Device.openTimer(ctx) }, m.testTag("tile-timer"))
        "calculator" -> WatchButton(Symbols.calculator, "Calculator", act { Device.openCalculator(ctx) }, m.testTag("tile-calculator"))
        "camera" -> WatchButton(Symbols.camera, "Camera", act { Device.openCamera(ctx) }, m.testTag("tile-camera"))
        "mirroring" -> WatchButton(Symbols.mirroring, "Screen mirroring", act { Device.openMirroring(ctx) }, m.testTag("tile-mirroring"))
        "hotspot" -> WatchButton(Symbols.hotspot, said("Hotspot", t.hotspot),
            act { Device.open(ctx, "android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS) },
            m.testTag("tile-hotspot"), on = t.hotspot)
        "block" -> {
            val guardOn = FeedGuard.enabled(ctx)
            WatchButton(Symbols.block, said("Block", guardOn),
                act { ctx.startActivity(Intent(ctx, FeedsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                m.testTag("tile-block"), on = guardOn)
        }
        "settings" -> WatchButton(Symbols.settings, "Honeycomb Settings",
            act { ctx.startActivity(Intent(ctx, HoneycombSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            m.testTag("tile-settings"))
    }
}

private fun controlIcon(id: String): Icon = when (id) {
    "bluetooth" -> Symbols.bluetooth
    "wifi" -> Symbols.wifi
    "airplane" -> Symbols.airplane
    "battery" -> Symbols.batteryFull
    "silent" -> Symbols.bell
    "flashlight" -> Symbols.flashlightOn
    "quiet" -> Symbols.moon
    "rotation" -> Symbols.rotationLock
    "volume" -> Symbols.volume
    "output" -> Symbols.output
    "brightness" -> Symbols.sun
    "timer" -> Symbols.timer
    "calculator" -> Symbols.calculator
    "camera" -> Symbols.camera
    "mirroring" -> Symbols.mirroring
    "hotspot" -> Symbols.hotspot
    "block" -> Symbols.block
    else -> Symbols.settings
}

/** WatchButton's own glyph size, wp, so the flashlight's dark glyph covers its white one exactly. */
private const val FLASHLIGHT_GLYPH = 26f

// ---- A7: Quiet ----------------------------------------------------------------------------------------------------

/**
 * Quiet's own screen (touch and hold on the Quiet pill): unified Material 3 Expressive header, segmented choice cards,
 * and repeat-caller protection switch on the same sheet backdrop as Control Centre.
 */
@Composable
fun QuietScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = rememberToggles()
    var mode by remember { mutableStateOf(Quiet.callMode(ctx)) }
    var repeat by remember { mutableStateOf(Quiet.repeatCallers(ctx)) }
    val body = HoneycombType.body.copy(color = HoneycombColors.secondaryLabel)
    HoneycombTheme { Backdrop(Modifier.testTag("quiet"), sheet = true) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = statusBarInset + 12.dp, bottom = scrollBottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MinimalScreenHeader(
                title = "Quiet",
                onBack = onBack,
                backTag = "quiet-back",
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val (state, calls) = Quiet.words(t.quiet, mode, repeat)
            BasicText(state, Modifier.padding(horizontal = 4.dp, vertical = 4.dp).testTag("quiet-state"), style = body)
            SectionKicker("Allow calls from")
            Column(Modifier.fillMaxWidth().testTag("quiet-contacts"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Quiet.CallMode.entries.forEachIndexed { idx, option ->
                    CleanChoiceRow(
                        title = option.label,
                        selected = mode == option,
                        onClick = { if (Quiet.setCallMode(ctx, option)) mode = option },
                        index = idx,
                        count = Quiet.CallMode.entries.size,
                        modifier = Modifier.testTag("quiet-mode-${option.name.lowercase()}"),
                    )
                }
            }
            if (mode != Quiet.CallMode.EVERYONE) {
                SectionKicker("Emergency breakthrough")
                CleanSwitchRow(
                    title = "Repeat callers",
                    subtitle = "2nd call within 15 min rings",
                    checked = repeat,
                    onCheckedChange = { if (Quiet.setRepeatCallers(ctx, it)) repeat = it },
                    index = 0,
                    count = 1,
                    glyph = Symbols.bell,
                    modifier = Modifier.testTag("quiet-repeat"),
                )
            }
            SettingsNote(calls, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
    } }
}

