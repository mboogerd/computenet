package civictech.oracle.run

import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.model.ModelState
import civictech.oracle.model.SourceId
import kotlin.random.Random

/**
 * The wave-prefix glitch-freedom oracle `ORA1 §DIFF-06` — epic computenet-4ru design **D5**,
 * REQUIRED and never weakened to final-state equality: while a case is driven, **every
 * intermediate observation of a terminal must equal the reference model's result for SOME
 * prefix of the wave sequence, and the matched prefix index must never regress**.
 *
 * Final-state equality alone cannot see a glitch. A reconvergent graph can publish a torn
 * composite mid-wave — one arm of a diamond updated, the other not — and still settle on the
 * right answer, so a run that only compares at quiescence reports `Success` for a graph that
 * showed a state no serial execution of the inputs could produce. That is what this file
 * bounds.
 *
 * ## The construction, and where it comes from
 *
 * `kernel/src/test/kotlin/civictech/cell/consistency/InternalConsistencyTest.kt` (~371-406,
 * `Oracle` / `prefixesOf` / `every observed composite is a completed-transfer prefix, for
 * every seed`) holds the construction this generalizes: a from-scratch recompute folded
 * forward one input wave at a time into a `prefixes` list, every observed composite required
 * to equal SOME entry of it, with the matched index monotone.
 *
 * That class is `private` and lives in the **kernel test source set** over a domain-specific
 * `Transfer` type, so it is unreachable from `:oracle`'s `src/main` and cannot be reused as
 * code. The **cross-check is therefore behavioral**, not by reuse: the same shape (a
 * reconvergent diamond driven with seed-randomized partial drains), the same positive property
 * (every observation is some prefix, monotone), and the same *negative* controls (a torn
 * composite matching no prefix is rejected) — see `WavePrefixTest`, which states the
 * correspondence case by case.
 *
 * ## Prefixes: one script Op is one wave
 *
 * [prefixesOf] evaluates the reference on the script restricted to its first *i* [CaseStep.Op]s,
 * for *i* in `0..opCount`. One Op is one source delta, hence one wave, and a
 * [CaseStep.Barrier] falls out naturally (it contributes no Op, so it advances no prefix).
 * `prefixes[0]` is the empty-input answer, which is the state a freshly built graph must show.
 *
 * A [civictech.oracle.model.ScriptEvent.Observe] Op is a model-only causality statement that
 * injects nothing into the kernel, so it can leave `prefixes[i] == prefixes[i-1]`. That is
 * harmless: [Checker] matches the LOWEST admissible index, so an equal pair is one plateau
 * rather than a forced advance.
 *
 * ## Where it is sound, and where it deliberately refuses
 *
 * [appliesTo] admits **single-host** cases whose frontier lattice fits [MAX_FRONTIER_LATTICE],
 * and each refusal is a soundness or a cost statement rather than a convenience:
 *
 * - **Multi-source: admitted since computenet-2hur, against the per-source frontier product.**
 *   A total-order prefix denotes a real frontier only for one source. With two independent
 *   sources the kernel may legally have absorbed three of source A's deltas and one of source
 *   B's — a per-source frontier the total-order prefix list does not contain — so checking a
 *   multi-source case against total-order prefixes alone would manufacture glitch reports out
 *   of correct runs (measured 2026-08-19 before this generalization: with the old guard
 *   bypassed, 4 of 38 correctly-settling three-source cases produced an observation matching no
 *   total-order prefix). [Checker] therefore matches an observation against the **product** of
 *   the per-source prefixes — the frontier vector `(f_1..f_n)`, one absorbed-op count per
 *   source — and requires that vector to be **componentwise non-decreasing per terminal**,
 *   which is what `InternalConsistencyTest` means by "in per-source counter order". The
 *   single-source case is the `n = 1` degenerate of exactly that: the lattice is the chain
 *   `0..opCount`, the antichain of admissible frontiers is always a single scalar floor, and
 *   the behavior is unchanged.
 *
 *   **The frontier lattice is the cost, and it is bounded per case, not per observation.**
 *   [Checker] memoizes `frontier -> every terminal's modelled state`, so a case evaluates the
 *   reference at most `prod(opsPerSource_i + 1)` times however many observations it takes
 *   (against `opCount + 1` for the single-source chain, which is that product at `n = 1`).
 *   [MAX_FRONTIER_LATTICE] is where the admission line is drawn, and the number behind it is
 *   measured, not estimated — see that constant.
 * - **Multi-host.** A cross-host arm was measured producing mid-wave states matching no
 *   prefix. **computenet-g25w settled what that is (2026-08-21), and it is neither of the two
 *   things that bead asked about**: not a kernel glitch, and not [CaseExecution.assemble]'s
 *   bare-`Propagate` wiring dropping arm completeness. It is an **eager per-arm publish at a
 *   fan-in that never declared glitch-freedom**, which `[22-GF-01]` expressly permits — "a cell
 *   that has declared itself glitch-free SHALL NOT expose derived state that mixes pre-wave and
 *   post-wave inputs … Non-declaring cells process eagerly with zero coordination cost" — and
 *   `UnionSetCell` declares none (no `civictech.cell.consistency.GlitchFree` marker, no
 *   ALIGN-tier inlet policy; asserted in `WavePrefixTest`).
 *
 *   The discriminating measurement is that **the tear is not caused by the host boundary at
 *   all**: read the union's *published stream* instead of the scheduler-step boundary and the
 *   fully **co-hosted** diamond — zero cross-host edges — publishes the identical six-state
 *   sequence over `add(ab), add(cd), remove(ab)`, the three torn states included
 *   (`{ab}`, `{ab, a, b, cd}`, `{a, b, cd, c, d}`). What the host boundary changes is
 *   **observation granularity**: co-hosted, the whole cascade runs inline inside the one
 *   scheduler task the source op started, so the per-step observer can never land between the
 *   two arms' publishes; across a cut the far arm is a separate task and the same intermediate
 *   becomes visible. Both placements settle on the same correct final state; nothing is lost.
 *
 *   So the restriction **stays**, with its reason upgraded from undecided to confirmed: what
 *   makes this check sound is not "one host" as such but that co-hosted inlining puts every
 *   observation on a wave boundary (see the granularity bullet below). Lift the guard and the
 *   oracle would report a spec-permitted intermediate as kernel evidence — the false-positive
 *   direction D5 forbids just as firmly as weakening. No `doc/demo-findings.md` entry follows,
 *   because there is no kernel counterexample to record.
 *
 *   One residual the bead's own hypothesis was half-right about, filed rather than fixed here:
 *   [CaseExecution.assemble]'s cross-host connect establishes **no link on the target inlet**
 *   (measured: 1 link for a same-host connect, 0 across the cut), so it carries no `EdgeOpen`
 *   and no per-inlink frontier bookkeeping to the target. Data delivery is unaffected — same
 *   publish sequence, same final value — and no generated case builds a frontier join, so it
 *   cannot explain this observation; but it does mean `[22-GF-03]` would not hold across that
 *   cut for a case that ever placed one there. Filed as **computenet-xj0v**.
 *
 * Neither refusal is a weakening to final-state equality: the check is unchanged wherever it
 * is sound, and [WavePrefixOption] cannot turn it off by default (see [DEFAULT_FRACTION]).
 *
 * ## Measured coverage — what this instrument actually bounds today
 *
 * Two numbers, stated here because a fraction in a knob is not coverage:
 *
 * - **Granularity.** On a co-hosted graph every hop runs inline inside the scheduler step that
 *   started it, so the observations are one-per-wave, not one-per-hop: a 3-Op script over the
 *   BS-8 diamond produces 3 productive steps and 3 observations. This oracle therefore bounds
 *   "the terminal equals the model at every wave, never only the last, and never goes
 *   backwards"; it cannot resolve an instant *inside* one wave on one host.
 * - **Generated-path admission.** Until computenet-2hur [appliesTo] rejected every multi-source
 *   config, and the configs this feature's other suites construct are all `sourceCount >= 3` —
 *   measured 0/200 seeds admitted on each (`GraphSpecLinkSweepTest.sweepConfig`,
 *   `Bs16Case.CONFIG`, the BS-1 sweep config; measured 2026-08-19). Two of those three are now
 *   admitted or excluded for a *different* reason, and the distinction matters when reading an
 *   old measurement: `Bs16Case.CONFIG` is still refused, but for `hostCount = 2`; the BS-1
 *   sweep config (`sourceCount = 4`, `scriptLength = 200`) is still refused, but for a frontier
 *   lattice of ~6.8M (see [MAX_FRONTIER_LATTICE]); `GraphSpecLinkSweepTest.sweepConfig`
 *   (`sourceCount = 3`, `scriptLength = 40`) is now **admitted**, at a lattice of a few
 *   thousand. `WavePrefixTest`'s own two-source diamond and its multi-source sweep are what
 *   demonstrate the widened check.
 * - **What that sweep found** (`WavePrefixTest.generatedSweepConfig`, seeds 0..59, ordinary
 *   `writerCount = 2` / `unobservedRemoveRatio = 0.25` knobs, Darwin arm64, 2026-08-19): 60/60
 *   admitted, 47/60 carrying a source-to-terminal pair joined by two paths with *different*
 *   operator sequences, and **48/60 prefix-clean**. The other twelve are pinned seed lists in
 *   that file, not silence: five already `Mismatch` at quiescence with checking OFF, five
 *   REGRESSED across a wave the model did not change, one a single-path chain showing a state
 *   that is no prefix, one a reconvergent shape showing a state that is no prefix.
 *
 *   **The pinned twelve are one population, and it takes a cross-writer remove to produce
 *   them.** Measured at review time (2026-08-19, Darwin arm64, this same config and seed range,
 *   and reproduced on an independent second read): with `writerCount = 1` the sweep is **60/60
 *   clean — no `Mismatch` and no violation at all**, and with `addRemoveRatio = 1.0` (no remove
 *   event generated at all) at `writerCount = 2` it is **60/60 clean** too. A second writer and
 *   a remove are each necessary, which puts the whole population in the territory of the
 *   pre-existing cross-writer remove seam (computenet-qcm1, computenet-4ru.6.3: a spawned
 *   `SetCell` retracts a live element on any remove, while the model no-ops a cross-writer
 *   remove no `Observe` preceded).
 *
 *   `unobservedRemoveRatio = 0.0` at `writerCount = 2` removes *neither* mismatch nor violation
 *   (it only re-shuffles which seeds carry them: 9 mismatches and 6 violations), so the
 *   divergence is not confined to the **unobserved** remove that bead's own description turns on.
 *
 *   ### The verdict (computenet-eeys, settled 2026-08-20)
 *
 *   **The diverging step is any remove whose element stays live in the reference model — and the
 *   reference model is the wrong side of it, not the kernel.**
 *
 *   The mechanism, in one sentence each: `SetCell.inletHandler.remove` retracts `liveTags(e)`,
 *   every un-tombstoned tag the cell holds, while `civictech.oracle.model.Membership` covers only
 *   the adds the *removing writer* had observed — so a remove of an element some other writer
 *   also added, and whose add the remover never observed, takes effect in the kernel and is a
 *   no-op in the model. Three events reproduce it with no generator and no seed: `w0` adds `ab`,
 *   `w1` adds `ab`, `w0` removes `ab`; at the diamond's terminal the model answers
 *   `{ab, a, b}` (the `filter` arm's `ab` plus the `flatMapSet` arm's `a`, `b`) and the kernel
 *   the empty set
 *   (`WavePrefixTest`, "a remove of an element another writer added is applied by the kernel and
 *   ignored by the model", with a single-writer control that succeeds).
 *
 *   That accounts for every knob measured above. The remove is `ScriptGenerator`'s
 *   `emitObservedRemove` — its *direct* branch when the remover added the element itself, its
 *   *cross* branch when a monotone `known` set offers it an element whose newest add its
 *   `Observe` predates — and both audit `observed = true`, which is why `unobservedRemoveRatio`
 *   cannot reach them. At `writerCount = 1` no such step is constructible at all (a writer
 *   observes its own adds), which is why that knob is 60/60 clean; measured, 0 of 60
 *   single-writer scripts carry one against 22 of 60 two-writer scripts.
 *
 *   **Which side is wrong.** `[24-SET-03]` requires a remove to retract "the tags it observed",
 *   and the observer is the *cell*: a `SetCell` driven through its own `inlet` has observed every
 *   add that reached it, because it is a single serialization point. Writer identity has no
 *   kernel counterpart on this drive path — `CaseExecution` funnels every writer's op through one
 *   inlet and `ScriptEvent.Observe` injects nothing — so the script's "concurrent writers" are in
 *   fact sequential. Real concurrency in this kernel is across *replicas*
 *   (`SetCell.deltaInlet`/`applyRemote`, spec 40/42), and a generated case builds one replica.
 *   `ORA1 §MODEL-04`/`ORA1 §MODEL-05` are therefore sound only for a script whose writers are
 *   separate replicas; these are not. **No kernel defect is implied by any pinned seed.**
 *
 *   **Relation to computenet-qcm1: same kernel asymmetry, distinct generator path — not a
 *   duplicate.** qcm1 constrained `emitUnobservedRemove` to never name a live element; the
 *   residual is the identical constraint missing from `emitObservedRemove`, which qcm1's own
 *   acceptance bound explicitly excluded. The repair is a generator post-condition — no remove
 *   may leave its element live in `Membership` — not a change to `SetCell`, and it belongs to a
 *   file this bead does not claim (`civictech.oracle.gen.ScriptGenerator`).
 *
 *   The attribution is **necessary, not sufficient**, and is stated that way in the test: 22 of
 *   60 seeds carry such a step and 9 surface as a failure; the other 13 are masked downstream by
 *   a `filter`, `quorumSet` or `count` that keeps the element's presence off the terminal.
 *
 *   **How exhaustively it was checked.** Mutating `Membership.observes` to `return true` — the
 *   one edit that makes the model retract every add a remove can reach, i.e. adopt `SetCell`'s
 *   rule — turns the whole sweep clean in one step: `settledMismatch=[]`, `plateauFlicker=[]`,
 *   `chainArtifact=[]`, `glitchCandidate=[]` over the same seeds 0..59, and the three-event case
 *   above reports `Success` instead of `Mismatch` (Darwin arm64, 2026-08-20; mutation reverted,
 *   never committed). So the nine pinned seeds are not merely *consistent with* this mechanism —
 *   there is no residual population left once it is removed, which is what rules out a second,
 *   independent cause hiding inside them.
 *
 *   The five `Mismatch` seeds are the cases where the divergence survives to quiescence; the
 *   seven violation seeds are the cases where it appears at an intermediate wave and *heals*
 *   before the end (all seven settle `Success`). **Catching those seven is this instrument's
 *   contribution** — the final-state comparison cannot see them at all.
 *
 *   **What the violations are NOT: an artifact of this observation point.** A [TerminalFold]
 *   applies each delta as it arrives, while `InternalConsistencyTest` reads an *aligned* sink
 *   ("the aligned sink buffers a wave's deltas and applies them together, so this particular
 *   flicker is invisible at the sink") — a real difference between the two read paths, but not
 *   the explanation here (the explanation is the verdict above), and an earlier session's own
 *   probe had already discarded it. Two
 *   measurements, both at review time and both reproduced on the second read with the same
 *   per-seed counts: (i) re-driving each violating seed with a `Barrier`
 *   after *every* Op and inspecting ONLY the `onBarrier` states — read after `drainToIdle()`,
 *   where a raw fold and an aligned sink hold the same value by construction — reproduces every
 *   violation, 1 to 13 offending states per seed, several persisting across consecutive
 *   quiesced boundaries; (ii) the granularity bullet above is why: at one productive step per
 *   wave, essentially every observation this oracle takes is already a quiesced wave boundary,
 *   so "the dip happened inside one wave" cannot be the mechanism for any of them. Treat the
 *   pinned seeds as the writer-concurrent remove divergence surfacing earlier, not as a limit of
 *   this instrument — and do not close the gap by weakening the check (D5).
 */
