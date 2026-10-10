package com.sensirion.sps30

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No user-facing string may claim an air-quality category.
 *
 * The watch used to label live readings with the US EPA PM2.5 categories
 * ("Good" through "Hazardous"). A compliance review found the breakpoints out
 * of date, every one of them understating severity, and the whole scale defined
 * for a 24-hour average while being applied to an instantaneous sample - so the
 * categories were removed, and every watch surface now shows the reading and
 * its unit, as the phone always has. This test is what keeps them out: it walks
 * the wear module's main-source string literals and resource XML the same way
 * [PlaceholderTokenTest] does, and fails on any category word, naming the file.
 *
 * The app and core modules never carried the categories, and the two words with
 * no innocent reading are held out of them too, so a category label cannot come
 * back through the phone or a notification either. Comments may discuss the
 * words - documenting the removal is correct - but no string a user could see
 * may carry one. The user's own alert-rule labels ("Elevated", "High", "Very
 * high") are a different thing: those are thresholds the user sets and names,
 * not a health judgement made on their behalf, and they are not matched here.
 */
class AirQualityCategoryTest {

    /** Words with no plausible innocent use in a user-facing string. */
    private val severityWords = listOf("Unhealthy", "Hazardous")

    /** Ambiguous elsewhere, but the watch has no other use for them. */
    private val wearOnlyWords = listOf("Moderate")

    /** Too common for a contains-match; only an exact literal is a label. */
    private val wearExactLiterals = listOf("\"Good\"")

    private val tripleQuoted = Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val quoted = Regex("\"(?:\\\\.|[^\"\\\\\n])*\"")

    private fun gradleRoot(): File {
        val start = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var dir = File(start).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("no settings.gradle.kts above $start")
        }
        return dir
    }

    private fun stringLiterals(kotlinSource: String): List<String> {
        val triples = tripleQuoted.findAll(kotlinSource).map { it.value }.toList()
        val rest = tripleQuoted.replace(kotlinSource, "")
        return triples + quoted.findAll(rest).map { it.value }
    }

    /** Scans one source tree; returns "file: literal" lines for every hit. */
    private fun scan(
        root: File,
        main: File,
        containsWords: List<String>,
        exactLiterals: List<String>,
        counts: IntArray,
    ): List<String> {
        val leaks = mutableListOf<String>()
        for (file in main.walkTopDown().filter { it.isFile }) {
            when (file.extension) {
                "kt" -> {
                    counts[0]++
                    for (literal in stringLiterals(file.readText())) {
                        val hit = containsWords.any { literal.contains(it, ignoreCase = true) } ||
                            exactLiterals.any { literal == it }
                        if (hit) leaks += "${file.relativeTo(root)}: $literal"
                    }
                }
                "xml" -> {
                    counts[1]++
                    val text = file.readText()
                    for (word in containsWords) {
                        if (text.contains(word, ignoreCase = true)) {
                            leaks += "${file.relativeTo(root)}: contains $word"
                        }
                    }
                }
            }
        }
        return leaks
    }

    @Test
    fun `no watch string carries a category word`() {
        val root = gradleRoot()
        val main = root.resolve("wear/src/main")
        assertTrue("wear main sources missing at $main", main.isDirectory)

        val counts = IntArray(2)
        val leaks = scan(root, main, severityWords + wearOnlyWords, wearExactLiterals, counts)

        // A scan that found nothing to scan proves nothing; these floors are
        // well below the real counts and fail loudly if the tree moves.
        assertTrue("scanned only ${counts[0]} Kotlin files - wrong root?", counts[0] >= 6)
        assertTrue("scanned only ${counts[1]} XML files - wrong root?", counts[1] >= 4)

        assertTrue(
            "air-quality category words in watch strings:\n" + leaks.joinToString("\n"),
            leaks.isEmpty(),
        )
    }

    @Test
    fun `no phone or core string carries a severity word`() {
        val root = gradleRoot()
        val mains = listOf(
            root.resolve("app/src/main"),
            root.resolve("core/src/commonMain"),
            root.resolve("core/src/jvmMain"),
        ).filter { it.isDirectory }
        assertTrue("expected 3 main source sets under $root, found ${mains.size}", mains.size == 3)

        val counts = IntArray(2)
        val leaks = mains.flatMap { scan(root, it, severityWords, emptyList(), counts) }

        assertTrue("scanned only ${counts[0]} Kotlin files - wrong root?", counts[0] > 50)
        assertTrue("scanned only ${counts[1]} XML files - wrong root?", counts[1] > 3)

        assertTrue(
            "air-quality severity words in phone/core strings:\n" + leaks.joinToString("\n"),
            leaks.isEmpty(),
        )
    }
}
