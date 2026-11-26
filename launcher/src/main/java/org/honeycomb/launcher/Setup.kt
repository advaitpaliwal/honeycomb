package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import org.honeycomb.launcher.design.Space

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * First-run setup: every role and permission Honeycomb uses, one per screen, each through Android's own prompt or Settings
 * page, the way open-source launchers onboard (Olauncher asks for the home role with RoleManager and sends usage access
 * to its Settings page).
 */
enum class Step(val title: String, val body: String, val button: String) {
    Home("Make Honeycomb\nyour home", "Going home always brings you back to Honeycomb.", "Set as home"),
    Navigation("Go home", "Swipe up on the line to go home: turn on gesture navigation", "Open settings"),
    Notifications("See your\nnotifications", "Honeycomb shows your notifications and what is playing.", "Turn on"),
    Quiet("Quiet", "Quiet will be on: only calls from your contacts and alarms ring. Turn it off any time in Control Centre.", "Turn on"),
    Usage("Your apps", "Usage access lets Honeycomb show the apps you use most and the ones you opened last.", "Turn on"),
    Brightness("Brightness", "Lets the brightness slider in Control Centre set your screen's brightness.", "Turn on"),
}

/**
 * Android 13 and later block notification access (and other "restricted settings") for an app installed from a file or
 * a browser until the person allows it in the app's App info (support.google.com/android/answer/12623953). This screen
 * sends them there, with the words to tap; back from App info, setup returns to the step that was blocked.
 */
object Restricted {
    const val TITLE = "Allow restricted\nsettings"
    const val BODY = "Android blocked this because Honeycomb was installed from a file. In App info, tap \u22EE \u203A Allow restricted settings, then come back and turn it on."
    const val BUTTON = "Open App info"

    /** The steps whose Settings toggle Android greys out for a restricted app. */
    val steps = setOf(Step.Notifications)

    /**
     * Android blocks these toggles for an app installed from a local or downloaded file (the package installer records
     * the source) until "Allow restricted settings" is tapped in its App info. Whether that was tapped is an app op only
     * the system may read (checking it raw throws SecurityException on Android 16), and App info offers the menu item
     * only after Android has refused the toggle once. So setup sends the person to the Settings page first, and shows
     * this screen when a sideloaded install comes back from it still without access.
     */
    fun sideloaded(source: Int): Boolean =
        source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE

    fun sideloaded(ctx: Context): Boolean =
        runCatching { sideloaded(ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource) }.getOrDefault(false)

    fun appInfo(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
}

/** Which screen comes next: the first step in order that is neither granted nor already passed (skipped or answered). */
object SetupFlow {
    fun next(granted: Set<Step>, passed: Set<Step>): Step? = Step.entries.firstOrNull { it !in granted && it !in passed }

    /** First run shows only when setup was never finished and something is still missing. */
    fun shows(done: Boolean, granted: Set<Step>): Boolean = !done && next(granted, emptySet()) != null
}

object Setup {
    private const val PREFS = "setup"

