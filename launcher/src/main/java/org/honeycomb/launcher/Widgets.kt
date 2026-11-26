package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText

/**
 * Real Android widgets in the Smart Stack, the way a launcher hosts them (developer.android.com/develop/ui/views/appwidgets/host):
 * one AppWidgetHost, the bound ids kept in prefs, listening while the activity is started.
 */
/** Custom AppWidgetHostView with zero outer padding so widgets render edge-to-edge inside Smart Stack cards. */
class HoneycombWidgetHostView(context: Context) : AppWidgetHostView(context) {
    init {
        setPadding(0, 0, 0, 0)
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
    }
}

object Widgets {
    private const val HOST_ID = 7041
    private const val PREFS = "phone"
    private var host: AppWidgetHost? = null
    val changes = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun host(ctx: Context): AppWidgetHost = host ?: object : AppWidgetHost(ctx.applicationContext, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            HoneycombWidgetHostView(context)
    }.also { host = it }

    fun ids(ctx: Context): List<Int> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("widgets", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    /** Optional user-chosen portrait row span (1..4) for widget [id], or null to use the widget's declared cell height. */
    fun customRows(ctx: Context, id: Int): Int? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("widget_rows_$id", 0).takeIf { it in 1..4 }

    fun setCustomRows(ctx: Context, id: Int, rows: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("widget_rows_$id", rows.coerceIn(1, 4)).apply()
        changes.value++
    }

    fun add(ctx: Context, id: Int) = save(ctx, ids(ctx) + id)

    /** Moves widget [id] [by] places in his order. */
    fun move(ctx: Context, id: Int, by: Int) {
        val l = ids(ctx).toMutableList(); val i = l.indexOf(id); if (i < 0) return
        val j = (i + by).coerceIn(0, l.size - 1); l.removeAt(i); l.add(j, id); save(ctx, l)
    }

    /**
     * Takes out the widgets the stack once added on its own (the phone's Calendar, Clock and Weather, seeded on first run
     * until wl-live-stack), recorded by provider in "stack_seed_offered", then forgets every seeding pref. Runs once.
     */
    fun dropSeeded(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seeded = p.getStringSet("stack_seed_offered", null) ?: return
        val mgr = AppWidgetManager.getInstance(ctx)
        ids(ctx).filter { mgr.getAppWidgetInfo(it)?.provider?.flattenToString() in seeded }.forEach { discard(ctx, it) }
        p.edit().remove("stack_seed_offered").remove("stack_seed_done").remove("stack_seeded").apply()
    }

    /** A widget that never got bound or configured gives its id back. */
    fun discard(ctx: Context, id: Int) {
        host(ctx).deleteAppWidgetId(id)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("widget_rows_$id").apply()
        save(ctx, ids(ctx) - id)
    }

    private fun save(ctx: Context, ids: List<Int>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("widgets", ids.distinct().joinToString(",")).apply()
        changes.value++
    }
}

private class Adding { val queue = ArrayDeque<AppWidgetProviderInfo>(); var pending: Int? = null; var bound: ((Boolean) -> Unit)? = null }

/**
 * Adds widgets one at a time, the way a launcher binds them (Lawnchair's and Launcher3's flow): allocate an id, bind it
 * (bindAppWidgetIdIfAllowed, else the system's ACTION_APPWIDGET_BIND prompt), then run the widget's configure activity
 * when it has one. A widget that is refused or not configured gives its id back.
 */
@Composable
fun rememberWidgetAdder(): (List<AppWidgetProviderInfo>) -> Unit {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val mgr = remember { AppWidgetManager.getInstance(ctx) }
    val a = remember { Adding() }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> a.bound?.invoke(r.resultCode == Activity.RESULT_OK) }

    fun next() {
        if (a.pending != null) return
        val p = a.queue.removeFirstOrNull() ?: return
        val id = Widgets.host(ctx).allocateAppWidgetId()
        a.pending = id
        fun added() { Widgets.add(ctx, id); a.pending = null; next() }
        fun dropped() { Widgets.discard(ctx, id); a.pending = null; next() }
        fun configureOrAdd() {
            val info = mgr.getAppWidgetInfo(id) ?: return dropped()
            val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
            if (info.configure == null || optional) return added()
            WidgetResults.onConfigure = { ok -> WidgetResults.onConfigure = null; if (ok) added() else dropped() }
            // The host starts the configure activity itself, so it also works for providers that don't export it.
            runCatching { Widgets.host(ctx).startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null) }
                .onFailure { WidgetResults.onConfigure = null; dropped() }
        }
        // Cancel on the bind prompt drops the rest of the queue too, so one refusal is never followed by another prompt.
        a.bound = { ok -> a.bound = null; if (ok) configureOrAdd() else { a.queue.clear(); dropped() } }
        if (mgr.bindAppWidgetIdIfAllowed(id, p.provider)) { a.bound = null; configureOrAdd() }
        else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, p.provider)
        })
    }
    return { list -> a.queue.addAll(list); next() }
}

/** The configure activity's result comes back through the activity (AppWidgetHost.startAppWidgetConfigureActivityForResult). */
object WidgetResults {
    var onConfigure: ((Boolean) -> Unit)? = null
}

const val REQUEST_CONFIGURE = 7042

/** Every installed widget, by app and name. */
@Composable
fun WidgetPicker(mgr: AppWidgetManager, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val providers = remember { mgr.installedProviders.sortedBy { it.loadLabel(ctx.packageManager).lowercase() } }
    Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(decorFitsSystemWindows = false)) { ProvideScale {
        Platter(padding = 0.dp) { Column(Modifier.padding(top = Space.m)) {
            BasicText("Add a widget", Modifier.padding(start = Space.l, bottom = Space.s),
                style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(providers) { i, p ->
                    val icon = remember(p) { runCatching { p.loadIcon(ctx, ctx.resources.displayMetrics.densityDpi)?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull() }
                    Row(p.loadLabel(ctx.packageManager), leading = {
                        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.wp)) }
                    }, subtitle = appLabel(ctx, p.provider.packageName), onClick = { onPick(p) })
                }
            }
        } }
    } }
}

private fun appLabel(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
