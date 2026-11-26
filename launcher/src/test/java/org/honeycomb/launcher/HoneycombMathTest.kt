package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

class HoneycombMathTest {
    @Test fun latticeIsHexagonal() {
        // Six neighbours of the centre, all one pitch away and 60 degrees apart.
        val n = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to -1, -1 to 1)
        for ((q, r) in n) assertEquals(1f, hypot(cellX(q, r), cellY(r)), 1e-5f)
        assertEquals(sqrt(3f) / 2f, cellY(1), 1e-6f)
        assertEquals(0.5f, cellX(0, 1), 1e-6f)
    }

    @Test fun slotsStartAtTheCentreThenTheFirstRing() {
        val s = honeycombSlots(19)
        assertEquals(0f, s[0], 0f); assertEquals(0f, s[1], 0f)
        for (i in 1..6) assertEquals("slot $i is in the first ring", 1f, hypot(s[2 * i], s[2 * i + 1]), 1e-4f)
        // No two slots share a cell, and every slot is on the lattice.
        val seen = HashSet<Pair<Int, Int>>()
        for (i in 0 until 19) {
            val r = Math.round(s[2 * i + 1] / Honeycomb.ROW)
            val q = Math.round(s[2 * i] - r / 2f)
            assertEquals(cellX(q, r), s[2 * i], 1e-4f)
            assertTrue(seen.add(q to r))
        }
    }

    @Test fun slotsGrowOutwardThroughTheLens() {
        val s = honeycombSlots(60)
        var last = 0f
        for (i in 0 until 60) {
            val d = lensDistance(s[2 * i] / Honeycomb.D0_PER_PITCH, s[2 * i + 1] / Honeycomb.D0_PER_PITCH)
            assertTrue("slot $i is no nearer than slot ${i - 1}", d >= last - 1e-6f)
            last = d
        }
    }

    /** Twenty icons measured in the 2015 Welcome film at 183.4 s: u, v, eta in D0, observed scale (all-fit-measurements.csv). */
    private val measured = arrayOf(
        doubleArrayOf(1.1202, 0.0000, 0.8538, 0.8852), doubleArrayOf(-1.1066, -0.0137, 0.8538, 0.8989),
        doubleArrayOf(1.0383, -1.8033, 0.6626, 0.6885), doubleArrayOf(0.5328, -0.9836, 1.4413, 0.8934),
        doubleArrayOf(-0.5328, 0.9699, 1.4276, 0.8852), doubleArrayOf(0.0000, -1.9126, 0.5533, 0.7869),
        doubleArrayOf(-0.5328, -0.9973, 1.4276, 0.8880), doubleArrayOf(-1.0383, 1.7896, 0.6626, 0.6940),
        doubleArrayOf(0.0000, 0.0000, 1.9604, 1.0000), doubleArrayOf(1.5301, 0.8880, 0.4440, 0.7022),
        doubleArrayOf(1.0519, 1.7896, 0.6626, 0.6831), doubleArrayOf(-1.5301, 0.8743, 0.4303, 0.7049),
        doubleArrayOf(1.5164, -0.9016, 0.4577, 0.6940), doubleArrayOf(-1.0383, -1.8169, 0.6489, 0.6831),
        doubleArrayOf(0.5328, 0.9563, 1.4413, 0.8770), doubleArrayOf(-1.5164, -0.9153, 0.4440, 0.6885),
        doubleArrayOf(0.0273, 1.8852, 0.5669, 0.7814), doubleArrayOf(-1.8238, -0.0205, 0.1366, 0.2049),
        doubleArrayOf(1.8238, 0.0000, 0.1503, 0.2117), doubleArrayOf(-0.5806, 2.3087, 0.1434, 0.1571),
    )

    @Test fun lensMatchesTheFootage() {
        var sq = 0.0
        for (m in measured) {
            val s = lensScale(m[0].toFloat(), m[1].toFloat(), m[2].toFloat())
            assertEquals("icon at (${m[0]}, ${m[1]})", m[3], s.toDouble(), 0.035)
            sq += (s - m[3]) * (s - m[3])
        }
        assertTrue("rms under 0.02", sqrt(sq / measured.size) < 0.02)
    }

    @Test fun lensEdgesAndReduceMotion() {
        assertEquals(1f, lensScale(0f, 0f, 2f), 0f)
        assertEquals(0f, lensScale(0.2f, 0.2f, 0f), 0f) // off screen
        assertEquals(0f, lensScale(Honeycomb.LENS_A, 0f, 5f), 0f) // past the lens
        assertEquals(0.5f, lensScale(0f, 0f, Honeycomb.LENS_E / 2), 1e-6f) // the edge ramp is linear
        assertEquals(1f, lensScale(1.8f, 0f, 0.15f, equal = true), 0f) // Reduce Motion: equal sizes
        assertEquals(0f, lensScale(1.8f, 0f, -0.1f, equal = true), 0f)
        // Smaller the further out, along each axis.
        assertTrue(lensScale(1f, 0f, 5f) > lensScale(2f, 0f, 5f))
        assertTrue(lensScale(0f, 1f, 5f) > lensScale(0f, 2f, 5f))
    }

    @Test fun rubberBandRoundTripsAndNeverPassesReach() {
        val d = 3f
        for (x in listOf(0f, 0.1f, 1f, 5f, 50f)) {
            val y = rubber(x, d)
            assertTrue(y <= d)
            assertEquals(x, unrubber(y, d), 1e-3f * maxOf(1f, x))
        }
        assertEquals(0.55f, (rubber(1e-4f, d) / 1e-4f), 1e-3f) // slope c at the edge
        assertEquals(-2f, band(-2f, -4f, 4f, d), 0f) // inside the bounds nothing changes
        assertEquals(4f + rubber(1f, d), band(5f, -4f, 4f, d), 1e-6f)
        assertEquals(-4f - rubber(2f, d), band(-6f, -4f, 4f, d), 1e-6f)
        assertEquals(5f, unband(band(5f, -4f, 4f, d), -4f, 4f, d), 1e-4f)
    }

    @Test fun edgeReturnMatchesTheFit() {
        // bounce-fit.json: 55.46 px decays to within 5 px in about 0.37 s after the fit starts (0.43 s after the peak).
        assertEquals(55.46f, edgeReturn(55.46f, 0f), 0f)
        assertEquals(55.46f * exp(-1f), edgeReturn(55.46f, Honeycomb.EDGE_TAU), 1e-4f)
        assertTrue(edgeReturn(55.46f, 0.37f) < 5f)
        assertTrue(edgeReturn(55.46f, 0.30f) > 5f)
    }

    @Test fun exponentialEasingEndsExactlyAndFollowsTheCurve() {
        val e = ExponentialEasing(Honeycomb.EDGE_TAU, Honeycomb.EDGE_MS / 1000f)
        assertEquals(0f, e.transform(0f), 0f)
        assertEquals(1f, e.transform(1f), 1e-6f)
        val t = 0.15f
        val raw = 1f - exp(-t / Honeycomb.EDGE_TAU)
        assertEquals(raw, e.transform(t / (Honeycomb.EDGE_MS / 1000f)), 0.02f) // the renormalisation is under 2%
    }

    @Test fun openWidthMatchesTheFilmFrames() {
        // tap-snapshot-widths.csv, 30000/1001 fps, from the first snapshot frame.
        val t = floatArrayOf(0f, 0.0334f, 0.0667f, 0.1001f, 0.1335f, 0.1668f, 0.2002f, 0.2336f, 0.2669f, 0.3003f, 0.3337f, 0.3670f, 0.4004f)
        val w = floatArrayOf(170f, 199f, 223f, 240f, 254f, 264f, 270f, 276f, 280f, 282f, 284f, 286f, 287f)
        for (i in t.indices) assertEquals("frame $i", w[i], openWidth(168.05f, 288f, t[i]), 3.5f)
        assertTrue("full width by 400 ms", 288f - openWidth(170f, 288f, Honeycomb.OPEN_MS / 1000f) < 3f)
    }

    @Test fun bounceIsCappedToTheMeasuredOvershoot() {
        val d0 = 120f
        assertEquals(Honeycomb.BOUNCE_PEAK * d0, bouncePeak(maxBounceSpeed(d0)), 1e-3f)
        // Critically damped x(t) = v t exp(-t/tau) peaks at t = tau.
        val v = 900f
        var peak = 0f
        var t = 0f
        while (t < 1f) { peak = maxOf(peak, v * t * exp(-t / Honeycomb.EDGE_TAU)); t += 1e-4f }
        assertEquals(bouncePeak(v), peak, 0.01f)
        assertEquals(44.15f, stiffnessFor(Honeycomb.EDGE_TAU), 0.01f)
    }

    @Test fun zoomSpansFourTimesInFourKeyPresses() {
        assertEquals(4f, Honeycomb.ZOOM_MAX / Honeycomb.ZOOM_MIN, 0f)
        var z = Honeycomb.ZOOM_MIN
        repeat(4) { z = zoomStep(z, 1) }
        assertEquals(Honeycomb.ZOOM_MAX, z, 1e-5f)
        assertEquals(Honeycomb.ZOOM_MAX, zoomStep(Honeycomb.ZOOM_MAX, 1), 0f)
        assertEquals(Honeycomb.ZOOM_MIN, zoomStep(Honeycomb.ZOOM_MIN, -1), 0f)
        assertTrue(abs(zoomStep(1f, -1) - 1f / sqrt(2f)) < 1e-6f)
    }
}
