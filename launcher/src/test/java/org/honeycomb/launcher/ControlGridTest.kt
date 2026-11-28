package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlGridTest {
    @Test fun twoColumnsTwoToARowAndNoControlTwice() {
        val cells = ControlGrid.cells
        assertTrue(cells.all { it.col in 0 until ControlGrid.COLS })
        assertEquals(cells.size, cells.map { it.row to it.col }.toSet().size)
        assertEquals(cells.size, cells.map { it.id }.toSet().size)
    }

    @Test fun theOrderIsTheWatchsReadRowByRow() {
        // 34-control-centre-top and 06-control-centre: cellular | Wi-Fi, Airplane | 100%, Silent | Flashlight, then Focus
        // and Theatre, the speaker and AirPlay, Display beside Timer; the phone's own controls after
        val rows = ControlGrid.cells.groupBy { it.row }.toSortedMap().values.map { r -> r.sortedBy { it.col }.map { it.id } }
        assertEquals(listOf(
            listOf("bluetooth", "wifi"), listOf("airplane", "battery"), listOf("silent", "flashlight"),
            listOf("quiet", "rotation"), listOf("volume", "output"), listOf("brightness", "timer"),
            listOf("calculator", "camera"), listOf("mirroring", "hotspot"),
        ), rows)
    }

    @Test fun theWatchHasNoSlidersNoNowPlayingAndNoEdit() {
        val ids = ControlGrid.order.toSet()
        for (gone in listOf("media", "connectivity", "edit")) assertFalse(gone, gone in ids)
        // brightness and volume stay, as buttons that open the system's own controls
        assertTrue(ids.containsAll(listOf("brightness", "volume")))
    }

    @Test fun theButtonsAreTheWatchsMeasuredSizeAndFillItsWidth() {
        // 422 px = 211 pt: buttons 176 x 120 px, 6 px apart across, 8 px down, 32 px from each side
        assertEquals(88f, ControlGrid.BUTTON_W, 0f)
        assertEquals(60f, ControlGrid.BUTTON_H, 0f)
        val place = ControlGrid.place(ControlGrid.cells)
        assertEquals(ControlGrid.SIDE, place.values.minOf { it.x }, 0.01f)
        assertEquals(ControlGrid.WIDTH - ControlGrid.SIDE, place.values.maxOf { it.x + it.w }, 0.01f)
        val (l, r) = place.getValue("bluetooth") to place.getValue("wifi")
        assertEquals(ControlGrid.GAP_X, r.x - (l.x + l.w), 0.01f)
        val below = place.getValue("airplane")
        assertEquals(ControlGrid.GAP_Y, below.y - (l.y + l.h), 0.01f)
        assertEquals(ControlGrid.height(ControlGrid.cells), place.values.maxOf { it.y + it.h }, 0.01f)
    }

    @Test fun everyButtonIsAtLeast48dpOnASmallPhone() {
        // a 360dp-wide phone: wp = (360 - 2 x 16) / 211
        val wp = (360f - 32f) / ControlGrid.WIDTH
        assertTrue(minOf(ControlGrid.BUTTON_W, ControlGrid.BUTTON_H) * wp >= 48f)
    }

    @Test fun theBrightnessSliderMapsItsWholeTravelOntoAndroidsRange() {
        assertEquals(1, Levels.brightnessValue(0f))
        assertEquals(255, Levels.brightnessValue(1f))
        assertEquals(128, Levels.brightnessValue(0.5f))
    }

    @Test fun theBrightnessStepComesAfterUsage() {
        assertEquals(Step.Brightness, SetupFlow.next(setOf(Step.Home, Step.Navigation, Step.Notifications, Step.Quiet, Step.Usage), emptySet()))
        assertEquals(Step.Brightness, SetupFlow.next(emptySet(), Step.entries.toSet() - Step.Brightness))
    }

    @Test fun controlPrefsReorderMovesItemsCleanly() {
        val base = listOf("bluetooth", "wifi", "airplane", "battery")
        assertEquals(listOf("wifi", "airplane", "bluetooth", "battery"), ControlPrefs.move(base, 0, 2))
        assertEquals(base, ControlPrefs.move(base, 1, 1))
        assertEquals(base, ControlPrefs.move(base, -1, 2))
        assertTrue(ControlGrid.validIds.containsAll(listOf("block", "settings")))
    }
}
