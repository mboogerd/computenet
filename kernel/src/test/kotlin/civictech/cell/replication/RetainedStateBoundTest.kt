package civictech.cell.replication

import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.GraphSpec
import civictech.testkit.dst.churn.MeshConvergences
import civictech.testkit.dst.churn.MeshPeers
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * **BS-16 (`[KE3-37]`), computenet-9sm.6.5 — the retained-state accounting, and the bound stated
 * over retained state AS A WHOLE.**
 *
 * ## What this test decides, and why the answer is a finding rather than a bound
 *
 * `[KE3-37]` asks for a seeded long-running session (adds/removes, member churn, heartbeat on,
 * periodic checkpoint-driven reclamation) whose per-cell retained state stays under a STATED
 * bound of the form `constant × (ops per checkpoint window)` — a constant times the in-flight
 * window, **not** a function of wall time or op count — with a control arm (reclamation off) that
 * shows growth.
 *
 * The clause was rewritten before this test was written, and the rewrite is the point:
 * **reclamation as landed is an EXCHANGE, not a bound.** `SetCell.compactBelow` discards a `dels`
 * entry and the `adds` tags under it, and records exactly those tags in `ReclaimedDots` — one
 * ELEMENT KEY per element ever reclaimed, plus a per-source list of contiguous counter runs. The
 * saving is the tag SETS, not the element keys, and nothing ever prunes the keys. `compactBelow`'s
 * own KDoc says it outright: "a reduction, not a bound". A bound stated over tombstone count alone
 * is therefore satisfiable by moving the growth into the fence, which is precisely why the bound
 * has to be stated over `SetCell.RetainedState.total` — tombstone tags PLUS fence runs PLUS fence
 * element keys.
 *
 * **Measured here, and the answer is negative for the whole:**
 *
 *  - the tombstone component IS `O(in-flight window)`: with the window pinned at one element it is
 *    **0 across a 16x sweep of op counts**, and on the churn rig it stays at a MEASURED max of 15
 *    against the 36 the no-reclaimer control reaches. That part is asserted as a bound below;
 *  - `fenceElements` is **monotone non-decreasing by construction** and equals the number of
 *    distinct elements this replica has ever reclaimed, so retained state as a whole grows with
 *    OP COUNT and is not `O(in-flight window)`. [`the fence grows with op count, not with the
 *    in-flight window`] measures that directly, at a window of one element, across a 16x range of
 *    op counts.
 *
 * Per `[KE3-37]`'s own disposition — "If no `O(in-flight window)` bound over the whole is
 * achievable without G-42, that SHALL be recorded as a finding and a DISPUTE rather than asserted
 * as a weaker passing bound" — the negative result is filed, not weakened:
 * `doc/kernel-lane-findings.md` `## KE3-BS16-RETAINED` and `concord/corpus/DISPUTES.md`
 * `## KE3-GC-BS16-RETAINED`. G-42 (epoch hygiene) stays open in `91-gap-analysis.md` (`[KE3-41]`).
 *
 * ## What is deliberately NOT in the accounting
 *
 * The computenet-dwkp diagnostic maps `mintedHere`/`incarnations` are unpruned, unreclaimable by
 * `compactBelow` and `O(local mints)`. They are **not** the reclaimer's growth, and computenet-fzd3
 * decided they stay unbounded on purpose rather than bounding or build-gating them — the per-entry
 * cost is measured and recorded, together with the workload bound under which that cost is
 * acceptable, at `SetCell.kt`'s `mintedHere` declaration site; if that bound is ever crossed,
 * option (a) — pruning `mintedHere` in `compactBelow` — is the repair. `SetCell.retainedState`
 * excludes them by construction, and this test's numbers are therefore about the reclaimer only. Live
 * add-tags with no `dels` entry are excluded for the opposite reason: they are `O(live elements)`
 * and irreducible — an element that is present must carry the tag that makes it present.
 *
 * ## How it is gated, and the wall time
 *
 * There is no `@Tag("slow")` convention in `:kernel` — a `git grep` for `@Tag(` under
 * `kernel/src/test` returns nothing — so there is no mechanism to gate on. It is gated the way
 * `GcSafetySweepTest` is: a
 * **bounded seed range** ([SEEDS]) and a **recorded wall time**, both printed by the run and both
 * never narrowed after a red seed.
 *
 * **MEASURED 2026-09-07**, Apple-silicon 16-core macOS host, `uptime` load average recorded beside
 * each figure in the run's own `[BS-16]` output because four sibling agents were running
 * concurrently on this machine (load ~19 at dispatch). The numbers this KDoc quotes are reproduced
 * by re-running this class; the printed lines carry the per-seed distribution, not a single
 * number, because a single run on a churn rig is not evidence — `GcSafetySweepTest`'s reviewer
 * measured STABLE divergence counts of 4,5,5,5,8,9 against a control of 4,4,5,6,6,6 over ten
 * 200-seed runs. That variance is in the DIVERGENCE observable; the observable here is a retained
 * COUNT read at every reclaim point, and its spread across seeds is reported below rather than
 * assumed away.
 */
