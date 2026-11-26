package org.honeycomb.launcher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry

/** Puts real app widgets in the Smart Stack and measures the empty space a card leaves above and below each one's content. */
internal class WidgetFit(private val rule: AndroidComposeTestRule<*, LauncherActivity>) {
    private val inst = InstrumentationRegistry.getInstrumentation()
    val ctx get() = inst.targetContext
    val mgr: AppWidgetManager get() = AppWidgetManager.getInstance(ctx)

    fun shell(cmd: String): String = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd))
        .use { it.readBytes().decodeToString() }

    /** The :stub app, from the test's assets, with its widget fixtures (Fixtures.java). */
    fun installStub() {
        val apk = java.io.File(ctx.getExternalFilesDir(null), "stub.apk")
        inst.context.assets.open("stub.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        shell("cp ${apk.absolutePath} /data/local/tmp/livestub.apk"); apk.delete()
        val out = shell("pm install -r /data/local/tmp/livestub.apk")
        check("Success" in out) { "pm install: $out" }
        // the widget service registers a new package's providers a moment after the install
        rule.waitUntil(10_000) { mgr.installedProviders.count { it.provider.packageName == STUB } >= 8 }
    }

    fun removeStub() { shell("pm uninstall $STUB"); shell("rm -f /data/local/tmp/livestub.apk") }

    /** Lets the home bind widgets without the system's prompt, as a launcher the user has allowed. */
    fun allowBinding() {
        shell("appwidget grantbind --package ${ctx.packageName} --user 0")
    }

    fun clear() = rule.runOnUiThread { Widgets.ids(ctx).forEach { Widgets.discard(ctx, it) } }

    /** Binds [provider] and adds it to the stack, as Edit's picker does; returns its id. */
    fun add(provider: ComponentName): Int {
        var id = 0
        rule.runOnUiThread {
            id = Widgets.host(ctx).allocateAppWidgetId()
            check(mgr.bindAppWidgetIdIfAllowed(id, provider)) { "bind refused for $provider" }
            Widgets.add(ctx, id)
        }
        return id
    }

    fun openStack() {
        rule.waitUntil(20_000) { AppCache.apps.value?.isNotEmpty() == true }
        rule.onNodeWithTag("faces").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("smart-stack").fetchSemanticsNodes().isNotEmpty() }
    }

    fun card(id: Int): Rect {
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("widget-$id").fetchSemanticsNodes().isNotEmpty() }
        val b = rule.onNodeWithTag("widget-$id").fetchSemanticsNode().boundsInWindow
        return Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
    }

    fun hostView(id: Int): AppWidgetHostView? {
        var found: AppWidgetHostView? = null
        rule.runOnUiThread { found = find(rule.activity.window.decorView) { it is AppWidgetHostView && it.appWidgetId == id } as AppWidgetHostView? }
        return found
    }

    /**
     * The widget's content, in window px: the union of its visible leaf views (text, images, list rows), leaving out any
     * leaf as tall as the whole widget (a background image), so what is left is what a person reads.
     */
    fun content(id: Int): Rect? {
        val host = hostView(id) ?: return null
        var r: Rect? = null
        rule.runOnUiThread {
            val hr = bounds(host)
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return
                if (v is ViewGroup && v.childCount > 0) { for (i in 0 until v.childCount) walk(v.getChildAt(i)); return }
                val b = bounds(v)
                if (b.height() >= hr.height() - 2 && v !is android.widget.TextView) return
                if (!b.intersect(hr)) return
                r = r?.apply { union(b) } ?: Rect(b)
            }
            walk(host)
        }
        return r
    }

    private fun bounds(v: View): Rect { val p = IntArray(2); v.getLocationInWindow(p); return Rect(p[0], p[1], p[0] + v.width, p[1] + v.height) }

    private fun find(v: View, ok: (View) -> Boolean): View? {
        if (ok(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), ok)?.let { return it }
        return null
    }

    /** The fixture view the stub marks fixture-content, in window px. */
    fun fixtureContent(id: Int): Rect? {
        val host = hostView(id) ?: return null
        var r: Rect? = null
        rule.runOnUiThread { find(host) { it.contentDescription == "fixture-content" }?.let { r = bounds(it) } }
        return r
    }

    companion object {
        const val STUB = "com.example.livestub"
        fun fixture(name: String) = ComponentName(STUB, "org.honeycomb.stub.Fixtures\$$name")
    }
}
