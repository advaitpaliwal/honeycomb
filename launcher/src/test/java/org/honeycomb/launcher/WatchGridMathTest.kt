package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Against measurements from Apple's watchOS simulator, on the watch's own 422 x 514 screen. */
class WatchGridMathTest {
    private val native = WatchGridLayout(422f, 514f, 60)

    @Test fun expandingTheWindowFitsMoreIconsWithoutEnlargingThem() {
        var previous = 0
        for (width in listOf(840f, 1024f, 1280f)) {
            val grid = WatchGridLayout(width, 800f, 60, equalSizes = true, scaleWidth = 600f, sideInset = 24f)
            val row = grid.slotRow.indices.filter { grid.slotRow[it] == 1 }.map { grid.place(it, grid.row) }
            assertTrue(row.size > previous)
            assertEquals(24f, row.first().x - row.first().d / 2f, 1f)
            assertEquals(width - 24f, row.last().x + row.last().d / 2f, 1f)
            assertEquals(102f * 600 / 422, row.first().d, 0.01f)
            previous = row.size
        }
    }

    /** grid-middle.png (static-grid-row-spacing.json): centre x, y, diameter of every icon, rows 1..7 with row 4 centred. */
    private val gridMiddle = listOf(
        1 to listOf(Triple(147.5f, 24.5f, 22f), Triple(273.5f, 24.5f, 22f)),
        2 to listOf(Triple(105.5f, 66.5f, 74f), Triple(210.5f, 62.5f, 82f), Triple(315.5f, 66.5f, 74f)),
        3 to listOf(Triple(50.5f, 161.5f, 82f), Triple(154.5f, 151.5f, 94f), Triple(266.5f, 151.5f, 94f), Triple(370.5f, 161.5f, 82f)),
        4 to listOf(Triple(92.5f, 256.5f, 96f), Triple(210.5f, 256.5f, 108f), Triple(328.5f, 256.5f, 96f)),
        5 to listOf(Triple(50.5f, 351.5f, 82f), Triple(154.5f, 361.5f, 94f), Triple(266.5f, 361.5f, 94f), Triple(370.5f, 351.5f, 82f)),
        6 to listOf(Triple(105.5f, 446.5f, 74f), Triple(210.5f, 450.5f, 82f), Triple(315.5f, 446.5f, 74f)),
    )

    @Test fun centreColumnSizeFollowsTheMeasuredKnots() {
        val knots = listOf(2.25f to 108.25f, 77f to 100.5f, 192.5f to 84f, 233f to 24f, 240f to 9.75f)
        for ((d, size) in knots) assertEquals("size at $d", size, WatchGrid.SIZE[0](d), 0.01f)
        // the shape-preserving fit never rises toward the edge
        var last = Float.MAX_VALUE
        for (d in 0..257) { val s = WatchGrid.SIZE[0](d.toFloat()); assertTrue(s <= last + 1e-3f); last = s }
    }

    @Test fun rowsLandWhereGridMiddleHasThem() {
        val scroll = 4 * WatchGrid.ROW
        var worstXY = 0f; var worstD = 0f
        for ((row, icons) in gridMiddle) {
            val slots = (0 until native.count).filter { native.slotRow[it] == row }
            assertEquals("row $row icons", if (row % 2 == 0) 3 else 4, slots.size)
            val placed = slots.map { native.place(it, scroll) }.filter { it.d > 15f }
            assertEquals("row $row visible", icons.size, placed.size)
            for ((p, m) in placed.zip(icons)) {
                worstXY = maxOf(worstXY, abs(p.x - m.first), abs(p.y - m.second))
                worstD = maxOf(worstD, abs(p.d - m.third))
            }
        }
        assertTrue("worst position error $worstXY px", worstXY <= 5f)
        assertTrue("worst diameter error $worstD px", worstD <= 4f)
    }

