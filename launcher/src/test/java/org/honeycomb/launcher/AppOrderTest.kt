package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private val dialer = "com.google.android.dialer/.Main"
    private val maps = "com.google.android.apps.maps/.Maps"
    private val messages = "com.google.android.apps.messaging/.Ui"
    private val clock = "com.google.android.deskclock/.Clock"
    private val music = "com.google.android.apps.youtube.music/.Music"
    private val wallet = "com.google.android.apps.walletnfcrel/.Wallet"
    private val camera = "com.android.camera2/.Camera"
    private val chrome = "com.android.chrome/.Main"
    private val installed = listOf(chrome, camera, wallet, music, clock, messages, maps, dialer)

    private val label = { k: String -> k.substringBefore('/').substringAfterLast('.') } // camera2, chrome, deskclock, ...

    @Test fun theDynamicGridPutsTheMostUsedAppInTheCentreAndTheRestOutwardByUse() {
        val used = listOf("com.android.chrome", "com.android.camera2", "com.google.android.apps.maps", "com.google.android.apps.messaging")
        // most used in slot 0 (the centre), then outward; with fewer than seven known, the rest alphabetically
        assertEquals(listOf(chrome, camera, maps, messages, clock, dialer, music), AppOrder.favourites(emptyList(), installed, used, label))
        // the order follows use, not a fixed list: other use, other apps in the centre
        assertEquals(wallet, AppOrder.favourites(emptyList(), installed, listOf("com.google.android.apps.walletnfcrel"), label)[0])
    }

    @Test fun withNoUseKnownTheDynamicGridIsAlphabetical() {
        assertEquals(listOf(camera, chrome, clock, dialer, maps, messages, music), AppOrder.favourites(emptyList(), installed, emptyList(), label))
    }

    @Test fun pinnedSlotsNeverMoveFreeSlotsFollowUseAndAMissingAppIsRefilled() {
        val used = listOf("com.android.chrome", "com.android.camera2", "com.google.android.apps.maps")
        val saved = listOf("", wallet, "", "", "", "", "")
        assertEquals(listOf(chrome, wallet, camera, maps, clock, dialer, messages), AppOrder.favourites(saved, installed, used, label))
        // Wallet uninstalled: its slot is free again and takes the next app by use
        assertEquals(listOf(chrome, camera, maps, clock, dialer, messages, music), AppOrder.favourites(saved, installed - wallet, used, label))
        // the bottom slot pins like the rest
        assertEquals(wallet, AppOrder.favourites(listOf("", "", "", "", "", "", wallet), installed, used, label)[6])
    }

    @Test fun pinsSavedWhenTheGridHadSixSlotsStayPutAndTheSeventhFollowsUse() {
        val used = listOf("com.android.chrome", "com.android.camera2", "com.google.android.apps.maps")
        val six = listOf(music, "", "", "", "", wallet)
        val shown = AppOrder.favourites(six, installed, used, label)
        assertEquals(listOf(music, chrome, camera, maps, clock, wallet, dialer), shown)
        // an edit after the update saves seven slots, the old pins still pinned and the new one free
        assertEquals(listOf(music, "", "", "", "", wallet, ""), AppOrder.pinned(six, shown, shown))
    }

    @Test fun anEditPinsOnlyTheSlotsItChanged() {
        val before = listOf(chrome, camera, maps, clock, dialer, messages)
        // hold-to-edit swapped slots 1 and 2: those two are pinned, the rest stay free to follow use
        assertEquals(listOf("", maps, camera, "", "", ""), AppOrder.pinned(emptyList(), before, AppOrder.swap(before, 1, 2)))
        // a later edit keeps the earlier pins
        val shown = AppOrder.swap(before, 1, 2)
        assertEquals(listOf("", maps, camera, "", "", wallet), AppOrder.pinned(listOf("", maps, camera, "", "", ""), shown, AppOrder.pin(shown, 5, wallet)))
    }

    @Test fun byCountRanksMostOpenedFirstAndLeavesHoneycombOut() {
        assertEquals(listOf("b", "a", "c"), byCount(mapOf("a" to 2, "b" to 5, "c" to 2, "self" to 9, "z" to 0).let { it + ("c" to 1) }, "self"))
    }

    @Test fun pinningAnAlreadyPinnedAppSwapsTheTwoSlots() {
        val pins = listOf(dialer, maps, messages, clock, music, wallet)
        assertEquals(listOf(dialer, wallet, messages, clock, music, maps), AppOrder.pin(pins, 1, wallet))
        assertEquals(listOf(dialer, camera, messages, clock, music, wallet), AppOrder.pin(pins, 1, camera))
    }

    @Test fun theGridPutsTheMostUsedFirstThenTheRestAlphabetically() {
        val installed = listOf("z.zoo/.A", "b.bank/.A", "m.maps/.A", "c.chat/.A", "m.maps/.B")
        val label = mapOf("z.zoo/.A" to "Zoo", "b.bank/.A" to "Bank", "m.maps/.A" to "Maps", "c.chat/.A" to "Chat", "m.maps/.B" to "Earth")
        assertEquals(listOf("c.chat/.A", "m.maps/.B", "m.maps/.A", "b.bank/.A", "z.zoo/.A"),
            AppOrder.byUse(listOf("c.chat", "gone.app", "m.maps", "c.chat"), installed) { label.getValue(it) })
        assertEquals(listOf("b.bank/.A", "c.chat/.A", "m.maps/.B", "m.maps/.A", "z.zoo/.A"), AppOrder.byUse(emptyList(), installed) { label.getValue(it) })
    }

    @Test fun theListIsAlphabeticalUntilReorderedThenKeepsTheSavedOrder() {
        val label = { k: String -> k.substringBefore('/').substringAfterLast('.') }
        val alpha = AppOrder.list(emptyList(), installed, label)
        assertEquals(listOf(camera, chrome, clock, dialer, maps, messages, music, wallet), alpha)
        val moved = AppOrder.move(alpha, 5, 0)
        assertEquals(messages, moved.first())
        // a new app goes after the saved order; an uninstalled one drops out
        assertEquals(moved - clock + "z.new/.Zed", AppOrder.list(moved, installed - clock + "z.new/.Zed", label))
    }

    @Test fun dontRecommendExcludesAnAppFromFavouritesAndRefillsWithNextApp() {
        val used = listOf("com.android.chrome", "com.android.camera2", "com.google.android.apps.maps")
        val shown = AppOrder.favourites(emptyList(), installed, used, label, hidden = setOf(chrome))
        assertEquals(false, chrome in shown)
        assertEquals(camera, shown[0])
        assertEquals(7, shown.size)
    }

    @Test fun dontRecommendPushesAppToBottomOfGridOrder() {
        val used = listOf("com.android.chrome", "com.android.camera2", "com.google.android.apps.maps")
        val ordered = AppOrder.byUse(used, installed, hidden = setOf(chrome), label = label)
        assertEquals(camera, ordered.first())
        assertEquals(chrome, ordered.last())
        assertEquals(installed.size, ordered.size)
    }
}
