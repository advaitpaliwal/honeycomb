package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space
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
    fun isOn(ctx: Context) = nm(ctx).currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY

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
)

object Device {
    fun read(ctx: Context, torch: Boolean): Toggles {
        val audio = ctx.getSystemService(AudioManager::class.java)
        return Toggles(
            wifi = runCatching { ctx.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled }.getOrDefault(false),
            bluetooth = runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false),
            quiet = Quiet.isOn(ctx),
            silent = audio.ringerMode == AudioManager.RINGER_MODE_SILENT,
            airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
            torch = torch,
            // no public API reads the hotspot; WifiManager.isWifiApEnabled is hidden, so this is off where it is blocked
            hotspot = runCatching {
                val wm = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
                wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
            }.getOrDefault(false),
            battery = ctx.getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
            rotationLock = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0,
            charging = ctx.getSystemService(android.os.BatteryManager::class.java).isCharging,
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

    /** The timer: the clock app's timers (AlarmClock.ACTION_SHOW_TIMERS). */
    fun openTimer(ctx: Context) = launch(ctx, Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS))

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
    val scroll = rememberScrollState()
    fun act(f: () -> Unit): () -> Unit = { f(); nudge++ }
    HoneycombTheme { Backdrop(Modifier.testTag("control-centre"), sheet = true, ground = HoneycombColors.sheet) {
        // a swipe up closes it once the list is at its end (the Space's table: sheetEnd)
        LaunchedEffect(scroll) { androidx.compose.runtime.snapshotFlow { scroll.value >= scroll.maxValue }.collect(onEnd) }
        // ControlGrid's numbers are watch points
        Box(Modifier.fillMaxSize()) {
            val pt = 1.wp
            Column(Modifier.fillMaxSize().softTopScrollFade(clearTop = cutoutTopInset, feather = 16.dp).verticalScroll(scroll).padding(top = statusBarInset, bottom = scrollBottomInset), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(pt * ControlGrid.CAPSULE_TOP))
                BatteryCapsule(now, pt)
                Spacer(Modifier.height(pt * ControlGrid.CAPSULE_GAP))
                ControlGrid.cells.groupBy { it.row }.toSortedMap().values.forEachIndexed { i, row ->
                    if (i > 0) Spacer(Modifier.height(pt * ControlGrid.GAP_Y))
                    Row(horizontalArrangement = Arrangement.spacedBy(pt * ControlGrid.GAP_X)) {
                        row.sortedBy { it.col }.forEach { c ->
                            Control(c.id, now, Modifier.size(pt * ControlGrid.BUTTON_W, pt * ControlGrid.BUTTON_H), pt, onHoldQuiet, ::act)
                        }
                    }
                }
                val ctx = LocalContext.current
                val updateState by AppUpdater.state.collectAsState()
                val availableUpdate = when (val s = updateState) {
                    is UpdateState.Available -> s.release
                    is UpdateState.ReadyToInstall -> s.release
                    else -> null
                }
                Spacer(Modifier.height(pt * ControlGrid.GAP_Y))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleButton(
                        text = "Block",
                        onClick = { ctx.startActivity(Intent(ctx, FeedsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        modifier = Modifier.testTag("control-feeds"),
                        icon = Symbols.block,
                        height = 40.dp,
                    )
                    if (availableUpdate != null) {
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
                            height = 40.dp,
                        )
                    } else {
                        CapsuleButton(
                            text = "Settings",
                            onClick = {
                                ctx.startActivity(
                                    Intent(ctx, HoneycombSettingsActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            modifier = Modifier.testTag("control-settings"),
                            icon = Symbols.settings,
                            height = 40.dp,
                        )
                    }
                }
                Spacer(Modifier.height(pt * ControlGrid.CAPSULE_TOP))
            }
        }
    } }
}

/**
 * The watch's status capsule: 78 x 21 wp, centred, holding the glyphs that apply (the green bolt while charging, the
 * Quiet moon while Quiet is on). The watch's phone glyph means "connected to its iPhone", which a phone never is.
 */
@Composable
private fun BatteryCapsule(t: Toggles, pt: androidx.compose.ui.unit.Dp) {
    val said = buildString { append("battery ${t.battery} percent"); if (t.charging) append(", charging"); if (t.quiet) append(", Quiet on") }
    Row(
        Modifier.size(pt * ControlGrid.CAPSULE_W, pt * ControlGrid.CAPSULE_H).controlSurface(HoneycombColors.button, status = true)
            .testTag("status").semantics { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(pt * 4, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.charging) Glyph(Bolt, HoneycombColors.accent, pt * ControlGrid.CAPSULE_GLYPH, Modifier.testTag("status-charging"))
        if (t.quiet) Glyph(Symbols.moon, HoneycombColors.accent, pt * ControlGrid.CAPSULE_GLYPH, Modifier.testTag("status-quiet"))
    }
}

/** One control: a glyph-only WatchButton, its tint filling it when on. */
@Composable
private fun Control(id: String, t: Toggles, m: Modifier, pt: androidx.compose.ui.unit.Dp, onHoldQuiet: () -> Unit, act: (() -> Unit) -> () -> Unit) {
    val ctx = LocalContext.current
    when (id) {
        "bluetooth" -> WatchButton(Symbols.bluetooth, said("Bluetooth", t.bluetooth), act { Device.open(ctx, Settings.ACTION_BLUETOOTH_SETTINGS) },
            m.testTag("tile-bluetooth"), on = t.bluetooth)
        "wifi" -> WatchButton(Symbols.wifi, said("Wi-Fi", t.wifi), act { Device.open(ctx, Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS) },
            m.testTag("tile-wifi"), on = t.wifi)
        "airplane" -> WatchButton(Symbols.airplane, said("Airplane mode", t.airplane), act { Device.open(ctx, Settings.ACTION_AIRPLANE_MODE_SETTINGS) },
            m.testTag("tile-airplane"), on = t.airplane)
        "battery" -> WatchButton(Symbols.batteryFull, "Battery, ${t.battery} percent", { Device.openBattery(ctx) }, m.testTag("tile-battery"),
            label = "${t.battery}%")
        "silent" -> WatchButton(if (t.silent) Symbols.bellSlash else Symbols.bell, said("Silent", t.silent), act { Device.setSilent(ctx, !t.silent) },
            m.testTag("tile-silent"), on = t.silent)
        // the watch's flashlight fills white when on, its glyph dark: the dark glyph is drawn over the button's own
        "flashlight" -> Box(m, contentAlignment = Alignment.Center) {
            WatchButton(if (t.torch) Symbols.flashlightOn else Symbols.flashlightOff, said("Flashlight", t.torch),
                act { Device.setTorch(ctx, !t.torch) }, Modifier.fillMaxSize().testTag("tile-flashlight"), on = t.torch)
            if (t.torch) Glyph(Symbols.flashlightOn, HoneycombColors.onAccent, FLASHLIGHT_GLYPH.wp)
        }
        "quiet" -> WatchButton(Symbols.moon, said("Quiet", t.quiet), act { Quiet.set(ctx, !t.quiet) }, m.testTag("tile-quiet"),
            on = t.quiet, onLongClick = onHoldQuiet)
        "rotation" -> WatchButton(Symbols.rotationLock, said("Rotation lock", t.rotationLock), act { Device.setRotationLock(ctx, !t.rotationLock) },
            m.testTag("tile-rotation"), on = t.rotationLock)
        "volume" -> WatchButton(Symbols.volume, "Volume", { Device.openVolume(ctx) }, m.testTag("tile-volume"))
        "output" -> WatchButton(Symbols.output, "Audio output", { openOutput(ctx) }, m.testTag("tile-output"))
        "brightness" -> WatchButton(Symbols.sun, "Brightness", { Levels.openBrightness(ctx) }, m.testTag("tile-brightness"))
        "timer" -> WatchButton(Symbols.timer, "Timer", { Device.openTimer(ctx) }, m.testTag("tile-timer"))
        "calculator" -> WatchButton(Symbols.calculator, "Calculator", { Device.openCalculator(ctx) }, m.testTag("tile-calculator"))
        "camera" -> WatchButton(Symbols.camera, "Camera", { Device.openCamera(ctx) }, m.testTag("tile-camera"))
        "mirroring" -> WatchButton(Symbols.mirroring, "Screen mirroring", { Device.openMirroring(ctx) }, m.testTag("tile-mirroring"))
        "hotspot" -> WatchButton(Symbols.hotspot, said("Hotspot", t.hotspot),
            act { Device.open(ctx, "android.settings.TETHER_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS) },
            m.testTag("tile-hotspot"), on = t.hotspot)
    }
}

/** WatchButton's own glyph size, wp, so the flashlight's dark glyph covers its white one exactly. */
private const val FLASHLIGHT_GLYPH = 26f

// ---- A7: Quiet ----------------------------------------------------------------------------------------------------

/**
 * Quiet's own screen (touch and hold on the Quiet pill): Back and the time, the title and state, and one setting on a platter, on
 * the same sheet backdrop as Control Centre.
 */
@Composable
fun QuietScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val t = rememberToggles()
    var mode by remember { mutableStateOf(Quiet.callMode(ctx)) }
    var repeat by remember { mutableStateOf(Quiet.repeatCallers(ctx)) }
    val body = HoneycombType.body.copy(color = HoneycombColors.secondaryLabel)
    HoneycombTheme { Backdrop(Modifier.testTag("quiet"), sheet = true) {
        // the watch's own insets (31-settings-notifications): Back and text 16.5 pt from the screen's left, the time 18 pt
        // from its right, the platter 2.5 pt from each side with its text 14 pt in
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = scrollTopInset, bottom = scrollBottomInset)) {
            Row(Modifier.fillMaxWidth().padding(start = QuietInset.text, end = QuietInset.time), verticalAlignment = Alignment.CenterVertically) {
                CircleButton(Symbols.back, "Back", onBack, Modifier.testTag("quiet-back"))
                Spacer(Modifier.weight(1f))
                // a watchOS page header: Back at the left, the time at the top right; battery shows only in Control Centre
                BasicText(clock(System.currentTimeMillis()), Modifier.align(Alignment.Top).testTag("quiet-time"), style = HoneycombType.time)
            }
            Spacer(Modifier.height(Space.s))
            BasicText("Quiet", Modifier.padding(horizontal = QuietInset.text), style = HoneycombType.largeTitle)
            Spacer(Modifier.height(Space.s))
            val (state, calls) = Quiet.words(t.quiet, mode, repeat)
            BasicText(state, Modifier.padding(horizontal = QuietInset.text).testTag("quiet-state"), style = body)
            Spacer(Modifier.height(Space.m))
            BasicText("Allow calls from", Modifier.padding(horizontal = QuietInset.text), style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel))
            Spacer(Modifier.height(Space.xs))
            Platter(Modifier.fillMaxWidth().padding(horizontal = QuietInset.platter).testTag("quiet-contacts"), padding = 0.dp) {
                Column(Modifier.fillMaxWidth()) {
                    Quiet.CallMode.entries.forEachIndexed { idx, option ->
                        if (idx > 0) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = QuietInset.row).height(1.dp).background(HoneycombColors.outline))
                        }
                        val selected = mode == option
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (Quiet.setCallMode(ctx, option)) mode = option }
                                .sizeIn(minHeight = ControlSize.minHitArea)
                                .padding(horizontal = QuietInset.row, vertical = Space.s)
                                .testTag("quiet-mode-${option.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(option.label, Modifier.weight(1f), style = HoneycombType.body)
                            if (selected) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(HoneycombColors.accent))
                            }
                        }
                    }
                }
            }
            if (mode != Quiet.CallMode.EVERYONE) {
                Spacer(Modifier.height(Space.s))
                Platter(Modifier.fillMaxWidth().padding(horizontal = QuietInset.platter).testTag("quiet-repeat"), padding = 0.dp) {
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(repeat, role = Role.Switch, onValueChange = {
                                if (Quiet.setRepeatCallers(ctx, it)) repeat = it
                            })
                            .sizeIn(minHeight = ControlSize.minHitArea)
                            .padding(horizontal = QuietInset.row, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText("Repeat callers", style = HoneycombType.body)
                            BasicText("2nd call within 15 min rings", style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel))
                        }
                        Spacer(Modifier.size(Space.s))
                        NativeSwitch(checked = repeat)
                    }
                }
            }
            Spacer(Modifier.height(Space.m))
            BasicText(calls, Modifier.padding(horizontal = QuietInset.text), style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel))
        }
    } }
}

/** A watchOS settings page's insets, measured in 31-settings-notifications (422 px = 211 pt). */
private object QuietInset {
    val text: Dp get() = 16.5f.wp
    val time: Dp get() = 18.wp
    val platter: Dp get() = 2.5f.wp
    val row: Dp get() = 14.wp
}

