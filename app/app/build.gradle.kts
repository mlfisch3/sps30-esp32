plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Where a unit-test task records that it genuinely executed. Deliberately
// outside `build/test-results/`: everything in there is a task output, so
// Gradle would restore the marker from the build cache along with the XML and
// every cached run would claim it had run. See "the verdict" below.
fun unitTestMarker(taskName: String) =
    layout.buildDirectory.file("tmp/$taskName-executed.marker").get().asFile

android {
    namespace = "com.sensirion.sps30"
    compileSdk = 36

    defaultConfig {
        // A separate application id from the Pi-era app, so the two install
        // side by side and neither replaces the other. The proven Raspberry Pi
        // version stays launchable on its own icon while this one is developed
        // against new hardware; they merge once the ESP32 version is the better
        // of the two. Note `namespace` deliberately does NOT change - that is
        // the code package, and renaming it would touch every file for nothing.
        applicationId = "com.sensirion.sps30.esp32"
        minSdk = 26
        // Play's floor for new submissions from 31 August 2026. Raising it opts
        // the app into Android 16's target-gated behaviour: predictive back
        // replaces onBackPressed (nothing here overrides it), large-screen
        // orientation and resizability attributes are ignored (none are
        // declared), and Local Network Protections become available to opt into
        // - which the /24 sweep in net/Discovery.kt will eventually have to
        // meet, and does not yet. See AGENTS.md, "What the app declares to
        // Google Play".
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests {
            // Android framework stubs throw by default in JVM tests. Anything
            // that touches android.util.Log inside a retry loop then throws on
            // every attempt and the loop spins forever - which is exactly how
            // adding a Log call to the connect path hung the whole suite.
            isReturnDefaultValues = true

            // Compose layout tests run on the JVM under Robolectric, and a
            // Compose hierarchy needs real resources to resolve its theme.
            isIncludeAndroidResources = true

            // The standard pack and the slow one, on JUnit 4's own mechanism
            // rather than a home-made one:
            //
            //   ./gradlew :app:testDebugUnitTest           the standard pack
            //   ./gradlew :app:testDebugUnitTest -Pslow    only the slow ones
            //
            // A test joins the slow pack by carrying `@Category(Slow::class)`;
            // see src/test/kotlin/com/sensirion/sps30/Slow.kt. The standard pack
            // runs in about six seconds and is meant to stay there - anything
            // that would spoil that is marked and excluded rather than tolerated.
            //
            // `providers.gradleProperty` rather than `project.hasProperty` so the
            // configuration cache (on in gradle.properties) records the flag as
            // an input and re-configures when it changes.
            val slowOnly = providers.gradleProperty("slow").isPresent
            all { test ->
                test.useJUnit {
                    if (slowOnly) {
                        includeCategories("com.sensirion.sps30.Slow")
                    } else {
                        excludeCategories("com.sensirion.sps30.Slow")
                    }
                }
                // Leave a mark when this task genuinely executes, so its verdict
                // can tell a real run from one Gradle served out of the cache.
                // See the verdict tasks below the `android` block.
                val marker = unitTestMarker(test.name)
                // An explicit Action rather than a lambda: `Test.doLast` also
                // has a Groovy Closure overload, and Kotlin picks that one.
                test.doLast(object : Action<Task> {
                    override fun execute(t: Task) {
                        marker.parentFile.mkdirs()
                        marker.writeText("executed")
                    }
                })
                test.finalizedBy("${test.name}Verdict")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    // The board fixtures (real clockless sessions and their equivalences) live
    // with :core, whose data tests are their main consumer. The app-side tests
    // that cross into ui policy (BoardSessionsTest, ClocklessOverviewGapTest)
    // read the same files, from the same single copy.
    sourceSets["test"].resources.srcDir("../core/src/jvmTest/resources")
}

// ---------------------------------------------------------------------------
// The verdict: did the unit tests run, or did they only look like they did?
// ---------------------------------------------------------------------------
// BUILD SUCCESSFUL answers "did anything fail?", which is a different question
// from "did anything run?", and a green build that ran nothing reads exactly
// like a green build that ran everything. That is worse than a red one, because
// it manufactures confidence. So each unit-test task is finalized by a verdict
// task that answers the second question in words:
//
//   * no test XML at all  -> the pack ran nothing; fail. The exception is
//                            -Pslow, where an empty set is legitimate and is
//                            said out loud instead (see Slow.kt).
//   * 0 tests reported    -> the same. A filter that matches nothing is not a
//                            pass.
//   * anything skipped    -> named, counted, and the build fails. Skips are
//                            @Ignore, or an Assume that did not hold - like
//                            PlotCacheTest's unwritable-directory case when the
//                            build runs as root. A run with skips is not the
//                            clean run it otherwise prints as. Accept it
//                            knowingly with -PallowDegraded.
//
// A finalizer task rather than a `doLast` on the test task, because `doLast`
// does not run when Gradle serves the task FROM-CACHE or calls it UP-TO-DATE -
// and a run that printed no verdict at all is the very silence this removes.
// This task always runs, and the marker file tells it which of the two it is
// looking at.
//
// The excluded pack is named, with its last known state
// ----------------------------------------------------
// Every run of this task is one pack or the other, so every run *excludes* a
// pack - and a verdict that says "fine" without saying that is how two red
// layout tests sat unseen behind weeks of green standard runs: the exclusion
// is fine, the silence about it was not. So each verdict ends with the other
// pack's name and its last known state, read from a small record the other
// pack's own verdict left in `build/tmp/`, with its age - or, when there is no
// record, the words "state is UNKNOWN", because unknown is what it is. A green
// verdict now carries exactly the coverage it exercised and no more.
//
// The record is deliberately *reported*, never failed on. This run's exit code
// answers for this run; a stale red from the other pack is knowledge about a
// different run over possibly different code, and failing the fast pack on it
// would make its exit code lie about what the fast pack found - and make the
// fast pack a thing people stop running while a slow fix is in progress. The
// words are the guarantee: nobody quoting this verdict can pass the red along
// as green. A run narrowed with `--tests` leaves no record, because a
// hand-picked subset is not the pack and must not overwrite the pack's state.
//
// A named class, not a lambda: an action defined inline in a `.kts` captures the
// script object, which the configuration cache (on in gradle.properties) cannot
// serialise. A top-level class captures nothing.
//
// The Python and firmware packs guard themselves the same way; see
// ../run_tests.sh and ../firmware/test/Makefile.
abstract class UnitTestVerdict : DefaultTask() {
    @get:Input abstract val label: Property<String>
    @get:Input abstract val slowPack: Property<Boolean>
    @get:Input abstract val allowDegraded: Property<Boolean>
    @get:Input abstract val otherPackName: Property<String>
    @get:Input abstract val otherPackCommand: Property<String>
    @get:Input abstract val filteredRun: Property<Boolean>
    @get:Internal abstract val resultsDir: DirectoryProperty
    @get:Internal abstract val markerFile: RegularFileProperty
    @get:Internal abstract val ownRecordFile: RegularFileProperty
    @get:Internal abstract val otherRecordFile: RegularFileProperty

    /** `key=value` pairs out of a pack record, or null when there is none. */
    private fun readRecord(file: java.io.File): Map<String, Long>? {
        if (!file.exists()) return null
        return Regex("""(\w+)=(\d+)""").findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }
            .takeIf { it.containsKey("tests") && it.containsKey("at") }
    }

    private fun age(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 2 -> "moments"
            min < 90 -> "$min min"
            min < 48 * 60 -> "${min / 60} h"
            else -> "${min / (24 * 60)} days"
        }
    }

    /**
     * The sentence every verdict ends with: which pack this run did not touch,
     * and the most recent thing actually known about it.
     */
    private fun excludedPackState(): String {
        val name = otherPackName.get()
        val cmd = otherPackCommand.get()
        val record = readRecord(otherRecordFile.get().asFile)
            ?: return "This run EXCLUDED the $name, which has no recorded run in this " +
                "build directory: its state is UNKNOWN, not green. Run it: $cmd"
        val red = record.getOrDefault("failures", 0) + record.getOrDefault("errors", 0)
        val ago = age(System.currentTimeMillis() - record.getValue("at"))
        return if (red > 0) {
            "This run EXCLUDED the $name, and the $name was RED when it last ran " +
                "($ago ago): $red of ${record.getValue("tests")} tests failing. A green " +
                "line here does not cover it - rerun it: $cmd"
        } else {
            "This run EXCLUDED the $name; when it last ran ($ago ago) it was green - " +
                "${record.getValue("tests")} tests. That is its last known state, not " +
                "this run's finding."
        }
    }

    @TaskAction
    fun report() {
        val name = label.get()
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
            if (slowPack.get()) {
                logger.lifecycle(
                    "VERDICT: nothing carries @Category(Slow::class), so the slow pack ran " +
                        "no tests. That is an empty set, not a pass. See " +
                        "src/test/kotlin/com/sensirion/sps30/Slow.kt\n  " +
                        excludedPackState(),
                )
                return
            }
            throw GradleException(
                "VERDICT: $name produced no test results at all. The standard pack ran " +
                    "nothing, which is a build fault and not a pass.",
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
            // Name the skipped ones: "1 skipped" is something to shrug at,
            // "this test, for this reason" is something to fix.
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
                "VERDICT: $name reported 0 tests. It ran nothing, which is not the same as " +
                    "passing.",
            )
        }

        // What this run learned, left where the next run of the *other* pack
        // can find it - including a red result, which is the case the record
        // exists for. Not when `--tests` narrowed the run: a hand-picked
        // subset is not the pack, and recording it as though it were would let
        // one focused rerun overwrite the whole pack's last known state.
        if (!filteredRun.get()) {
            val own = ownRecordFile.get().asFile
            own.parentFile.mkdirs()
            own.writeText(
                "tests=$total failures=$failures errors=$errors skipped=$skipped " +
                    "at=${System.currentTimeMillis()}",
            )
        }

        if (failures > 0 || errors > 0) {
            // Gradle fails the build itself here; just be clear about which
            // question the red is answering.
            logger.lifecycle(
                "VERDICT: it ran, and it was not fine - $total tests, $failures failures, " +
                    "$errors errors, $skipped skipped\n  " + excludedPackState(),
            )
            return
        }
        if (skipped == 0) {
            logger.lifecycle(
                "VERDICT: it ran, and it was fine - $total tests, none skipped$vintage\n  " +
                    excludedPackState(),
            )
            return
        }
        val detail = skippedNames.joinToString("\n") { "    $it" }
        val incomplete =
            "VERDICT: INCOMPLETE - $total tests, $skipped skipped on this machine. A run " +
                "with skips is not a clean run:\n$detail$vintage\n  " + excludedPackState()
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

// One per build type. `unitTests.all {}` cannot register these itself - the task
// container is locked while it runs - and AGP names both the task and its default
// report directory from the build type, so both are derived here.
val slowPackRequested = providers.gradleProperty("slow").isPresent
val degradedAllowed = providers.gradleProperty("allowDegraded").isPresent
// The portable tests moved to :core with the code they test, and the standard
// pack must not quietly shrink by their count: the one documented command,
// `./gradlew :app:testDebugUnitTest`, still runs everything the pack ran
// before the extraction, because the app test task depends on :core:jvmTest
// (the JVM half of the now-multiplatform :core; its linuxX64 pack is the
// Kotlin/Native proof and has its own command and verdict).
// Not under -Pslow: every @Category(Slow::class) test is an :app test (Compose
// layout, service), so the slow pack is :app's alone and running :core's
// standard tests inside it would blur what "-Pslow" measures.
if (!slowPackRequested) {
    listOf("Debug", "Release").forEach { buildType ->
        tasks.matching { it.name == "test${buildType}UnitTest" }.configureEach {
            dependsOn(":core:jvmTest")
            // The watch tests ride the same command, for the same reason: a
            // pack a test does not run in is a pack it silently falls out of.
            // :wear has its own verdict, like :core.
            dependsOn(":wear:test${buildType}UnitTest")
        }
    }
}
// `--tests` on the command line means this invocation is a hand-picked subset,
// not the pack; see "the excluded pack is named" above.
val testsWereFiltered = gradle.startParameter.taskRequests
    .any { request -> request.args.any { it == "--tests" || it.startsWith("--tests=") } }
listOf("Debug", "Release").forEach { buildType ->
    val testTask = "test${buildType}UnitTest"
    tasks.register<UnitTestVerdict>("${testTask}Verdict") {
        // Never up to date and never cached: the whole job is to speak on every
        // invocation, including the ones where nothing else did.
        outputs.upToDateWhen { false }
        label.set(testTask)
        slowPack.set(slowPackRequested)
        allowDegraded.set(degradedAllowed)
        resultsDir.set(layout.buildDirectory.dir("test-results/$testTask"))
        markerFile.set(layout.buildDirectory.file("tmp/$testTask-executed.marker"))
        val ownPack = if (slowPackRequested) "slow" else "standard"
        val otherPack = if (slowPackRequested) "standard" else "slow"
        // The record this sentence reads covers only this module's test task.
        // For the standard pack that is half the story - :core:jvmTest is the
        // other half and has its own verdict - so name the half, or the count
        // here gets quoted as the whole pack.
        otherPackName.set(
            if (slowPackRequested) "standard pack's :app half (:core has its own verdict)"
            else "$otherPack pack",
        )
        otherPackCommand.set(
            "./gradlew :app:$testTask" + if (slowPackRequested) "" else " -Pslow",
        )
        filteredRun.set(testsWereFiltered)
        ownRecordFile.set(layout.buildDirectory.file("tmp/$testTask-$ownPack.lastrun"))
        otherRecordFile.set(layout.buildDirectory.file("tmp/$testTask-$otherPack.lastrun"))
    }
}

dependencies {
    // The portable half of this app: wire client, data layer, connection and
    // relay policy. Same packages as before the extraction - the split is a
    // module boundary, not a rename.
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    testImplementation("junit:junit:4.13.2")
    // 4.16 is the first release that knows about API 36 at all. It still cannot
    // build a 36 sandbox here - that needs a Java 21 test JVM and this project
    // is on JDK 17 - so 35 remains the pack's ceiling; see AGENTS.md.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
