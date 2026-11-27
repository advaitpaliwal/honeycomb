package org.honeycomb.launcher.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.honeycomb.launcher.LocalScreen

/*
 * Honeycomb's components (docs/design-language.md, Shapes and components): the watch's structure (a card, the Control
 * Centre tile, the circle, the capsule, the list row and the status line) in Material 3 Expressive's colour roles and
 * corner scale. Flat tonal surfaces, no rims or gloss. Nothing else draws a surface. Every tap target keeps at least 44 x 44.
 */

/** The share of a control a glyph fills. */
private const val GLYPH_FRACTION = 0.5f

/** The opacity of a control that cannot act right now. */
private const val DISABLED_ALPHA = 0.3f

/** A flat surface: [color] in [shape]. [rim] is kept for callers and draws nothing: Material surfaces have no edge light. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.surface(color: Color, shape: Shape, rim: Boolean = true): Modifier = this.clip(shape).background(color, shape)

/** A Control Centre tile's surface: [fill] in a [Radius.button] rounded rectangle; the battery status is a capsule. */
fun Modifier.controlSurface(fill: Color, status: Boolean = false): Modifier =
    if (status) clip(Capsule).background(fill) else clip(rounded(Radius.button)).background(fill)

/** A tap, a touch and hold, and (with [on] set) a switch's semantics. */
private fun Modifier.tap(on: Boolean?, onClick: () -> Unit, onLongClick: (() -> Unit)?, interaction: MutableInteractionSource) =
    if (on == null) this.combinedClickable(interaction, null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
    else this.combinedClickable(interaction, null, role = Role.Switch, onLongClick = onLongClick, onClick = onClick)
        .semantics { toggleableState = ToggleableState(on) }

/** A plain button's semantics for a control that may be [enabled] or not. */
private fun Modifier.action(contentDescription: String, enabled: Boolean, onClick: () -> Unit) = this
    .alpha(if (enabled) 1f else DISABLED_ALPHA)
    .clearAndSetSemantics {
        role = Role.Button
        this.contentDescription = contentDescription
        if (!enabled) disabled()
        onClick { if (enabled) onClick(); enabled }
    }

/** A card: surfaceContainerHigh, extraLarge corners, 12 wp padding inside. */
@Composable
fun Platter(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = PlatterShape,
    padding: Dp = 12.wp,
    rim: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) = Box(modifier.surface(HoneycombColors.platter, shape, rim).padding(padding), content = content)

/**
 * A Control Centre tile: a tonal 86 x 58 wp rounded rectangle (largeIncreased corners), the glyph centred. With [on] set
 * it is a toggle: surfaceContainerHighest when off, [tint] (primary) when on, its glyph in the matching on-colour.
 * [label] replaces the glyph with text (the battery's "100%").
 */
@Composable
fun WatchButton(
    icon: Icon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean? = null,
    tint: Color = HoneycombColors.accent,
    onLongClick: (() -> Unit)? = null,
    label: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill = if (on == true) tint else HoneycombColors.button
    val ink = HoneycombColors.contentFor(fill)
    Box(
        modifier.sizeIn(minWidth = ControlSize.minHitArea, minHeight = ControlSize.minHitArea)
            .controlSurface(fill)
            .tap(on, onClick, onLongClick, interaction).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) BasicText(label, style = HoneycombType.headline.copy(color = ink), maxLines = 1)
        else Glyph(icon, ink, 26.wp)
    }
}

/** The size of a [WatchButton] (34-control-centre-top: 86 x 58 pt). */
object WatchButtonSize {
    val width: Dp get() = 86.wp
    val height: Dp get() = 58.wp
}

/**
 * A tonal circle (surfaceContainerHighest), 36 wp, its glyph centred: back, x, bell, previous, play, next. [tint] fills
 * it instead (a remove button in errorContainer); not [enabled], it is dimmed and inert. With [on] set it is a toggle
 * filled with [tint] (primary) when on. The glyph takes the fill's on-colour.
 */