object WavePrefixOracle {

    /**
     * The fraction of eligible cases prefix-checked when a caller names no [WavePrefixOption]
     * — **nonzero by construction**, which is D5's floor: prefix checking may be narrowed for
     * `ORA1 §PERF-01`, never dropped. Turning it off is an explicit
     * [WavePrefixOption.OFF] at a call site, never a default.
     *
     * Prefix checking costs one model evaluation per Op (the prefix list) plus one terminal
     * read per productive scheduler step, so a fully-checked sweep is several times the cost
     * of a plain drain — which is why the default is a documented subset rather than all.
     *
     * Read together with the admission numbers above: 0.25 is the fraction of *eligible*
     * cases, and eligibility is what [appliesTo] decides.
     */
    const val DEFAULT_FRACTION: Double = 0.25

    /**
     * The largest frontier lattice — `prod(opsPerSource_i + 1)` — a case may have and still be
     * prefix-checked. Above it, [notApplicableBecause] refuses on **cost**, which is a different
     * refusal from the two soundness ones and says so.
     *
     * ## The number is measured
     *
     * The lattice is the per-case ceiling on reference evaluations ([Checker] memoizes
     * `frontier -> states`, so no frontier is evaluated twice however many observations a run
     * takes). Measured on Darwin arm64 (M-series), 2026-08-24, by
     * `WavePrefixTest."the cost of frontier matching is the memoized lattice, measured"`:
     *
     * ```
     * WAVE-PREFIX COST sources=3 scriptLength=40 cases=8 admitted=8 lattice/case=2794
     *   evaluations/case=2794 scanned/case=86882 observations/case=35 wall/case=30.6ms
     *   outcomes={Success=8}
     * ```
     *
     * Read it as three facts:
     *
     * 1. **Evaluations equal the lattice, not the observation count.** 2794 of 2794 frontiers
     *    were evaluated and 35 observations were taken — the very first observation's search
     *    region is the whole lattice (its floor is the zero frontier), so a multi-source case
     *    pays its ceiling once and every later observation is memo hits. `scanned/case` (86882)
     *    is those hits: ~31 sweeps of the lattice, costing no model evaluation at all.
     * 2. **~8-11 µs per frontier, all-in** (`wall/case` was 30.6 ms on the first run of the day
     *    and 21.8 ms on a warm JVM in the same session — quote the range, not either end). That
     *    figure is the whole `DifferentialRunner.run`, kernel driving included, for a case whose
     *    checking dominates it; the same case with `WavePrefixOption.OFF` evaluates the
     *    reference once.
     * 3. **So 50_000 is of the order of a second** of prefix checking for the worst case it
     *    admits, against minutes for the BS-1 sweep shape (`sourceCount = 4`,
     *    `scriptLength = 200`, ~6.8M frontiers) that it refuses.
     *
     * **Fact 3 is an EXTRAPOLATION from facts 1-2, not a measurement, and it is a lower bound.**
     * The µs/frontier rate above was measured at `scriptLength = 40`; one frontier evaluation
     * replays the *retained* script, so the rate grows roughly linearly with a case's op count.
     * A 50_000-frontier case at `sourceCount = 3` needs ~36 Ops per source (`scriptLength ~ 108`,
     * ~2.7x the measured shape), so read "half a second at the measured rate" as ~1 s in
     * practice; the BS-1 shape's 50 Ops per source is ~5x, which is where "minutes" comes from.
     * Only the fenced line above was run. Neither figure changes where the line is drawn: a
     * second of checking on the worst admitted case is affordable and the refused shape is not.
     *
     * It is a **budget knob** `ORA1 §PERF-01`, not a soundness one — raising it admits more
     * cases and costs more; it can never make the check accept a torn observation. The cheaper
     * exact algorithm the bead asked about (advancing the frontier incrementally so only
     * frontiers near the run's own trajectory are evaluated) is not what is implemented, and
     * deliberately: the run's real frontier is not observable from the terminal's value, so a
     * search that stopped at the first match could pick a frontier the run was not on and reject
     * a legal later observation for not being above it. The other option the bead named — a
     * per-source counter read off the observation, the way `InternalConsistencyTest` reads the
     * outer map's `size` — is unavailable here: a [ModelState] is a set, map or count with no
     * per-source provenance in it, which is precisely what makes that test domain-specific and
     * this one generic.
     *
     * Single-source cases are unaffected: their lattice is `opCount + 1`, which no generated
     * config comes near.
     */
    const val MAX_FRONTIER_LATTICE: Long = 50_000L

