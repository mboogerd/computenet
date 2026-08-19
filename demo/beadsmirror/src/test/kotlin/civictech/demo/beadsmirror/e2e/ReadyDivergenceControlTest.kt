package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.dolt.DoltSql
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Task computenet-98u.2.3 — the differential ready harness's **divergence
 * controls** and its **exclusion-masking** case (feature computenet-98u.2,
 * rules 5 and 3's test half; epic computenet-98u/BDS3).
 *
 * [ReadyDifferentialTest] only ever runs the *correct* pipeline, and a suite
 * that only does that cannot distinguish an equality check that works from one
 * that is never reached. These four tests are what make its green
 * non-vacuous:
 *
 * | test | defect | expectation |
 * |---|---|---|
 * | `dropping edge-removal deltas …` | [ReadyHarnessDefects.dropEdgeDeletions] | **red** at the `dep remove` step, with the full [DivergenceRecord] |
 * | `… the identical run is green` | none | green, one comparison per step |
 * | `a future defer_until … does not mask …` | [ReadyHarnessDefects.dropEdgeDeletions] | **red**, and the symmetric difference names *only* the modelled divergence |
 * | `… with no defect seeded the deferred issue is invisible` | none | green, and the deferred issue is on **both** sides |
 *
 * **The defect is seeded at the subscription seam, never in main source.**
 * `ReadySetCell.kt` and `MirrorProjector.kt` are untouched by this task —
 * sibling epic items `computenet-vsbx` and `computenet-98u.3` own the former.
 * See [ReadyHarnessDefects] and `ReadyDifferentialHarness`'s "The seeded
 * defect" section for the mechanism: the two subscriptions
 * [civictech.demo.beadsmirror.ready.ReadySetCell.derivedFrom] would make are
 * made by hand, with a `SetDelta`-rewriting adapter on the edge arm.
 *
 * **Scratch workspaces only** (epic computenet-dqj §4): every workspace comes
 * from [BdScratchWorkspace.create], never this repository's live `.beads`.
 * Guarded on `bd`/`dolt` being on PATH, like every other live-workspace test
 * in this module.
 */
class ReadyDivergenceControlTest {

    @BeforeEach
    fun checkPrerequisites() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    // -----------------------------------------------------------------
    // control — feature rule 5 (Ex/diverge-record)
    // -----------------------------------------------------------------

    /**
     * **The control.** With edge-removal deltas dropped on the derived side, a
     * `bd dep remove` of a **live blocking edge** is invisible to
     * [civictech.demo.beadsmirror.ready.ReadySetCell]: it keeps believing the
     * edge exists and keeps [DEPENDENT] blocked, while `bd ready` — an
     * independent implementation of the same predicate — reports it ready.
     *
     * The comparison is required to catch that, at **that** step, and to record
     * everything a later reader needs to triage it: seed, step index, the
     * producing mutation, both id sets, and per-issue `bd show` evidence for
     * the id in the symmetric difference.
     *
     * The mutation text is the [ScheduleStep] data-class rendering, which names
     * the verb and both arguments the `bd dep remove <blocked> <blocker>`
     * invocation was built from — task computenet-98u.2.2's decided form for
     * [DivergenceRecord.mutation].
     */
    @Test
    fun `divergence control - dropping edge-removal deltas turns a dep remove into a recorded divergence`() {
        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness.withDefects(
                workspace,
                CONTROL_SEED,
                ReadyHarnessDefects(dropEdgeDeletions = true),
            )

            val error = shouldThrow<ReadyDivergenceError> { harness.run(depRemoveSchedule()) }
            val record = error.divergence

            // ...at exactly the dep-remove step, named by the mutation itself.
            record.stepIndex shouldBe DEP_REMOVE_STEP
            record.mutation shouldContain "DepRemove"
            record.mutation shouldContain BLOCKER
            record.mutation shouldContain DEPENDENT

            // ...reproducible: the seed is carried, and is the one it was run with.
            record.seed shouldBe CONTROL_SEED

            // ...and both sides are recorded, disagreeing on exactly the dependent:
            // the oracle saw the removal, the defective derived side did not.
            record.oracle shouldContain DEPENDENT
            record.derived shouldNotContain DEPENDENT
            record.symmetricDifference shouldBe setOf(DEPENDENT)

            // ...with the evidence that discriminates harness/ComputeNet/bd captured.
            record.perIssueEvidence shouldContainKey DEPENDENT
            record.perIssueEvidence.getValue(DEPENDENT) shouldContain "bd show $DEPENDENT --json"
        }
    }

    /**
     * The other half of the control: the **identical** schedule with the defect
     * off is green, and runs one comparison per step. Without this, the test
     * above could be passing because the schedule is broken rather than because
     * the seeded defect is what breaks it.
     */
    @Test
    fun `with edge-removal deltas intact the identical run is green`() {
        val schedule = depRemoveSchedule()

        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness(workspace, CONTROL_SEED)

            val report = harness.run(schedule)

            report.comparisons shouldBe schedule.size
            // The dep remove really did unblock the dependent — the run is green
            // because both sides agree it is ready, not because nothing moved.
            report.outcomes.last().readyIds shouldContain DEPENDENT
        }
    }

    // -----------------------------------------------------------------
    // exclusion masking — feature rule 3, test half (Ex/exclusion-explicit)
    // -----------------------------------------------------------------

    /**
     * **Ex/exclusion-explicit.** An **excluded-clause** difference must not
     * mask a **modelled-clause** divergence on a different issue in the same
     * run.
     *
     * The excluded clause is `READY-COVERAGE.md` row 10a — `defer_until` vs
     * `UTC_TIMESTAMP()`, a wall-clock comparison the derived predicate
     * deliberately does not model. [DEFERRED] is driven into the one state that
     * makes the clause bite while every modelled clause still says "ready":
     * `status = open`, `defer_until` far in the future.
     *
     * **Why `dolt sql` and not `bd`.** `bd update --defer` cannot produce that
     * state: it *also* flips the status to `deferred`, which is a **modelled**
     * difference and would make this test prove something else entirely.
     * Verified live 2026-08-19, `bd` 1.1.2, scratch sandbox:
     * `bd update <id> --defer 24h` yields `"status": "deferred"` alongside the
     * `defer_until` value. So the state is crafted the way `READY-COVERAGE.md`
     * §3's own pinned-column probe crafted its (`UPDATE issues SET … ; CALL
     * DOLT_COMMIT(…)`) — see [DeferUntilViaDoltSql].
     *
     * With the harness's `--include-deferred` alignment both sides then include
     * [DEFERRED], so the excluded clause produces **no** divergence — while in
     * the same run the seeded edge-deletion defect still fails the run on
     * [DEPENDENT]. The load-bearing assertion is the last one: the symmetric
     * difference is **exactly** the modelled divergence, with [DEFERRED]
     * neither hiding it nor joining it.
     */
    @Test
    fun `a future defer_until on an open issue does not mask a modelled divergence on another issue`() {
        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness.withDefects(
                workspace,
                MASKING_SEED,
                ReadyHarnessDefects(dropEdgeDeletions = true),
            )

            val error = shouldThrow<ReadyDivergenceError> { harness.run(maskingSchedule()) }
            val record = error.divergence

            // The deferred issue is on BOTH sides — the exclusion is applied
            // mechanically (--include-deferred), not by pruning a mismatch.
            record.derived shouldContain DEFERRED
            record.oracle shouldContain DEFERRED

            // ...and the modelled divergence is the ONLY thing the run reports.
            record.symmetricDifference shouldBe setOf(DEPENDENT)
            record.stepIndex shouldBe MASKING_DEP_REMOVE_STEP
            record.mutation shouldContain "DepRemove"
        }
    }

    /**
     * The companion green run: the same schedule with **no** defect seeded
     * passes every comparison, with the deferred issue present on both sides.
     *
     * The final assertion is what proves the crafted state is a genuine
     * excluded-clause difference rather than a no-op: `bd ready --json` **with
     * default flags** omits [DEFERRED], and it is only the harness's explicit
     * `--include-deferred` alignment that puts it back. Without this check the
     * masking test above could pass on an issue `bd` never deferred at all.
     */
    @Test
    fun `with no defect seeded the deferred issue is invisible to the comparison`() {
        val schedule = maskingSchedule()

        BdScratchWorkspace.create().use { workspace ->
            val harness = ReadyDifferentialHarness(workspace, MASKING_SEED)

            val report = harness.run(schedule)

            report.comparisons shouldBe schedule.size
            report.outcomes.last().readyIds shouldContain DEFERRED
            report.outcomes.last().readyIds shouldContain DEPENDENT

            // The exclusion is real: the UNALIGNED oracle drops the deferred issue.
            defaultOracleIds(workspace) shouldNotContain DEFERRED
            defaultOracleIds(workspace) shouldContain DEPENDENT
        }
    }

    // -----------------------------------------------------------------

    /**
     * `A` blocks `B`, then the edge is removed. Step [DEP_REMOVE_STEP] is the
     * one the seeded defect cannot see.
     */
    private fun depRemoveSchedule(): List<ScheduleStep> = listOf(
        ScheduleStep.Create(BLOCKER, "blocker $BLOCKER"),
        ScheduleStep.Create(DEPENDENT, "dependent $DEPENDENT"),
        ScheduleStep.DepAdd(blockedId = DEPENDENT, blockerId = BLOCKER, type = "blocks"),
        ScheduleStep.DepRemove(blockedId = DEPENDENT, blockerId = BLOCKER),
    )

    /**
     * [depRemoveSchedule] plus a third issue driven into the excluded
     * `defer_until` state, so the run carries an excluded-clause difference and
     * a modelled-clause divergence at once.
     */
    private fun maskingSchedule(): List<ScheduleStep> = listOf(
        ScheduleStep.Create(BLOCKER, "blocker $BLOCKER"),
        ScheduleStep.Create(DEPENDENT, "dependent $DEPENDENT"),
        ScheduleStep.Create(DEFERRED, "deferred $DEFERRED"),
        DeferUntilViaDoltSql(DEFERRED, FUTURE_DEFER_UNTIL),
        ScheduleStep.DepAdd(blockedId = DEPENDENT, blockerId = BLOCKER, type = "blocks"),
        ScheduleStep.DepRemove(blockedId = DEPENDENT, blockerId = BLOCKER),
    )

    /** `bd ready --json` with **no** alignment flags — the unaligned oracle. */
    private fun defaultOracleIds(workspace: BdScratchWorkspace): Set<String> {
        val raw = workspace.run("ready", "--json", "--limit", "0")
        val start = raw.indexOfFirst { it == '[' }
        if (start < 0) return emptySet()
        return (Json.parseToJsonElement(raw.substring(start)) as JsonArray)
            .mapTo(LinkedHashSet()) { (it as JsonObject).getValue("id").jsonPrimitive.content }
    }

    private companion object {
        /** Pinned — a control that goes red for the wrong reason is triaged, never re-seeded. */
        const val CONTROL_SEED: Long = 20260819L

        /** Pinned, and deliberately distinct from [CONTROL_SEED] so a record names its run. */
        const val MASKING_SEED: Long = 2026081902L

        const val BLOCKER: String = "R-1"
        const val DEPENDENT: String = "R-2"
        const val DEFERRED: String = "R-3"

        /** Index of the `dep remove` in [depRemoveSchedule]. */
        const val DEP_REMOVE_STEP: Int = 3

        /** Index of the `dep remove` in [maskingSchedule]. */
        const val MASKING_DEP_REMOVE_STEP: Int = 5

        /**
         * Far enough out that this test does not acquire an expiry date. The
         * clause under exclusion is `defer_until > UTC_TIMESTAMP()`, so any
         * future instant works; a distant one keeps it future for good.
         */
        const val FUTURE_DEFER_UNTIL: String = "2099-01-01 00:00:00"
    }

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

