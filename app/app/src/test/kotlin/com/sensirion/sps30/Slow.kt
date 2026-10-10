package com.sensirion.sps30

/**
 * The marker for a test too long-running to belong in the standard pack.
 *
 * ```kotlin
 * @Category(Slow::class)
 * @Test fun aFullRingSurvivesBeingWrittenRightThrough() { ... }
 * ```
 *
 * It works on a class as well as on a method, and it is JUnit 4's own
 * `@Category` rather than anything invented here - `build.gradle.kts` excludes
 * this category from `:app:testDebugUnitTest` and includes only it under
 * `-Pslow`:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest           # the standard pack, about 6 s
 * ./gradlew :app:testDebugUnitTest -Pslow    # only what is marked here
 * ```
 *
 * The standard pack is 430 tests in about seven seconds, and the reason it gets
 * run before every build is that it costs nothing to. Keeping it there is the
 * whole point of this marker.
 *
 * What carries it today: the three Compose layout tests,
 * [ui.SafetyBadgeLayoutTest], [ui.YOverrideHintTest] and
 * [ui.OptionsSheetScrollTest]. They render the real UI on the JVM under
 * Robolectric in native graphics mode, which is what makes them able to measure
 * where a thing is drawn, whether a sentence fits, and whether the bottom of a
 * menu can be reached - and also what makes them cost seconds each rather than
 * milliseconds. See `ui/LayoutTest.kt` for the mechanics and for the two false
 * witnesses that were tried first.
 *
 * A test that is slow because it waits on a *clock* usually should not be marked
 * - it should take the clock as a parameter instead, the way [ui.BackfillQueue]
 * takes `nowMs` and `stallMs`. Marking is for work that is genuinely large:
 * driving the plot cache through a full 19 MB journal, or a transfer measured
 * end to end. Tests needing a real board live in Python, in
 * `tests/test_board_slow.py`, because that is where the protocol definition is.
 */
interface Slow
