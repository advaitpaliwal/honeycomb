package org.honeycomb.launcher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

/**
 * circleIcon's crop on a real phone's icons: a disc is one clean circle with no ring inside it; a tile or glyph
 * keeps its whole glyph on its plate.
 *
 * Fixtures are the phone's own icons (captured with IconLayerProbe at xxxhdpi, as installedApps loads them): each
 * adaptive icon's background and foreground drawn together over the 108 dp square at 4 px per dp. The mask shows the
 * middle 72 dp, the 288 px [planCrop] measures.
 */
class CircleIconTest {
    private val n = PLAN_PX

    /** The middle 72 dp of the real icon's layers, as ARGB. */
    private fun real(name: String): IntArray {
        val img = ImageIO.read(javaClass.getResource("/icons-real/$name.layers.png")!!)
        val o = (img.width - n) / 2
        return IntArray(n * n) { img.getRGB(o + it % n, o + it / n) }
    }

    /** The pixels of the finished circle (out px across) at radii [from]..[to] of it, sampled from [art] through [crop]. */
    private fun band(art: IntArray, crop: FloatArray?, from: Float, to: Float, out: Int = 200): List<Int> {
        val c = crop ?: floatArrayOf(0f, 0f, 1f)
        val px = ArrayList<Int>()
        for (y in 0 until out) for (x in 0 until out) {
            val r = hypot(x + 0.5f - out / 2f, y + 0.5f - out / 2f) / (out / 2f)
            if (r < from || r > to) continue
            val ax = ((c[0] + (x + 0.5f) / out * c[2]) * n).toInt().coerceIn(0, n - 1)
            val ay = ((c[1] + (y + 0.5f) / out * c[2]) * n).toInt().coerceIn(0, n - 1)
            px.add(art[ay * n + ax])
        }
        return px
    }

    private val PLATE = -1

    private fun white(p: Int) = (0..2).all { (p shr (it * 8) and 255) >= 239 }
    private fun pale(p: Int) = (0..2).all { (p shr (it * 8) and 255) >= 160 }
    private fun share(px: List<Int>, f: (Int) -> Boolean) = px.count(f).toFloat() / px.size

    @Test fun theClockIsItsFaceWithNoBezelRingAndNoPlate() {
        val art = real("deskclock")
        val crop = planCrop(art, n, adaptive = true)
        assertNotNull("the Clock is a disc on a white plate", crop)
        // the outer tenth of the circle: before, the white plate and then the clock's own pale bezel (175-247/255)
        val rim = band(art, crop, 0.85f, 0.98f)
        assertTrue("pale share of the Clock's rim ${share(rim, ::pale)}", share(rim, ::pale) < 0.02f)
    }

    @Test fun aTileOnAWhitePlateKeepsItsWholeGlyphCentredOnThePlate() {
        // Calendar's 31, the Camera, Files: cropping the tile to a circle cut Calendar's top band, turned Files into a
        // corner and took the Camera's body. Launcher3 shows the
        // whole 72 dp the mask covers, the glyph inside its safe zone, on the app's own white background.
        for (name in listOf("calendar", "camera", "files")) {
            val art = real(name)
            val crop = planCrop(art, n, adaptive = true) ?: floatArrayOf(0f, 0f, 1f)
            val glyph = contentMask(art, n, plateColour(art, n)!!, PLATE_TOL)
            // every glyph pixel, where it lands in the finished circle (0.5 is the circle's edge)
            var cut = 0; var far = 0f; var l = 1f; var t = 1f; var r = 0f; var b = 0f
            for (i in glyph.indices) if (glyph[i] == 255) {
                val u = ((i % n + 0.5f) / n - crop[0]) / crop[2]; val v = ((i / n + 0.5f) / n - crop[1]) / crop[2]
                val d = hypot(u - 0.5f, v - 0.5f); far = maxOf(far, d); if (d > 0.5f) cut++
                l = minOf(l, u); t = minOf(t, v); r = maxOf(r, u); b = maxOf(b, v)
            }
            assertTrue("$name: $cut glyph pixels cut by the circle (farthest ${far / 0.5f} of the radius)", cut == 0 && far < 0.48f)
            assertTrue("$name: glyph centre ${(l + r) / 2}, ${(t + b) / 2}", abs((l + r) / 2 - 0.5f) < 0.04f && abs((t + b) / 2 - 0.5f) < 0.04f)
            val rim = band(art, crop, 0.9f, 0.98f)
            assertTrue("$name: white share of the rim ${share(rim, ::white)}", share(rim, ::white) > 0.98f)
        }
    }

    @Test fun aRealDiscFillsTheCircle() {
        val art = real("chrome")
        val crop = planCrop(art, n, adaptive = true)
        assertNotNull(crop)
        assertTrue(share(band(art, crop, 0.85f, 0.98f), ::white) < 0.02f)
    }

    @Test fun aGlyphOnAPlateKeepsItsPlate() {
        // Gmail's M, Settings' gear on blue, the Messages bubble, the Phone handset, Photos' pinwheel, a map pin, Meet
        for (name in listOf("gm", "settings", "messaging", "dialer", "photos", "maps", "tachyon"))
            assertNull("$name keeps its plate", planCrop(real(name), n, adaptive = true))
    }

    @Test fun aLegacyIconFillsTheCircleAndItsShadowDoesNotCount() {
        // a round face of radius 86 at full alpha with a soft shadow (alpha 100) under it
        val face = IntArray(n * n) { i ->
            val x = i % n + 0.5f; val y = i / n + 0.5f
            when {
                hypot(x - 144, y - 140) <= 86 -> 0xFF4285F4.toInt()
                hypot(x - 144, y - 146) <= 90 -> 100 shl 24
                else -> 0
            }
        }
        val crop = planCrop(face, n, adaptive = false)!!
        assertArrayEquals(floatArrayOf(58f / n, 54f / n, 172f / n), crop, 1.5f / n)
    }

    @Test fun anEdgeThatIsNotOneColourIsNoPlate() {
        val gradient = IntArray(n * n) { i -> (0xFF000000.toInt()) or ((i % n) * 255 / n shl 16) }
        assertNull(plateColour(gradient, n))
        assertNull(planCrop(gradient, n, adaptive = true))
    }
}
