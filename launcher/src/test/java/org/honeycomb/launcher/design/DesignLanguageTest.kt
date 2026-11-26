package org.honeycomb.launcher.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The enforcement test (docs/design-language.md §9): outside `design/`, no screen may use a colour literal, a literal
 * corner, its own text style or size, an RGB colour, plain White or Black, a White/Black alpha, the bitmap outerShadow, or a tween with a literal duration.
 */
class DesignLanguageTest {
    private val rules = linkedMapOf(
        "Color(0x" to Regex("""Color\(0x"""),
        "RoundedCornerShape(" to Regex("""RoundedCornerShape\("""),
        "TextStyle(" to Regex("""TextStyle\("""),
        "fontSize =" to Regex("""fontSize\s*="""),
        "White/Black.copy(alpha" to Regex("""\b(White|Black)\.copy\(\s*alpha"""),
        "outerShadow" to Regex("""outerShadow"""),
        "tween(<digit>" to Regex("""tween\(\s*(durationMillis\s*=\s*)?\d"""),
        // the one scale (design/Scale.kt): no screen divides by the watch's width or sets its own density
        "/ 211" to Regex("""/\s*(211(\.0)?f?\b|(Wp\.)?WATCH_W\b|ControlGrid\.WIDTH\b)"""),
        "density override" to Regex("""\bDensity\(|LocalDensity\s+provides"""),
        // colours come from the scheme's roles (design/HoneycombColors.kt): no RGB literal and no plain white or black
        "Color(<digit>" to Regex("""\bColor\(\s*\d"""),
        "Color.White/Black" to Regex("""\bColor\.(White|Black)\b"""),
    )

    private val root: File = listOf(File("src/main/java"), File("launcher/src/main/java")).first { it.isDirectory }

    /** Violations per file (path relative to src/main/java), each rule's count, files outside design/ only. */
    private fun violations(): Map<String, Map<String, Int>> = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/design/" !in it.invariantSeparatorsPath.removePrefix(root.invariantSeparatorsPath) }
        .associate { f ->
            val text = f.readText()
            f.relativeTo(root).invariantSeparatorsPath to rules.mapValues { (_, r) -> r.findAll(text).count() }.filterValues { it > 0 }
        }
        .filterValues { it.isNotEmpty() }

    @Test fun reportsViolationsPerFile() {
        val v = violations()
        val total = v.values.sumOf { it.values.sum() }
        println("DesignLanguageTest: $total violations in ${v.size} files")
        v.entries.sortedByDescending { it.value.values.sum() }.forEach { (file, counts) ->
            println("  %4d  %s  %s".format(counts.values.sum(), file, counts.entries.joinToString { "${it.key} ${it.value}" }))
        }
        assertTrue("the scan found no Kotlin files under $root", root.walkTopDown().any { it.extension == "kt" })
    }

    @Test fun theScanCatchesEachRule() {
        // each rule matches the thing it forbids, so a clean report means clean code, not a broken pattern
        val samples = listOf("Color(0xFF000000)", "RoundedCornerShape(12.dp)", "TextStyle(color = c)", "fontSize = 12.sp",
            "Color.White.copy(alpha = 0.5f)", "Modifier.outerShadow()", "tween(300)", "maxWidth / 211", "Density(2f, 1f)",
            "Color(255, 66, 69)", "Color.White")
        rules.values.zip(samples).forEach { (r, s) -> assertTrue("$r misses $s", r.containsMatchIn(s)) }
        assertTrue(!rules.getValue("tween(<digit>").containsMatchIn("tween(Motion.DEPTH_MS)"))
        listOf("w / Wp.WATCH_W", "maxWidth / ControlGrid.WIDTH", "x / 211f").forEach { assertTrue(it, rules.getValue("/ 211").containsMatchIn(it)) }
        assertTrue(rules.getValue("density override").containsMatchIn("CompositionLocalProvider(LocalDensity provides real)"))
        assertTrue(!rules.getValue("/ 211").containsMatchIn("x / 2110f"))
    }

    @Test fun noScreenBreaksTheLanguage() {
        val v = violations()
        assertTrue("design-language violations: $v", v.isEmpty())
    }
}
