package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.BdInvocation
import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.beadsmirror.ready.ReadySetCell
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Task computenet-98u.2.2 — the **differential ready harness** (feature
 * computenet-98u.2; epic computenet-98u/BDS3).
 *
 * Runs a [ReadySchedule] of real `bd` mutations against a real
 * [BdScratchWorkspace] mirrored by a real [MirrorProjector], and after every
 * single mutation compares the **derived** ready set ([ReadySetCell.readySet])
 * against the **oracle** — `bd ready --json` on that same workspace. `bd
 * ready` is a free second implementation of the same predicate, which is what
 * makes this differential rather than a restatement of our own code.
 *
 * ```
 *   step.apply(workspace)   ──►  bd mutates, dolt commits
 *          │
 *          ▼
 *   drainToHead()           ──►  DoltCommitFeed.readFrom(checkpoint) ──► MirrorProjector.apply
 *          │                     (bounded on FEED POSITION only — see below)
 *          ▼
 *   compare()               ──►  ReadySetCell.readySet()  ==  bd ready --json ids
 *                                (exactly once per step; never retried)
 * ```
 *
 * ## Its own graph, not [civictech.demo.beadsmirror.BeadsMirrorApp]'s
 *
 * The harness owns its [MirrorProjector], its [ReadySetCell] and its
 * [DoltCommitFeed], single-threaded, with no poller thread and no HTTP.
 * [ReadySetCell.derivedFrom] must be attached **before the projector's first
 * record** (its own "Attach before you feed" KDoc: `subscribe` replays
 * nothing), and `BeadsMirrorApp` both wires no ready cell and would feed the
 * projector from a start-time `bd export` baseline that defeats exactly that
 * ordering. So the wiring lives here.
 *
 * ## The bounded wait is on feed POSITION, never on the comparison
 *
 * [drainToHead] pulls the feed until the checkpoint has caught up with the
 * head of `dolt_log` (`feed.history().last()`, the same head test
 * [ScriptedSequenceTest]'s `quiesce` uses). `bd` exits only after its own
 * `dolt` commit, so at the moment a step returns the head is already final
 * and this loop is bounded and deterministic — [MAX_FEED_PASSES] is a
 * runaway guard, not a timeout.
 *
 * Then **exactly one** comparison runs. There is no retry of a failed
 * comparison anywhere in this file, deliberately: a retried comparison is how
 * a real divergence turns into a pass, because the next mutation's feed
 * records would paper over the disagreement the first read exposed. If the
 * one comparison disagrees, the run fails.
 *
 * A caught-up feed is normally `checkpoint == head` exactly. The one accepted
 * alternative is a pass that returns **no records at all**: the range from the
 * checkpoint to the head holds commits with no issue and no edge diff (a `bd
 * --sandbox init` workspace already carries 8 such commits), so there is
 * nothing left for the projector to apply and the derived side is as
 * caught-up as it can be. [ComparisonOutcome.exactHead] records which of the
 * two it was, per step, so this concession is visible rather than assumed.
 *
 * ## The comparison domain, applied identically to both sides
 *
 * The domain is `READY-COVERAGE.md`'s **modelled** clause set. Two mechanical
 * alignments, both at the one readable site ([compare]), both applied to BOTH
 * sides — never by pruning mismatches after the fact:
 *
 * 1. **`--include-deferred`** on the oracle invocation. This removes
 *    READY-COVERAGE rows 10a/10b (the `defer_until` clauses, which the derived
 *    side deliberately does not model) from `bd ready`'s own `WHERE`. It is a
 *    flag, not a filter: the oracle simply stops asking the excluded question.
 * 2. **`status == "open"` post-filter, on both sides.** This one is an
 *    alignment away from a genuine **bd-CLI-vs-`ready.go` discrepancy**, and
 *    it costs real coverage, so it is stated here rather than buried:
 *
 *    > Probed live 2026-08-19, `bd` 1.1.2, scratch sandbox workspace:
 *    > `bd ready --json` **excludes `in_progress` issues** — its own
 *    > `bd ready --help` says so in as many words ("Excludes in_progress,
 *    > blocked, deferred, and hooked issues") — while `ready.go`'s default
 *    > `status IN (...)` clause, which
 *    > [civictech.demo.beadsmirror.ready.ReadyPredicate.DEFAULT_READY_STATUSES]
 *    > models, includes them. No CLI flag widens the oracle back:
 *    > `bd list --ready --status in_progress` still omits them (probed).
 *
 *    So the harness restricts both sides to `status == "open"`. The
 *    consequence, stated plainly: **the `in_progress` half of the modelled
 *    status clause is aligned away, not differentially tested.** It is
 *    covered by [civictech.demo.beadsmirror.ready.ReadySetCellTest] /
 *    `ReadyClauseCoverageTest` at the unit level and by nothing here. On the
 *    oracle side the filter is empirically a no-op (the CLI already omits
 *    them); it is applied anyway, symmetrically, so the code at the
 *    comparison site says what the domain is without the reader having to
 *    know which side it binds on.
 *
 * Every other exclusion is exactly READY-COVERAGE's excluded-clause list and
 * needs no per-issue code here: the excluded caller-filter clauses (label,
 * assignee, parent, molecule, …) are absent from a default `bd ready` call by
 * construction, and the derived side never models them either.
 *
 * Ordering is out of scope throughout — both sides are compared as id **sets**.
 *
 * ## What a divergence MEANS
 *
 * A failure here is **not** automatically a harness bug. Three hypotheses, in
 * triage order:
 *
 * - **(a) a harness / domain-alignment defect** — the two sides were asked
 *   subtly different questions (a new `bd` release changing a default, a
 *   clause moving between READY-COVERAGE's modelled and excluded halves).
 * - **(b) a ComputeNet bug in the derived path** — [ReadySetCell]'s
 *   incremental join, [MirrorProjector]'s deltas, or the feed. This is the
 *   hypothesis the harness exists to falsify.
 * - **(c) a `bd` defect** — specifically beads' **denormalized `is_blocked`
 *   column going stale against the live edge set**. This is not hypothetical:
 *   it was observed live on *this repository's own tracker* on 2026-08-19
 *   (recorded as a comment on epic `computenet-98u`): issue
 *   `computenet-98u.1.3` stayed out of `bd ready` while its only blocking
 *   edge pointed at an **already-closed** bead, and neither `bd dolt pull` nor
 *   `bd dolt push` cleared it. `ReadySetCell` never consumes that column — it
 *   derives blockedness from the edge set and the blocker's status — so a
 *   stale column makes the *oracle* wrong and the derived side right.
 *
 * **The captured evidence is what discriminates the three.** Every
 * [DivergenceRecord] carries, per id in the symmetric difference, that issue's
 * `bd show <id> --json` (its real status, type and edge list) plus one
 * `bd ready --explain` for the whole workspace. If `bd`'s own reported edge
 * list, evaluated under READY-COVERAGE §2, agrees with the derived side and
 * disagrees with `bd ready`'s answer, it is **(c)**. Handling of case (c) —
 * recording it rather than weakening the harness — belongs to the sibling
 * divergence-controls task, not to this file.
 *
 * **A discovered failing seed is pinned verbatim, never swapped for a
 * friendlier one** (AGENTS.md's "do not replace a discovered failing seed";
 * [SeededSchedule]'s pinned-seed slots are the module's precedent).
 */
class ReadyDifferentialHarness(
    private val workspace: BdScratchWorkspace,
    private val seed: Long,
    identity: String = "ready-differential-$seed",
) {

    private val projector = MirrorProjector(DotMinter(identity))

    /** Attached before the projector's first record — see the type KDoc. */
    private val ready: ReadySetCell = ReadySetCell.derivedFrom(projector)

    private val feed = DoltCommitFeed(workspace.doltRoot)

    /** The feed position, exactly as [civictech.demo.beadsmirror.feed.DoltFeedPoller] maintains it. */
    private var checkpoint: String? = null

    /**
     * Runs [schedule] step by step. Per step: apply the mutation, drain the
     * feed to the workspace head, compare **once**.
     *
     * @return the per-step outcomes, one entry per step — [Report.comparisons]
     *   is what a caller asserts equals the step count.
     * @throws AssertionError on the first divergence, carrying the full
     *   [DivergenceRecord].
     */
    fun run(schedule: List<ScheduleStep>): Report {
        val outcomes = mutableListOf<ComparisonOutcome>()
        schedule.forEachIndexed { index, step ->
            step.apply(workspace)
            val exactHead = drainToHead()
            outcomes += compare(index, step, exactHead)
        }
        return Report(seed, outcomes)
    }

    /**
     * Pulls the feed until the checkpoint has caught up with the head of
     * `dolt_log`, applying every record through the projector.
     *
     * @return `true` when the checkpoint landed exactly on the head; `false`
     *   when it stopped short because the remaining commits carry no issue or
     *   edge diffs at all (see the type KDoc).
     */
    private fun drainToHead(): Boolean {
        val head = feed.history().last()
        var passes = 0
        while (checkpoint != head) {
            check(++passes <= MAX_FEED_PASSES) {
                "feed did not reach head $head in $MAX_FEED_PASSES passes (checkpoint=$checkpoint)"
            }
            val records = feed.readFrom(checkpoint)
            records.forEach { projector.apply(it) }
            if (records.isEmpty()) return false
            checkpoint = records.last().commitHash
        }
        return true
    }

    /** The derived ready set, restricted to the comparison domain. See the type KDoc. */
    private fun derivedIds(): Set<String> {
        val view = projector.view()
        return ready.readySet().filterTo(LinkedHashSet()) { id ->
            plainField(view[id]?.get("status")) == COMPARISON_STATUS
        }
    }

    /** The oracle's ready set, restricted to the same domain by the same test. */
    private fun oracleIds(): Set<String> =
        oracleIssues().filterTo(LinkedHashSet()) { issue ->
            issue["status"]?.jsonPrimitive?.content == COMPARISON_STATUS
        }.mapTo(LinkedHashSet()) { it.getValue("id").jsonPrimitive.content }

    private fun oracleIssues(): List<JsonObject> {
        val raw = workspace.run("ready", "--json", "--include-deferred", "--limit", "0")
        val start = raw.indexOfFirst { it == '[' }
        if (start < 0) return emptyList()
        val parsed = Json.parseToJsonElement(raw.substring(start)) as JsonArray
        return parsed.map { it as JsonObject }
    }

    /**
     * **The** comparison site: exactly one comparison, both domains applied
     * here, side by side, and no retry on failure.
     */
    private fun compare(index: Int, step: ScheduleStep, exactHead: Boolean): ComparisonOutcome {
        val derived = derivedIds()
        val oracle = oracleIds()
        if (derived != oracle) {
            val divergence = record(index, step, derived, oracle)
            throw AssertionError(divergence.render())
        }
        return ComparisonOutcome(index, step, derived, exactHead)
    }

    private fun record(
        index: Int,
        step: ScheduleStep,
        derived: Set<String>,
        oracle: Set<String>,
    ): DivergenceRecord {
        val symmetricDifference = (derived - oracle) + (oracle - derived)
        val evidence = symmetricDifference.associateWith { id ->
            workspace.runAllowingFailure("show", id, "--json").rendered()
        }
        return DivergenceRecord(
            seed = seed,
            stepIndex = index,
            mutation = step.toString(),
            derived = derived,
            oracle = oracle,
            perIssueEvidence = evidence,
            explain = workspace.runAllowingFailure("ready", "--explain").rendered(),
        )
    }

    /** `<command> -> exit <n>` plus the combined output — the evidence form the record stores. */
    private fun BdInvocation.rendered(): String = "$command -> exit $exitCode\n$output"

    private fun plainField(raw: String?): String? =
        raw?.let { Json.parseToJsonElement(it).jsonPrimitive.content }

    companion object {
        /**
         * The one status both sides are restricted to — see the type KDoc's
         * bd-CLI-vs-`ready.go` discrepancy paragraph for why it is not
         * [civictech.demo.beadsmirror.ready.ReadyPredicate.DEFAULT_READY_STATUSES].
         */
        const val COMPARISON_STATUS: String = "open"

        /**
         * Runaway guard on [drainToHead], not a timeout: the head is already
         * final when a `bd` process exits, so one pass normally suffices.
         */
        const val MAX_FEED_PASSES: Int = 8
    }
}

/** One passing comparison. [exactHead] is [ReadyDifferentialHarness.drainToHead]'s answer. */
data class ComparisonOutcome(
    val stepIndex: Int,
    val step: ScheduleStep,
    val readyIds: Set<String>,
    val exactHead: Boolean,
)

/**
 * What a completed run reports. [comparisons] is the count a caller asserts
 * equals the schedule's step count — the feature's "no comparison was skipped"
 * clause, which a bounded wait that gave up would otherwise satisfy silently.
 */
data class Report(val seed: Long, val outcomes: List<ComparisonOutcome>) {
    val comparisons: Int get() = outcomes.size
}

/**
 * A recorded divergence — everything a later reader needs to classify it as
 * (a), (b) or (c) of [ReadyDifferentialHarness]'s "What a divergence MEANS".
 *
 * [mutation] is the [ScheduleStep]'s own `data class` rendering, which names
 * the verb and every argument the `bd` invocation was built from.
 */
data class DivergenceRecord(
    val seed: Long,
    val stepIndex: Int,
    val mutation: String,
    val derived: Set<String>,
    val oracle: Set<String>,
    val perIssueEvidence: Map<String, String>,
    val explain: String,
) {
    val symmetricDifference: Set<String> get() = (derived - oracle) + (oracle - derived)

    fun render(): String = buildString {
        appendLine("derived ready set != bd ready --json")
        appendLine("  seed:            $seed")
        appendLine("  step index:      $stepIndex")
        appendLine("  mutation:        $mutation")
        appendLine("  derived:         ${derived.sorted()}")
        appendLine("  oracle:          ${oracle.sorted()}")
        appendLine("  symmetric diff:  ${symmetricDifference.sorted()}")
        appendLine("  --- per-issue evidence (bd show <id> --json) ---")
        perIssueEvidence.forEach { (id, evidence) ->
            appendLine("  [$id] $evidence")
        }
        appendLine("  --- bd ready --explain ---")
        appendLine(explain)
        appendLine(
            "Triage: (a) harness/domain-alignment defect, (b) ComputeNet bug in the derived " +
                "path, (c) bd's denormalized is_blocked stale against the live edge set " +
                "(observed live 2026-08-19 on computenet-98u.1.3). If bd's own edge list above " +
                "agrees with the derived side and disagrees with bd ready, it is (c). " +
                "Do NOT swap this seed for a friendlier one.",
        )
    }
}
