package org.honeycomb.launcher.design

import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.honeycomb.launcher.Motion
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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

/** WWDC18 803 immediate tactile press response: compresses smoothly on finger-down and springs back on lift. */
fun Modifier.fluidPress(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.93f,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val reduce = HoneycombTheme.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) pressedScale else 1f,
        animationSpec = Motion.orSnap(reduce) { Motion.press.spec() },
        label = "fluid-press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Tactile click modifier for list rows, search rows, and menu items: combines spring scale compression on finger-down
 * with a subtle pressed state overlay (zero harsh square ripple) and optional haptic tick.
 */
fun Modifier.tactileClickable(
    onClick: () -> Unit,
    pressedScale: Float = 0.97f,
    role: Role = Role.Button,
    haptic: Boolean = false,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    this
        .fluidPress(interaction, pressedScale = pressedScale)
        .then(if (pressed) Modifier.background(HoneycombColors.pressed) else Modifier)
        .clickable(
            interactionSource = interaction,
            indication = null,
            role = role,
            onClick = {
                if (haptic) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            },
        )
}

/**
 * Unified spatial spring transition for sub-page drill-downs and step flows (Block routes, Settings, Quiet, Face Picker, Setup).
 * Uses [Motion.tap] (critically damped, zero wobble) and snaps cleanly when Android's Reduce Motion is active.
 */
fun routeTransitionSpec(forward: Boolean, reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) {
        return ContentTransform(fadeIn(snap()), fadeOut(snap()))
    }
    val enter = fadeIn(Motion.tap.spec()) +
        slideInHorizontally(Motion.tap.spec()) { fullW -> if (forward) fullW / 14 else -fullW / 14 } +
        scaleIn(Motion.tap.spec(), initialScale = 0.985f)
    val exit = fadeOut(Motion.tap.spec()) +
        slideOutHorizontally(Motion.tap.spec()) { fullW -> if (forward) -fullW / 18 else fullW / 18 } +
        scaleOut(Motion.tap.spec(), targetScale = 0.985f)
    return ContentTransform(enter, exit)
}

/** Configures edge-to-edge transparent system bars with light/dark icon appearance matching Honeycomb's active ground. */
fun ComponentActivity.configureEdgeToEdgeBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.isNavigationBarContrastEnforced = false
    val dark = Native.isDark(this)
    val ground = Native.ground(Native.scheme(this), dark)
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground.toArgb()))
    WindowInsetsControllerCompat(window, window.decorView).apply {
        show(WindowInsetsCompat.Type.statusBars())
        show(WindowInsetsCompat.Type.navigationBars())
        val lightIcons = ground.luminance() > 0.5f
        isAppearanceLightStatusBars = lightIcons
        isAppearanceLightNavigationBars = lightIcons
    }
}

/** A Control Centre tile's surface: true Apple Watch full-pill [Capsule] fill. */
fun Modifier.controlSurface(fill: Color, status: Boolean = false): Modifier = clip(Capsule).background(fill)

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
            .fluidPress(interaction, pressedScale = 0.93f)
            .controlSurface(fill)
            .tap(on, onClick, onLongClick, interaction).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) BasicText(
            label,
            style = HoneycombType.title.copy(
                color = ink,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = (-0.5).sp,
            ),
            maxLines = 1,
        )
        else Glyph(icon, ink, 26.wp)
    }
}

/** The size of a [WatchButton] (34-control-centre-top: 86 x 58 pt). */
object WatchButtonSize {
    val width: Dp get() = 86.wp
    val height: Dp get() = 58.wp
}

/**
 * A tonal circle (surfaceContainerHighest), [ButtonSize.circleRegular] by default, its glyph centred: back, x, bell,
 * previous, play, next. [tint] fills it instead (a remove button in errorContainer); not [enabled], it is dimmed and
 * inert. With [on] set it is a toggle filled with [tint] (primary) when on. Visual bounds stay exact while
 * [WatchTouchTargets] expands pointer hit-slop automatically.
 */
@Composable
fun CircleButton(
    icon: Icon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = ButtonSize.circleRegular,
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
    Box(
        modifier
            .size(size)
            .fluidPress(interaction, pressedScale = 0.90f, enabled = enabled)
            .surface(fill, Circle)
            .then(tap),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, HoneycombColors.contentFor(fill), size * GLYPH_FRACTION)
    }
}