class RetainedStateBoundTest {

    // ------------------------------------------------------------------------ the sweep arms

    /**
     * One replica's retained state at one sampling point, plus the step it was read at.
     *
     * Read AFTER the reclaimer's hook at the same step: this class installs its sampling hook on
     * the world that [GcSafetySweep.graphOf] has already built, and `StepHooks` fire in
     * registration order, so the sample sees the state the compaction point left behind.
     */
    private data class Sample(val step: Int, val peer: String, val state: SetCell.RetainedState)

    /** Compaction period in controller steps — [GcSafetySweep]'s `K`, restated (it is private). */
    private val samplePeriod = 25

    /**
     * [GcSafetySweep]'s graph for [trigger] with a sampling hook appended.
     *
     * The rig is deliberately REUSED rather than rebuilt: it already is "adds/removes with member
     * churn, heartbeat on, periodic checkpoints" — `ChurnMesh` keeps the run alive with a trivial
     * task per step, the removes hook removes every odd-ordinal write, and the STABLE arm reclaims
     * through the production trigger (`SetCell.snapshot()` reading the installed stability hook),
     * substituted by computenet-9sm.6.4. Building a second rig would measure a different workload
     * and prove nothing about the one `[KE3-37]` names.
     */
    private fun sampledGraph(trigger: GcSafetySweep.Trigger, into: MutableList<Sample>): GraphSpec =
        GraphSpec("retained-state-bound-${trigger.id}") { world ->
            GcSafetySweep.graphOf(trigger).builder.build(world)
            world.steps.onStep { w, step -> if (step > 0 && step % samplePeriod == 0) sample(w, step, into) }
        }

    private fun sample(world: DstWorld, step: Int, into: MutableList<Sample>) {
        for (peer in MeshPeers.all(world)) {
            if (!peer.member) continue
            @Suppress("UNCHECKED_CAST")
            val cell = (peer.replica ?: continue) as? SetCell<String> ?: continue
            into += Sample(step, peer.name, cell.retainedState())
        }
    }

    /** Every sample taken over [SEEDS] on one arm. */
    private fun samplesOver(trigger: GcSafetySweep.Trigger): List<Sample> {
        val all = mutableListOf<Sample>()
        for (seed in SEEDS) {
            val seedSamples = mutableListOf<Sample>()
            MeshConvergences.observing {
                DstRun(sampledGraph(trigger, seedSamples), GcSafetySweep.plan(seed), BUDGET).execute()
            }
            all += seedSamples
        }
        return all
    }

    private fun report(tag: String, samples: List<Sample>): String {
        val totals = samples.map { it.state.total }.sorted()
        val tombstones = samples.map { it.state.tombstoneTags }.sorted()
        val runs = samples.sumOf { it.state.fenceRuns }
        val elements = samples.sumOf { it.state.fenceElements }
        return "[BS-16] $tag samples=${samples.size} " +
            "total(min/median/max)=${totals.firstOrNull()}/${totals.getOrNull(totals.size / 2)}/" +
            "${totals.lastOrNull()} " +
            "tombstoneTags(min/median/max)=${tombstones.firstOrNull()}/" +
            "${tombstones.getOrNull(tombstones.size / 2)}/${tombstones.lastOrNull()} " +
            "maxFenceElements=${samples.maxOfOrNull { it.state.fenceElements }} " +
            "maxFenceRuns=${samples.maxOfOrNull { it.state.fenceRuns }} " +
            "runs/elements=${if (elements == 0) "n/a" else "%.3f".format(runs.toDouble() / elements)}"
    }