    /** Whether the prefix check is sound and affordable for [case] — see [notApplicableBecause]. */
    fun appliesTo(case: GeneratedCase): Boolean = notApplicableBecause(case) == null

    /**
     * How many [CaseStep.Op]s [case]'s script directs at each source, in the order the sources
     * first appear in the total drive order — the per-source axis lengths of the frontier
     * lattice.
     *
     * A source *node* the script never drives contributes no axis: an axis of length 0 is a
     * factor of 1 in the lattice and a coordinate that can never advance, so leaving it out
     * changes nothing but the arithmetic's readability.
     */
    fun opCountsPerSource(case: GeneratedCase): Map<SourceId, Int> {
        val counts = LinkedHashMap<SourceId, Int>()
        case.script.steps.filterIsInstance<CaseStep.Op>().forEach { op ->
            counts[op.source] = (counts[op.source] ?: 0) + 1
        }
        return counts
    }

    /**
     * The size of [case]'s frontier lattice, `prod(opsPerSource_i + 1)`, saturating at
     * [Long.MAX_VALUE] rather than overflowing — a config wide enough to overflow is refused
     * either way, and an overflowed negative would admit it.
     */
    fun frontierLatticeSize(case: GeneratedCase): Long =
        opCountsPerSource(case).values.fold(1L) { acc, count ->
            val next = acc * (count + 1L)
            if (count > 0 && next / (count + 1L) != acc) Long.MAX_VALUE else next
        }

