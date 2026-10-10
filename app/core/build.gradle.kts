// The portable core of the app: the wire client (`wire/`), the data layer
// (`data/`), the connection and discovery policy (`net/`, minus the Android
// radios), and the watch relay policy (`wear/RelayPolicy` and friends).
//
// This module has **no Android dependency**, and that is its reason to exist:
// everything in it is behaviour a second platform needs verbatim, and keeping
// it compilable without an SDK is what proves that claim on every build rather
// than by inspection. Do not add an Android or AndroidX dependency here - code
// that needs one belongs in :app, behind a seam this module defines (see
// `net/ControllerSockets.kt` and `net/ServiceBrowser.kt` for two existing ones).
//
// It is a Kotlin **Multiplatform** module - the architecture the iOS decision
// selected per the shared-core design decision. Three source
// sets matter:
//
//   commonMain    code proven to compile for every target. Today that is the
//                 wire client, held to `vectors/wire_vectors.json` on every
//                 target by the commonTest vector suite. Code moves here from
//                 jvmMain as its JVM-only dependencies are migrated, never the
//                 other way.
//   jvmMain       everything not yet migrated off JVM-only APIs (java.time,
//                 java.io/zip, java.net sockets, String.format). This is the
//                 to-do list for the iOS core, kept honest by the build: a file
//                 here either compiles for linuxX64 and moves to commonMain, or
//                 names the API that keeps it.
//   linuxX64      the proving target. It exists so "the shared core compiles
//                 and passes its tests under Kotlin/Native" is checked on this
//                 machine, before any Apple hardware exists. The iosArm64 /
//                 iosSimulatorArm64 targets are added on a Mac later; the
//                 friction they would surface is the friction linuxX64 already
//                 surfaces.
//
// The tests are split the same way: commonTest runs under BOTH the JVM and
// linuxX64 (same tests, two runtimes - retiring the one risk KMP added), and
// jvmTest holds the not-yet-migrated majority. `:app:testDebugUnitTest`
// depends on :core:jvmTest, so the one documented pack command still runs
// everything it ran before the conversion.

import org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)

    jvm()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: Flow, StateFlow and the
            // serialization types are part of this module's own API surface,
            // and :app compiles against them.
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        }
    }
}

// ---------------------------------------------------------------------------
// The verdicts: the same question :app's test tasks answer - "did it run?" -
// asked of each of this module's packs. See apk/app/build.gradle.kts for the
// whole rationale. There are two packs now, so there are two verdicts, and
// each names its pack: the JVM one is half of the standard Kotlin pack, and
// the linuxX64 one is the Kotlin/Native proof, which would otherwise be the
// easiest green line in the build to quote without having run it.
// ---------------------------------------------------------------------------
abstract class CoreTestVerdict : DefaultTask() {
    @get:Input abstract val packName: Property<String>
    @get:Input abstract val allowDegraded: Property<Boolean>
    @get:Internal abstract val resultsDir: DirectoryProperty
    @get:Internal abstract val markerFile: RegularFileProperty

