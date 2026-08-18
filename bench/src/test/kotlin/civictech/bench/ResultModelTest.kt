package civictech.bench

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The result model's refusal rules (`[BEN1-23]`..`[BEN1-27]`, BS-11/BS-12/BS-13).
 *
 * Every test here demonstrates a *refusal*: it constructs the offending shape and
 * asserts the construction fails (or, for BS-11's classification and BEN1-26's
 * requiredness, that the model draws the line the spec says it draws). Each is
 * written so that removing the corresponding `require`/`check`, default value, or
 * comparison in the production code makes the specific test fail — not just "some
 * test somewhere".
 */
class ResultModelTest {

    // ---------------------------------------------------------------------------
    // Shared fixtures
    // ---------------------------------------------------------------------------

    private fun validEnvironment(): RunEnvironment = RunEnvironment(
        jvmVendor = "Eclipse Adoptium",
        jvmVersion = "21.0.4",
        heapSettings = "-Xms1g -Xmx4g",
        cpuModel = "Apple M2 Pro",
        coreCount = 10,
        os = "Mac OS X 14.5",
        jmhMode = "Throughput",
        forkCount = 2,
        warmupIterations = 3,
        measurementIterations = 5,
        harnessCommitSha = "0123456789abcdef0123456789abcdef01234567",
    )

    private fun validResult(
        value: Double = 100.0,
        dispersion: Double = 1.0,
        drive: Drive = Drive.SIM,
        env: RunEnvironment = validEnvironment(),
    ): BenchResult = BenchResult(
        value = value,
        unit = "ops/s",
        dispersion = dispersion,
        drive = drive,
        env = env,
    )

    // ---------------------------------------------------------------------------
    // BS-13 [BEN1-23]: every RunEnvironment field is required and non-blank/positive.
    // ---------------------------------------------------------------------------

    @Test
    fun `a valid RunEnvironment constructs`() {
        // Control: the fixture itself must not throw, or every refusal test below
        // would be discriminating nothing.
        validEnvironment().coreCount shouldBe 10
    }

