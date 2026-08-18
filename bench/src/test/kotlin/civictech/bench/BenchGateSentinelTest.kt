package civictech.bench

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The discriminator for the `@Tag("bench")` gate in
 * `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts` (BEN1, computenet-x9e.2.1)
 * [BEN1-10][BEN1-11], per the feature's BS-3/BS-4 examples.
 *
 * This test's only job is to be slow and tagged, so that the gate's behavior is
 * directly observable rather than asserted:
 *
 *   - Under the default property set (no `-PbenchOnly`), the gate's unconditional
 *     `excludeTags("bench")` must keep this test from executing at all, so
 *     `:bench:test` stays sub-second and the other, untagged, fast tests in this
 *     package (`BenchmarkDiscoverySmokeTest`, `ProjectGraphTest`, and
 *     computenet-x9e.3's `ResultModelTest`/`FindingsTest`) are unaffected.
 *   - Under `-PbenchOnly=true`, the gate's `includeTags("bench")` (with no
 *     matching exclusion) must make this the ONLY test that executes, and the
 *     task must take visibly longer than a second because this body actually ran.
 *
 * A sentinel that merely asserts something about tags, without an observable cost
 * of its own, would pass whether or not the gate worked — this one cannot: if the
 * gate ever regresses to running bench tests by default, the sleep below shows up
 * directly in `:bench:test`'s wall-clock time and in the JUnit XML's duration for
 * this test, not just in a boolean.
 */
@Tag("bench")
class BenchGateSentinelTest {

    @Test
    fun `sentinel sleeps past the sub-second default budget`() {
        // Comfortably over 1s so it cannot be mistaken for scheduling jitter, but
        // small enough that a deliberate `-PbenchOnly=true` run stays a matter of
        // seconds, not minutes.
        Thread.sleep(1_500)
    }
}
