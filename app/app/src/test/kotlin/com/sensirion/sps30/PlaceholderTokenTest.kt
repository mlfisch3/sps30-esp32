package com.sensirion.sps30

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The placeholder product tokens must never reach a user.
 *
 * `SENSORQ` and `APPQ` stand in for a product name that has not been chosen.
 * They exist so a single search-and-replace can put the real name into the
 * *documentation* - which is exactly why they are poisonous on screen: a
 * string that ships with one in it ships the placeholder as the product name.
 * One did: the power panel told the captain about "The SENSORQ controller".
 *
 * So this test walks every main-source string literal in all three modules
 * (app, core and wear), every resource XML and both manifests, and fails on
 * any match, naming the file. Comments may mention the tokens - the mDNS instance name
 * on the wire really is `SENSORQ` until the rename, and documenting that is
 * correct - but no string a user could see may carry one.
 *
 * Case-sensitive on purpose: the tokens are the uppercase forms, and the wire
 * service type `_sensorq._tcp` is a protocol constant that must keep matching
 * the firmware, not a leak.
 */
class PlaceholderTokenTest {

    private val tokens = listOf("SENSORQ", "APPQ")

    /** Both `"""..."""` blocks and ordinary `"..."` literals, in that order so
     *  a triple-quote is never half-read as two empty strings. */
    private val tripleQuoted = Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val quoted = Regex("\"(?:\\\\.|[^\"\\\\\n])*\"")

    /** The Gradle project root: the nearest ancestor with settings.gradle.kts.
     *  Tests run with the module directory as the working directory, but this
     *  must not silently scan nothing if that ever changes. */
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

    @Test
    fun `no user-facing string carries a placeholder product token`() {
        val root = gradleRoot()
        val mains = listOf(
            root.resolve("app/src/main"),
            // :core is multiplatform: its production code is split across the
            // common and JVM source sets, and both are user-facing strings.
            root.resolve("core/src/commonMain"),
            root.resolve("core/src/jvmMain"),
            root.resolve("wear/src/main"),
        ).filter { it.isDirectory }
        // All three modules (four trees), and loudly if one goes missing: a scan
        // that walks fewer trees than the project has is the silent kind of green.
        assertTrue("expected 4 main source sets under $root, found ${mains.size}", mains.size == 4)

        val leaks = mutableListOf<String>()
        var kotlinScanned = 0
        var xmlScanned = 0

        for (main in mains) {
            for (file in main.walkTopDown().filter { it.isFile }) {
                when (file.extension) {
                    "kt" -> {
                        kotlinScanned++
                        for (literal in stringLiterals(file.readText())) {
                            for (token in tokens) {
                                if (literal.contains(token)) {
                                    leaks += "${file.relativeTo(root)}: $literal"
                                }
                            }
                        }
                    }
                    "xml" -> {
                        xmlScanned++
                        val text = file.readText()
                        for (token in tokens) {
                            if (text.contains(token)) {
                                leaks += "${file.relativeTo(root)}: contains $token"
                            }
                        }
                    }
                }
            }
        }

        // A scan that found nothing to scan proves nothing. These floors are
        // far below the real counts, and exist so a moved directory fails
        // loudly instead of passing vacuously.
        assertTrue("scanned only $kotlinScanned Kotlin files - wrong root?", kotlinScanned > 50)
        assertTrue("scanned only $xmlScanned XML files - wrong root?", xmlScanned > 5)

        assertTrue(
            "placeholder product tokens in user-facing strings:\n" +
                leaks.joinToString("\n"),
            leaks.isEmpty(),
        )
    }
}