@Composable
fun CircleButton(
    icon: Icon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.wp,
    tint: Color? = null,
    enabled: Boolean = true,
    on: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill = when {
        on == true -> tint ?: HoneycombColors.accent
        on == null && tint != null -> tint
        else -> HoneycombColors.circle
    }
    val tap = if (on != null) Modifier.tap(on, onClick, onLongClick, interaction).semantics { this.contentDescription = contentDescription }
    else Modifier.tap(null, { if (enabled) onClick() }, onLongClick, interaction).action(contentDescription, enabled, onClick)
    Box(modifier.sizeIn(minWidth = ControlSize.minHitArea, minHeight = ControlSize.minHitArea).then(tap), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).surface(fill, Circle), contentAlignment = Alignment.Center) {
            Glyph(icon, HoneycombColors.contentFor(fill), size * GLYPH_FRACTION)
        }
    }
}

/** Crisp phone-proportioned label style for pill buttons (~16 real sp inside a ~46 real dp capsule). */
private val CapsuleLabelStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )

/**
 * A tonal capsule (secondaryContainer) with crisp, balanced button typography (~16 real sp inside a ~46 real dp pill).
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: Icon? = null,
    on: Boolean? = null,
    tint: Color = HoneycombColors.accent,
    onLongClick: (() -> Unit)? = null,
    height: Dp = 40.dp,
    minWidth: Dp = 0.dp,
    style: TextStyle = CapsuleLabelStyle,
    iconSize: Dp = 16.dp,
    sidePad: Dp = 18.dp,
    filled: Boolean = false,
) {
    if (onClick == null) { Box(modifier.heightIn(min = ControlSize.minHitArea).height(height)); return }
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier.sizeIn(minWidth = ControlSize.minHitArea, minHeight = ControlSize.minHitArea).tap(on, onClick, onLongClick, interaction),
        contentAlignment = Alignment.Center,
    ) {
        val width = if (constraints.hasFixedWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = minWidth)
        val fill = if (on == true || filled) tint else HoneycombColors.tonal
        val ink = HoneycombColors.contentFor(fill)
        androidx.compose.foundation.layout.Row(
            width.height(height).surface(fill, Capsule).padding(horizontal = sidePad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Glyph(icon, ink, iconSize)
            BasicText(text, style = style.copy(color = ink), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A list row: a [Platter] holding a 28 wp tinted icon disc (or the [leading] image) and a body label, with an
 * optional [subtitle] and [trailing] controls. Settings rows sit on black, so they have no rim. Controls too wide to
 * sit beside the label go in [below], a line of their own under it inside the same platter, so the label keeps the
 * row's full width (three 44 wp controls beside it leave a 211 wp screen's label one letter).
 */
@Composable
fun Row(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Icon? = null,
    iconTint: Color = HoneycombColors.tonal,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    below: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier.fillMaxWidth().heightIn(min = 52.dp).surface(HoneycombColors.platter, PlatterShape, rim = false)
            .then(tap).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                leading != null -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
                icon != null -> Box(Modifier.size(36.dp).surface(iconTint, Circle, rim = false), contentAlignment = Alignment.Center) {
                    Glyph(icon, HoneycombColors.contentFor(iconTint), 20.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(title, style = HoneycombType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) BasicText(subtitle, style = HoneycombType.footnote.copy(color = HoneycombColors.secondaryLabel),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
        }
        if (below != null) androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp), content = below,
        )
    }
}

/** The gap between rows and between cards. */
val RowGap: Dp get() = 8.dp

/**
 * The status line: the time at the top right in [HoneycombType.time] when given, and the battery as a small capsule
 * centred (Control Centre), with the Quiet moon when on.
 */