/**
 * Sets an issue's `defer_until` column directly in the workspace's embedded
 * Dolt database, leaving `status` alone, and commits — so the mirror's feed
 * sees a real `dolt_diff_issues` row for it like any other mutation.
 *
 * A [ScheduleStep] rather than test setup precisely so it runs **inside** the
 * harness's step loop and gets its own comparison, drained to head like every
 * other step.
 *
 * **This exists because `bd` has no verb for the state it produces.** `bd
 * update --defer` sets `defer_until` *and* flips `status` to `deferred`
 * (verified live 2026-08-19, `bd` 1.1.2) — a **modelled**-clause change, which
 * would make the exclusion-masking test prove the wrong thing. The direct
 * `UPDATE` + `CALL DOLT_COMMIT(…)` is the same technique `READY-COVERAGE.md`
 * §3 used to set the `pinned` column, which `bd` 1.1.2 likewise cannot reach.
 *
 * Verified live 2026-08-19 against `bd` 1.1.2 / `dolt` 2.2.3 in a scratch
 * sandbox: after this step, `bd show <id> --json` reports `"status": "open"`
 * with the future `defer_until`; `bd ready --json` omits the issue and
 * `bd ready --json --include-deferred` includes it.
 *
 * [deferUntil] is a Dolt `DATETIME` literal (`yyyy-MM-dd HH:mm:ss`); [id] is a
 * schedule-minted id, so neither is attacker-controlled and neither is escaped.
 */
data class DeferUntilViaDoltSql(val id: String, val deferUntil: String) : ScheduleStep {
    override fun apply(workspace: BdScratchWorkspace) {
        val sql = DoltSql(workspace.doltRoot)
        sql.query("UPDATE issues SET defer_until = '$deferUntil' WHERE id = '$id'")
        sql.query("CALL DOLT_COMMIT('-A', '-m', 'craft defer_until on $id')")
    }
}