    /**
     * **The part of the bound that HOLDS**, asserted as a bound — and asserted next to the
     * negative result below, never in place of it.
     *
     * The checkpoint-driven arm reclaims tombstones: the `dels` tags and the `adds` tags under
     * them are discarded once the whole entry is at or below the stable frontier, so the tombstone
     * component tracks the removes still in flight rather than the removes ever issued. The
     * control arm ([GcSafetySweep.Trigger.NONE], the no-reclaimer arm) never discards, so its
     * tombstone component ends at every remove the workload issued — that is the growth
     * `[KE3-37]` asks the control to show, and it is asserted here rather than described.
     *
     * The constant is MEASURED and recorded in [MAX_STABLE_TOMBSTONE_TAGS], not chosen: read the
     * `[BS-16]` lines this test prints. It is stated as a headroom over the measured max on a
     * bounded seed range, so a regression that stops reclaiming reddens it (proved by mutation —
     * see the class KDoc of the mutation record in `doc/kernel-lane-findings.md`).
     */
    @Test
    fun `a checkpoint-driven session keeps its TOMBSTONE component bounded, and the control grows`() {
        val startedAt = System.nanoTime()
        val stable = samplesOver(GcSafetySweep.Trigger.STABLE)
        val control = samplesOver(GcSafetySweep.Trigger.NONE)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        println("[BS-16] seeds=$SEEDS budget=$BUDGET elapsedMs=$elapsedMs load=${loadAverage()}")
        println(report("STABLE ", stable))
        println(report("CONTROL", control))

        assertTrue(stable.isNotEmpty() && control.isNotEmpty(), "[KE3-37]: no sample was taken at all")
        assertTrue(
            stable.any { it.state.fenceElements > 0 },
            "[KE3-37]: the reclaimer never discarded anything on the STABLE arm, so this run " +
                "proves nothing about reclamation: ${report("STABLE ", stable)}",
        )
        assertTrue(
            control.all { it.state.fenceElements == 0 && it.state.fenceRuns == 0 },
            "[KE3-37]: the no-reclaimer control must never write the fence, or it is not a " +
                "control: ${report("CONTROL", control)}",
        )

        val stableMax = stable.maxOf { it.state.tombstoneTags }
        val controlMax = control.maxOf { it.state.tombstoneTags }
        assertTrue(
            stableMax <= MAX_STABLE_TOMBSTONE_TAGS,
            "[KE3-37] BS-16, the component that holds: on a checkpoint-driven churn session the " +
                "TOMBSTONE component of retained state stays under $MAX_STABLE_TOMBSTONE_TAGS " +
                "tags per cell, against an arithmetic ceiling of 36 the no-reclaimer control " +
                "reaches. MEASURED max=$stableMax over $SEEDS. This bound is over tombstone tags " +
                "ONLY and is NOT the bound BS-16 asks for; see the negative result in " +
                "`the fence grows with op count, not with the in-flight window` and the finding " +
                "`## KE3-BS16-RETAINED`. ${report("STABLE ", stable)}",
        )
        assertTrue(
            controlMax > stableMax,
            "[KE3-37]: the control arm must SHOW GROWTH the reclaiming arm does not — its " +
                "tombstone component retains every remove the workload ever issued. " +
                "controlMax=$controlMax stableMax=$stableMax. A control that does not exceed " +
                "the reclaiming arm means the reclaimer is not the thing being measured. " +
                "${report("CONTROL", control)}",
        )
    }

    // -------------------------------------------------------- the negative result, measured