@Composable
fun StatusLine(time: String?, battery: Int, quiet: Boolean, modifier: Modifier = Modifier) {
    val said = buildString { time?.let { append(it); append(", ") }; append("battery $battery percent"); if (quiet) append(", Quiet on") }
    Box(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = said }) {
        androidx.compose.foundation.layout.Row(
            Modifier.align(Alignment.Center).surface(HoneycombColors.button, Capsule).padding(horizontal = 12.wp, vertical = 4.wp),
            horizontalArrangement = Arrangement.spacedBy(4.wp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (quiet) Glyph(Symbols.moon, HoneycombColors.accent, 14.wp)
            BasicText("$battery%", style = HoneycombType.footnote.copy(color = if (battery <= 20) HoneycombColors.error else HoneycombColors.label))
        }
        if (time != null) BasicText(time, Modifier.align(Alignment.CenterEnd), style = HoneycombType.time)
    }
}

/**
 * Draw the screen ground edge to edge, behind the status bar, the cutout and the navigation bar:
 * [HoneycombColors.background], or [HoneycombColors.sheet] for a sheet over the face. Backgrounds and colour fields run
 * to every edge, as Android's edge-to-edge guidance and the HIG's safe areas ask; only content keeps clear of the bars
 * and the cutout (Faces.kt safe(), [scrollTopInset]), and only scrolling content fades before it ([fadeBeforeCutout]).
 */
@Composable
fun Backdrop(modifier: Modifier = Modifier, sheet: Boolean = false, ground: Color = if (sheet) HoneycombColors.sheet else HoneycombColors.background, content: @Composable BoxScope.() -> Unit) {
    val screen = LocalScreen.current
    BarsOver(ground)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.Center).requiredSize(screen.width.dp, screen.height.dp).background(ground))
        content()
    }
}

/**
 * The colours under the status bar and the navigation bar on the screen in front. Each screen says what it draws there;
 * the one composed last (the screen on top) decides, and when it leaves, the one under it decides again. The activity
 * turns that into light or dark bar icons (WindowInsetsController.setSystemBarsAppearance), so the bars' icons read on
 * whatever colour runs under them.
 */
object BarsUnder {
    internal class Entry(val top: Color, val bottom: Color)
    internal val stack = androidx.compose.runtime.mutableStateListOf<Pair<Any, Entry>>()

    /** The colours under the bars on the screen in front: (top, bottom), or null while no screen has said. */
    val front: Pair<Color, Color>? get() = stack.lastOrNull()?.second?.let { it.top to it.bottom }
}

/** This screen draws [top] under the status bar and [bottom] under the navigation bar (see [BarsUnder]). */
@Composable
fun BarsOver(top: Color, bottom: Color = top) {
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(key) {
        BarsUnder.stack.add(key to BarsUnder.Entry(top, bottom))
        onDispose { BarsUnder.stack.removeAll { it.first === key } }
    }
    androidx.compose.runtime.SideEffect {
        val i = BarsUnder.stack.indexOfFirst { it.first === key }
        if (i >= 0 && (BarsUnder.stack[i].second.top != top || BarsUnder.stack[i].second.bottom != bottom))
            BarsUnder.stack[i] = key to BarsUnder.Entry(top, bottom)
    }
}

/**
 * Full-bleed scrolling edge-to-edge under the camera cutout: no top fade mask or dark band
 * chopping off icons or erasing off-screen sliding sheets.
 */
@Composable
fun Modifier.fadeBeforeCutout(): Modifier = this

