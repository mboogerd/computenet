package civictech.cell.repro

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import java.util.concurrent.TimeUnit

/** The token the fixtures below record as their expected failure signature. */
private const val RECORDED = "CHA2-SELFTEST-RECORDED"

/** A second token, standing in for "this reproduction now fails for a different reason". */
private const val UNRECORDED = "CHA2-SELFTEST-UNRECORDED"

private const val FIXTURE_REASON = "self-test fixture: the recorded divergence, simulated"
private const val FIXTURE_OWNER = "computenet-umx.1.2"
private const val FIXTURE_FILED_AS = "doc/evidence-lane-findings.md#expected-failure-self-test"

/**
 * BS-14, BS-15, BS-16 — the tests that make CHA2's expected-failure discipline load-bearing
 * rather than decorative (`[CHA2-42]`, `[CHA2-43]`, `[CHA2-44]`, `[CHA2-45]`).
 *
 * The mechanism under test inverts verdicts, so it cannot be exercised by ordinary tests in
 * this suite: a fixture proving `[CHA2-44]` has to *fail the build*, which is precisely
 * what a green self-test may not do. Every case therefore runs its fixture as a **nested
 * JUnit Platform execution** (`EngineTestKit`) and asserts over the resulting events, so
 * the inversion is observed at the same seam Gradle observes it at, while this class itself
 * reports pass/fail normally.
 *
 * ## Why the fixtures carry [SelfTestFixture]
 *
 * Four of them are *designed* to fail, so a direct execution by the ordinary run would
 * redden the build for no defect. And Gradle does select them directly: measured on
 * 2026-08-16, a full `./gradlew :kernel:test --rerun` with these fixtures unguarded ran
 * `ExpectedFailureSelfTest$PassesUnexpectedly > theFixLanded()` and four siblings as
 * top-level tests and failed the build — Gradle's class-file detection picks up member
 * classes that declare `@Test` methods, and JUnit resolves a directly-selected one. So
 * "nested classes are private to their outer test" is false here, and the guard below is
 * load-bearing rather than defensive.
 *
 * [SelfTestFixture] is **not** a way to skip a reproduction, and `[CHA2-40]` is not bent by
 * it: every fixture body still executes on every build — inside the nested run this class
 * drives — and the guard is only what stops it *also* executing a second time, unattended,
 * outside the harness that interprets its verdict. It is scoped to this file and reachable
 * by nothing in the suite.
 */
class ExpectedFailureSelfTest {

    // ---------------------------------------------------------------- BS-14

    @Test
    fun `a passing body under the annotation fails the build with the remove-the-annotation message`() {
        val run = execute(PassesUnexpectedly::class.java)

        run.value.testEvents().assertStatistics { it.started(1).succeeded(0).failed(1) }
        val message = run.value.soleFailureMessage()
        message shouldContain "expected failure now passes"
        message shouldContain "the fix landed; remove the annotation, keep the test, and " +
            "update the findings entry"
        // The verdict has to be actionable without reading source: which test, and where the
        // finding it belongs to lives.
        message shouldContain PassesUnexpectedly::class.java.name
        message shouldContain FIXTURE_FILED_AS

        // A body that passed is not a standing expected failure and must not be counted as one.
        run.entries.shouldBeEmpty()
    }

    // ---------------------------------------------------------------- BS-15

    @Test
    fun `a failure carrying a different signature fails the build`() {
        val run = execute(FailsWithADifferentToken::class.java)

        run.value.testEvents().assertStatistics { it.started(1).succeeded(0).failed(1) }
        val message = run.value.soleFailureMessage()
        message shouldContain "changed signature"
        message shouldContain RECORDED
        run.entries.shouldBeEmpty()
    }

    @Test
    fun `a plain exception instead of the recorded assertion fails the build`() {
        val run = execute(FailsWithAPlainException::class.java)

        run.value.testEvents().assertStatistics { it.started(1).succeeded(0).failed(1) }
        run.value.soleFailureMessage() shouldContain "changed signature"
        run.entries.shouldBeEmpty()
    }