    /**
     * **THE FINDING, measured rather than argued: retained state as a whole is `O(elements ever
     * reclaimed)`, not `O(in-flight window)`.**
     *
     * The in-flight window here is exactly **one element**: each iteration adds an element,
     * removes it, and reclaims at a frontier that covers every tag — so at every reclaim point at
     * most one tombstone exists and the tombstone component returns to zero. If retained state
     * were `constant × (ops per window)` it would be flat as the op count grows. It is not: it
     * grows linearly, because `ReclaimedDots` keeps one element key (and here one run) per element
     * ever reclaimed, and nothing prunes them. That needs epoch hygiene — G-42, research-gated,
     * which is why G-42 stays OPEN in `91-gap-analysis.md` (`[KE3-41]`).
     *
     * This arm is deliberately NOT the churn sweep. The sweep's workload is fixed at 24 writes and
     * 12 removes, so it can show the fence's monotonicity but cannot vary the op count against a
     * held-constant window — and varying that ratio is the whole content of "not a function of op
     * count". Here the ratio is swept over 16x with the window pinned at 1, in-process and
     * deterministic, and the growth is read off directly.
     *
     * `runCount / elementCount` — the number `[KE3-37]` marks `unverified:` — is reported by both
     * arms. Here it is the coalescing best case (the add-tag and the del-dot are minted adjacently
     * by one source, so they collapse to a single run); the sweep arm reports it on a real
     * interleaving.
     */
    @Test
    fun `the fence grows with op count, not with the in-flight window`() {
        val measured = OP_COUNTS.associateWith { retainedAfterPairs(it) }
        measured.forEach { (pairs, state) ->
            println(
                "[BS-16] window=1 pairs=$pairs total=${state.total} tombstoneTags=${state.tombstoneTags} " +
                    "fenceRuns=${state.fenceRuns} fenceElements=${state.fenceElements} " +
                    "runs/elements=${"%.3f".format(state.fenceRuns.toDouble() / state.fenceElements)}",
            )
        }

        measured.forEach { (pairs, state) ->
            assertTrue(
                state.tombstoneTags == 0,
                "[KE3-37]: at a window of one element every tombstone is reclaimable, so the " +
                    "TOMBSTONE component must be empty — if it is not, this arm is measuring a " +
                    "reclaimer that did not run rather than the fence. pairs=$pairs state=$state",
            )
            assertTrue(
                state.fenceElements == pairs,
                "[KE3-37]: the fence retains one element key per element ever reclaimed, by " +
                    "construction (`ReclaimedDots` is keyed on the element). pairs=$pairs " +
                    "state=$state",
            )
        }

        // The bound BS-16 asks for, stated and CONTRADICTED: `constant × ops-per-window` with the
        // window pinned at one element is a constant, so a 16x rise in op count must leave
        // retained state flat. It rises by the same 16x.
        val smallest = measured.getValue(OP_COUNTS.first())
        val largest = measured.getValue(OP_COUNTS.last())
        val opRatio = OP_COUNTS.last().toDouble() / OP_COUNTS.first()
        val retainedRatio = largest.total.toDouble() / smallest.total
        println("[BS-16] opRatio=$opRatio retainedRatio=${"%.3f".format(retainedRatio)}")
        assertTrue(
            retainedRatio >= opRatio * 0.9,
            "[KE3-37]: THIS ASSERTION IS THE FINDING, not a regression guard. Retained state as " +
                "a whole scales with OP COUNT at a fixed in-flight window: ${OP_COUNTS.first()} " +
                "pairs -> total=${smallest.total}, ${OP_COUNTS.last()} pairs -> " +
                "total=${largest.total} (opRatio=$opRatio retainedRatio=$retainedRatio). If this " +
                "ever goes red because retained state stopped growing, the fence has acquired a " +
                "pruning rule — that is G-42 landing, and `## KE3-BS16-RETAINED` in " +
                "doc/kernel-lane-findings.md, the DISPUTE `## KE3-GC-BS16-RETAINED`, and G-42 in " +
                "91-gap-analysis.md must all be revisited rather than this assertion relaxed.",
        )
    }

    /**
     * The tombstone component counts only tags a reclaim could actually discard — **a live
     * re-added tag is not one of them.**
     *
     * This is a deterministic pin of `SetCell.retainedState`'s definition, and it exists because
     * the churn arm above **cannot** exercise it. MEASURED during this bead's mutation check:
     * removing the `adds[e] ∩ dels[e]` filter from the accessor (counting every `adds[e]` tag
     * under a `dels[e]` entry instead) changed the sweep's numbers by **nothing at all** —
     * `tombstoneTags` max 15, control 36, byte-identical. The reason is a property of the
     * workload, not of the accessor: `GcSafetySweep` never re-adds an element it removed, and
     * `SetCell.remove` folds every observed add-tag into `dels[e]` while leaving it in `adds[e]`,
     * so on that workload `adds[e] ⊆ dels[e]` whenever `dels[e]` exists and the filter is a no-op.
     *
     * A mutation that survives is a property left unproven, so it is proven here instead. After
     * `add / remove / add`, the second add-tag is LIVE — no `dels` entry covers it, `compactBelow`
     * can never discard it, and counting it would inflate the tombstone component with state that
     * is `O(live elements)` and irreducible. The element is a member throughout, which is what
     * makes the tag live rather than merely present.
     */
    @Test
    fun `the tombstone component excludes a live re-added tag`() {
        val cell = SetCell<String>()
        cell.inlet.call.add("e")
        cell.inlet.call.remove("e")
        cell.inlet.call.add("e") // live: minted after the remove, so no `dels` tag covers it

        assertTrue(cell.membership() == setOf("e"), "the re-add must make the element live again")
        val before = cell.retainedState()
        assertTrue(
            before.tombstoneTags == 3,
            "[KE3-37]: `dels[e]` holds the first add-tag and the del-dot, and `adds[e]` holds that " +
                "first add-tag plus the LIVE second one — so the tombstone component is 2 (the two " +
                "`dels` tags) + 1 (the `adds` tag under them) minus nothing, and the live tag is " +
                "NOT counted. Counting it would report 4. state=$before",
        )

        cell.compactBelow(coveringFrontier(cell))
        val after = cell.retainedState()
        assertTrue(
            cell.membership() == setOf("e") && after.tombstoneTags == 0 && after.fenceElements == 1,
            "[KE3-37]: the reclaim discards exactly the tags the tombstone component counted, " +
                "leaves the live tag and the membership alone, and exchanges them for one fence " +
                "element key. state=$after membership=${cell.membership()}",
        )
    }

