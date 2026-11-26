package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one input table: every gesture and key, in every place it means something. */
class SpaceTest {
    private fun go(s: Space, vararg inputs: Input) = inputs.fold(s) { acc, i -> reduce(acc, intentFor(acc, i)) }
    private val face = Space()

    @Test fun homeAlwaysReturnsToTheFace() {
        assertEquals(face, go(face, Input.Home))
        assertEquals(face, go(face, Input.Home, Input.Home))
        assertEquals(face, go(face, Input.Tap, Input.AllApps, Input.Home))
        assertEquals(face, go(face, Input.TopRight, Input.Home))
        assertEquals(face, go(face, Input.Up, Input.Home))
    }

    @Test fun homeWithHoneycombInFrontOpensTheGridFromTheBareFaceAndOtherwiseGoesToTheFace() {
        assertEquals(face.copy(depth = Depth.Favourites), go(face, Input.HomeAgain))
        assertEquals(face, go(face, Input.Up, Input.HomeAgain))
        assertEquals(face, go(face, Input.HomeAgain, Input.HomeAgain))
        assertEquals(face, go(face, Input.Tap, Input.HomeAgain))
        assertEquals(face, go(face, Input.TopLeft, Input.HomeAgain))
        assertEquals(face, go(face, Input.Hold, Input.HomeAgain))
    }

    @Test fun aTapOnTheFaceOpensTheDynamicGridAndItsAllAppsButtonOrSwipeUpOpensTheFullGrid() {
        val dynamic = go(face, Input.Tap)
        assertEquals(Depth.Favourites, dynamic.depth)
        assertEquals(Depth.Apps, go(dynamic, Input.AllApps).depth)
        assertEquals(Depth.Apps, go(dynamic, Input.Up).depth)
        assertEquals(Depth.Favourites, go(dynamic, Input.Up, Input.Back).depth)
        assertEquals(Intent.None, intentFor(dynamic.copy(picking = true), Input.Up))
    }

    @Test fun tallMeansTallerThanThreeByTwo() {
        assertEquals(true, tallScreen(1344, 2992))
        assertEquals(true, tallScreen(720, 1280))
        assertEquals(false, tallScreen(1000, 1500))
        assertEquals(false, tallScreen(360, 413))
        assertEquals(false, tallScreen(2992, 1344))
    }

    @Test fun theTopEdgeOpensControlCentreFromTheRightThirdAndNotificationsElsewhere() {
        assertEquals(Edge.Control, go(face, Input.TopRight).edge)
        assertEquals(Edge.Notifications, go(face, Input.TopLeft).edge)
        assertEquals(Edge.Control, go(face, Input.Tap, Input.AllApps, Input.TopRight).edge)
        assertEquals(Depth.Apps, go(face, Input.Tap, Input.AllApps, Input.TopRight, Input.Up).depth)
    }

    @Test fun sheetsCloseTheWayTheyCameOrWithBackAndQuietIsOneLevelIn() {
        assertEquals(null, go(face, Input.TopLeft, Input.Up).edge)
        assertEquals(null, go(face, Input.TopLeft, Input.Back).edge)
        val quiet = go(face, Input.TopRight, Input.HoldQuiet)
        assertEquals(true, quiet.quiet)
        assertEquals(Space(edge = Edge.Control), go(quiet, Input.Back))
        assertEquals(Intent.None, intentFor(quiet, Input.Up))
        assertEquals(Intent.None, intentFor(go(face, Input.TopRight).copy(sheetEnd = false), Input.Up))
    }

    @Test fun aSheetPulledOpenOverAListThatCanStillScrollScrollsItBeforeClosing() {
        for (edge in listOf(Input.TopRight, Input.TopLeft)) {
            val opened = go(face.copy(sheetEnd = false), edge)
            assertEquals(Intent.None, intentFor(opened, Input.Up))
            assertEquals(null, go(opened.copy(sheetEnd = true), Input.Up).edge)
            assertEquals(Intent.None, intentFor(go(opened, Input.Home, edge), Input.Up))
        }
    }

