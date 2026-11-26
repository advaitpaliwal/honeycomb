package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A fresh install queues no widget bind: the Smart Stack never adds a widget on its own (no seeded
 * Calendar or Clock widget, and no "Create widget and allow access?" prompt on first run). The one place a bind is queued is
 * the adder, and the stack calls it only from Edit's pick. Read from the source, as DesignLanguageTest reads it.
 */
class StackBindTest {
    private val root: File = listOf(File("src/main/java"), File("launcher/src/main/java")).first { it.isDirectory }
    private val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }

    @Test fun onlyTheAdderAsksTheSystemToBind() {
        val binders = sources.filterValues { Regex("""bindAppWidgetIdIfAllowed|ACTION_APPWIDGET_BIND\b""").containsMatchIn(it) }.keys
        assertEquals(setOf("Widgets.kt"), binders)
    }

    @Test fun theAdderIsMadeOnceAndOnlyEditsPickFeedsIt() {
        val makers = sources.mapValues { (_, t) -> Regex("""=\s*rememberWidgetAdder\(""").findAll(t).count() }.filterValues { it > 0 }
        assertEquals(mapOf("SmartStack.kt" to 1), makers)
        val stack = sources.getValue("SmartStack.kt")
        val calls = Regex("""(?<![.\w])add\(""").findAll(stack).map { stack.lineSequence().elementAt(stack.substring(0, it.range.first).count { c -> c == '\n' }).trim() }.toList()
        assertEquals(listOf("if (editing) StackEditor(widgets, onAdd = { add(listOf(it)) }, onClose = { editing = false })"), calls)
    }

    @Test fun nothingSeedsTheStack() {
        val left = sources.filterValues { Regex("""seedProviders|claimFirstRun|seedsToOffer""").containsMatchIn(it) }.keys
        assertEquals(emptySet<String>(), left)
    }
}
