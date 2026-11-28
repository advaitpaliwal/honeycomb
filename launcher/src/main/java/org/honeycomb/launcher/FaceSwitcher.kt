package org.honeycomb.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.honeycomb.launcher.design.*

/** A face's saved colour choice: a name from [faceColours]; none, or a name no longer offered, is the phone's colours. */
data class FaceStyle(val colour: String? = null) {
    /** The picked colour's seed, or null for the phone's own colours. */
    val seed: androidx.compose.ui.graphics.Color? get() = faceColours[colour]

    /** The scheme the face draws from: [phone] (the wallpaper's), or one generated from the picked seed in the same light or dark. */
    fun scheme(phone: androidx.compose.material3.ColorScheme, dark: Boolean) = seed?.let { FacePalette.seeded(it, dark) } ?: phone
}
private enum class Panel { Faces, Edit, Gallery, Confirm }

@Composable
private fun Thumbnail(kind: FaceKind, ms: Long, style: FaceStyle, modifier: Modifier) {
    val screen = LocalScreen.current
    BoxWithConstraints(modifier.clip(rounded(22))) {
        val scale = maxOf(maxWidth.value / screen.width, maxHeight.value / screen.height)
        Box(Modifier.align(Alignment.Center).requiredSize(screen.width.dp, screen.height.dp).graphicsLayer {
            scaleX = scale; scaleY = scale
        }) { Backdrop { AnyFace(kind, ms, style = style) } }
    }
}

@Composable
internal fun FaceSwitcher(faces: List<FaceKind>, current: Int, ms: Long, style: (Int) -> FaceStyle,
    save: (Int, FaceStyle) -> Unit, select: (Int) -> Unit, add: (FaceKind) -> Unit) {
    val pager = rememberPagerState(initialPage = current) { faces.size + 1 }
    var panel by remember { mutableStateOf(Panel.Faces) }
    var editing by remember { mutableIntStateOf(current) }
    var candidate by remember { mutableStateOf(FaceKind.Stacked) }
    val reduce = HoneycombTheme.reduceMotion
    BackHandler(panel != Panel.Faces) { panel = if (panel == Panel.Confirm) Panel.Gallery else Panel.Faces }
    AnimatedContent(
        targetState = panel,
        modifier = Modifier.fillMaxSize().background(HoneycombColors.background),
        transitionSpec = { routeTransitionSpec(forward = targetState.ordinal >= initialState.ordinal, reduceMotion = reduce) },
        label = "face-switcher",
    ) { shown ->
        when (shown) {
            Panel.Faces -> BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val screen = LocalScreen.current
                val scale = minOf(maxWidth.value * .64f / screen.width, maxHeight.value * .65f / screen.height)
                val w = screen.width.dp * scale; val h = screen.height.dp * scale
                HorizontalPager(pager, Modifier.fillMaxSize().testTag("face-picker"),
                    contentPadding = PaddingValues(horizontal = (maxWidth - w) / 2), pageSpacing = 12.dp) { i ->
                    val kind = faces.getOrNull(i)
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly) {
                        BasicText(if (pager.currentPage == i) kind?.label ?: "New" else "", style = HoneycombType.title)
                        val shape = rounded(22)
                        Box(Modifier.size(w, h).clip(shape), contentAlignment = Alignment.Center) {
                            if (kind == null) {
                                Box(Modifier.fillMaxSize().border(2.dp, HoneycombColors.button, shape), contentAlignment = Alignment.Center) {
                                    CircleButton(Symbols.add, "Add", { panel = Panel.Gallery }, size = 64.dp, tint = HoneycombColors.button)
                                }
                            } else {
                                Thumbnail(kind, ms, style(i), Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().border(2.dp, HoneycombColors.button, shape)
                                    .testTag("pick-$i").semantics { contentDescription = kind.label }
                                    .clickable(role = Role.Button) { select(i) })
                            }
                        }
                        CapsuleButton(
                            text = "Edit",
                            onClick = if (kind == null || pager.currentPage != i) null else ({ editing = i; panel = Panel.Edit }),
                            modifier = Modifier.testTag("edit-$i"),
                            height = ButtonSize.regular,
                            minWidth = ButtonSize.minPillWidth,
                        )
                    }
                }
            }
            Panel.Edit -> FaceEditor(faces[editing], ms, style(editing)) { save(editing, it); panel = Panel.Faces }
            Panel.Gallery -> LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag("face-gallery"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "header") {
                    MinimalScreenHeader(
                        title = "All Watch Faces",
                        onBack = { panel = Panel.Faces },
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                itemsIndexed(FaceKind.entries, key = { _, k -> k.name }) { idx, kind ->
                    val rowShape = settingsCardShape(idx, FaceKind.entries.size)
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(rowShape)
                            .background(HoneycombColors.platter)
                            .tactileClickable(onClick = { candidate = kind; panel = Panel.Confirm }, pressedScale = 0.98f)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Thumbnail(kind, ms, FaceStyle(), Modifier.size(48.dp, 62.dp))
                        BasicText(kind.label, Modifier.weight(1f), style = HoneycombType.headline)
                        CapsuleButton(
                            text = "Get",
                            onClick = { candidate = kind; panel = Panel.Confirm },
                            modifier = Modifier.semantics { contentDescription = "Get ${kind.label}" },
                            height = ButtonSize.compact,
                        )
                    }
                }
            }
            Panel.Confirm -> BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                val screen = LocalScreen.current
                val s = minOf(maxWidth.value * .64f / screen.width, maxHeight.value * .59f / screen.height)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly) {
                    BasicText(candidate.label, style = HoneycombType.title)
                    Thumbnail(candidate, ms, FaceStyle(), Modifier.size(screen.width.dp * s, screen.height.dp * s))
                    CapsuleButton(
                        text = "Add",
                        onClick = { add(candidate) },
                        height = ButtonSize.regular,
                        minWidth = ButtonSize.minPillWidth,
                        filled = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceEditor(kind: FaceKind, ms: Long, initial: FaceStyle, done: (FaceStyle) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val screen = LocalScreen.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val s = minOf(maxWidth.value * .68f / screen.width, maxHeight.value * .57f / screen.height)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                BasicText("Color", style = HoneycombType.title)
                Thumbnail(kind, ms, draft, Modifier.size(screen.width.dp * s, screen.height.dp * s))
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    faceColours.forEach { (name, seed) ->
                        val selected = draft.seed == seed
                        val swatch = FacePalette(FaceStyle(name).scheme(HoneycombColors.scheme, HoneycombColors.dark)).hours
                        Box(Modifier.size(44.dp).selectable(selected, role = Role.RadioButton) { draft = draft.copy(colour = name) }
                            .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(36.dp).border(if (selected) 2.dp else 0.dp, if (selected) HoneycombColors.accent else Color.Transparent, CircleShape)
                                .padding(4.dp).background(swatch, CircleShape))
                        }
                    }
                }
            }
        }
        CapsuleButton(
            text = "Done",
            onClick = { done(draft) },
            height = ButtonSize.regular,
            minWidth = ButtonSize.minPillWidth,
            filled = true,
        )
    }
}