/** One action inside an [ActionMenu]: crisp glyph + 16 sp phone-proportioned label, optional destructive tint. */
data class MenuAction(
    val label: String,
    val icon: Icon,
    val tag: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Clean Material 3 / iOS-proportioned context menu card for phone screens: single flat platter (no nested cards),
 * compact app header with hairline divider, and crisp 48-unit (~55 real dp) action rows with 20 dp glyphs and 16 real sp
 * labels so items never wrap, truncate, or waste vertical space.
 */
@Composable
fun ActionMenu(
    title: String,
    icon: androidx.compose.ui.graphics.ImageBitmap,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    val titleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val itemStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    Column(
        modifier
            .fillMaxWidth()
            .surface(HoneycombColors.platter, rounded(NativeShapes.largeIncreased), rim = false)
            .padding(vertical = 6.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.Image(icon, null, Modifier.size(26.dp))
            BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HoneycombColors.outline.copy(alpha = 0.16f)))
        actions.forEach { a ->
            val ink = if (a.destructive) HoneycombColors.error else HoneycombColors.label
            androidx.compose.foundation.layout.Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = a.onClick)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .testTag(a.tag)
                    .semantics { contentDescription = a.label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Glyph(a.icon, ink, 18.dp)
                BasicText(a.label, style = itemStyle.copy(color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Native Material 3 ModalBottomSheet wrapping [ActionMenu] with real drag-to-dismiss physics and scrim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionMenuSheet(
    title: String,
    icon: androidx.compose.ui.graphics.ImageBitmap,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HoneycombColors.platter,
        contentColor = HoneycombColors.label,
        scrimColor = HoneycombColors.sheetDim,
    ) {
        ActionMenu(
            title = title,
            icon = icon,
            actions = actions,
            modifier = modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
        )
    }
}

/** Clean phone-proportioned settings card grouping per-app feature toggles (e.g. Instagram -> Reels, Explore, Feed). */
@Composable
fun SettingsGroup(
    title: String,
    badge: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val headerStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val badgeStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    Column(
        modifier
            .fillMaxWidth()
            .surface(HoneycombColors.platter, rounded(NativeShapes.largeIncreased), rim = false)
            .padding(vertical = 4.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(title, style = headerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != null) BasicText(badge, style = badgeStyle, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(HoneycombColors.outline.copy(alpha = 0.14f)))
        content()
    }
}

/** One compact phone-proportioned toggle row inside a [SettingsGroup]. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    Column(modifier.fillMaxWidth()) {
        if (showDivider) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(HoneycombColors.outline.copy(alpha = 0.10f)))
        }
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .semantics { toggleableState = ToggleableState(checked); contentDescription = "$title, ${if (checked) "on" else "off"}" }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(subtitle, style = subStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            NativeSwitch(checked = checked)
        }
    }
}

/** Crisp phone-proportioned page heading for settings screens. */
@Composable
fun SettingsHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    val titleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(title, style = titleStyle)
        if (subtitle != null) BasicText(subtitle, style = subStyle)
    }
}

/** Compact phone-proportioned footnote helper text for settings cards. */
@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    val noteStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    BasicText(text, modifier = modifier, style = noteStyle)
}

/** Exact top clearance for the status bar / camera cutout (without inflating to the bottom nav bar height). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
val statusBarInset: Dp
    @Composable get() {
        val d = androidx.compose.ui.platform.LocalDensity.current
        val bars = androidx.compose.foundation.layout.WindowInsets.statusBarsIgnoringVisibility
        val cut = androidx.compose.foundation.layout.WindowInsets.displayCutout
        return (maxOf(bars.getTop(d), cut.getTop(d)).toFloat() / d.density).dp
    }

/** Physical camera cutout height at the top edge ONLY (`0.dp` when no cutout exists, zero artificial forehead). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
val cutoutTopInset: Dp
    @Composable get() {
        val d = androidx.compose.ui.platform.LocalDensity.current
        return (androidx.compose.foundation.layout.WindowInsets.displayCutout.getTop(d).toFloat() / d.density).dp
    }

/** Sleek iOS-style translucent app search pill floating cleanly over edge-to-edge scrolling apps. */
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    focusKey: Int = 0,
    onFocused: () -> Unit = {},
    placeholder: String = "Search apps",
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(focusKey) {
        if (focusKey > 0) {
            androidx.compose.runtime.withFrameNanos {}
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
            onFocused()
        }
    }
    val inputStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val hintStyle = inputStyle.copy(color = HoneycombColors.secondaryLabel)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .surface(HoneycombColors.platter.copy(alpha = 0.92f), Capsule, rim = false)
            .border(0.75.dp, HoneycombColors.outline.copy(alpha = 0.16f), Capsule)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(Symbols.search, HoneycombColors.secondaryLabel, 17.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) BasicText(placeholder, style = hintStyle, maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = inputStyle,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(HoneycombColors.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = placeholder },
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(Circle)
                    .clickable(role = Role.Button) { onQueryChange("") }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Symbols.close, HoneycombColors.secondaryLabel, 15.dp)
            }
        }
    }
}

