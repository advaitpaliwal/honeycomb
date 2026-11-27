package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HoneycombCellsTest {
    @Test fun packsAlternatingThreeAndFourIconRowsOnEightSpans() {
        val keys = List(10) { "app-$it" }
        val cells = honeycombCells(keys)
        // Row 0: [1] [2] [2] [2] [1] (3 apps)
        // Row 1: [2] [2] [2] [2]     (4 apps)
        // Row 2: [1] [2] [2] [2] [1] (3 apps)
        assertEquals(listOf(1, 2, 2, 2, 1, 2, 2, 2, 2, 1, 2, 2, 2, 1), cells.map { it.span })
        assertEquals(keys, cells.filterIsInstance<HoneycombCell.App>().map { it.appKey })
        assertEquals(cells.size, cells.map { it.gridKey }.toSet().size)
    }

    @Test fun partialTrailingRowsStayCenteredAndSumToEightSpans() {
        for (n in 1..25) {
            val cells = honeycombCells(List(n) { "a$it" })
            assertEquals("total spans divisible by 8 for n=$n", 0, cells.sumOf { it.span } % 8)
            var rowSum = 0
            for (c in cells) {
                rowSum += c.span
                assertTrue("row never overflows 8 spans for n=$n", rowSum <= 8)
                if (rowSum == 8) rowSum = 0
            }
            assertEquals("exact row termination for n=$n", 0, rowSum)
        }
    }
}