    /**
     * One `SetCell`, [pairs] add/remove pairs, a reclaim at a covering frontier after each pair.
     *
     * `compactBelow` rather than `snapshot()`: this arm is not under `Replication`, so no
     * stability hook is installed and `snapshot()` would reclaim nothing. The trigger is not what
     * this arm measures — the sweep arm above measures the production trigger — what it measures
     * is what the reclaimer RETAINS, which is the same either way (`snapshot()` reclaims by
     * calling `compactBelow`).
     */
    private fun retainedAfterPairs(pairs: Int): SetCell.RetainedState {
        val cell = SetCell<String>()
        for (i in 0 until pairs) {
            cell.inlet.call.add("e$i")
            cell.inlet.call.remove("e$i")
            cell.compactBelow(coveringFrontier(cell))
        }
        return cell.retainedState()
    }

    /** Every source this cell currently holds a tag for, at `Long.MAX_VALUE` — covers everything. */
    @Suppress("UNCHECKED_CAST")
    private fun coveringFrontier(cell: SetCell<String>): TagFrontier {
        val snapshot = cell.snapshot() as Map<String, Any?>
        val sources = listOf("adds", "dels")
            .flatMap { (snapshot[it] as Map<Any?, Set<Timestamp>>).values }
            .flatten()
            .map { it.sourceId }
            .toSet()
        return TagFrontier(sources.associateWith { Long.MAX_VALUE })
    }

    private fun loadAverage(): String =
        runCatching { java.lang.management.ManagementFactory.getOperatingSystemMXBean().systemLoadAverage }
            .getOrDefault(-1.0)
            .let { "%.2f".format(it) }

    companion object {
        /**
         * The gate, in place of a `@Tag("slow")` that `:kernel` has no mechanism for: a bounded
         * seed range, RECORDED and never narrowed after a red seed, with the wall time the run
         * prints beside it.
         */
        private val SEEDS = 1L..20L

        /** [GcSafetySweepTest]'s own budget, so the two arms drain the same workload. */
        private const val BUDGET: Int = 40_000

        /**
         * MEASURED 2026-09-07 with headroom, and stated for what it is: a **recorded ceiling on
         * this workload**, not the `O(in-flight window)` claim.
         *
         * The workload issues 12 removes, each carrying at most 2 `dels` tags (the covered add-tag
         * and the del-dot) plus the same add-tag still in `adds`, so **36 is the arithmetic ceiling
         * of the tombstone component on this rig with reclamation off** — and the CONTROL arm
         * reaches it. The STABLE arm MEASURED max 15 over seeds 1..20 (median 3). 24 sits between
         * the two: a regression that stops reclaiming tombstones lands at the control's 36 and
         * reddens this, which is what the mutation in `## KE3-BS16-RETAINED` proves.
         *
         * **Why this is not itself the window bound.** 12 removes over a 5 190-step workload with
         * a 25-step compaction period is ~0.17 ops per window, so no constant times that window is
         * distinguishable from "every remove the workload ever issued" at this op count. The
         * `O(window)` evidence for the tombstone component is the window-pinned arm
         * ([`the fence grows with op count, not with the in-flight window`]), where the window is
         * held at one element and the tombstone component is 0 across a 16x sweep of op counts.
         * This constant is the churn rig's regression pin beside it.
         */
        private const val MAX_STABLE_TOMBSTONE_TAGS: Int = 24

        /** Op counts for the window-pinned scaling arm: a 16x sweep at a window of one element. */
        private val OP_COUNTS = listOf(25, 50, 100, 200, 400)
    }
}