/** Tappable "Search apps" pill matching [AppSearchBar] for jumping straight into search from Dynamic Grid. */
@Composable
fun AppSearchTrigger(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hintStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .surface(HoneycombColors.platter.copy(alpha = 0.90f), Capsule, rim = false)
            .border(0.75.dp, HoneycombColors.outline.copy(alpha = 0.16f), Capsule)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = "Search apps" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(Symbols.search, HoneycombColors.secondaryLabel, 17.dp)
        BasicText("Search apps", style = hintStyle, maxLines = 1)
    }
}

/** Crisp phone-proportioned label style for All Apps list rows. */
val AppRowLabelStyle: TextStyle
    @Composable get() = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 19.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )

/**
 * Clean, phone-proportioned Now Playing card for the Smart Stack: rounded album art, crisp title/artist,
 * and direct Previous / Play-Pause / Next transport controls.
 */
@Composable
fun NowPlayingCard(
    title: String,
    artist: String?,
    appName: String?,
    playing: Boolean,
    art: androidx.compose.ui.graphics.ImageBitmap?,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kickerStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = if (playing) HoneycombColors.accent else HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val titleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val artistStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    Platter(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen),
        padding = 14.dp,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(rounded(14.dp))
                        .background(HoneycombColors.tonal),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        androidx.compose.foundation.Image(
                            bitmap = art,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Glyph(Symbols.music, HoneycombColors.onTonal, 26.dp)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val kicker = buildString {
                        append(if (playing) "Now Playing" else "Paused")
                        if (!appName.isNullOrBlank()) {
                            append(" · ")
                            append(appName)
                        }
                    }
                    BasicText(kicker, style = kickerStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!artist.isNullOrBlank()) {
                        BasicText(artist, style = artistStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(
                    icon = Symbols.previous,
                    contentDescription = "Previous track",
                    onClick = onPrevious,
                    size = 38.dp,
                )
                CircleButton(
                    icon = if (playing) Symbols.pause else Symbols.play,
                    contentDescription = if (playing) "Pause" else "Play",
                    onClick = onPlayPause,
                    size = 44.dp,
                    tint = HoneycombColors.accent,
                )
                CircleButton(
                    icon = Symbols.next,
                    contentDescription = "Next track",
                    onClick = onNext,
                    size = 38.dp,
                )
            }
        }
    }
}

