// Root build script. Plugin versions are declared here and applied per-module.
plugins {
    // 8.13.x is the floor for `compileSdk = 36`: AGP refuses to compile against
    // an SDK it was not tested with, and 8.6 was tested only to 35. The Gradle
    // wrapper moves with it (8.13 needs Gradle 8.13+).
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // :core is a Kotlin Multiplatform module with no Android plugin - the
    // portable half of the app compiles with no SDK in sight, for the JVM the
    // app consumes and for the linuxX64 target that proves it under
    // Kotlin/Native (the iOS targets join on a Mac later).
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