    /**
     * Why the prefix check does not apply to [case], or `null` if it does.
     *
     * A *reason*, not a boolean, so a sweep that skips a case can say which soundness limit it
     * hit and against which filed bead — a silent skip would make a zero-coverage run
     * indistinguishable from a clean one, which is the failure mode this epic exists to avoid.
     */
    fun notApplicableBecause(case: GeneratedCase): String? {
        val hosts = case.topology.placement.values.filter { it != 0 }.distinct()
        if (hosts.isNotEmpty()) {
            return "the case places cells on host ordinals ${hosts.sorted()} besides 0; a " +
                "cross-host arm makes a fan-in's eager per-arm publish observable at a " +
                "scheduler-step boundary, and at a fan-in that declares no glitch-freedom that " +
                "intermediate is permitted by [22-GF-01] rather than kernel evidence — " +
                "co-hosted inlining, not the host count, is what puts every observation on a " +
                "wave boundary (settled, computenet-g25w)"
        }
        val lattice = frontierLatticeSize(case)
        if (lattice > MAX_FRONTIER_LATTICE) {
            val counts = opCountsPerSource(case)
            return "the case's frontier lattice is $lattice frontiers " +
                "(${counts.entries.joinToString(" x ") { "${it.key.id}:${it.value + 1}" }}), " +
                "above MAX_FRONTIER_LATTICE=$MAX_FRONTIER_LATTICE; that is a COST refusal " +
                "ORA1 §PERF-01, not a soundness one — the per-source frontier check is exact " +
                "here, it is the memoized evaluation ceiling that does not fit the module's " +
                "test budget"
        }
        return null
    }