    @TaskAction
    fun report() {
        val pack = packName.get()
        val marker = markerFile.get().asFile
        val freshlyRun = marker.exists()
        marker.delete()
        val vintage = if (freshlyRun) "" else
            "\n  (not re-run this time: Gradle reused an earlier run over identical " +
                "inputs. `--rerun-tasks` forces a fresh one.)"

        val xml = resultsDir.get().asFile
            .listFiles { f: java.io.File -> f.name.endsWith(".xml") }
            ?.toList().orEmpty()
        if (xml.isEmpty()) {
            throw GradleException(
                "VERDICT: $pack produced no test results at all. It ran nothing, " +
                    "which is a build fault and not a pass.",
            )
        }

        var total = 0
        var skipped = 0
        var failures = 0
        var errors = 0
        val skippedNames = mutableListOf<String>()
        val counts = Regex("""\b(tests|skipped|failures|errors)="(\d+)"""")
        val caseName = Regex("""\bname="([^"]*)"""")
        val skipWhy = Regex("""<skipped[^>]*message="([^"]*)"""")
        for (file in xml) {
            val text = file.readText()
            val head = text.substringAfter("<testsuite", "").substringBefore(">")
            for (m in counts.findAll(head)) {
                val n = m.groupValues[2].toInt()
                when (m.groupValues[1]) {
                    "tests" -> total += n
                    "skipped" -> skipped += n
                    "failures" -> failures += n
                    "errors" -> errors += n
                }
            }
            for (case in text.split("<testcase").drop(1)) {
                val body = case.substringBefore("</testcase>")
                if (!body.contains("<skipped")) continue
                val who = caseName.find(case.substringBefore(">"))?.groupValues?.get(1)
                    ?: "(unnamed)"
                val why = skipWhy.find(body)?.groupValues?.get(1)
                skippedNames += if (why.isNullOrBlank()) who else "$who - $why"
            }
        }

        if (total == 0) {
            throw GradleException(
                "VERDICT: $pack reported 0 tests. It ran nothing, which is not the " +
                    "same as passing.",
            )
        }
        if (failures > 0 || errors > 0) {
            logger.lifecycle(
                "VERDICT: $pack ran, and it was not fine - $total tests, $failures " +
                    "failures, $errors errors, $skipped skipped",
            )
            return
        }
        if (skipped == 0) {
            logger.lifecycle(
                "VERDICT: $pack ran, and it was fine - $total tests, none skipped$vintage",
            )
            return
        }
        val detail = skippedNames.joinToString("\n") { "    $it" }
        val incomplete =
            "VERDICT: $pack INCOMPLETE - $total tests, $skipped skipped on this machine. " +
                "A run with skips is not a clean run:\n$detail$vintage"
        if (allowDegraded.get()) {
            logger.lifecycle("$incomplete\n  (-PallowDegraded: accepted knowingly)")
        } else {
            throw GradleException(
                "$incomplete\n  Fix the environment, or pass -PallowDegraded to accept an " +
                    "incomplete run knowingly.",
            )
        }
    }
}

val degradedAllowed = providers.gradleProperty("allowDegraded").isPresent

// The cross-platform wire vectors, generated from wire.py. An environment
// variable rather than a classpath resource, deliberately: the native targets
// have no classloader, and one mechanism that works everywhere beats two that
// each work somewhere. The loaders also walk up from the working directory,
// so an IDE run without the variable still finds the file; missing entirely is
// a FAILURE, not a skip.
val wireVectorsPath: String =
    rootDir.parentFile.resolve("vectors/wire_vectors.json").absolutePath

fun markerFor(name: String) =
    layout.buildDirectory.file("tmp/$name-executed.marker")

tasks.named<KotlinJvmTest>("jvmTest") {
    environment("WIRE_VECTORS_JSON", wireVectorsPath)
    // Leave a mark when this task genuinely executes, so the verdict can tell a
    // real run from one Gradle served out of the cache. Same arrangement as
    // :app; the marker lives outside test-results/ so the build cache cannot
    // restore it alongside the XML.
    val marker = markerFor("jvmTest").get().asFile
    doLast(object : Action<Task> {
        override fun execute(t: Task) {
            marker.parentFile.mkdirs()
            marker.writeText("executed")
        }
    })
    finalizedBy("testVerdict")
}

tasks.withType<KotlinNativeTest>().configureEach {
    environment("WIRE_VECTORS_JSON", wireVectorsPath)
    val marker = markerFor(name).get().asFile
    doLast(object : Action<Task> {
        override fun execute(t: Task) {
            marker.parentFile.mkdirs()
            marker.writeText("executed")
        }
    })
}
tasks.named("linuxX64Test") { finalizedBy("linuxX64TestVerdict") }

tasks.register<CoreTestVerdict>("testVerdict") {
    // Never up to date and never cached: the whole job is to speak on every
    // invocation, including the ones where nothing else did.
    outputs.upToDateWhen { false }
    packName.set(":core:jvmTest")
    allowDegraded.set(degradedAllowed)
    resultsDir.set(layout.buildDirectory.dir("test-results/jvmTest"))
    markerFile.set(markerFor("jvmTest"))
}

tasks.register<CoreTestVerdict>("linuxX64TestVerdict") {
    outputs.upToDateWhen { false }
    packName.set(":core:linuxX64Test (Kotlin/Native)")
    allowDegraded.set(degradedAllowed)
    resultsDir.set(layout.buildDirectory.dir("test-results/linuxX64Test"))
    markerFile.set(markerFor("linuxX64Test"))
}
