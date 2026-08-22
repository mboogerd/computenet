package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The claim-relative reportability criterion (`computenet-785b`).
 *
 * These tests pin the criterion's ARITHMETIC — the margin, the conservative sum, the
 * strictness of the comparison — because the criterion's whole value is that it is fixed
 * in advance rather than adjusted after a measurement disappoints. A change to any
 * number here is a re-derivation, and `Dispersion.kt`'s amendment condition applies to it:
 * stated forward, margin fixed before the numbers are known, appended to
 * `doc/bench/findings.md`.
 */
class DispersionTest {

    private val env = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.11+10-LTS",
        heapSettings = "maxHeapBytes=4294967296",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 26.6.1",
        jmhMode = "Throughput",
        forkCount = 5,
        warmupIterations = 5,
        measurementIterations = 5,
        harnessCommitSha = "b861114d",
    )

    private fun result(value: Double, dispersion: Double, unit: String = "ops/s") =
        BenchResult(value = value, unit = unit, dispersion = dispersion, drive = Drive.SIM, env = env)

    @Test
    fun `the margin is 1x, the value fixed before any number it gates was known`() {
        COMBINED_ERROR_MARGIN shouldBe 1.0
    }

    @Test
    fun `NOISE_FLOOR is unchanged by the demotion — the value is not re-derived here`() {
        NOISE_FLOOR shouldBe 0.005
    }

    @Test
    fun `combined error is the conservative SUM of the two error bars, not the RSS`() {
        // RSS of two equal 10.0 bars is 14.142...; the sum is 20.0. The sum is the
        // criterion, and this test is what makes swapping it a visible change.
        combinedError(result(100.0, 10.0), result(130.0, 10.0)) shouldBe 20.0
    }

    @Test
    fun `an effect wider than the combined bars resolves`() {
        resolveEffect(result(100.0, 10.0), result(130.0, 10.0)) shouldBe EffectResolution.Resolved
    }

    @Test
    fun `an effect inside the combined bars does not resolve`() {
        resolveEffect(result(100.0, 10.0), result(108.0, 10.0)) shouldBe EffectResolution.Unresolved
    }

    @Test
    fun `an effect exactly equal to the combined bars does not resolve — the test is strict`() {
        resolveEffect(result(100.0, 10.0), result(120.0, 10.0)) shouldBe EffectResolution.Unresolved
    }

    @Test
    fun `resolution is symmetric and sign-blind`() {
        val a = result(100.0, 10.0)
        val b = result(130.0, 10.0)
        resolveEffect(a, b) shouldBe resolveEffect(b, a)
    }

    @Test
    fun `two identical rows with zero error bars do not resolve a difference`() {
        // 0.0 > 0.0 is false. Two exact, identical measurements establish that the rows
        // are the same, never that they differ.
        resolveEffect(result(100.0, 0.0), result(100.0, 0.0)) shouldBe EffectResolution.Unresolved
    }

    @Test
    fun `a dispersed row still resolves against a far-enough sibling`() {
        // This is the case the absolute NOISE_FLOOR gate refused outright: both rows are
        // far above the harness sanity bound, and their difference is still established.
        val noisy = result(1000.0, 100.0)
        val noisier = result(3000.0, 300.0)
        classify(noisy) shouldBe Reportability.Unreportable
        classify(noisier) shouldBe Reportability.Unreportable
        resolveEffect(noisy, noisier) shouldBe EffectResolution.Resolved
    }

    @Test
    fun `combining error bars across units is refused rather than guessed`() {
        shouldThrow<IllegalArgumentException> {
            combinedError(result(100.0, 1.0, unit = "ns/op"), result(100.0, 1.0, unit = "ops/s"))
        }
    }

    // -------------------------------------------------------------------------------
    // The magnitude overload (computenet-b7k4) — the shared arithmetic core the
    // regression-tracking series reaches, so that "beyond the band" cannot become a
    // second, independently-drifting definition of "beyond the error bars".
    // -------------------------------------------------------------------------------

    @Test
    fun `the magnitude overload agrees with the two-row form for every sign arrangement`() {
        listOf(
            Triple(100.0, 130.0, 10.0),
            Triple(100.0, 100.0, 10.0),
            Triple(130.0, 100.0, 1.0),
            Triple(-100.0, -130.0, 10.0),
            Triple(100.0, 121.0, 10.0),
        ).forEach { (left, right, error) ->
            val a = result(left, error)
            val b = result(right, error)
            resolveEffect(kotlin.math.abs(left - right), combinedError(a, b)) shouldBe resolveEffect(a, b)
        }
    }

    @Test
    fun `the magnitude overload applies COMBINED_ERROR_MARGIN and the same strictness`() {
        resolveEffect(effect = 10.0, combinedError = 10.0) shouldBe EffectResolution.Unresolved
        resolveEffect(effect = 10.000001, combinedError = 10.0) shouldBe EffectResolution.Resolved
        resolveEffect(effect = 0.0, combinedError = 0.0) shouldBe EffectResolution.Unresolved
        // A caller that computed the bar itself would get the same answer, which is what
        // makes COMBINED_ERROR_MARGIN the single knob.
        resolveEffect(effect = 3.0, combinedError = 3.0 / COMBINED_ERROR_MARGIN) shouldBe
            EffectResolution.Unresolved
    }

    @Test
    fun `the magnitude overload refuses NaN rather than silently resolving Unresolved`() {
        // IEEE 754 makes `NaN > x` false, so an unchecked NaN would report "no difference
        // established" — the reassuring answer — for a comparison that is meaningless.
        shouldThrow<IllegalArgumentException> { resolveEffect(Double.NaN, 1.0) }
        shouldThrow<IllegalArgumentException> { resolveEffect(1.0, Double.NaN) }
        shouldThrow<IllegalArgumentException> { resolveEffect(Double.POSITIVE_INFINITY, 1.0) }
    }

    @Test
    fun `the magnitude overload refuses a negative magnitude`() {
        // `effect` is a magnitude by contract; a negative one means the caller forgot the
        // abs(), and would then never resolve regardless of how large the difference was.
        shouldThrow<IllegalArgumentException> { resolveEffect(-5.0, 1.0) }
        shouldThrow<IllegalArgumentException> { resolveEffect(5.0, -1.0) }
    }
}
