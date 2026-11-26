package org.honeycomb.launcher

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag

/** The dynamic grid's "All apps" accessibility actions, as a screen reader lists them (none when it has none). */
fun ComposeTestRule.allAppsActions() =
    onNodeWithTag("favourites").fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().filter { it.label == "All apps" }

/** Runs the dynamic grid's "All apps" accessibility action, as a screen reader does: the full grid without the swipe. */
fun ComposeTestRule.allAppsAction() {
    val action = allAppsActions().single()
    runOnIdle { action.action() }
}
