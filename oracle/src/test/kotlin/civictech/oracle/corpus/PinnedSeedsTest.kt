package civictech.oracle.corpus

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseGenerator
import civictech.oracle.gen.GeneratorConfig
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.RunOutcome
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `ORA1 §REPRO-01` / `ORA1 §REPRO-03`, BS-15: [PinnedSeeds.ALL] runs on every
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
                "reordered or replaced to make the suite green ORA1 §REPRO-02"
        }
    } catch (cause: Throwable) {
        "pinned seed ${pinned.seed} (${pinned.fixedBy}: ${pinned.reason}) is no longer " +
            "constructible: ${cause.message} ORA1 §REPRO-03"
    }

    @Test
    fun `every pinned seed replays Success on every build`() {
        val failures = PinnedSeeds.ALL.mapNotNull(::replay)
        withClue(
            "the append-only pinned regression corpus must construct and run EVERY entry to " +
                "Success on every build ORA1 §REPRO-01; failures: $failures",
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

    // ---------------------------------------------------------- computenet-4ru.1.7: the append
    // mechanism, exercised without pinning a tagged seed

    /**
     * `ORA2 §REPRO-02`, BS-19: no tagged/OR-map seed was discovered-and-fixed during this
     * feature, so [PinnedSeeds.ALL] gains no new entry — the corpus is append-only, and pinning
     * one anyway to exercise the mechanism would be exactly the manufactured failure the bead
     * this test closes forbids. What this test proves instead is that [PinnedSeed]'s entry
     * FORM — a bare `(seed, GeneratorConfig, fixedBy, reason)` tuple — already round-trips a
     * tagged-shaped config through the same [replay] path every real entry runs on every build,
     * and that the path answers correctly at the one tagged case it CAN construct.
     *
     * ## Why there is no LEGITIMATE tagged case to pin
     *
     * `TaggedOperators.registerAll()` binds exactly one id, `orMap`, as an ARITY-0 source, so the
     * config below — whose vocabulary names `orMap` and nothing else — is refused by
     * `ORA1 §GEN-03`/`GraphGenerator`'s `check(operatorEntries.isNotEmpty())`, which is the
     * throw this test observes.
     *
     * **That is where the claim stops.** It is NOT true that [CaseGenerator] cannot build a
     * terminal-bearing `orMap` graph at all: `GraphGenerator` is shape-typed on `ElementShape`
     * and branches on no catalog id, and `orMap`'s output shape `MapOf(Scalar, Scalar)` equals
     * `map`'s, so a vocabulary naming `orMap` beside `join`, `combineLatest` or `lookupJoin`
     * generates one on 20 of 20 seeds (measured in review of computenet-4ru.1.7). What makes
     * such a graph illegitimate is the KERNEL typing, not the generator: `OrMapCell.outlet` is
     * `Propagate<TaggedMapDelta<K, V>>` and those three consumers take `Propagate<MapDelta<K, V>>`,
     * so the case runs to `DeadLetterFailure` with `ClassCastException: TaggedMapDelta cannot be
     * cast to MapDelta` — the same MapDelta-vs-TaggedMapDelta bound computenet-valh tracks, and
     * the same one `civictech.oracle.tagged.TaggedSweepTest`'s KDoc names when it says no
     * operator can *legally* consume an `orMap` outlet.
     *
     * So [PinnedSeeds.ALL] holds no tagged entry because there is no fixed defect to pin AND no
     * legitimate generated tagged case for a fix to have been pinned against — not because the
     * generator structurally refuses one. `TaggedSweepTest`/`ConvergenceSweepTest` cover
     * `ORA2 §DIFF-01..09` through `DifferentialRunner`'s bring-your-own seam instead — a
     * different entry point than this corpus replays, and one [PinnedSeed] cannot name.
     *
     * ## What "round-trips" means here, concretely
     *
     * The [PinnedSeed] value below embeds a [GeneratorConfig] naming `orMap` in its vocabulary —
     * the tagged case SHAPE — exactly as a real entry would if there were ever a legitimate
     * tagged case to pin. [replay] treats it the same as any other entry: it tries
     * [CaseGenerator.generate], and because this config's vocabulary names `orMap` and nothing
     * else it throws on `check(operatorEntries.isNotEmpty())` (the arity-0-only vocabulary
     * above — NOT "no operator can consume `orMap`", which is a kernel-typing bound, not a
     * generator one), so the SAME
     * `ORA1 §REPRO-03` "no longer constructible" branch a real pinned case would hit on a stale
     * shape rule fires — loudly, naming the seed and the underlying exception, never a silent
     * skip. That is the entry form and its build-time check working exactly as designed, at the
     * one tagged input this test can honestly hand it.
     */
    @Test
    fun `computenet-4ru 1 7 the pinned-seed entry form round-trips a tagged config without pinning one`() {
        TaggedOperators.registerAll()
        val taggedShapeCase = PinnedSeed(
            seed = 1L,
            config = GeneratorConfig(
                depthRange = 1..1,
                sourceCount = 1,
                vocabulary = listOf(TaggedOperators.Ids.OR_MAP),
                elementDomainSize = 4,
                scriptLength = 6,
                addRemoveRatio = 0.6,
                unobservedRemoveRatio = 0.0,
                terminalCount = 1,
            ),
            fixedBy = "n/a - mechanism proof only, never appended to PinnedSeeds.ALL",
            reason = "computenet-4ru.1.7: proves the entry form and replay() handle a " +
                "tagged/orMap-shaped config, without a discovered-and-fixed seed to pin.",
        )

        val diagnosis = replay(taggedShapeCase)

        withClue("PinnedSeeds.ALL must gain no entry from this mechanism proof") {
            PinnedSeeds.ALL.none { it.seed == taggedShapeCase.seed && it.fixedBy == taggedShapeCase.fixedBy } shouldBe true
        }
        diagnosis.shouldNotBeNull()
        withClue("must fail LOUDLY, naming the seed and ORA1 §REPRO-03 - never a silent skip: $diagnosis") {
            diagnosis!! shouldContain "seed ${taggedShapeCase.seed}"
            diagnosis!! shouldContain "ORA1 §REPRO-03"
        }
    }
}
