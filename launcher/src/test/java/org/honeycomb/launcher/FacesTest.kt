package org.honeycomb.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.min
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class FacesTest {
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before fun pin() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); Locale.setDefault(Locale.US) }
    @After fun restore() { TimeZone.setDefault(zone); Locale.setDefault(locale) }

    private fun at(h: Int, m: Int, s: Int = 0) =
        Calendar.getInstance().apply { clear(); set(2026, 10, 19, h, m, s) }.timeInMillis

    @Test fun digitalFacesFollowTheTwelveOrTwentyFourHourSetting() {
        assertEquals("9" to "41", digits(at(21, 41), h24 = false))
        assertEquals("21" to "41", digits(at(21, 41), h24 = true))
        assertEquals("12" to "05", digits(at(0, 5), h24 = false))
        assertEquals("00" to "05", digits(at(0, 5), h24 = true))
    }

    @Test fun numeralsDuoShowsTwoDigitsInEachRowAsTheWatchDoes() {
        assertEquals("09" to "41", duoDigits(at(21, 41), h24 = false))
        assertEquals("21" to "41", duoDigits(at(21, 41), h24 = true))
        assertEquals("12" to "05", duoDigits(at(0, 5), h24 = false))
        assertEquals("00" to "05", duoDigits(at(0, 5), h24 = true))
    }

    @Test fun siriModularsTimeIsSizedFromTheCapturesCapHeight() {
        // Siri Modular's time is 42.5 pt tall: Inter at about 58.4 pt
        assertEquals(58.42f, WatchFace.sizeFor(WatchFace.TIME_CAP, WatchFace.INTER_CAP), 0.01f)
    }

    @Test fun watchScreenDigitsScaleWholeAndFillTheirBox() {
        // a wide run in a tall box: the width binds, the run spans it edge to edge and stands centred, never stretched
        val wide = placeDigits(inkLeft = 5f, inkWidth = 90f, capTop = -80f, capHeight = 80f, x = 16f, y = 40f, w = 380f, h = 820f)
        assertEquals(wide.scaleX, wide.scaleY, 0f)
        assertEquals(16f, wide.dx + 5f * wide.scaleX, 0.01f)
        assertEquals(16f + 380f, wide.dx + 95f * wide.scaleX, 0.01f)
        assertEquals(40f + 410f, wide.dy + -40f * wide.scaleY, 0.01f)
        // a narrow run in a short box: the height binds, top and bottom of the digits on the box's edges
        val tall = placeDigits(inkLeft = 0f, inkWidth = 40f, capTop = -80f, capHeight = 80f, x = 0f, y = 100f, w = 380f, h = 400f)
        assertEquals(tall.scaleX, tall.scaleY, 0f)
        assertEquals(100f, tall.dy + -80f * tall.scaleY, 0.01f)
        assertEquals(500f, tall.dy, 0.01f)
        // every shape of run and box, from a "1" to "00" in a phone, a tablet or a sliver: one factor both ways
        for (iw in listOf(20f, 47f, 95f, 300f)) for ((bw, bh) in listOf(380f to 820f, 800f to 1200f, 50f to 900f, 900f to 50f)) {
            val f = placeDigits(0f, iw, -80f, 80f, 0f, 0f, bw, bh)
            assertEquals("ink $iw in $bw x $bh", f.scaleX, f.scaleY, 0f)
        }
    }

    @Test fun watchScreenFacesAreSizedFromTheScreenNotTheWatch() {
        // Stacked, Split and Hour take their size from the screen box (safe) alone: no watch point drives them, and they
        // draw their digits only through drawDigits, whose one transform scales by GlyphFit's factors
        val main = listOf(java.io.File("src/main/java"), java.io.File("launcher/src/main/java")).first { it.isDirectory }
        val src = java.io.File(main, "org/honeycomb/launcher/Faces.kt").readText()
        val start = src.indexOf("// ---- The watch-screen digits")
        val end = src.indexOf("// ---- hands, shared by the analog faces")
        val hourStart = src.indexOf("// ---- Hour:")
        val faces = src.substring(start, end) + src.substring(hourStart, src.indexOf("// ---- Plain:"))
        listOf("WatchFace.", "watchUnit", "SiriModular", "safe(margins = false)").forEach {
            assertFalse("a watch-screen face reads $it", faces.contains(it))
        }
        listOf("private fun StackedFace", "private fun SplitFace", "private fun HourFace").forEach { assertTrue(it, faces.contains(it)) }
        // the only scaling is drawDigits' scale(fit.scaleX, fit.scaleY), and nothing squeezes text
        assertEquals(listOf("scale(fit.scaleX, fit.scaleY"), Regex("""\bscale\([^,)]*,[^,)]*""").findAll(faces).map { it.value }.toList())
        listOf("textScaleX", "scaleX =", "scaleY =", "graphicsLayer", "drawText(").forEach { assertFalse(it, faces.contains(it)) }
    }

    @Test fun handsPointWhereAClocksDo() {
        assertEquals(Triple(90f, 0f, 0f), handAngles(at(15, 0)))
        // half past nine: the hour hand halfway between 9 and 10, the minute hand at 6
        assertEquals(Triple(285f, 180f, 0f), handAngles(at(21, 30)))
        // the minute hand creeps with the seconds, the second hand moves 6 degrees a second
        assertEquals(Triple(0.25f, 3f, 180f), handAngles(at(0, 0, 30)))
    }

    @Test fun thePickerHasSixFacesAndKeepsTheOldInformationFaceAtItsSavedIndex() {
        assertEquals(listOf("Stacked", "Info", "Split", "Hour", "Classic", "Plain"), FaceChoice.names)
    }

    @Test fun theClassicTrackFollowsATallFace() {
        // a 100 x 200 face: twelve sits on the top edge, three on the side, and one o'clock on the side (a tall face's ray at 30 degrees misses the top)
        val top = trackPoint(0f, 50f, 100f, 1f); assertEquals(0f, top.x, 0.01f); assertEquals(-100f, top.y, 0.01f)
        val three = trackPoint(90f, 50f, 100f, 1f); assertEquals(50f, three.x, 0.01f); assertEquals(0f, three.y, 0.01f)
        val one = trackPoint(30f, 50f, 100f, 1f); assertEquals(50f, one.x, 0.05f); assertEquals(-86.6f, one.y, 0.1f)
    }

    /** How far, in degrees, [p] seen from [c] is off the direction [deg] clockwise from twelve. */
    private fun off(c: androidx.compose.ui.geometry.Offset, p: androidx.compose.ui.geometry.Offset, deg: Float): Float {
        val a = Math.toDegrees(kotlin.math.atan2((p.x - c.x).toDouble(), (c.y - p.y).toDouble())).toFloat()
        return ((a - deg) % 360f + 540f) % 360f - 180f
    }

    // Pixel 9a: 1080 x 2424 px at 2.625, a 16 dp window margin, the status bar and gesture bar, and its 116 px display corners
    private val pixel9a = dialBox(1080f, 2424f, 42f, 160f, 1038f, 2424f - 105f, 116f)
    // numerals' ink half-sizes per px of font size, about what the system font draws (XII wide, 3 narrow)
    private val inks = californiaNumerals.map { (0.18f + 0.12f * (it.length - 1) + if (it.all { c -> c in "XVI" }) 0.05f else 0f) to 0.36f }

    private fun assertMarksPointLikeTheHands(box: DialBox) {
        val dial = classicDial(box, min(box.a, box.b) * 0.26f, inks)
        for (i in 0 until 60) {
            assertEquals("tick $i outer end", 0f, off(box.centre, dial.tickOuter[i], i * 6f), 0.05f)
            assertEquals("tick $i inner end", 0f, off(box.centre, dial.tickInner[i], i * 6f), 0.05f)
        }
        for (i in 0 until 12) assertEquals("numeral ${californiaNumerals[i]}", 0f, off(box.centre, dial.numerals[i], i * 30f), 0.05f)
    }

    @Test fun everyClassicMarkSitsOnTheAngleItsHandPointsTo() {
        assertMarksPointLikeTheHands(pixel9a)
        // a landscape tablet: 2560 x 1600 px at 2, 24 dp margins, square display corners
        assertMarksPointLikeTheHands(dialBox(2560f, 1600f, 48f, 48f + 48f, 2512f, 1600f - 48f - 48f, 0f))
    }

    @Test fun theDialFillsTheScreenConcentricWithItsCorners() {
        // the track reaches the safe area on the short axis and comes in from the display's corner by the margin
        assertEquals(498f, pixel9a.a, 0.01f)
        assertEquals(116f - 42f, pixel9a.r, 0.01f)
        val dial = classicDial(pixel9a, min(pixel9a.a, pixel9a.b) * 0.26f, inks)
        // twelve stands on the top of the track, three on its side
        assertEquals(pixel9a.centre.y - pixel9a.b, dial.tickOuter[0].y, 0.5f)
        assertEquals(pixel9a.centre.x + pixel9a.a, dial.tickOuter[15].x, 0.5f)
    }

    @Test fun classicNumeralsNeverCrowdTheyShrinkToTheGap() {
        for (box in listOf(pixel9a, dialBox(2560f, 1600f, 48f, 96f, 2512f, 1504f, 0f), dialBox(1000f, 1000f, 40f, 40f, 960f, 960f, 100f))) {
            val dial = classicDial(box, min(box.a, box.b) * 0.26f, inks)
            val s = dial.numeralSize
            for (i in 0 until 12) {
                val j = (i + 1) % 12
                val dx = kotlin.math.abs(dial.numerals[i].x - dial.numerals[j].x) - (inks[i].first + inks[j].first) * s
                val dy = kotlin.math.abs(dial.numerals[i].y - dial.numerals[j].y) - (inks[i].second + inks[j].second) * s
                assertTrue("${californiaNumerals[i]} and ${californiaNumerals[j]} crowd", maxOf(dx, dy) >= 0.4f * s - 0.5f)
            }
        }
    }

    @Test fun noClassicTickTouchesANumeralOnTallSquarishOrPhoneScreens() {
        // a Pixel 9a, a 4:3 screen (960 x 1280) and a tall 20:9 one (720 x 1600), with their bars and margins
        val shapes = listOf(pixel9a, dialBox(960f, 1280f, 32f, 32f + 48f, 928f, 1280f - 32f - 48f, 60f), dialBox(720f, 1600f, 32f, 32f + 80f, 688f, 1600f - 32f - 50f, 80f))
        for (box in shapes) {
            val dial = classicDial(box, min(box.a, box.b) * 0.26f, inks)
            val s = dial.numeralSize
            for (i in 0 until 12) for (t in 0 until 60) {
                val gap = segmentBoxDistance(dial.tickOuter[t], dial.tickInner[t], dial.numerals[i], inks[i].first * s, inks[i].second * s) - dial.tickWidth(t) / 2
                assertTrue("${californiaNumerals[i]} is $gap px from tick $t", gap >= classicTickGap * s - 0.5f)
            }
        }
    }

    @Test fun theClassicMinuteHandStopsAtTheNumerals() {
        for (box in listOf(pixel9a, dialBox(2560f, 1600f, 48f, 96f, 2512f, 1504f, 0f))) {
            val dial = classicDial(box, min(box.a, box.b) * 0.26f, inks)
            for (i in 0 until 12) {
                val v = dial.numerals[i] - box.centre; val len = v.getDistance()
                val inner = len - (inks[i].first * kotlin.math.abs(v.x) + inks[i].second * kotlin.math.abs(v.y)) / len * dial.numeralSize
                assertTrue("minute hand runs into ${californiaNumerals[i]}", dial.handRadius * 0.85f <= inner)
            }
        }
    }

    @Test fun tallerScreensGetLongerHandsThatStayOnTheDial() {
        val phone = dialHandRadius(pixel9a)
        val square = dialHandRadius(dialBox(1080f, 1080f, 42f, 42f, 1038f, 1038f, 0f))
        assertTrue(phone > square)
        // the second hand (0.87 of the radius) ends inside the short axis
        assertTrue(phone * 0.87f <= pixel9a.a + 0.01f)
    }

    @Test fun facesKeepMaterialWindowMargins() {
        assertEquals(16f, windowMargin(411f))
        assertEquals(16f, windowMargin(599f))
        assertEquals(24f, windowMargin(600f))
        assertEquals(24f, windowMargin(800f))
    }

    @Test fun withNothingLiveTheInfoFaceShowsNoCirclesAndCentresTheDateAndTime() {
        // a Pixel 9a's safe box: 379 x 820 dp; the time fills the width at 170 dp tall
        val l = infoScreen(379f, 820f, 170f, 0)
        assertEquals(0, l.circles)
        assertEquals(170f, l.timeCap, 0.01f)
        val bottom = l.timeTop + l.timeCap
        assertEquals("centred", l.dateTop, 820f - bottom, 0.01f)
        assertEquals(379f * InfoScale.MIN_GAP, l.timeTop - (l.dateTop + l.dateCap), 0.01f)
    }

    @Test fun liveSourcesBecomeOneRowOfCirclesAndTheScreenSharesItsSpareHeightEvenly() {
        val l = infoScreen(379f, 820f, 170f, 2)
        assertEquals(2, l.circles)
        assertEquals(379f * 32f / 144f, l.circle, 0.01f)
        val above = l.dateTop
        val dateToTime = l.timeTop - (l.dateTop + l.dateCap)
        val timeToCircles = l.circlesTop - (l.timeTop + l.timeCap)
        val below = 820f - (l.circlesTop + l.circle)
        listOf(dateToTime, timeToCircles, below).forEach { assertEquals(above, it, 0.01f) }
        // a short screen gives the time only what the rest leaves, and the row still fits
        val short = infoScreen(379f, 300f, 170f, 3)
        assertTrue(short.circlesTop + short.circle <= 300f + 0.01f)
        assertTrue(short.timeCap < 170f)
    }

    @Test fun complicationsAreOnlyRealSourcesLiveFirstThenMusicThenTheAlarmAndAtMostThree() {
        assertEquals(emptyList<Complication>(), complications(emptyList(), playing = false, alarm = false))
        assertEquals(listOf(Complication.Live("t"), Complication.Playing), complications(listOf("t"), playing = true, alarm = false))
        assertEquals(listOf(Complication.Playing, Complication.Alarm), complications(emptyList(), playing = true, alarm = true))
        assertEquals(listOf(Complication.Live("a"), Complication.Live("b"), Complication.Playing),
            complications(listOf("a", "b"), playing = true, alarm = true))
    }

    @Test fun theInfoTimeNarrowsOnTheFontsWidthAxisAndOtherwiseScalesWhole() {
        // fits already: normal width, full size
        assertEquals(InfoTimeFit(INFO_WDTH_NORMAL, 1f), infoTimeFit(500f, 480f, 360f) { error("not measured") })
        // 600 wide, 450 at the narrowest, 525 wanted: half way down the axis, and the checked width fits whole
        val half = infoTimeFit(525f, 600f, 450f) { w -> 450f + (600f - 450f) * (w - INFO_WDTH_NARROW) / (INFO_WDTH_NORMAL - INFO_WDTH_NARROW) }
        assertEquals(87.5f, half.wdth, 0.01f); assertEquals(1f, half.scale, 0.001f)
        // too wide even at the narrowest: the narrowest outlines, then the size scales down uniformly
        val both = infoTimeFit(400f, 600f, 450f) { 450f }
        assertEquals(INFO_WDTH_NARROW, both.wdth, 0.01f); assertEquals(400f / 450f, both.scale, 0.001f)
        // a font without the axis measures the same at every width: no axis value, the whole time scales
        val flat = infoTimeFit(400f, 600f, 600f) { error("not measured") }
        assertEquals(InfoTimeFit(INFO_WDTH_NORMAL, 400f / 600f), flat)
    }
}
