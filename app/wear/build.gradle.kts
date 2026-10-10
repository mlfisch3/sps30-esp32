plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sensirion.sps30.wear"
    compileSdk = 36

    defaultConfig {
        // Same applicationId as the phone app: with matching (debug) signing keys
        // this pairs the two as companions so the Wearable Data Layer syncs between
        // them. See WearContract for the data path.
        applicationId = "com.sensirion.sps30.esp32"
        minSdk = 30 // Wear OS 3+ (Galaxy Watch 7 is Wear OS 5)
        targetSdk = 35 // Play's Wear requirement; Wear OS 5 is API 34, Wear OS 6 is 36
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        compose = true
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        unitTests {
            // Same arrangement as :app and :core: each test task leaves a marker
            // when it genuinely executes, and is finalized by a verdict task
            // that answers "did it run?" - a green line that ran nothing must
            // not be quotable as a pass.
            all { test ->
                val marker = layout.buildDirectory
                    .file("tmp/${test.name}-executed.marker").get().asFile
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
}

// The verdict, asked of this module's unit tests: the same question :app and
// :core ask of theirs - see apk/app/build.gradle.kts for the whole rationale.
// :app:testDebugUnitTest depends on this module's tests (standard pack only,
// like :core:jvmTest), so the one documented pack command runs the watch tests
// too, and this task is what keeps that from shrinking silently.
abstract class WearTestVerdict : DefaultTask() {
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
        val counts = Regex("""\b(tests|skipped|failures|errors)="(\d+)"""")
        for (file in xml) {
            val head = file.readText().substringAfter("<testsuite", "").substringBefore(">")
            for (m in counts.findAll(head)) {
                val n = m.groupValues[2].toInt()
                when (m.groupValues[1]) {
                    "tests" -> total += n
                    "skipped" -> skipped += n
                    "failures" -> failures += n
                    "errors" -> errors += n
                }
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
        if (skipped > 0 && !allowDegraded.get()) {
            throw GradleException(
                "VERDICT: $pack INCOMPLETE - $total tests, $skipped skipped. A run " +
                    "with skips is not a clean run. Fix the environment, or pass " +
                    "-PallowDegraded to accept an incomplete run knowingly.",
            )
        }
        logger.lifecycle(
            "VERDICT: $pack ran, and it was fine - $total tests, $skipped skipped$vintage",
        )
    }
}

listOf("Debug", "Release").forEach { buildType ->
    val testTask = "test${buildType}UnitTest"
    tasks.register<WearTestVerdict>("${testTask}Verdict") {
        // Never up to date and never cached: the whole job is to speak on every
        // invocation, including the ones where nothing else did.
        outputs.upToDateWhen { false }
        packName.set(":wear:$testTask")
        allowDegraded.set(providers.gradleProperty("allowDegraded").isPresent)
        resultsDir.set(layout.buildDirectory.dir("test-results/$testTask"))
        markerFile.set(layout.buildDirectory.file("tmp/$testTask-executed.marker"))
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Wear-specific UI + platform.
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")

    // Complications data source + the Data Layer client for phone-relayed values.
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Tiles (glanceable PM2.5 + sparkline) via ProtoLayout.
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