    @Test
    fun `blank jvmVendor fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(jvmVendor = "  ") }
    }

    @Test
    fun `blank jvmVersion fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(jvmVersion = "") }
    }

    @Test
    fun `blank heapSettings fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(heapSettings = "") }
    }

    @Test
    fun `blank cpuModel fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(cpuModel = "") }
    }

    @Test
    fun `non-positive coreCount fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(coreCount = 0) }
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(coreCount = -1) }
    }

    @Test
    fun `blank os fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(os = "") }
    }

    @Test
    fun `blank jmhMode fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(jmhMode = "") }
    }

    @Test
    fun `non-positive forkCount fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(forkCount = 0) }
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(forkCount = -1) }
    }

    @Test
    fun `non-positive warmupIterations fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(warmupIterations = 0) }
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(warmupIterations = -1) }
    }

    @Test
    fun `non-positive measurementIterations fails construction`() {
        shouldThrow<IllegalArgumentException> {
            validEnvironment().copy(measurementIterations = 0)
        }
        shouldThrow<IllegalArgumentException> {
            validEnvironment().copy(measurementIterations = -1)
        }
    }

    @Test
    fun `blank harnessCommitSha fails construction`() {
        shouldThrow<IllegalArgumentException> { validEnvironment().copy(harnessCommitSha = "") }
    }

    // ---------------------------------------------------------------------------
    // BEN1-26: Drive is required, with no default.
    // ---------------------------------------------------------------------------

    @Test
    fun `BenchResult declares no default-value constructor for drive (or any field)`() {
        // :bench does not depend on kotlin-reflect, so this checks the same fact
        // through plain java.lang.reflect instead of kotlin.reflect.full: the
        // Kotlin compiler emits an EXTRA synthetic constructor (an int bitmask
        // plus a trailing kotlin.jvm.internal.DefaultConstructorMarker parameter)
        // only when at least one constructor parameter has a default value. A
        // constructor with no defaults at all compiles to exactly one JVM
        // constructor. BenchResult declaring exactly one is direct evidence that
        // NONE of its parameters — drive included — has a default: were
        // `drive: Drive = Drive.SIM` (or a default on value/unit/dispersion/env)
        // ever added, a second constructor would appear here and this assertion
        // would fail.
        val constructors = BenchResult::class.java.declaredConstructors
        constructors.size shouldBe 1
    }

    @Test
    fun `BenchResult carries the Drive it was constructed with`() {
        validResult(drive = Drive.REAL).drive shouldBe Drive.REAL
        validResult(drive = Drive.SIM).drive shouldBe Drive.SIM
    }

    // ---------------------------------------------------------------------------
    // BEN1-24: dispersion and relative dispersion.
    // ---------------------------------------------------------------------------

    @Test
    fun `relativeDispersion is dispersion over value`() {
        val result = validResult(value = 200.0, dispersion = 4.0)
        result.relativeDispersion shouldBe 0.02
    }

    @Test
    fun `negative dispersion fails construction`() {
        shouldThrow<IllegalArgumentException> { validResult(dispersion = -0.1) }
    }

    // ---------------------------------------------------------------------------
    // BS-12 [BEN1-27]: FindingsTable refuses a mixed-drive or empty collection.
    // ---------------------------------------------------------------------------

    @Test
    fun `FindingsTable accepts a single-drive collection`() {
        val table = FindingsTable(
            listOf(
                validResult(drive = Drive.SIM),
                validResult(drive = Drive.SIM),
            ),
        )
        table.drive shouldBe Drive.SIM
        table.results.size shouldBe 2
    }

    @Test
    fun `FindingsTable refuses a mixed SIM and REAL collection`() {
        shouldThrow<IllegalArgumentException> {
            FindingsTable(
                listOf(
                    validResult(drive = Drive.SIM),
                    validResult(drive = Drive.REAL),
                ),
            )
        }
    }

    @Test
    fun `FindingsTable refuses an empty collection`() {
        shouldThrow<IllegalArgumentException> { FindingsTable(emptyList()) }
    }

    // ---------------------------------------------------------------------------
    // BEN1-23 at the reporting boundary: FindingsTable refuses a mixed-RunEnvironment
    // collection, mirroring BS-12's single-Drive refusal. Every environment field
    // that could plausibly differ between two runs of the same benchmark is checked
    // individually so a removal of any one comparison in the production check is
    // caught here rather than by "some test somewhere".
    // ---------------------------------------------------------------------------

    @Test
    fun `FindingsTable accepts a single-environment collection`() {
        val table = FindingsTable(listOf(validResult(), validResult()))
        table.results.size shouldBe 2
    }

    @Test
    fun `FindingsTable refuses a collection whose harnessCommitSha differs`() {
        val second = validResult(
            env = validEnvironment().copy(harnessCommitSha = "fedcba9876543210fedcba9876543210fedcba98"),
        )
        val ex = shouldThrow<IllegalArgumentException> { FindingsTable(listOf(validResult(), second)) }
        ex.message shouldContain "harnessCommitSha"
    }

    @Test
    fun `FindingsTable refuses a collection whose jvmVendor or jvmVersion differs`() {
        val second = validResult(env = validEnvironment().copy(jvmVendor = "GraalVM", jvmVersion = "17.0.1"))
        val ex = shouldThrow<IllegalArgumentException> { FindingsTable(listOf(validResult(), second)) }
        ex.message shouldContain "jvmVendor"
        ex.message shouldContain "jvmVersion"
    }

    @Test
    fun `FindingsTable refuses a collection whose jmhMode or forkCount differs`() {
        val second = validResult(env = validEnvironment().copy(jmhMode = "AverageTime", forkCount = 99))
        val ex = shouldThrow<IllegalArgumentException> { FindingsTable(listOf(validResult(), second)) }
        ex.message shouldContain "jmhMode"
        ex.message shouldContain "forkCount"
    }

    @Test
    fun `FindingsTable refuses a collection whose coreCount, os, heapSettings or cpuModel differs`() {
        val second = validResult(
            env = validEnvironment().copy(
                coreCount = 4,
                os = "Linux 6.9",
                heapSettings = "-Xmx8g",
                cpuModel = "Intel Xeon",
            ),
        )
        val ex = shouldThrow<IllegalArgumentException> { FindingsTable(listOf(validResult(), second)) }
        ex.message shouldContain "coreCount"
        ex.message shouldContain "os"
        ex.message shouldContain "heapSettings"
        ex.message shouldContain "cpuModel"
    }

    @Test
    fun `FindingsTable refuses a collection whose warmupIterations or measurementIterations differs`() {
        val second = validResult(
            env = validEnvironment().copy(warmupIterations = 7, measurementIterations = 11),
        )
        val ex = shouldThrow<IllegalArgumentException> { FindingsTable(listOf(validResult(), second)) }
        ex.message shouldContain "warmupIterations"
        ex.message shouldContain "measurementIterations"
    }

    // ---------------------------------------------------------------------------
    // BS-11 (the classification half of BEN1-25): threshold-relative, not tied to
    // NOISE_FLOOR's current value.
    // ---------------------------------------------------------------------------

    @Test
    fun `relative dispersion above NOISE_FLOOR classifies Unreportable`() {
        val value = 1.0
        val dispersion = value * (NOISE_FLOOR + 0.001)
        val result = validResult(value = value, dispersion = dispersion)
        classify(result) shouldBe Reportability.Unreportable
    }

    @Test
    fun `relative dispersion below NOISE_FLOOR classifies Reportable`() {
        val value = 1.0
        val dispersion = value * (NOISE_FLOOR - 0.001)
        val result = validResult(value = value, dispersion = dispersion)
        classify(result) shouldBe Reportability.Reportable
    }

    @Test
    fun `relative dispersion exactly at NOISE_FLOOR classifies Reportable`() {
        val value = 1.0
        val dispersion = value * NOISE_FLOOR
        val result = validResult(value = value, dispersion = dispersion)
        classify(result) shouldBe Reportability.Reportable
    }

    // ---------------------------------------------------------------------------
    // BS-11 continued (computenet-x9e.3.6): relativeDispersion is a SIGNED ratio
    // (dispersion / value), so a naive "relativeDispersion > NOISE_FLOOR" comparison
    // lets a negative value or a NaN ratio walk straight around the gate. Each test
    // constructs the exact shape measured in the bug report and fails if classify
    // goes back to comparing the signed ratio directly instead of its magnitude.
    // ---------------------------------------------------------------------------

    @Test
    fun `negative value with a large relative dispersion classifies Unreportable`() {
        // The PROBE-A case from the bug report: value=-100.0, dispersion=50.0 makes
        // relativeDispersion == -0.5, which "-0.5 > NOISE_FLOOR" never catches.
        val result = validResult(value = -100.0, dispersion = 50.0)
        result.relativeDispersion shouldBe -0.5
        classify(result) shouldBe Reportability.Unreportable
    }

    @Test
    fun `value and dispersion both zero classifies Unreportable, not NaN-passes-through`() {
        // The PROBE-B case from the bug report: value=0.0, dispersion=0.0 makes
        // relativeDispersion == NaN, and "NaN > NOISE_FLOOR" is false under IEEE 754,
        // so a naive comparison falls through to Reportable.
        val result = validResult(value = 0.0, dispersion = 0.0)
        result.relativeDispersion.isNaN() shouldBe true
        classify(result) shouldBe Reportability.Unreportable
    }
}