/** Tier-matched typography for [CapsuleButton] across compact (36dp), regular (42dp), and large (50dp) pills. */
@Composable
private fun capsuleLabelStyleFor(height: Dp, filled: Boolean): TextStyle {
    val (sizeSp, lineSp, weight) = when {
        height <= ButtonSize.compact -> Triple(13.sp, 17.sp, androidx.compose.ui.text.font.FontWeight.Medium)
        height >= ButtonSize.large -> Triple(
            15.sp,
            20.sp,
            if (filled) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
        )
        else -> Triple(14.sp, 18.sp, androidx.compose.ui.text.font.FontWeight.Medium)
    }
    return TextStyle(
        fontFamily = NativeFont,
        fontWeight = weight,
        fontSize = sizeSp,
        lineHeight = lineSp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
}

/**
 * Unified Material 3 Expressive capsule button: primary ([filled] = true, `accent`) for commit/done/primary CTAs,
 * tonal ([filled] = false, `tonal`) for secondary/mode-switch actions, with automatic tier-scaled padding & type.
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
    height: Dp = ButtonSize.regular,
    minWidth: Dp = 0.dp,
    style: TextStyle? = null,
    iconSize: Dp = Dp.Unspecified,
    sidePad: Dp = Dp.Unspecified,
    filled: Boolean = false,
) {
    if (onClick == null) { Box(modifier.height(height)); return }
    val resolvedStyle = style ?: capsuleLabelStyleFor(height, filled || on == true)
    val resolvedPad = if (sidePad != Dp.Unspecified) sidePad else when {
        height <= ButtonSize.compact -> 14.dp
        height >= ButtonSize.large -> 22.dp
        else -> 20.dp
    }
    val resolvedIconSize = if (iconSize != Dp.Unspecified) iconSize else when {
        height <= ButtonSize.compact -> 15.dp
        height >= ButtonSize.large -> 19.dp
        else -> 17.dp
    }
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier.sizeIn(minWidth = maxOf(minWidth, height), minHeight = height)
            .fluidPress(interaction, pressedScale = 0.94f)
            .tap(on, onClick, onLongClick, interaction),
        contentAlignment = Alignment.Center,
    ) {
        val width = if (constraints.hasFixedWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = minWidth)
        val fill = if (on == true || filled) tint else HoneycombColors.tonal
        val ink = HoneycombColors.contentFor(fill)
        androidx.compose.foundation.layout.Row(
            width.height(height).surface(fill, Capsule).padding(horizontal = resolvedPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            if (icon != null) Glyph(icon, ink, resolvedIconSize)
            BasicText(text, style = resolvedStyle.copy(color = ink), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * A list row: a [Platter] holding a 36 dp tinted icon disc (or the [leading] image) and a body label, with an
 * optional [subtitle] and [trailing] controls. Tappable rows use tactile press physics.
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
    val rowShape = rounded(NativeShapes.largeIncreased)
    val tap = if (onClick != null) Modifier.tactileClickable(onClick = onClick, pressedScale = 0.98f) else Modifier
    Column(
        modifier.fillMaxWidth().heightIn(min = 56.dp).surface(HoneycombColors.platter, rowShape, rim = false)
            .clip(rowShape).then(tap).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    .tactileClickable(onClick = a.onClick, pressedScale = 0.97f, haptic = true)
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

/** Native Material 3 ModalBottomSheet styled with Honeycomb's surface tokens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = HoneycombColors.platter,
        contentColor = HoneycombColors.label,
        scrimColor = HoneycombColors.sheetDim,
        content = content,
    )
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
    val interaction = remember { MutableInteractionSource() }
    Platter(
        modifier = modifier
            .fillMaxWidth()
            .fluidPress(interaction, pressedScale = 0.98f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen),
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
                        .clip(rounded(NativeShapes.large))
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
                    size = ButtonSize.circleRegular,
                )
                CircleButton(
                    icon = if (playing) Symbols.pause else Symbols.play,
                    contentDescription = if (playing) "Pause" else "Play",
                    onClick = onPlayPause,
                    size = ButtonSize.circleLarge,
                    tint = HoneycombColors.accent,
                )
                CircleButton(
                    icon = Symbols.next,
                    contentDescription = "Next track",
                    onClick = onNext,
                    size = ButtonSize.circleRegular,
                )
            }
        }
    }
}

/** Clean search-result row: circular app icon + crisp phone-proportioned app name + tactile spring press. */
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
            .tactileClickable(onClick = onClick, pressedScale = 0.97f)
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

/** Native Android 16 Material 3 Settings top app bar: tactile `←` back button on sub-pages, clean title, optional compact pill action. */
@Composable
fun MinimalScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    backTag: String? = null,
    actionTag: String? = null,
) {
    val titleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = if (onBack != null) 22.sp else 26.sp,
        lineHeight = if (onBack != null) 28.sp else 32.sp,
        letterSpacing = (-0.2).sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = if (onBack == null) 8.dp else 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (onBack != null) {
            CircleButton(
                icon = Symbols.arrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = ButtonSize.circleRegular,
                modifier = if (backTag != null) Modifier.testTag(backTag) else Modifier,
            )
        }
        BasicText(
            title,
            Modifier.weight(1f).padding(start = 4.dp),
            style = titleStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            CapsuleButton(
                text = actionLabel,
                onClick = onAction,
                height = ButtonSize.compact,
                filled = true,
                modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier,
            )
        }
    }
}

