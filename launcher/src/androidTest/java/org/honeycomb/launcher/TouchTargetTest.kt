package org.honeycomb.launcher

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.roundToInt
import org.honeycomb.launcher.design.ProvideScale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TouchTargetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val minimum get() = with(ctx.resources.displayMetrics) { (minOf(widthPixels.toFloat(), 600 * density) * 44 / 211).roundToInt() }

    private fun screen(content: @Composable () -> Unit) = rule.setContent { ProvideScale { FrameSize { content() } } }

    private fun checkTargets(scope: SemanticsMatcher, expected: Int) {
        val match = scope and isEnabled()
        val nodes = rule.onAllNodes(match).fetchSemanticsNodes()
        assertTrue("Expected at least $expected controls; found ${nodes.size}", nodes.size >= expected)
        val errors = mutableListOf<String>()
        for (node in nodes) {
            val target = rule.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id })
            if (hasAnyAncestor(hasScrollAction()).matches(node)) target.performScrollTo()
            val n = target.fetchSemanticsNode()
            val b = n.touchBoundsInRoot
            if (b.width.roundToInt() < minimum || b.height.roundToInt() < minimum) errors += "${n.config.getOrElseNullable(SemanticsProperties.TestTag) { null } ?: n.id}: $b"
        }
        assertTrue("Required $minimum px on each axis: $errors", errors.isEmpty())
    }

    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    @Test fun populatedEditor() {
        val mgr = AppWidgetManager.getInstance(ctx)
        val host = Widgets.host(ctx)
        val provider = mgr.installedProviders.first()
        val ids = List(2) { host.allocateAppWidgetId() }
        val id = ids.first()
        try {
            inst.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.BIND_APPWIDGET)
            try { ids.forEach { assertTrue(mgr.bindAppWidgetIdIfAllowed(it, provider.provider)) } }
            finally { inst.uiAutomation.dropShellPermissionIdentity() }
            var done = false
            screen { StackEditor(ids, {}, { done = true }) }
            val window = rule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val column = rule.onNodeWithTag("stack-editor").fetchSemanticsNode().boundsInRoot
            val density = ctx.resources.displayMetrics.density
            val unitPx = org.honeycomb.launcher.design.Wp.pxPerUnit(window.width.roundToInt(), density)
            val margin = if (window.width / unitPx < 600f) 16f else 24f
            assertEquals(window.center.x, column.center.x, 1f)
            assertEquals(minOf(window.width - 2 * margin * unitPx, org.honeycomb.launcher.design.Wp.MAX_CONTENT_DP * unitPx), column.width, 2f)
            checkTargets(hasTestTag("stack-done") or hasTestTag("stack-add"), 2)
            val list = rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("stack-editor")))
            ids.forEachIndexed { index, widget ->
                list.performScrollToIndex(index)
                checkTargets(hasClickAction() and hasAnyAncestor(hasTestTag("edit-widget-$widget")), 2)
            }
            list.performScrollToIndex(0)
            fun tapTopEdge(tag: String) {
                val node = rule.onNodeWithTag(tag)
                if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
                val bounds = node.fetchSemanticsNode().touchBoundsInRoot
                val editor = rule.onNodeWithTag("stack-editor")
                val origin = editor.fetchSemanticsNode().boundsInRoot.topLeft
                editor.performTouchInput {
                    click(Offset(bounds.center.x, bounds.top + 2) - origin)
                }
            }
            tapTopEdge("stack-done")
            assertTrue("Done top edge", done)
            tapTopEdge("remove-$id")
            assertNull("Remove top edge", mgr.getAppWidgetInfo(id))
            tapTopEdge("stack-add")
            rule.onNodeWithText("Add a widget").assertExists()
        } finally { ids.forEach(host::deleteAppWidgetId) }
    }

    @Test fun controlCentre() {
        screen { ControlCentre({}, {}) }
        checkTargets(hasClickAction() and hasAnyAncestor(hasTestTag("control-centre")), ControlGrid.order.size)
    }

    @Test fun setupSteps() {
        val intent = Intent(ctx, SetupActivity::class.java).setAction(Intent.ACTION_APPLICATION_PREFERENCES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<SetupActivity>(intent).use {
            repeat(Step.entries.size + 1) {
                val skip = rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()
                checkTargets(hasClickAction(), if (skip) 2 else 1)
                if (!skip) return
                rule.onNodeWithText("Skip").performClick()
            }
            fail("Setup never reached Done")
        }
    }

    @Test fun notificationListAndLongLook() {
        val component = ComponentName(ctx, MediaListener::class.java)
        val granted = ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        val tag = "honeycomb-touch-target"
        try {
            if (!granted) shell("cmd notification allow_listener ${component.flattenToString()}")
            android.service.notification.NotificationListenerService.requestRebind(component)
            shell("cmd notification post -t Target $tag Target")
            rule.waitUntil(10_000) { MediaListener.Defaults.live?.activeNotifications?.any { it.tag == tag } == true }
            val groups = groups(notes(MediaListener.Defaults.live!!.activeNotifications.toList(), ctx.packageName))
            screen { NotificationCentre {} }
            val list = rule.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("notifications")))
            checkTargets(hasTestTag("clear-all"), 1)
            for ((index, group) in groups.withIndex()) {
                list.performScrollToIndex(index + 1)
                val parent = hasTestTag("group-${group.pkg}")
                if (group.notes.size > 1) {
                    // Only the front card is a control in a collapsed pile; the slivers are previews.
                    rule.onNodeWithTag("group-${group.pkg}-0").performTouchInput { click() }
                    rule.waitForIdle()
                }
                checkTargets(hasAnyAncestor(parent) and (hasClickAction() or hasTestTag("note")), group.notes.size)
            }
            list.performScrollToIndex(groups.indexOfFirst { g -> g.notes.any { it.title == "Target" } } + 1)
            val note = rule.onNode(hasTestTag("note") and hasContentDescription("Target"))
            note.performScrollTo().performTouchInput { click() }
            rule.onNodeWithTag("long-look").assertExists()
            rule.onNodeWithTag("look-dismiss").assertHasClickAction()
            rule.onNodeWithTag("look-mute").assertHasClickAction()
            checkTargets(hasAnyAncestor(hasTestTag("long-look")) and (hasClickAction() or hasTestTag("note")), 3)
        } finally {
            MediaListener.Defaults.live?.let { listener -> listener.activeNotifications.filter { it.tag == tag }.forEach { listener.cancelNotification(it.key) } }
            if (!granted) shell("cmd notification disallow_listener ${component.flattenToString()}")
        }
    }

    @Test fun gridListViewFooter() {
        var list = false
        screen { WatchGrid(listOf(ImageBitmap(8, 8)), listOf("Only app"), emptyFlow(), { _, _ -> }, onListView = { list = true }) }
        val footer = rule.onNodeWithContentDescription("List View")
        val b = footer.fetchSemanticsNode().boundsInRoot
        checkTargets(hasClickAction() and hasContentDescription("List View"), 1)
        val p = Offset(b.center.x, b.center.y - minimum / 2 + 2)
        rule.onRoot().performTouchInput { click(p) }
        assertTrue("Footer must accept taps outside the painted capsule", list)
    }

    @Test fun facePicker() {
        screen { FaceSwitcher(FaceKind.entries, 0, 0L, { FaceStyle() }, { _, _ -> }, {}, {}) }
        val picker = rule.onNodeWithTag("face-picker")
        for (i in FaceKind.entries.indices) {
            picker.performScrollToIndex(i)
            checkTargets(hasClickAction() and (hasTestTag("pick-$i") or hasTestTag("edit-$i")), 2)
        }
        val info = FaceKind.Info.ordinal
        picker.performScrollToIndex(info)
        rule.onNodeWithTag("edit-$info").performClick()
        checkTargets(hasClickAction(), 5) // four colours and Done
        rule.onNodeWithText("Done").performClick()
        picker.performScrollToIndex(FaceKind.entries.size)
        checkTargets(hasContentDescription("Add") and hasClickAction(), 1)
        rule.onNodeWithContentDescription("Add").performClick()
        val gallery = rule.onNodeWithTag("face-gallery")
        for (kind in FaceKind.entries) {
            gallery.performScrollToIndex(kind.ordinal + 1)
            checkTargets(hasContentDescription("Get ${kind.label}") and hasClickAction(), 1)
        }
        rule.onNodeWithContentDescription("Get ${FaceKind.entries.last().label}").performClick()
        checkTargets(hasText("Add") and hasClickAction(), 1)
    }

    @Test fun gridSendsEdgeAndOverlapTapsAtTheClosestVisibleCentre() {
        val count = 19
        val labels = List(count) { "Icon $it" }
        val colours = List(count) { Color.rgb(16 + it * 10, 255, 0) }
        val icons = colours.map { colour ->
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }.asImageBitmap()
        }
        var chosen = -1
        var visit by mutableIntStateOf(0)
        screen { key(visit) { WatchGrid(icons, labels, emptyFlow(), { i, _ -> chosen = i }, Modifier.testTag("grid")) } }
        val grid = rule.onNodeWithTag("grid")
        val frame = grid.fetchSemanticsNode().boundsInRoot
        val seen = mutableSetOf<Int>()
        fun measureVisible(): Map<Int, Offset> {
            // Read visibility from the grid itself. The system's active accessibility window
            // can belong to another activity even when this Compose root is ready for input.
            val image = grid.captureToImage()
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            return colours.indices.filter { colours[it] in pixels }.associateWith { item ->
                val node = rule.onNodeWithContentDescription(labels[item]).assertHasClickAction().fetchSemanticsNode()
                // Use the centre of the circle, not the centre of its partially clipped rectangle.
                val c = node.layoutInfo.coordinates
                c.localToRoot(Offset(c.size.width / 2f, c.size.height / 2f)) - frame.topLeft
            }
        }
        fun end() { grid.performTouchInput { swipeUp() }; rule.waitForIdle() }
        for (atEnd in listOf(false, true)) {
            if (atEnd) end()
            val visible = measureVisible()
            assertTrue("No accessible grid icons", visible.isNotEmpty())
            val delta = minimum * 0.35f
            val samples = mutableListOf<Offset>()
            for ((item, c) in visible) if (item !in seen) {
                samples += listOf(c, c + Offset(-delta, delta), c + Offset(delta, -delta))
            }
            val footer = rule.onNodeWithContentDescription("List View").fetchSemanticsNode().touchBoundsInRoot
            for (point in samples) {
                if (point.x !in 1f..frame.width-1 || point.y !in 1f..frame.height-1 || footer.contains(point + frame.topLeft)) continue
                val expected = visible.minBy { (_, c) -> (c - point).getDistanceSquared() }.key
                grid.performTouchInput { click(point) }
                rule.runOnIdle { assertEquals("tap $point", expected, chosen); seen += chosen; chosen = -1; visit++ }
                if (atEnd) end()
            }
        }
        assertEquals("Every icon received a physical tap", labels.indices.toSet(), seen)
    }
}