    @Test
    fun `a reproduction that times out fails the build rather than counting as expected`() {
        val run = execute(TimesOutBeforeItFails::class.java)

        // The failure surfaces as a TimeoutException (the recorded signal, had there been
        // one, would be demoted to a *suppressed* throwable, which the extension
        // deliberately does not search) — what matters is that it is a failure and not an
        // expected-as-passed verdict.
        run.value.testEvents().assertStatistics { it.started(1).succeeded(0).failed(1) }
        run.entries.shouldBeEmpty()
    }

    @Test
    fun `an incomplete annotation fails the build instead of silently weakening the gate`() {
        val run = execute(MissingOwner::class.java)

        run.value.testEvents().assertStatistics { it.started(1).succeeded(0).failed(1) }
        val message = run.value.soleFailureMessage()
        message shouldContain "@ExpectedFailure"
        message shouldContain "owner"
        message shouldContain "CHA2-41"
        run.entries.shouldBeEmpty()
    }

    // ------------------------------------------------------- CHA2-42 / BS-16

    @Test
    fun `a failure carrying the recorded signature passes the gate and enters the standing report`() {
        val run = execute(FailsAsRecorded::class.java)

        run.value.testEvents().assertStatistics { it.started(1).succeeded(1).failed(0) }

        val entry = run.entries.single()
        entry.testId shouldBe "${FailsAsRecorded::class.java.name}.stillDiverges"
        entry.signature shouldBe RECORDED
        entry.reason shouldBe FIXTURE_REASON
        entry.owner shouldBe FIXTURE_OWNER
        entry.filedAs shouldBe FIXTURE_FILED_AS

        // BS-16: the ledger is legible without reading source — count, reason and owner.
        val report = ExpectedFailureLedger.render(run.entries)
        report shouldContain "Standing expected failures (@ExpectedFailure): 1"
        report shouldContain "${FailsAsRecorded::class.java.name}.stillDiverges"
        report shouldContain FIXTURE_REASON
        report shouldContain FIXTURE_OWNER

        // And the report file's record carries the same fields, one line per reproduction,
        // which is what kernel/build.gradle.kts aggregates across the run's test JVMs.
        val line = entry.toReportLine().split("\t")
        line shouldBe listOf(entry.testId, RECORDED, FIXTURE_OWNER, FIXTURE_REASON, FIXTURE_FILED_AS)
    }

    @Test
    fun `an unannotated test is untouched by the extension`() {
        val run = execute(PlainTestUnderTheExtension::class.java)

        run.value.testEvents().assertStatistics { it.started(1).succeeded(1).failed(0) }
        run.entries.shouldBeEmpty()
    }

    // ------------------------------------------------------------- machinery