/** Native Android 16 Material 3 Settings section heading (title case, calm accent tint). */
@Composable
fun SectionKicker(text: String, modifier: Modifier = Modifier) {
    val style = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
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

/** Native Material 3 Slider synced to Honeycomb's theme. */
@Composable
fun NativeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = HoneycombColors.accent,
            activeTrackColor = HoneycombColors.accent,
            inactiveTrackColor = HoneycombColors.button,
        ),
    )
}

/**
 * Full-bleed Apple Control Center capsule slider built on official Material 3 Slider (native drag & accessibility,
 * zero double-boxed chrome or skinny vertical thumb). Matches WatchButton's surface and corner radius exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlCapsuleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: Icon,
    contentDescription: String,
    modifier: Modifier = Modifier,
    height: Dp = 54.dp,
) {
    val sliderState = remember {
        androidx.compose.material3.SliderState(value = value.coerceIn(0f, 1f))
    }
    androidx.compose.runtime.LaunchedEffect(value) {
        val clamped = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(sliderState.value - clamped) > 0.005f) sliderState.value = clamped
    }
    val fraction = sliderState.value.coerceIn(0f, 1f)
    val shape = Capsule
    val fillColor = HoneycombColors.accent
    val trackColor = HoneycombColors.button
    val iconColor = if (fraction > 0.14f) HoneycombColors.contentFor(fillColor) else HoneycombColors.contentFor(trackColor)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(trackColor)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.CenterStart,
    ) {
        Slider(
            state = sliderState,
            onValueChange = { next ->
                val clamped = next.coerceIn(0f, 1f)
                sliderState.value = clamped
                onValueChange(clamped)
            },
            modifier = Modifier.fillMaxWidth().height(height),
            thumb = { Box(Modifier.size(0.dp)) },
            track = { state ->
                val f = state.value.coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(height).clip(shape).background(trackColor)) {
                    Box(
                        Modifier
                            .fillMaxWidth(f.coerceAtLeast(0.02f))
                            .height(height)
                            .background(fillColor),
                    )
                }
            },
        )
        Box(
            modifier = Modifier.padding(start = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(icon, iconColor, 22.dp)
        }
    }
}

@Composable
private fun SettingsSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    NativeSwitch(checked = checked, modifier = modifier)
}

/**
 * Native Android 16 Material 3 Settings preference row:
 * Own segmented surface card (20dp outer / 4dp inner corners), tactile press physics, clean leading glyph or 34dp app icon,
 * 16sp title + 13sp summary line (accent-highlighted when [accentActive]), optional overlapping mini app icons, and chevron.
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
    showChevron: Boolean = true,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = if (accentActive) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = if (accentActive) HoneycombColors.accent else HoneycombColors.secondaryLabel,
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
            .tactileClickable(onClick = onClick, pressedScale = 0.98f)
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
        if (showChevron) {
            Glyph(Symbols.chevronRight, HoneycombColors.secondaryLabel, 20.dp)
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
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
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
        color = if (checked) HoneycombColors.accent else HoneycombColors.secondaryLabel,
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
                .tactileClickable(onClick = onClick, pressedScale = 0.98f)
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
                .tactileClickable(onClick = { onCheckedChange(!checked) }, pressedScale = 0.92f, role = Role.Switch, haptic = true)
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

/** Native Android 16 Material 3 Settings switch row inside a segmented card group with tactile spring press & haptics. */
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
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = HoneycombColors.label,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = if (checked) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = if (checked) HoneycombColors.accent else HoneycombColors.secondaryLabel,
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
            .tactileClickable(onClick = { onCheckedChange(!checked) }, pressedScale = 0.98f, role = Role.Switch, haptic = true)
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

/** Segmented single-choice row (e.g. Quiet call policy) with tactile press and trailing accent check mark. */
@Composable
fun CleanChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    index: Int = 0,
    count: Int = 1,
) {
    val labelStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
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
            .tactileClickable(onClick = onClick, pressedScale = 0.98f, role = Role.RadioButton, haptic = true)
            .semantics { contentDescription = if (selected) "$title, selected" else title }
            .heightIn(min = 58.dp)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                BasicText(subtitle, style = subStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Glyph(Symbols.check, HoneycombColors.accent, 22.dp)
        }
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
                .height(ButtonSize.large)
                .surface(HoneycombColors.platter, Capsule, rim = false)
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
            height = ButtonSize.large,
            filled = value.isNotBlank(),
        )
    }
}

