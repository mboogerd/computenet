package civictech.oracle.corpus

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.RunOutcome
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `[ORA1-REPRO-01]` / `[ORA1-REPRO-03]`, BS-15: [PinnedSeeds.ALL] runs on every
 * `./gradlew :oracle:test` build, and an entry that can no longer be constructed fails the
 * build loudly — naming the pinned case and what broke — rather than being skipped.
 *
 * Both tests share [replay]: construct the pinned case through
 * [CaseGenerator.generate] and execute it through [DifferentialRunner.run], reporting `null` on
 * a clean [RunOutcome.Success] and a diagnosis string — naming the seed, the fixing bead, and
 * the reason, plus whatever broke — otherwise. This is the one place either failure mode (a
 * genuine regression, or the case becoming unconstructible) is turned into a build failure, so
 * the two tests below differ only in what state they put the catalog in before calling it.
 */
class PinnedSeedsTest {

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    /**
     * Diagnoses one pinned entry: `null` if it replays [RunOutcome.Success], otherwise a
     * message naming the seed, the bead that fixed it, its recorded reason, and either the
     * outcome it replayed as instead, or — if it could not even be constructed — the
     * underlying exception's message (which, for an id absent from [OperatorCatalog], is
     * [civictech.oracle.gen.GeneratorConfig.validateAgainstCatalog]'s own message naming that
     * id — [CaseGenerator]'s `init` runs it before generation touches the seed at all).
     */
    private fun replay(pinned: PinnedSeed): String? = try {
        val case = CaseGenerator.generate(pinned.seed, pinned.config)
        val outcome = DifferentialRunner.run(case)
        if (outcome == RunOutcome.Success) {
            null
        } else {
            "pinned seed ${pinned.seed} (${pinned.fixedBy}: ${pinned.reason}) was expected to " +
                "replay Success but replayed as $outcome — a pinned seed is never narrowed, " +
                "reordered or replaced to make the suite green [ORA1-REPRO-02]"
        }
    } catch (cause: Throwable) {
        "pinned seed ${pinned.seed} (${pinned.fixedBy}: ${pinned.reason}) is no longer " +
            "constructible: ${cause.message} [ORA1-REPRO-03]"
    }

    @Test
    fun `every pinned seed replays Success on every build`() {
        val failures = PinnedSeeds.ALL.mapNotNull(::replay)
        withClue(
            "the append-only pinned regression corpus must construct and run EVERY entry to " +
                "Success on every build [ORA1-REPRO-01]; failures: $failures",
        ) {
            failures.shouldBeEmpty()
        }
    }

    /**
     * BS-15: simulate a pinned case whose vocabulary names a catalog id that has since gone
     * missing — a test-local registry state, exactly as
     * `kernel/src/test/kotlin/civictech/cell/oracle/OracleConsumerTest.kt` manipulates the
     * same process-wide [OperatorCatalog] and undoes it with `@AfterEach`. The build must fail
     * with an explicit incompatibility message naming the pinned case AND the missing id —
     * never a silent skip.
     */
    @Test
    fun `BS-15 - a pinned case whose catalog id has gone missing fails loudly, naming the case and the id`() {
        val missingId = CoreOperators.Ids.QUORUM_SET
        val incompatible = PinnedSeeds.ALL.first { missingId in it.config.vocabulary }

        OperatorCatalog.unregister(missingId)

        val diagnosis = replay(incompatible)

        diagnosis.shouldNotBeNull()
        withClue("must name the pinned seed: $diagnosis") {
            diagnosis!! shouldContain "seed ${incompatible.seed}"
        }
        withClue("must name the missing catalog id: $diagnosis") {
            diagnosis!! shouldContain missingId
        }

        // Control: with the id back, the same entry is constructible again — the failure
        // above was the missing registration, not something wrong with the pinned entry itself.
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        replay(incompatible).shouldBeNull()
    }
}