/** Clean search-result row: circular app icon + crisp phone-proportioned app name. */
@Composable
fun AppSearchRow(
    label: String,
    icon: androidx.compose.ui.graphics.ImageBitmap,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(rounded(NativeShapes.large))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.foundation.Image(icon, null, Modifier.size(38.dp).clip(Circle))
        BasicText(label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val SCRUB_LETTERS = ('A'..'Z').toList() + '#'

/**
 * Contacts / iOS App Library A–Z fast-scroll rail along the right edge of List View.
 * Touch or slide vertically to jump instantaneously to any section with haptic ticks and a floating letter bubble.
 */
@Composable
fun BoxScope.AlphabetScrubber(
    activeLetter: Char?,
    availableLetters: Set<Char>,
    onScrubLetter: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val currentCallback = androidx.compose.runtime.rememberUpdatedState(onScrubLetter)
    var scrubbingLetter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Char?>(null) }
    var scrubFraction by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    var railHeightPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { org.honeycomb.launcher.DragOwners.set("az-scrubber", null) }
    }

    val baseLetterStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 9.5.sp,
        lineHeight = 11.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val activeLetterStyle = baseLetterStyle.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 10.5.sp,
        color = HoneycombColors.accent,
    )
    val dimLetterStyle = baseLetterStyle.copy(
        color = HoneycombColors.secondaryLabel.copy(alpha = 0.42f),
    )
    val bubbleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        color = HoneycombColors.onAccent,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )

    val shownActive = scrubbingLetter ?: activeLetter
    val density = androidx.compose.ui.platform.LocalDensity.current
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val endKeepOutDp = with(density) {
        val cutR = androidx.compose.foundation.layout.WindowInsets.displayCutout.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
        val navR = androidx.compose.foundation.layout.WindowInsets.navigationBarsIgnoringVisibility.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr)
        maxOf(cutR, navR).toDp()
    }

    BoxWithConstraints(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .padding(end = endKeepOutDp + 4.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (maxHeight < 140.dp) return@BoxWithConstraints
        val displayLetters = remember(maxHeight) {
            when {
                maxHeight >= 340.dp -> SCRUB_LETTERS
                maxHeight >= 210.dp -> listOf('A', '•', 'D', '•', 'G', '•', 'J', '•', 'M', '•', 'P', '•', 'S', '•', 'V', '•', 'Z', '#')
                else -> listOf('A', '•', 'G', '•', 'M', '•', 'S', '•', 'Z', '#')
            }
        }
        if (scrubbingLetter != null) {
            val yOffsetDp = with(density) { ((scrubFraction - 0.5f) * railHeightPx).toDp() }
            Box(
                Modifier
                    .padding(end = 36.dp)
                    .graphicsLayer { translationY = with(density) { yOffsetDp.toPx() } }
                    .size(48.dp)
                    .surface(HoneycombColors.accent, Circle, rim = false),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(scrubbingLetter.toString(), style = bubbleStyle)
            }
        }

        Column(
            Modifier
                .widthIn(min = 24.dp)
                .surface(
                    if (scrubbingLetter != null) HoneycombColors.platter.copy(alpha = 0.90f)
                    else HoneycombColors.platter.copy(alpha = 0.40f),
                    Capsule,
                    rim = false,
                )
                .padding(vertical = 6.dp, horizontal = 4.dp)
                .onGloballyPositioned { coords ->
                    railHeightPx = coords.size.height.toFloat().coerceAtLeast(1f)
                    val root = coords.findRootCoordinates()
                    val r = root.localBoundingBoxOf(coords, clipBounds = false)
                    // Generous horizontal hit cushion so thumb drags never slip into launcher gestures
                    org.honeycomb.launcher.DragOwners.set(
                        "az-scrubber",
                        androidx.compose.ui.geometry.Rect(r.left - 24f, r.top - 16f, r.right + 24f, r.bottom + 16f),
                    )
                }
                .clearAndSetSemantics { contentDescription = "Alphabet index" }
                .pointerInputScrubber { y, totalH, finished ->
                    if (finished) {
                        scrubbingLetter = null
                    } else {
                        val frac = (y / totalH.coerceAtLeast(1f)).coerceIn(0f, 0.9999f)
                        scrubFraction = frac
                        val letter = SCRUB_LETTERS[(frac * SCRUB_LETTERS.size).toInt()]
                        if (letter != scrubbingLetter) {
                            scrubbingLetter = letter
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            currentCallback.value(letter)
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            displayLetters.forEach { ch ->
                val style = when {
                    ch != '•' && ch == shownActive -> activeLetterStyle
                    ch != '•' && ch in availableLetters -> baseLetterStyle
                    else -> dimLetterStyle
                }
                BasicText(ch.toString(), style = style, maxLines = 1)
            }
        }
    }
}

private fun Modifier.pointerInputScrubber(onScrub: (y: Float, height: Float, finished: Boolean) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            onScrub(down.position.y, size.height.toFloat(), false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    onScrub(change.position.y, size.height.toFloat(), true)
                    break
                }
                onScrub(change.position.y, size.height.toFloat(), false)
            }
            onScrub(0f, size.height.toFloat(), true)
        }
    }

/** Native Material 3 SingleChoiceSegmentedButtonRow switcher (e.g. Feeds · Apps · Websites). */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        color = HoneycombColors.onAccent,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val inactiveStyle = activeStyle.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        color = HoneycombColors.secondaryLabel,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        tabs.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            SegmentedButton(
                selected = selected,
                onClick = { onSelect(idx) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = tabs.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = HoneycombColors.accent,
                    activeContentColor = HoneycombColors.onAccent,
                    activeBorderColor = Color.Transparent,
                    inactiveContainerColor = HoneycombColors.platter,
                    inactiveContentColor = HoneycombColors.secondaryLabel,
                    inactiveBorderColor = HoneycombColors.outline.copy(alpha = 0.25f),
                ),
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                BasicText(label, style = if (selected) activeStyle else inactiveStyle, maxLines = 1)
            }
        }
    }
}