/** Android 16 Material 3 segmented row for a user-added blocked website domain with a tactile remove button. */
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
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
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
            .padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
        CircleButton(
            icon = Symbols.close,
            contentDescription = "Remove $domain",
            onClick = onRemove,
            size = ButtonSize.circleSmall,
        )
    }
}

/**
 * Opal-inspired mindful breathing visualizer for the pre-open pause screen:
 * pure obsidian ground, a whisper-soft atmospheric aura that fades seamlessly to transparent (never a hard bright ball),
 * three delicate concentric hairline breathing rings with staggered organic motion, a precision circular countdown arc,
 * and a dark glass center medallion holding the paused app's icon.
 */
@Composable
fun MindfulBreathOrb(
    progress: Float,
    breath: Float,
    accentColors: List<Color>,
    appIcon: androidx.compose.ui.graphics.ImageBitmap?,
    label: String,
    phaseTitle: String,
    phaseSubtitle: String,
    modifier: Modifier = Modifier,
) {
    val primaryTint = accentColors.getOrElse(1) { HoneycombColors.accent }
    val glowTint = accentColors.firstOrNull() ?: HoneycombColors.accent
    val trackColor = HoneycombColors.label.copy(alpha = 0.12f)
    val rimColor = HoneycombColors.label.copy(alpha = 0.14f)
    val coreFill = HoneycombColors.platter
    val titleStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
        color = HoneycombColors.label,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val subStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = HoneycombColors.secondaryLabel,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )
    val pillStyle = TextStyle(
        fontFamily = NativeFont,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = HoneycombColors.secondaryLabel,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        // Subtle Opal-style app pill at top of the visualizer
        Box(
            Modifier
                .surface(HoneycombColors.platter, Capsule, rim = false)
                .border(1.dp, rimColor, Capsule)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = pillStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Ambient breathing halo + concentric hairline rings + precision sweep arc + obsidian medallion
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val maxR = size.minDimension / 2f
                val b = breath.coerceIn(0f, 1f)

                // 1. Whisper-soft atmospheric glow fading completely to transparent (zero hard circle edge)
                val auraR = maxR * (0.76f + 0.24f * b)
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to glowTint.copy(alpha = 0.16f + 0.08f * b),
                            0.42f to primaryTint.copy(alpha = 0.07f + 0.04f * b),
                            0.78f to primaryTint.copy(alpha = 0.02f),
                            1.00f to Color.Transparent,
                        ),
                        center = center,
                        radius = auraR,
                    ),
                    radius = auraR,
                    center = center,
                )

                // 2. Three delicate concentric hairline breathing ripples
                val ring1R = maxR * (0.64f + 0.14f * b)
                val ring2R = maxR * (0.76f + 0.16f * b)
                val ring3R = maxR * (0.88f + 0.11f * b)
                drawCircle(
                    color = primaryTint.copy(alpha = 0.24f + 0.14f * b),
                    radius = ring1R,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.25.dp.toPx()),
                )
                drawCircle(
                    color = primaryTint.copy(alpha = 0.13f + 0.08f * b),
                    radius = ring2R,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                )
                drawCircle(
                    color = glowTint.copy(alpha = 0.06f + 0.05f * b),
                    radius = ring3R,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.85.dp.toPx()),
                )

                // 3. Precision circular progress ring around the center medallion
                val arcR = 66.dp.toPx()
                val arcStroke = 2.25.dp.toPx()
                val topLeft = androidx.compose.ui.geometry.Offset(center.x - arcR, center.y - arcR)
                val arcSize = androidx.compose.ui.geometry.Size(arcR * 2f, arcR * 2f)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = arcStroke),
                )
                val sweep = (progress.coerceIn(0f, 1f)) * 360f
                if (sweep > 0.5f) {
                    drawArc(
                        brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                            colors = listOf(glowTint, primaryTint, glowTint),
                            center = center,
                        ),
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = arcStroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        ),
                    )
                }
            }

            // 4. Dark obsidian glass medallion holding the app's icon cleanly (never a bright filled disc)
            Box(
                Modifier
                    .size(104.dp)
                    .clip(Circle)
                    .background(coreFill)
                    .border(1.dp, rimColor, Circle),
                contentAlignment = Alignment.Center,
            ) {
                if (appIcon != null) {
                    androidx.compose.foundation.Image(
                        bitmap = appIcon,
                        contentDescription = label,
                        modifier = Modifier.size(56.dp).clip(Circle),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Glyph(Symbols.block, HoneycombColors.label, 34.dp)
                }
            }
        }

        // Calm Opal-style breathing guidance directly under the orb
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            BasicText(phaseTitle, style = titleStyle)
            BasicText(phaseSubtitle, style = subStyle)
        }
    }
}
