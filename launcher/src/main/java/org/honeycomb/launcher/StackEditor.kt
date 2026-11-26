package org.honeycomb.launcher

import org.honeycomb.launcher.design.*
import org.honeycomb.launcher.design.Space

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The Smart Stack's Edit, as watchOS has it: his widgets in their order, each with up, down and remove, and Add widget
 * at the bottom, which opens the system's own list of installed widgets. Every change is saved at once.
 */
@Composable
fun StackEditor(widgets: List<Int>, onAdd: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val mgr = remember { AppWidgetManager.getInstance(ctx) }
    var picking by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { ProvideScale {
        BoxWithConstraints(Modifier.fillMaxSize().background(HoneycombColors.background).statusBarsPadding().navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter) {
            val margin = windowMargin(maxWidth.value).dp
            Column(Modifier.padding(horizontal = margin).widthIn(max = Wp.MAX_CONTENT_DP.dp).fillMaxSize().padding(vertical = Space.l)
                .testTag("stack-editor"), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Edit Smart Stack", Modifier.weight(1f), style = HoneycombType.title)
                    BasicText("Done", Modifier.testTag("stack-done").heightIn(min = ControlSize.minHitArea)
                        .clickable(role = Role.Button, onClick = onClose).padding(horizontal = Space.s).wrapContentHeight(),
                        style = HoneycombType.headline.copy(color = HoneycombColors.accent))
                }
                BasicText("Live Updates and what is playing always come first.", style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel))
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(RowGap)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(RowGap)) {
                        itemsIndexed(widgets, key = { _, id -> id }) { i, id ->
                            val info = mgr.getAppWidgetInfo(id) ?: return@itemsIndexed
                            val name = info.loadLabel(ctx.packageManager)
                            val icon = remember(id) { runCatching { info.loadIcon(ctx, 0)?.toBitmap()?.asImageBitmap() }.getOrNull() }
                            val realDensity = ctx.resources.displayMetrics.density
                            val curRows = Widgets.customRows(ctx, id) ?: defaultWidgetRows(info.targetCellHeight, info.minHeight, realDensity)
                            Row(name, Modifier.testTag("edit-widget-$id"), subtitle = "${appName(ctx, info.provider.packageName)} · ${curRows}x height",
                                leading = icon?.let { { Image(it, null, Modifier.fillMaxSize()) } },
                                below = {
                                    CircleButton(Symbols.arrowUp, "Move $name up", { Widgets.move(ctx, id, -1) },
                                        size = 36.dp, tint = HoneycombColors.button, enabled = widgets.first() != id)
                                    CircleButton(Symbols.arrowDown, "Move $name down", { Widgets.move(ctx, id, 1) },
                                        size = 36.dp, tint = HoneycombColors.button, enabled = widgets.last() != id)
                                    CapsuleButton(
                                        text = if (curRows >= 4) "1x" else "${curRows + 1}x",
                                        onClick = { Widgets.setCustomRows(ctx, id, if (curRows >= 4) 1 else curRows + 1) },
                                        height = 36.dp,
                                        sidePad = 12.dp,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    CircleButton(Symbols.close, "Remove $name", { Widgets.discard(ctx, id) },
                                        Modifier.testTag("remove-$id"), size = 36.dp, tint = HoneycombColors.destructive)
                                })
                        }
                    }
                }
                Row("Add widget", Modifier.heightIn(min = LocalViewConfiguration.current.minimumTouchTargetSize.height)
                    .testTag("stack-add"), icon = Symbols.add, onClick = { picking = true })
            }
        }
        if (picking) WidgetPicker(mgr, onPick = { picking = false; onAdd(it) }, onClose = { picking = false })
    } }
}

private fun appName(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