    /**
     * Run one fixture class through a nested JUnit Platform execution, with the ledger
     * diverted so the fixtures never enter the real run's standing-failure report, and
     * [SelfTestFixtures.driving] raised so [SelfTestFixtureCondition] lets them run.
     */
    private fun execute(fixture: Class<*>): ExpectedFailureLedger.Captured<EngineExecutionResults> =
        ExpectedFailureLedger.capturing {
            SelfTestFixtures.driving {
                EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(fixture))
                    .execute()
            }
        }

    private fun EngineExecutionResults.soleFailureMessage(): String {
        val failure = testEvents().failed().list()
            .single()
            .getRequiredPayload(TestExecutionResult::class.java)
            .throwable
            .orElseThrow()
        return failure.message ?: failure.toString()
    }

    // -------------------------------------------------------------- fixtures

    /** BS-14: the defect is fixed, the annotation was left behind. */
    @SelfTestFixture
    class PassesUnexpectedly {
        @Test
        @ExpectedFailure(
            signature = RECORDED,
            reason = FIXTURE_REASON,
            owner = FIXTURE_OWNER,
            filedAs = FIXTURE_FILED_AS,
        )
        fun theFixLanded() = Unit
    }

    /** BS-15: still failing, but no longer for the recorded reason. */
    @SelfTestFixture
    class FailsWithADifferentToken {
        @Test
        @ExpectedFailure(
            signature = RECORDED,
            reason = FIXTURE_REASON,
            owner = FIXTURE_OWNER,
            filedAs = FIXTURE_FILED_AS,
        )
        fun nowDivergesElsewhere(): Unit = failAsExpected(UNRECORDED, "a second, unfiled defect")
    }

    /** BS-15: an exception where an assertion was recorded. */
    @SelfTestFixture
    class FailsWithAPlainException {
        @Test
        @ExpectedFailure(
            signature = RECORDED,
            reason = FIXTURE_REASON,
            owner = FIXTURE_OWNER,
            filedAs = FIXTURE_FILED_AS,
        )
        fun throwsInstead(): Unit = throw IllegalStateException("the kernel now throws here")
    }

    /** BS-15: a hang is a new defect, not the recorded one. */
    @SelfTestFixture
    class TimesOutBeforeItFails {
        @Test
        @Timeout(value = 200, unit = TimeUnit.MILLISECONDS)
        @ExpectedFailure(
            signature = RECORDED,
            reason = FIXTURE_REASON,
            owner = FIXTURE_OWNER,
            filedAs = FIXTURE_FILED_AS,
        )
        fun hangs() {
            Thread.sleep(60_000)
            failAsExpected(RECORDED, "unreachable: the deadline fires first")
        }
    }

    /** `[CHA2-41]`: a blank required attribute is not a declaration. */
    @SelfTestFixture
    class MissingOwner {
        @Test
        @ExpectedFailure(
            signature = RECORDED,
            reason = FIXTURE_REASON,
            owner = "",
            filedAs = FIXTURE_FILED_AS,
        )
        fun stillDiverges(): Unit = failAsExpected(RECORDED, "the recorded divergence")
    }

    /** `[CHA2-42]`: the ordinary standing state — failing exactly as recorded. */
    @SelfTestFixture
    class FailsAsRecorded {
        @Test
        @ExpectedFailure(
            signature = RECORDED,
            reason = FIXTURE_REASON,
            owner = FIXTURE_OWNER,
            filedAs = FIXTURE_FILED_AS,
        )
        fun stillDiverges() = withSignature(RECORDED) {
            // Stands in for a reproduction's assertion against the unfixed kernel.
            observedEffectCount() shouldBe 1
        }

        private fun observedEffectCount() = 2
    }

    /** The extension must be inert where the annotation is absent. */
    @SelfTestFixture
    @ExtendWith(ExpectedFailureExtension::class)
    class PlainTestUnderTheExtension {
        @Test
        fun ordinaryTest() {
            1 shouldBe 1
        }
    }
}

/**
 * Marks a class as a fixture of [ExpectedFailureSelfTest]: it runs **only** inside the
 * nested `EngineTestKit` execution that self-test drives, never as a test of the ordinary
 * `:kernel:test` run, which has no way to interpret an inverted verdict.
 *
 * Deliberately not `@Disabled`, and deliberately not a Gradle filter or tag exclusion: those
 * are what `[CHA2-40]` forbids for reproductions, and reaching for one here — even legally,
 * on a fixture rather than a reproduction — would put the rejected idiom in the same package
 * a reproduction author copies from. This is a condition on one file's fixtures, named for
 * what it does, and it leaves the fixture bodies executing on every build.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(SelfTestFixtureCondition::class)
internal annotation class SelfTestFixture

/** The flag [SelfTestFixtureCondition] reads; raised only around a nested self-test run. */
internal object SelfTestFixtures {
    @Volatile
    private var driving: Boolean = false

    val isDriving: Boolean get() = driving

    fun <T> driving(block: () -> T): T {
        driving = true
        try {
            return block()
        } finally {
            driving = false
        }
    }
}

/** @see SelfTestFixture */
internal class SelfTestFixtureCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult =
        if (SelfTestFixtures.isDriving) {
            ConditionEvaluationResult.enabled("driven by ExpectedFailureSelfTest")
        } else {
            ConditionEvaluationResult.disabled(
                "an ExpectedFailureSelfTest fixture; it runs inside that self-test's nested " +
                    "JUnit Platform execution, which is where its inverted verdict is asserted"
            )
        }
}