/**
 * Native Android 16 Material 3 Expressive segmented settings card corner shape:
 * outer corners = 20 dp, inner corners between grouped rows = 4 dp, separated by 2 dp gaps.
 */
fun settingsCardShape(index: Int, count: Int): RoundedCornerShape {
    val outer = NativeShapes.largeIncreased // 20.dp
    val inner = NativeShapes.extraSmall // 4.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

/** Native Android 16 Material 3 Settings top app bar: borderless `←` arrow on sub-pages, clean title, optional action. */
@Composable
fun MinimalScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val titleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = if (onBack != null) 22.sp else 28.sp,
        lineHeight = if (onBack != null) 28.sp else 34.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = if (onBack == null) 8.dp else 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(Circle)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Symbols.arrowBack, HoneycombColors.label, 24.dp)
            }
        }
        BasicText(
            title,
            Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 4.dp),
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            CapsuleButton(
                text = actionLabel,
                onClick = onAction,
                height = 36.dp,
                sidePad = 16.dp,
                filled = true,
            )
        }
    }
}

/** Native Android 16 Material 3 Settings section heading (title case, calm accent tint). */
@Composable
fun SectionKicker(text: String, modifier: Modifier = Modifier) {
    val style = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.accent,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    BasicText(
        text = text,
        style = style,
        maxLines = 1,
        modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Native Material 3 Switch synced to Honeycomb's theme (parent row owns click/toggleable semantics). */
@Composable
fun NativeSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = HoneycombColors.onAccent,
            checkedTrackColor = HoneycombColors.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = HoneycombColors.outline,
            uncheckedTrackColor = HoneycombColors.background,
            uncheckedBorderColor = HoneycombColors.outline,
        ),
    )
}

@Composable
private fun SettingsSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    NativeSwitch(checked = checked, modifier = modifier)
}

/**
 * Native Android 16 Material 3 Settings preference row:
 * Own segmented surface card (20dp outer / 4dp inner corners), clean leading 24dp glyph (no badge box) or 34dp app icon,
 * 16sp title + 13sp secondary summary line below, and optional overlapping mini app icons on the right.
 */
@Composable
fun NavSummaryRow(
    title: String,
    valueText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    appIcon: androidx.compose.ui.graphics.ImageBitmap? = null,
    glyph: Icon? = null,
    monogram: String? = null,
    previewIcons: List<androidx.compose.ui.graphics.ImageBitmap> = emptyList(),
    accentActive: Boolean = false,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val monogramStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.onTonal,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val shape = settingsCardShape(index, count)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .surface(HoneycombColors.platter, shape, rim = false)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .semantics { contentDescription = if (valueText.isEmpty()) title else "$title, $valueText" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            appIcon != null -> androidx.compose.foundation.Image(appIcon, null, Modifier.size(34.dp).clip(Circle))
            glyph != null -> Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Glyph(glyph, if (accentActive) HoneycombColors.accent else HoneycombColors.label, 24.dp)
            }
            monogram != null -> Box(
                Modifier.size(34.dp).surface(HoneycombColors.tonal, Circle, rim = false),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(monogram, style = monogramStyle)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (valueText.isNotEmpty()) {
                BasicText(valueText, style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (previewIcons.isNotEmpty()) {
            val shown = previewIcons.take(3)
            Box(Modifier.widthIn(min = (22 + (shown.size - 1) * 14).dp)) {
                shown.forEachIndexed { idx, bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = (idx * 14).dp)
                            .size(22.dp)
                            .clip(Circle)
                            .border(1.5.dp, HoneycombColors.platter, Circle),
                    )
                }
            }
        }
    }
}