    @Test fun onTheFaceUpIsTheStackDownIsSearchTapTheDynamicGridAndHoldThePicker() {
        assertEquals(true, go(face, Input.Up).stack)
        assertEquals(Depth.Face, go(face, Input.Up).depth)
        assertEquals(Intent.Search, intentFor(face, Input.Down))
        assertEquals(Depth.Apps, go(face, Input.Down).depth)
        assertEquals(face, go(face, Input.Down, Input.Back))
        assertEquals(Depth.Favourites, go(face, Input.Tap).depth)
        assertEquals(true, go(face, Input.Hold).picking)
        assertEquals(face, go(face, Input.Hold, Input.Back))
    }

    @Test fun theStackIsOnlyOnTheFace() {
        val fav = go(face, Input.Tap)
        assertEquals(Intent.In, intentFor(fav, Input.Up))
        assertEquals(Intent.None, intentFor(go(fav, Input.AllApps), Input.Down))
    }

    @Test fun theStackLowersFromItsFirstCardOnly() {
        val stack = go(face, Input.Up)
        assertEquals(face, go(stack, Input.Down))
        assertEquals(Intent.None, intentFor(stack.copy(stackTop = false), Input.Down))
        assertEquals(face, go(stack, Input.Back))
    }

    @Test fun backGoesOutOneLevel() {
        val apps = go(face, Input.Tap, Input.AllApps)
        assertEquals(Depth.Apps, apps.depth)
        assertEquals(Depth.Favourites, go(apps, Input.Back).depth)
        assertEquals(face, go(apps, Input.Back, Input.Back))
        assertEquals(Intent.None, intentFor(face, Input.Back))
    }

    @Test fun aSwipeLeftGoesInALevelAndASwipeRightComesBackOut() {
        val dynamic = go(face, Input.Left)
        assertEquals(face.copy(depth = Depth.Favourites), dynamic)
        assertEquals(Depth.Apps, go(face, Input.Left, Input.Left).depth)
        assertEquals(Intent.None, intentFor(go(face, Input.Left, Input.Left), Input.Left))
        assertEquals(Depth.Favourites, go(face, Input.Left, Input.Left, Input.Right).depth)
        assertEquals(face, go(face, Input.Left, Input.Left, Input.Right, Input.Right))
        assertEquals(Intent.None, intentFor(face, Input.Right))
    }

    @Test fun aSideSwipeMeansNothingUnderASheetTheStackThePickerOrWhileEditing() {
        for (over in listOf(Input.Up, Input.TopLeft, Input.TopRight, Input.Hold)) {
            assertEquals(Intent.None, intentFor(go(face, over), Input.Left))
            assertEquals(Intent.None, intentFor(go(face, over), Input.Right))
        }
        val dynamic = go(face, Input.Left)
        assertEquals(Intent.None, intentFor(dynamic.copy(picking = true), Input.Left))
        assertEquals(Intent.None, intentFor(dynamic.copy(picking = true), Input.Right))
        assertEquals(Intent.None, intentFor(go(dynamic, Input.TopLeft), Input.Left))
        assertEquals(Intent.None, intentFor(go(dynamic, Input.TopLeft), Input.Right))
    }

    @Test fun theRightEdgeBandIsKeptOnlyOnTheFaceAndTheDynamicGrid() {
        assertEquals(true, SideSwipe.claims(face))
        assertEquals(true, SideSwipe.claims(go(face, Input.Tap)))
        assertEquals(false, SideSwipe.claims(go(face, Input.Tap, Input.AllApps)))
        assertEquals(false, SideSwipe.claims(go(face, Input.Up)))
        assertEquals(false, SideSwipe.claims(go(face, Input.TopLeft)))
        assertEquals(false, SideSwipe.claims(go(face, Input.Tap).copy(picking = true)))
        val band = SideSwipe.band(1080f, 2400f, 2.625f)
        assertEquals(525f, band.height, 0.01f)
        assertEquals(1200f, band.center.y, 0.01f)
        assertEquals(1080f, band.right, 0f)
        assertEquals(126f, band.width, 0.01f)
        assertEquals(0f, SideSwipe.band(100f, 2400f, 2.625f).left, 0f)
    }
}