    /**
     * The prefix list for [script] under [reference]: `prefixesOf(...)[i]` is every terminal's
     * modelled state after the script's first *i* [CaseStep.Op]s, for *i* in `0..opCount`.
     *
     * Computed once per case — `opCount + 1` model evaluations — because the comparison is
     * `O(prefixes x observations)` and re-evaluating per observation would make it
     * `O(prefixes x observations x scriptLength)`.
     *
     * @throws Throwable whatever [reference] throws. The caller ([DifferentialRunner.run])
     *   turns that into [RunOutcome.ModelEvaluationFailure]: a reference that cannot evaluate a
     *   prefix is a broken oracle, never a broken kernel (D10, `ORA1 §DIFF-08`).
     */
    fun prefixesOf(script: CaseScript, reference: Reference): List<Map<String, ModelState>> {
        val ops = script.steps.filterIsInstance<CaseStep.Op>()
        return (0..ops.size).map { i -> reference.evaluate(CaseScript(ops.take(i)).toScript()) }
    }

    /**
     * A [Checker] over [case]'s script.
     *
     * The total-order chain [prefixesOf] computes is evaluated **eagerly**, exactly as before:
     * it is the checker's [Checker.prefixes], it seeds the frontier memo with the `opCount + 1`
     * frontiers a single-source case ever needs, and — load-bearingly — it is what makes a
     * reference that cannot evaluate a prefix throw HERE, before the graph is built, so
     * [DifferentialRunner.run] can report [RunOutcome.ModelEvaluationFailure] instead of letting
     * a throw escape from inside the per-step observer.
     *
     * Off-chain frontiers (multi-source only) are evaluated lazily and memoized, so a case pays
     * for the frontiers its observations actually reach, up to the [frontierLatticeSize]
     * ceiling.
     */
    fun checker(case: GeneratedCase, caseMarker: String, reference: Reference): Checker =
        Checker(case.seed, caseMarker, case.script, reference)