/**
 * Native Android 16 Settings TwoTargetPreference row (e.g. Wi-Fi / Modes / Website Categories):
 * Left area drills into the item's detail list; vertical hairline separates the independent right-side switch.
 */
@Composable
fun TwoTargetSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    glyph: Icon? = null,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val shape = settingsCardShape(index, count)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .surface(HoneycombColors.platter, shape, rim = false)
            .clip(shape)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                .semantics { contentDescription = "$title, $subtitle" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (glyph != null) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Glyph(glyph, if (checked) HoneycombColors.accent else HoneycombColors.label, 24.dp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    BasicText(subtitle, style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.size(1.dp, 28.dp).background(HoneycombColors.outline.copy(alpha = 0.24f)))
        Box(
            Modifier
                .clickable(role = Role.Switch) { onCheckedChange(!checked) }
                .semantics {
                    toggleableState = ToggleableState(checked)
                    contentDescription = "Toggle $title, ${if (checked) "on" else "off"}"
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            SettingsSwitch(checked = checked)
        }
    }
}

/** Native Android 16 Material 3 Settings switch row inside a segmented card group. */
@Composable
fun CleanSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    index: Int = 0,
    count: Int = 1,
    appIcon: androidx.compose.ui.graphics.ImageBitmap? = null,
    glyph: Icon? = null,
    monogram: String? = null,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val monogramStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.onTonal,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val shape = settingsCardShape(index, count)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .surface(HoneycombColors.platter, shape, rim = false)
            .clip(shape)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .semantics { toggleableState = ToggleableState(checked); contentDescription = "$title, ${if (checked) "on" else "off"}" }
            .heightIn(min = if (subtitle.isNullOrEmpty()) 58.dp else 64.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            appIcon != null -> androidx.compose.foundation.Image(appIcon, null, Modifier.size(34.dp).clip(Circle))
            glyph != null -> Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Glyph(glyph, if (checked) HoneycombColors.accent else HoneycombColors.label, 24.dp)
            }
            monogram != null -> Box(
                Modifier.size(34.dp).surface(HoneycombColors.tonal, Circle, rim = false),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(monogram, style = monogramStyle)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SettingsSwitch(checked = checked)
    }
}

/** Input bar for adding a custom website domain (globally or inside a category). */
@Composable
fun WebsiteInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "website.com",
) {
    val inputStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val hintStyle = inputStyle.copy(color = HoneycombColors.secondaryLabel)
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .surface(HoneycombColors.platter, rounded(24.dp), rim = false)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Glyph(Symbols.globe, HoneycombColors.secondaryLabel, 20.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(placeholder, style = hintStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = inputStyle,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(HoneycombColors.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onAdd() }),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Website domain" },
                )
            }
        }
        CapsuleButton(
            text = "Add",
            onClick = onAdd,
            height = 48.dp,
            sidePad = 18.dp,
            filled = value.isNotBlank(),
        )
    }
}

/** Android 16 Material 3 segmented row for a user-added blocked website domain with a clean remove action. */
@Composable
fun BlockedDomainRow(
    domain: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    subtitle: String? = null,
) {
    val domainStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val shape = settingsCardShape(index, count)
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .surface(HoneycombColors.platter, shape, rim = false)
            .clip(shape)
            .heightIn(min = 58.dp)
            .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Glyph(Symbols.globe, HoneycombColors.accent, 22.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(domain, style = domainStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(Circle)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove $domain" },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Symbols.close, HoneycombColors.secondaryLabel, 20.dp)
        }
    }
}