    @Test fun centreRowHasTheMeasuredPitchAndGap() {
        val scroll = 4 * WatchGrid.ROW
        val row = (0 until native.count).filter { native.slotRow[it] == 4 }.map { native.place(it, scroll) }
        assertEquals(108f, row[1].d, 0.5f)
        assertEquals(96f, row[0].d, 1.5f)
        assertEquals(118f, row[1].x - row[0].x, 0.5f)
        assertEquals(16f, row[1].x - row[0].x - (row[0].d + row[1].d) / 2f, 1.5f)
    }

    @Test fun rowsAlternateThreeAndFourAndFillLeftToRight() {
        val l = WatchGridLayout(422f, 514f, 12)
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 3, 3), l.slotRow.toList())
        assertEquals(listOf(-177f, -59f), l.slotX.drop(10)) // a part row sits at the left, as in grid-bottom.png
    }

    @Test fun firstItemsSitInTheMiddleAtRest() {
        val centre = native.restCentre
        assertEquals(2, native.slotRow[centre]); assertEquals(0f, native.slotX[centre])
        assertEquals(centre, native.slotsByDistance[0])
        assertEquals(native.minScroll, native.slotRow[centre] * native.row, 0.01f)
        val besides = native.slotsByDistance.slice(1..2).map { native.place(it, native.minScroll) }
        for (p in besides) assertEquals(257f, p.y, 0.5f) // Play Store and Settings either side of the centre
    }

    @Test fun onATallPhoneTheFirstItemsSitInTheScreensMiddleAndTheGridFillsItTopToBottom() {
        // a 9:20 phone's content area at the watch's width scale: 30 apps
        val l = WatchGridLayout(1312f, 2770f, 30)
        val centre = l.place(l.slotsByDistance[0], l.minScroll)
        assertEquals(l.height / 2f, centre.y, l.row)
        assertEquals(l.width / 2f, centre.x, 1f)
        assertEquals(108.25f * l.k, centre.d, 1f) // the centre column's full size
        // the next ring is around it, not below the top rows
        for (s in l.slotsByDistance.slice(1..6)) assertTrue(kotlin.math.hypot(l.place(s, l.minScroll).x - centre.x, l.place(s, l.minScroll).y - centre.y) < 1.3f * l.row)
        // whole icons from near the top edge to near the bottom edge
        val shown = (0 until l.count).filter { l.shownAtRest(it, l.minScroll) }.map { l.place(it, l.minScroll) }
        assertTrue(shown.minOf { it.y - it.d / 2 } < 0.12f * l.height)
        assertTrue(shown.maxOf { it.y + it.d / 2 } > 0.88f * l.height)
    }

    @Test fun flickOfTheMeasuredSpeedMovesTwoRows() {
        // 22-flick-timing.json: released at about 526 px/s, the icon moved from row +1 to row -1 (209.5 px on screen)
        val start = 4 * WatchGrid.ROW
        assertEquals(start + 2 * WatchGrid.ROW, native.flingTarget(start, 526f), 0.01f)
        assertEquals(start, native.flingTarget(start, 100f), 0.01f)
        assertEquals(native.maxScroll, native.flingTarget(native.maxScroll - 10f, 5000f), 0.01f)
    }

    @Test fun snapSpringSettlesInTheMeasuredTime() {
        // critically damped, w = sqrt(256) = 16/s, from 210 px away at 526 px/s toward the target: within 1 px by 0.45 s
        val w = kotlin.math.sqrt(WatchGrid.SNAP_STIFFNESS.toDouble())
        val x0 = -210.0; val v0 = 526.0
        fun x(t: Double) = (x0 + (v0 + w * x0) * t) * kotlin.math.exp(-w * t)
        assertTrue(abs(x(0.45)) <= 1.2)
        assertTrue(abs(x(0.35)) > 1.0)
    }

    @Test fun bounceReturnsOnTheMeasuredCurve() {
        // 10-bottom-edge-bounce-timing.json: 96 px overshoot, settled within 1 px about 0.89 s after the peak
        assertTrue(96 * BounceCurve.at(0.885f) <= 1.01f)
        assertTrue(96 * BounceCurve.at(0.78f) > 1f)
        assertTrue(BounceCurve.at(0.15f) > 0.9f) // it holds before returning
        assertEquals(0.4948f, BounceCurve.at(0.267f), 0.001f)
    }

    @Test fun rubberBandRoundTrips() {
        val d = 514f
        assertEquals(96f, rubberBand(rubberBandInverse(96f, d), d), 0.01f)
        assertTrue(rubberBand(1000f, d) < d)
    }

    @Test fun removeAnimationsDrawsEqualSizesOnTheLattice() {
        val l = WatchGridLayout(422f, 514f, 30, equalSizes = true)
        val sizes = (0 until l.count).map { l.place(it, l.minScroll).d }.toSet()
        assertEquals(setOf(WatchGrid.EQUAL_D), sizes)
        assertEquals(257f + 2 * WatchGrid.ROW, l.place(l.slotsByDistance.last { l.slotRow[it] == 4 }, l.minScroll).y, 0.01f)
    }

    @Test fun scalesByWidthOnOurScreen() {
        // the tested phone emulator: 480 x 854 px; the middle icon is 108 x 480/422 and the falloff sits at the screen edges
        val l = WatchGridLayout(480f, 854f, 60)
        val k = 480f / 422f
        assertEquals(108.25f * k, l.place(l.restCentre, l.minScroll).d, 0.5f)
        val top = (0 until l.count).map { l.place(it, l.maxScroll) }.filter { it.y in 0f..60f }
        assertTrue(top.all { it.d < 60f * k })
    }

    @Test fun flingReachingTheEndOvershootsAsClip13() {
        // 13-mid-grid-flick: about 200 px/s at the end, peak 4.5 px past it; the snap spring itself gives the overshoot
        assertEquals(4.5f, edgeOvershoot(200f), 0.2f)
        // and it is back within 1 px by about 0.3 s, as the pill (416.5 at 2.92 s, 0.3 s after crossing)
        val w = kotlin.math.sqrt(WatchGrid.SNAP_STIFFNESS)
        val x = 200f * 0.3f * kotlin.math.exp(-w * 0.3f)
        assertTrue(x < 1f)
    }

    @Test fun openZoomsLikeClip14() {
        assertEquals(1f, OpenCurve.scale(0f), 0f)
        assertEquals(345f / 96f, OpenCurve.scale(0.008f), 0.1f)
        assertEquals(0.098f, OpenCurve.DURATION_S, 0.001f)
    }

    /** The app's window cuts in only once it has drawn, so a fading grid showed black until then (sbs-220 d1-app-open-v2). */
    @Test fun openingNeverFadesTheGridToBlack() {
        for (i in 0..98) assertEquals(1f, OpenCurve.alpha(i / 1000f), 0f)
        assertEquals(1f, OpenCurve.alpha(1f), 0f)
    }

    @Test fun returnZoomsLikeClip20() {
        // scale of the grid around the icon that opened, frames 65, 68, 79 of 20-settings-open-return (t from 5.742 s)
        assertEquals(1.605f, ReturnCurve.scale(5.827f - 5.742f), 0.06f)
        assertEquals(1.26f, ReturnCurve.scale(5.877f - 5.742f), 0.04f)
        assertEquals(1.03f, ReturnCurve.scale(5.992f - 5.742f), 0.02f)
        assertEquals(1f, ReturnCurve.scale(ReturnCurve.DURATION_S), 0f)
        assertEquals(1f, ReturnCurve.alpha(0.04f), 0f)
    }

    /**
     * Real phone v8c2ebc9 04-full-grid: with 30 apps on the tested phone (480 x 854) the rows bunched at the bottom edge sat
     * there at rest as 0.4-30 px slivers across the edge. At every resting row, each icon drawn is a whole icon inside
     * the screen, and every app is drawn whole at some resting row.
     */
    @Test fun atRestOnlyWholeRowsInsideTheEdgesAndScrollingReachesEveryApp() {
        // At rest Apple shows the whole rows plus one squeezed row at each edge with more content past it, as a scroll
        // cue that overlaps nothing (11-full-grid: two 22 px icons below the last whole row); never two squeezed rows.
        for (n in listOf(16, 30, 40)) {
            val l = WatchGridLayout(480f, 854f, n)
            val shownWhole = BooleanArray(n)
            // wherever a release comes to rest, the List View pill is wholly on screen or wholly below it
            val rests = sortedSetOf<Float>()
            var at = l.minScroll - l.row
            while (at <= l.maxScroll + l.row) {
                val rest = l.flingTarget(at, 0f)
                rests += rest
                assertTrue(
                    "n=$n released at $at rests at $rest with the pill cut: ${l.pillTop(rest)}..${l.pillBottom(rest)}",
                    l.pillTop(rest) >= 854f || (l.pillTop(rest) >= 0f && l.pillBottom(rest) <= 854f),
                )
                at += 1f
            }
            for (dir in listOf(1, -1)) for (r in rests) assertTrue("n=$n crown from $r", l.crownTarget(r, dir) in rests)
            // at the bottom rest the whole pill is on screen, as far from the edge as grid-bottom's (68 of 514 native px)
            assertEquals("n=$n bottom rest", l.maxScroll, rests.last(), 0f)
            assertTrue("n=$n pill top at the bottom rest ${l.pillTop(l.maxScroll)}", l.pillTop(l.maxScroll) >= 0f)
            assertEquals("n=$n pill's gap to the edge at the bottom rest", 68f * l.k, 854f - l.pillBottom(l.maxScroll), 2f * l.k)
            for (scroll in rests) {
                val shown = (0 until n).filter { val p = l.place(it, scroll); l.alpha(it, p, scroll, moving = 0f) > 0f }
                val placed = shown.associateWith { l.place(it, scroll) }
                for ((i, p) in placed) {
                    assertTrue("n=$n scroll=$scroll slot $i crosses the edge: ${p.y - p.d / 2}..${p.y + p.d / 2}", p.y - p.d / 2 >= 0f && p.y + p.d / 2 <= 854f)
                    for ((j, q) in placed) if (j > i) assertTrue(
                        "n=$n scroll=$scroll slots $i and $j overlap at rest (${p.d} px and ${q.d} px)",
                        kotlin.math.hypot(p.x - q.x, p.y - q.y) >= (p.d + q.d) / 2 - 0.5f,
                    )
                }
                val rows = shown.groupBy { l.slotRow[it] }
                val squeezed = rows.filterValues { s -> s.all { placed.getValue(it).d < WatchGrid.REST_MIN_D * l.k } }.keys
                val whole = rows.keys - squeezed
                for (r in whole) for (i in rows.getValue(r)) shownWhole[i] = true
                val above = squeezed.filter { it < whole.min() }
                val below = squeezed.filter { it > whole.max() }
                assertTrue("n=$n scroll=$scroll peek rows above $above, below $below", above.size <= 1 && below.size <= 1)
                // the cue is there whenever more content lies past the last whole row, at the top too once scrolled down
                if (whole.max() < l.lastRow) assertEquals("n=$n scroll=$scroll bottom peek row", listOf(whole.max() + 1), below)
                if (whole.min() > 0) assertEquals("n=$n scroll=$scroll top peek row", listOf(whole.min() - 1), above)
                // 11-full-grid at the top rest: the peek row below is a 4-icon row showing only its inner two
                if (scroll == l.minScroll && below.isNotEmpty() && l.slotRow.count { it == below[0] } == 4) {
                    assertEquals("n=$n peek icons at the top rest", 2, rows.getValue(below[0]).size)
                }
            }
            assertTrue("n=$n apps never shown whole: ${(0 until n).filterNot { shownWhole[it] }}", shownWhole.all { it })
            // while moving, the bunched edge rows show
            for (i in 0 until n) {
                val p = l.place(i, l.minScroll)
                if (p.d >= 1f) assertEquals(1f, l.alpha(i, p, l.minScroll, moving = 1f), 0f)
            }
        }
    }
}