    /**
     * The running check: hand it every intermediate observation, in order, and it answers
     * `null` (admissible) or the [RunOutcome.WavePrefixViolation] that observation is.
     *
     * ## The floor is the whole non-regression property
     *
     * Per terminal it keeps the index of the lowest prefix that terminal has already matched,
     * and searches only `floor..lastIndex` for the next observation. An observation that
     * matches only a prefix *below* the floor is therefore not "found later" — it is reported
     * as [RunOutcome.WavePrefixViolation.Kind.REGRESSED], with the index it went back to. That
     * search bound is load-bearing and is mutation-checked in `WavePrefixTest`: widening it to
     * `0..lastIndex` makes the regression control pass, which is exactly the vacuous check
     * this property must not degrade into.
     *
     * Per terminal, not per run: two terminals of one case advance independently, and requiring
     * a shared floor would reject a legal run in which one arm of the graph is simply ahead.
     *
     * A terminal appearing for the first time part-way through (a late joiner linked at a
     * [CaseStep.Barrier]) starts at floor 0 and catches up monotonically, which is admissible
     * and is what `[24-CATCHUP-01]` requires of it.
     */
    class Checker internal constructor(
        private val seed: Long,
        private val caseMarker: String,
        private val script: CaseScript,
        private val reference: Reference,
    ) {
        /** The script's Ops in total drive order — the material every frontier restricts. */
        private val ops: List<CaseStep.Op> = script.steps.filterIsInstance<CaseStep.Op>()

        /** The frontier vector's axes, in the order the sources first appear in the total order. */
        private val sourceOrder: List<SourceId> = ops.map { it.source }.distinct()

        /** `ceiling[i]` = how many Ops source `i` has; a frontier coordinate's maximum. */
        private val ceiling: List<Int> = sourceOrder.map { source -> ops.count { it.source == source } }

        private val zero: List<Int> = List(sourceOrder.size) { 0 }

        /** `frontier -> every terminal's modelled state`, so no frontier is evaluated twice. */
        private val memo = LinkedHashMap<List<Int>, Map<String, ModelState>>()

        /** How many observations have been offered — a run's non-vacuity witness. */
        var observations: Int = 0
            private set

        /** How many terminal states have been compared, across all observations. */
        var comparisons: Int = 0
            private set

        /**
         * How many **distinct frontiers** this checker has evaluated the reference on — the cost
         * measure `ORA1 §PERF-01` cares about, bounded by [WavePrefixOracle.frontierLatticeSize]
         * however many observations arrive. `opCount + 1` at the moment a single-source checker
         * is constructed, and never more.
         */
        var frontierEvaluations: Int = 0
            private set

        /** How many frontiers have been *visited* (memo hit or miss) across all searches. */
        var frontiersScanned: Long = 0L
            private set

        /**
         * `prefixes[i]` = every terminal's modelled state after the first *i* Ops **of the total
         * drive order** — the chain through the frontier lattice that the total order traces.
         *
         * For a single-source case this chain IS the lattice, which is why the single-source
         * check is unchanged by the frontier generalization. For a multi-source case it is one
         * chain through it, kept because it is the eager evaluation that catches a broken
         * reference before driving, and because it seeds the memo along the path a run's
         * frontier usually stays near.
         */
        val prefixes: List<Map<String, ModelState>> = run {
            val counters = IntArray(sourceOrder.size)
            (0..ops.size).map { i ->
                if (i > 0) counters[sourceOrder.indexOf(ops[i - 1].source)]++
                stateAt(counters.toList())
            }
        }

        /** Per terminal, the antichain of minimal frontiers still consistent with its history. */
        private val candidates = LinkedHashMap<String, List<List<Int>>>()

        /**
         * [terminal]'s current matched floor as an **op count** — the total number of Ops the
         * componentwise-least admissible frontier has absorbed — or `null` if it has never
         * matched one.
         *
         * For a single-source case that count IS the prefix index, unchanged. For a multi-source
         * one it is the frontier vector's sum, which is lossy on purpose: this accessor's shape
         * is fixed by [RunOutcome.WavePrefixViolation]'s `Int` fields. [frontierFloorOf] is the
         * unlossy reading.
         */
        fun floorOf(terminal: String): Int? = frontierFloorOf(terminal)?.sum()

        /**
         * [terminal]'s componentwise-least admissible frontier — one absorbed-Op count per
         * source, in [sourceAxes] order — or `null` if it has never matched one.
         *
         * The componentwise minimum of the candidate antichain rather than a single frontier:
         * when several minimal frontiers match an observation, all of them stay live (a later
         * observation decides which the run was really on), and this is the greatest lower bound
         * on where the run can be.
         */
        fun frontierFloorOf(terminal: String): List<Int>? =
            candidates[terminal]?.let { set -> zero.indices.map { i -> set.minOf { it[i] } } }

        /** The frontier vector's axes: the sources, in the order the total drive order first names them. */
        fun sourceAxes(): List<SourceId> = sourceOrder

        /** How many minimal frontiers are still live for [terminal] — 1 for every single-source case. */
        fun candidateCountOf(terminal: String): Int = candidates[terminal]?.size ?: 0

        /**
         * Every terminal's modelled state at [frontier] — the reference evaluated on the script
         * restricted to each source's first `frontier[i]` Ops, memoized.
         *
         * Restricting per source and re-reading the result in total order is what makes this the
         * per-source frontier product rather than a total-order prefix: the surviving Ops keep
         * their relative order within each source, which is all
         * `civictech.oracle.model.Script` contracts on.
         */
        private fun stateAt(frontier: List<Int>): Map<String, ModelState> = memo.getOrPut(frontier) {
            frontierEvaluations++
            val taken = IntArray(sourceOrder.size)
            val kept = ops.filter { op ->
                val axis = sourceOrder.indexOf(op.source)
                (taken[axis] < frontier[axis]).also { if (it) taken[axis]++ }
            }
            reference.evaluate(CaseScript(kept).toScript())
        }

        /**
         * One intermediate observation — every terminal's fold at one instant, read through the
         * terminals' own views (never cell internals; see
         * [DifferentialRunner.Driving.readTerminals]).
         *
         * Returns the first violation among the terminals, or `null` if every one of them is
         * admissible. Terminals are checked in the observation's own iteration order, which is
         * the case's terminal declaration order.
         */
        fun observe(states: Map<String, ModelState>): RunOutcome.WavePrefixViolation? {
            observations++
            states.forEach { (terminal, state) ->
                val violation = observeTerminal(terminal, state)
                if (violation != null) return violation
            }
            return null
        }

        /**
         * One terminal's observed [state]. Advances that terminal's floor on a match; otherwise
         * reports [RunOutcome.WavePrefixViolation.Kind.REGRESSED] if the state is some *earlier*
         * prefix and [RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX] if it is no prefix
         * at all.
         *
         * Public so a test can feed the checker a fabricated observation stream directly — which
         * is how the torn and regressing controls discriminate without needing a kernel that
         * actually glitches.
         */
        fun observeTerminal(terminal: String, state: ModelState): RunOutcome.WavePrefixViolation? {
            comparisons++
            val live = candidates.getOrPut(terminal) { listOf(zero) }
            val lower = zero.indices.map { i -> live.minOf { it[i] } }

            // The admissible region: every frontier at or above SOME live candidate. Searched in
            // full, not to the first hit — the run's real frontier is one of the matches and we
            // do not know which, so every MINIMAL match has to stay live or a later legal
            // observation could be rejected for not being above the one we happened to pick.
            val matches = frontiersIn(lower).filter { f ->
                live.any { candidate -> dominates(f, candidate) } && stateAt(f)[terminal] == state
            }.toList()
            if (matches.isNotEmpty()) {
                candidates[terminal] = minimalElements(matches)
                return null
            }

            // No admissible frontier matches. Scanning the WHOLE lattice separates the two kinds:
            // a state that is some frontier the run has already passed is a regression, a state
            // that is no frontier at all is a tear. Reached only on a violation, and a violation
            // ends the run's checking, so the full scan is paid at most once per case.
            val regressed = frontiersIn(zero)
                .filter { stateAt(it)[terminal] == state }
                .minByOrNull { it.sum() }
            val regressedTo = regressed?.sum()
            return RunOutcome.WavePrefixViolation(
                seed = seed,
                terminal = terminal,
                kind = if (regressedTo == null) {
                    RunOutcome.WavePrefixViolation.Kind.NO_MATCHING_PREFIX
                } else {
                    RunOutcome.WavePrefixViolation.Kind.REGRESSED
                },
                renderedGraphSpec = caseMarker,
                script = script.toScript(),
                observed = state,
                observationIndex = observations,
                matchedFloor = lower.sum(),
                regressedTo = regressedTo,
                nearestPrefixes = nearestPrefixes(terminal, lower),
            )
        }

        /**
         * [terminal]'s modelled state at the floor and at the floor's successor — the two
         * frontiers a torn observation sits between, which is the evidence a reader needs and
         * the whole lattice is not.
         *
         * Keyed by absorbed-Op count, because [RunOutcome.WavePrefixViolation.nearestPrefixes]
         * is `Map<Int, ModelState>`. For a single-source case that key is the prefix index and
         * the two entries are `floor` and `floor + 1`, unchanged. For a multi-source case every
         * successor shares the key `floor + 1`, so the entry shown is the successor along the
         * FIRST axis that can still advance; [frontierFloorOf] and [sourceAxes] are how a reader
         * recovers the vector the count flattens.
         */
        private fun nearestPrefixes(terminal: String, floor: List<Int>): Map<Int, ModelState> {
            val nearest = LinkedHashMap<Int, ModelState>()
            stateAt(floor)[terminal]?.let { nearest[floor.sum()] = it }
            val axis = floor.indices.firstOrNull { floor[it] < ceiling[it] }
            if (axis != null) {
                val successor = floor.toMutableList().also { it[axis] = it[axis] + 1 }
                stateAt(successor)[terminal]?.let { nearest[successor.sum()] = it }
            }
            return nearest
        }

        /**
         * Every frontier in the box `[lower, ceiling]`, componentwise — the region an admissible
         * frontier can lie in, given that the run has already reached [lower] on every axis.
         *
         * Enumerated as a sequence so the caller's own filter decides how much of it is ever
         * evaluated, and bounded by construction: the box is a sub-box of the lattice, whose
         * size [notApplicableBecause] has already admitted against
         * [WavePrefixOracle.MAX_FRONTIER_LATTICE].
         */
        private fun frontiersIn(lower: List<Int>): Sequence<List<Int>> {
            var frontiers = sequenceOf(emptyList<Int>())
            lower.indices.forEach { axis ->
                frontiers = frontiers.flatMap { head -> (lower[axis]..ceiling[axis]).asSequence().map { head + it } }
            }
            return frontiers.onEach { frontiersScanned++ }
        }

        /** Whether [f] is at or above [other] on every axis. */
        private fun dominates(f: List<Int>, other: List<Int>): Boolean =
            f.indices.all { f[it] >= other[it] }

        /**
         * The componentwise-minimal elements of [frontiers] — the antichain that carries exactly
         * the same "everything at or above one of these" region as the whole set.
         *
         * Keeping only the minima is not an approximation: any frontier the run can reach later
         * is above the one it is on now, which is one of these.
         */
        private fun minimalElements(frontiers: List<List<Int>>): List<List<Int>> =
            frontiers.filter { candidate ->
                frontiers.none { other -> other != candidate && dominates(candidate, other) }
            }
    }
}