    fun done(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("done", false)
    fun finish(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("done", true).apply()

    /** Opens setup on first run; once it is finished it never opens by itself again. */
    fun firstRun(ctx: Context) {
        if (done(ctx)) return
        // its own task (SetupActivity's taskAffinity), so the home gesture always reaches the face, never setup
        if (SetupFlow.shows(false, granted(ctx))) ctx.startActivity(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else finish(ctx)
    }

    internal fun usesGestureNavigation(mode: Int) = mode == 2

    fun granted(ctx: Context): Set<Step> = Step.entries.filter { isGranted(ctx, it) }.toSet()

    private fun isGranted(ctx: Context, step: Step): Boolean = when (step) {
        Step.Home -> ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
        Step.Navigation -> usesGestureNavigation(Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", -1))
        Step.Notifications -> ctx.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(ctx, MediaListener::class.java))
        Step.Quiet -> Quiet.granted(ctx) && Quiet.chosen(ctx)
        Step.Usage -> ctx.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        Step.Brightness -> Settings.System.canWrite(ctx)
    }

    fun openSettings(ctx: Context, step: Step) {
        settings(ctx, step).firstOrNull { runCatching { ctx.startActivity(it) }.isSuccess }
    }

    /** The system screen that grants a Settings step: the closest page Android offers, then its general list. */
    fun settings(ctx: Context, step: Step): List<Intent> {
        val pkg = Uri.fromParts("package", ctx.packageName, null)
        return when (step) {
            Step.Home -> listOf(Intent(Settings.ACTION_HOME_SETTINGS))
            Step.Navigation -> listOf(Intent("com.android.settings.NAVIGATION_MODE_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
            Step.Notifications -> listOf(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, MediaListener::class.java).flattenToString()),
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            Step.Quiet -> listOf(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            Step.Usage -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkg), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Step.Brightness -> listOf(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg))
        }
    }
}

/**
 * The setup screens. Opened on first run (LauncherActivity) and again from "Run setup again" in Honeycomb's settings entry
 * in Android's app info (HoneycombSettingsActivity, APPLICATION_PREFERENCES). Granted steps never show; the last screen
 * marks setup finished.
 */
class SetupActivity : ComponentActivity() {
    private val passed = mutableSetOf<Step>()
    private var step by mutableStateOf<Step?>(null)
    /** The step Android blocks as a restricted setting; while set, the Allow restricted settings screen shows. */
    private var blocked by mutableStateOf<Step?>(null)
    /** The Allow restricted settings screen was shown; it shows once, then its step's button goes to Settings. */
    private var told = false
    /** The restricted step whose Settings page is open; back without access, Android blocked it. */
    private var tried: Step? = null
    /** The person pressed Turn on at the Quiet step: once access is granted, Quiet goes on (never before that screen). */
    private var quietAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList("passed")?.let { names -> passed += Step.entries.filter { it.name in names } }
        quietAsked = savedInstanceState?.getBoolean("quietAsked") ?: false
        told = savedInstanceState?.getBoolean("told") ?: false
        refresh()
        // nothing missing on first run: straight home; a re-run still says so
        if (step == null && intent?.action != Intent.ACTION_APPLICATION_PREFERENCES && savedInstanceState == null) {
            Setup.finish(this); finish(); return
        }
        fullScreen {
            val s = step
            val b = blocked
            if (b != null) SetupScreen(Restricted.TITLE, Restricted.BODY, Restricted.BUTTON, skip = { told = true; blocked = null }) {
                told = true
                runCatching { startActivity(Restricted.appInfo(this)) }
            }
            else if (s == null) SetupScreen("You're\nall set", "Go home any time to come back here.", "Done", skip = null) { Setup.finish(this); finish() }
            else SetupScreen(s.title, s.body, s.button, skip = { pass(s) }) { act(s) }
        }
    }

    /** Setup itself opened a Settings page or a role prompt; leaving for it is not leaving setup. */
    private var opening = false

    @Deprecated("Every start goes through here, so setup can tell its own Settings pages from Home")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        opening = true
        @Suppress("DEPRECATION") super.startActivityForResult(intent, requestCode, options)
    }

    /** Home (or Recents) while setup shows: setup is seen, and goes. Marked now, before a cold home's first run reads it. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!opening) { Setup.finish(this); finish() }
    }

    /** However setup ends (Done, Back, Home), it never opens by itself again. */
    override fun onDestroy() {
        if (isFinishing) Setup.finish(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("passed", ArrayList(passed.map { it.name }))
        outState.putBoolean("quietAsked", quietAsked)
        outState.putBoolean("told", told)
    }

    /** Back from a Settings page or a prompt: whatever was granted moves on. */
    override fun onResume() {
        super.onResume()
        opening = false
        refresh()
    }

    private fun refresh() {
        if (quietAsked && Quiet.choose(this)) quietAsked = false
        if (blocked != null && told) blocked = null // back from App info: the step again, its button now to Settings
        tried?.let { t -> tried = null; if (!told && t !in Setup.granted(this) && Restricted.sideloaded(this)) blocked = t }
        step = SetupFlow.next(Setup.granted(this), passed)
    }

    private fun pass(s: Step) { passed += s; refresh() }

    private fun act(s: Step) {
        if (s == Step.Home) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION") startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), s.ordinal)
                return
            }
        }
        if (s in Restricted.steps) tried = s
        if (s == Step.Quiet) { if (Quiet.choose(this)) { refresh(); return }; quietAsked = true }
        Setup.openSettings(this, s)
    }

    @Deprecated("The role request uses the platform result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refresh()
    }
}

/**
 * The status bar hidden and one layout unit per dp, as on the home. The navigation bar stays shown and transparent, as on
 * the home: hidden, the first swipe up only reveals it, so the home gesture would never leave setup.
 */
private fun ComponentActivity.fullScreen(content: @Composable () -> Unit) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.statusBars())
        show(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    window.isNavigationBarContrastEnforced = false
    setContent { org.honeycomb.launcher.design.ProvideScale { content() } }
}

/** One watchOS-style setup screen: big title, a line of text, one button, and Skip under it. */
@Composable
private fun SetupScreen(title: String, body: String, button: String, skip: (() -> Unit)?, onButton: () -> Unit) = HoneycombTheme {
    OnboardingPage(title, body) {
        CapsuleButton(button, onButton, Modifier.fillMaxWidth(), filled = true)
        if (skip != null) CapsuleButton("Skip", skip, Modifier.fillMaxWidth())
    }
}

/** The setup-style screen frame: black, inside the safe area, the watch's 16 wp list inset at the sides and [Space.xl] top and bottom. */
@Composable
internal fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxSize().background(HoneycombColors.background).safeDrawingPadding()) {
        Box(Modifier.fillMaxSize().padding(horizontal = Space.l, vertical = Space.xl), content = content)
    }

/**
 * One onboarding page: a large title and a line of body text at the top, and the actions ([actions]: one
 * [CapsuleButton], then at most one more) at the bottom.
 */
@Composable
internal fun OnboardingPage(title: String, body: String, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit) =
    ScreenFrame(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            key(title, body) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // ProvideScale lays its content in a Box, so the title and the explanation need their own Column to stack.
                    ProvideScale {
                        Column(Modifier.fillMaxWidth()) {
                            BasicText(title, style = HoneycombType.largeTitle)
                            Spacer(Modifier.height(Space.m))
                            BasicText(body, style = HoneycombType.body.copy(color = HoneycombColors.secondaryLabel))
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s),
                horizontalAlignment = Alignment.CenterHorizontally, content = actions)
        }
    }
