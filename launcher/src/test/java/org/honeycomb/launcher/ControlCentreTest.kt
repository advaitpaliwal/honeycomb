package org.honeycomb.launcher

import android.app.NotificationManager.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCentreTest {
    private val edge = 24f; private val dist = 48f; private val flick = 16f; private val speed = 1000f

    @Test fun onlyATouchInTheTopEdgeArmsTheSwipeDown() {
        assertTrue(EdgeSwipe.nearTop(0f, edge))
        assertTrue(EdgeSwipe.nearTop(24f, edge))
        assertFalse(EdgeSwipe.nearTop(25f, edge))
    }

    @Test fun theRightThirdOfTheTopEdgeIsControlCentreAndTheRestNotifications() {
        assertEquals(Input.TopRight, EdgeSwipe.fromTop(1000f, 1080f))
        assertEquals(Input.TopRight, EdgeSwipe.fromTop(721f, 1080f))
        assertEquals(Input.TopLeft, EdgeSwipe.fromTop(720f, 1080f)) // the centre is notifications, as on an iPhone
        assertEquals(Input.TopLeft, EdgeSwipe.fromTop(540f, 1080f))
        assertEquals(Input.TopLeft, EdgeSwipe.fromTop(10f, 1080f))
    }

    @Test fun aLongEnoughSwipeOrAFlickQualifies() {
        assertTrue(EdgeSwipe.opens(48f, 0f, dist, flick, speed))
        assertFalse(EdgeSwipe.opens(47f, 0f, dist, flick, speed))
        assertTrue(EdgeSwipe.opens(20f, 1500f, dist, flick, speed)) // a flick
        assertFalse(EdgeSwipe.opens(10f, 3000f, dist, flick, speed)) // too short even for a flick
        assertFalse(EdgeSwipe.opens(20f, 500f, dist, flick, speed)) // too slow
        assertFalse(EdgeSwipe.opens(-60f, -2000f, dist, flick, speed)) // the wrong way
    }

    @Test fun notificationTimesReadAsTheDesignWritesThem() {
        val now = 1_000_000_000L
        assertEquals("now", ago(now, now - 30_000))
        assertEquals("12m ago", ago(now, now - 12 * 60_000))
        assertEquals("1h ago", ago(now, now - 61 * 60_000))
        assertEquals("2d ago", ago(now, now - 49 * 3_600_000L))
    }

    @Test fun liveUpdatesComeFirstThenNowPlayingThenHisWidgetsInOrderAndEditLast() {
        assertEquals(listOf(StackItem.Live("t"), StackItem.Playing, StackItem.Widget(5), StackItem.Widget(2), StackItem.Edit),
            stackItems(listOf("t"), true, listOf(5, 2)))
        assertEquals(listOf(StackItem.Widget(2), StackItem.Widget(5), StackItem.Edit), stackItems(emptyList(), false, listOf(2, 5)))
    }

    @Test fun withNothingTheStackIsOnlyEdit() {
        assertEquals(listOf(StackItem.Edit), stackItems(emptyList(), false, emptyList()))
    }

    @Test fun widgetsListAtTheirOwnHeightsAndPileOnlyWhenTheyDoNotFit() {
        assertEquals(true, widgetsFit(listOf(15f, 56f, 56f), 473.4f)) // a row widget and two 4 x 2s, each its own height
        assertEquals(false, widgetsFit(listOf(120f, 120f, 120f, 120f), 466f)) // four tall ones do not: a pile
        assertEquals(false, widgetsFit(emptyList(), 466f))
        assertEquals(2, widgetsBeforePile(listOf(100f, 100f, 100f, 100f, 100f), 368.8f)) // two lead (216 wp), the pile takes 120
        assertEquals(0, widgetsBeforePile(listOf(100f, 100f, 100f, 100f, 100f), 100f))
        assertEquals(3, widgetsBeforePile(listOf(100f, 100f, 100f, 100f, 100f), 2000f)) // never fewer than two in the pile
    }

    @Test fun aWidgetsCardIsSizedFromItsProviderNotAFixedHeight() {
        assertEquals(79, widgetHeight(79, 79, 0, 420, 735)) // Apple Music, 30 dp at 2.625: a 30 dp card, not 160 dp
        assertEquals(289, widgetHeight(289, 200, 0, 420, 735)) // Gmail: its 110 dp default, not its resize minimum
        assertEquals(735, widgetHeight(2000, 0, 0, 420, 735)) // never past the tallest card
        assertEquals(131, widgetHeight(0, 131, 0, 420, 735)) // no default height: its resize minimum
        assertEquals(420, widgetHeight(0, 0, 0, 420, 735)) // a provider with no size gets the card height
        assertEquals(150, widgetHeight(289, 100, 150, 420, 735)) // capped by its own maxResizeHeight
    }

    @Test fun notificationsGroupPerAppNewestFirstAndTheGroupWithTheNewestComesFirst() {
        val a1 = Note("a1", "a", "A", "", 100); val a2 = Note("a2", "a", "A", "", 300); val b1 = Note("b1", "b", "B", "", 200)
        val g = groups(listOf(a1, b1, a2))
        assertEquals(listOf("a", "b"), g.map { it.pkg })
        assertEquals(listOf("a2", "a1"), g[0].notes.map { it.key })
    }

    @Test fun aLiveUpdatesChronometerReadsAsTheClockShowsIt() {
        assertEquals("4:59", chronoText(298_100)); assertEquals("1:00:00", chronoText(3_600_000)); assertEquals("0:00", chronoText(-5))
    }

    @Test fun liveActivitiesMatchRidesDeliveriesAndProgressStyleWhileSkippingMediaAndVpn() {
        val ongoing = android.app.Notification.FLAG_ONGOING_EVENT
        assertTrue(LiveUpdate.matchesLiveActivity("com.ubercab", ongoing, false, null, null, false, false, false, true, 3600001))
        assertTrue(LiveUpdate.matchesLiveActivity("com.any.app", ongoing, false, null, "android.app.Notification\$ProgressStyle", false, false, true, true, 3600001))
        assertFalse(LiveUpdate.matchesLiveActivity("com.tailscale.ipn", ongoing, false, android.app.Notification.CATEGORY_SERVICE, null, false, false, false, true, 3600001))
        assertFalse(LiveUpdate.matchesLiveActivity("com.apple.android.music", ongoing, false, android.app.Notification.CATEGORY_TRANSPORT, null, true, false, false, true, 3600001))
        assertEquals(0.62f, LiveUpdate.extractProgress(620, 0, false, 1000)!!, 1e-4f)
    }

    @Test fun collapsedThePileShowsTheFrontCardAndTwoNarrowerPeeksBelowIt() {
        val h = listOf(100f, 160f, 80f, 120f)
        assertEquals(0f, pileTop(0, 0f, h, 10f, 0f), 1e-4f); assertEquals(10f, pileTop(1, 0f, h, 10f, 0f), 1e-4f)
        assertEquals(20f, pileTop(2, 0f, h, 10f, 0f), 1e-4f); assertEquals(20f, pileTop(3, 0f, h, 10f, 0f), 1e-4f)
        assertEquals(100f, pileCardHeight(1, 0f, h), 1e-4f) // piled, a card takes the front card's height
        assertEquals(1f, pileScale(0, 0f), 1e-4f); assertEquals(0.95f, pileScale(1, 0f), 1e-4f); assertEquals(0.9f, pileScale(3, 0f), 1e-4f)
        assertEquals(0f, pileAlpha(3, 0f), 1e-4f); assertEquals(1f, pileAlpha(2, 0f), 1e-4f)
        assertEquals(120f, pileHeight(0f, h, 10f, 44f), 1e-4f)
    }

    @Test fun notificationSliversAreShortNarrowerCardsEndingOnePeekBelowTheCardInFront() {
        val h = listOf(62f, 80f, 62f, 90f)
        // iOS 27: each sliver is 46 tall and ends 29 below the card in front of it, 10 narrower a side per step
        assertEquals(46f, pileCardHeight(1, 0f, h, 46f), 1e-4f)
        assertEquals(62f + 29f - 46f, pileTop(1, 0f, h, 8f, 0f, 29f, 46f), 1e-4f)
        assertEquals(62f + 58f - 46f, pileTop(2, 0f, h, 8f, 0f, 29f, 46f), 1e-4f)
        assertEquals(62f + 58f, pileHeight(0f, h, 8f, 44f, 29f), 1e-4f)
        assertEquals(340f, pileWidth(1, 0f, 360f, 10f), 1e-4f); assertEquals(320f, pileWidth(3, 0f, 360f, 10f), 1e-4f)
        // open, they are full cards in a list, full width
        assertEquals(80f, pileCardHeight(1, 1f, h, 46f), 1e-4f); assertEquals(44f + 62f + 8f, pileTop(1, 1f, h, 8f, 44f, 29f, 46f), 1e-4f)
        assertEquals(360f, pileWidth(2, 1f, 360f, 10f), 1e-4f)
    }

    @Test fun openThePileIsAListOfCardsEachAsTallAsItsContent() {
        val h = listOf(100f, 160f, 80f)
        assertEquals(44f + 100f + 10f + 160f + 10f, pileTop(2, 1f, h, 10f, 44f), 1e-4f)
        assertEquals(160f, pileCardHeight(1, 1f, h), 1e-4f)
        assertEquals(1f, pileScale(2, 1f), 1e-4f); assertEquals(1f, pileAlpha(5, 1f), 1e-4f)
        assertEquals(44f + 340f + 20f, pileHeight(1f, h, 10f, 44f), 1e-4f)
        assertEquals(0f, pileHeight(1f, emptyList(), 10f, 44f), 1e-4f)
    }

    @Test fun quietLetsThroughCallsFromContactsMediaAndAlarmsOnly() {
        val r = Quiet.rules(contactsCall = true)
        assertEquals(Policy.PRIORITY_CATEGORY_CALLS or Policy.PRIORITY_CATEGORY_ALARMS or Policy.PRIORITY_CATEGORY_MEDIA, r.categories)
        assertEquals(Policy.PRIORITY_SENDERS_CONTACTS, r.callSenders)
        assertEquals(Policy.CONVERSATION_SENDERS_NONE, r.conversations)
        assertTrue(r.suppressed and Policy.SUPPRESSED_EFFECT_BADGE != 0) // no badges or dots
        assertTrue(r.suppressed and Policy.SUPPRESSED_EFFECT_STATUS_BAR != 0)
    }

    @Test fun withTheSwitchOffNoCallsRingOnlyAlarmsAndMedia() {
        assertEquals(Policy.PRIORITY_CATEGORY_ALARMS or Policy.PRIORITY_CATEGORY_MEDIA, Quiet.rules(contactsCall = false).categories)
    }

    @Test fun quietSupportsContactsRepeatCallersEveryoneAndNone() {
        val def = Quiet.rules(Quiet.CallMode.CONTACTS, repeatCallers = true)
        assertEquals(
            Policy.PRIORITY_CATEGORY_ALARMS or Policy.PRIORITY_CATEGORY_MEDIA or
                Policy.PRIORITY_CATEGORY_CALLS or Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
            def.categories,
        )
        assertEquals(Policy.PRIORITY_SENDERS_CONTACTS, def.callSenders)

        val everyone = Quiet.rules(Quiet.CallMode.EVERYONE, repeatCallers = true)
        assertEquals(Policy.PRIORITY_SENDERS_ANY, everyone.callSenders)
        assertTrue(everyone.categories and Policy.PRIORITY_CATEGORY_CALLS != 0)

        val noneWithRepeat = Quiet.rules(Quiet.CallMode.NONE, repeatCallers = true)
        assertEquals(
            Policy.PRIORITY_CATEGORY_ALARMS or Policy.PRIORITY_CATEGORY_MEDIA or Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
            noneWithRepeat.categories,
        )
    }
}