/**
 * The **runner-level** knob `ORA1 §PERF-01` allows for prefix checking's cost: which fraction
 * of eligible cases get checked.
 *
 * Deliberately a runner option and not a `civictech.oracle.gen.GeneratorConfig` field. Prefix
 * checking changes how a case is *observed*, not what case is generated: the same
 * `(seed, config)` pair must produce the same [civictech.oracle.gen.GeneratedCase] whether or
 * not anybody prefix-checks it (`ORA1 §GEN-01`), and putting the knob in the config would make
 * the corpus depend on the observation policy. `GeneratorConfig` also belongs to
 * computenet-4ru.6, not here.
 *
 * ## Selection is a pure function of the case seed
 *
 * [selects] hashes the seed rather than counting cases, so which cases get checked does not
 * depend on sweep order, sweep width, or how many JVMs a sweep is split across — the same seed
 * is checked or not checked identically everywhere. A counter-based "every fourth case" would
 * silently re-partition when a sweep range changed, and a shrink loop replaying one seed could
 * not reproduce the check that found the counterexample.
 *
 * @property fraction the fraction of eligible cases to check, in `0.0..1.0`. `0.0` disables
 *   prefix checking (final-state comparison is untouched); `1.0` checks every eligible case.
 *   The **default** is [WavePrefixOracle.DEFAULT_FRACTION], which is nonzero — D5 permits
 *   narrowing, never dropping.
 */
