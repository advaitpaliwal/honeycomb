package org.honeycomb.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.honeycomb.launcher.design.Space
import org.honeycomb.launcher.design.Wp
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A widget sits snugly in its Smart Stack card, as on Pixel Launcher: the :stub's 1-row 240 x 30 dp widget (Apple
 * Music's size) and its 110 dp widget (Gmail's) each get a card no taller than their content plus the card's design
 * padding (Space.m at most), where a fixed 160 dp card left 65 dp empty above and below the 30 dp one.
 */
@RunWith(AndroidJUnit4::class)
class WidgetFitTest {
    @get:Rule(order = -1) val setup = SetupFinished()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<LauncherActivity>()
    private val fit = WidgetFit(rule)

    @After fun clean() { fit.clear(); fit.removeStub() }

    @Test fun aWidgetsCardHugsItsContent() {
        fit.removeStub(); fit.installStub(); fit.allowBinding(); fit.clear()
        val row = fit.add(WidgetFit.fixture("Row"))
        val mail = fit.add(WidgetFit.fixture("Mail"))
        fit.openStack()
        val m = fit.ctx.resources.displayMetrics
        val mgr = android.appwidget.AppWidgetManager.getInstance(fit.ctx)
        for ((name, id) in listOf("1-row 30 dp" to row, "110 dp" to mail)) {
            rule.waitUntil(10_000) { fit.fixtureContent(id) != null }
            rule.waitForIdle()
            val card = fit.card(id); val content = fit.fixtureContent(id)!!
            val info = mgr.getAppWidgetInfo(id)!!
            val expectedH = portraitWidgetHeightPx(defaultWidgetRows(info.targetCellHeight, info.minHeight, m.density), m.density)
            assertTrue("$name: expected portrait cell height ~${expectedH}px, got ${card.height()}px", kotlin.math.abs(card.height() - expectedH) <= 4)
            assertTrue("$name: content clipped above card (${content.top} < ${card.top})", content.top >= card.top - 2)
            assertTrue("$name: content clipped below card (${content.bottom} > ${card.bottom})", content.bottom <= card.bottom + 2)
        }
    }
}