data class WavePrefixOption(val fraction: Double) {

    init {
        require(fraction in 0.0..1.0) { "WavePrefixOption.fraction must be in 0.0..1.0: $fraction" }
    }

    /** Whether the case with this [seed] is prefix-checked. Pure in [seed]. */
    fun selects(seed: Long): Boolean = when {
        fraction >= 1.0 -> true
        fraction <= 0.0 -> false
        else -> Random(seed xor SELECTION_SALT).nextDouble() < fraction
    }

    companion object {
        /**
         * A salt, so selection does not correlate with any other seed-derived stream a case
         * already has (`GeneratedCase.controllerSeed`, the injection interleaving). Without it,
         * "which cases are prefix-checked" and "which schedules those cases run under" would be
         * drawn from the same bits.
         */
        private const val SELECTION_SALT: Long = 0x57415645_50524658L // "WAVEPRFX"

        /**
         * The default: [WavePrefixOracle.DEFAULT_FRACTION] of eligible cases. Nonzero by
         * construction (D5) — a caller who wants no prefix checking asks for [OFF] explicitly.
         */
        val DEFAULT: WavePrefixOption = WavePrefixOption(WavePrefixOracle.DEFAULT_FRACTION)

        /** Every eligible case. What a targeted test of the property itself wants. */
        val ALWAYS: WavePrefixOption = WavePrefixOption(1.0)

        /**
         * No prefix checking at all — final-state comparison, dead-letter accounting and the
         * step budget are untouched. An explicit caller choice, never a default.
         */
        val OFF: WavePrefixOption = WavePrefixOption(0.0)
    }
}
