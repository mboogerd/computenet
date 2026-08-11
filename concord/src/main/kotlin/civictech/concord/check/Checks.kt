package civictech.concord.check

import civictech.concord.driver.Driver
import civictech.concord.driver.ReadPage
import civictech.concord.driver.WavePlane
import civictech.concord.oracle.BatchOracle
import civictech.concord.oracle.Functions
import civictech.concord.oracle.OracleUnsupported
import civictech.concord.oracle.Values
import civictech.concord.schema.ApplyStep
import civictech.concord.schema.Check
import civictech.concord.schema.EffectCount
import civictech.concord.schema.FinalView
import civictech.concord.schema.IncrementalEqualsBatch
import civictech.concord.schema.LateJoinEqualsEarly
import civictech.concord.schema.NoDeadLetters
import civictech.concord.schema.ObservationsAllSatisfy
import civictech.concord.schema.ObservationsMonotone
import civictech.concord.schema.ObservationsWholeWaves
import civictech.concord.schema.PagesEqualView
import civictech.concord.schema.ReplicasConverge
import civictech.concord.schema.WavePlaneUnchanged
import civictech.concord.schema.Scenario
import civictech.concord.schema.ViewsConverge
import civictech.concord.value.Value

/**
 * The executable check vocabulary (§1.4) — the oracles that turn a declarative
 * [Check] plus one run's driver observations into a pass/fail. Implemented once
 * in the harness, in terms of driver verbs only, so no per-implementation
 * duplication (§1.4). This package is neutral: it imports `civictech.concord.*`
 * only, never `civictech.cell.*`.
 *
 * **W0 freezes the signatures; the bodies are stubs.** W1-B fills them (plus the
 * batch oracle) and unit-tests them against hand-computed fixtures. Do not change
 * a signature here without a schema-change ticket — W1-A's runner and W1-B's
 * oracle both bind to them.
 */
object Checks {

    /** Dispatch one check to its evaluator. Runner entry point (W1-A/W2 wire this in). */
    fun evaluate(check: Check, ctx: CheckContext): CheckResult = when (check) {
        is FinalView -> finalView(check, ctx)
        is ViewsConverge -> viewsConverge(check, ctx)
        is IncrementalEqualsBatch -> incrementalEqualsBatch(check, ctx)
        is LateJoinEqualsEarly -> lateJoinEqualsEarly(check, ctx)
        is ObservationsAllSatisfy -> observationsAllSatisfy(check, ctx)
        is ObservationsMonotone -> observationsMonotone(check, ctx)
        is ObservationsWholeWaves -> observationsWholeWaves(check, ctx)
        is ReplicasConverge -> replicasConverge(check, ctx)
        is NoDeadLetters -> noDeadLetters(ctx)
        is EffectCount -> effectCount(check, ctx)
        is WavePlaneUnchanged -> wavePlaneUnchanged(check, ctx)
        is PagesEqualView -> pagesEqualView(check, ctx)
    }

    /** At quiescence, `readView(view)` equals the golden value. */
    fun finalView(check: FinalView, ctx: CheckContext): CheckResult {
        val actual = ctx.driver.readView(check.view)
        val type = viewType(ctx.scenario, check.view)
        return if (Values.equalForView(actual, check.expected, type)) {
            CheckResult.Passed
        } else {
            CheckResult.Failed("final-view(${check.view}): expected ${Values.render(check.expected)} but read ${Values.render(actual)}")
        }
    }

    /** All listed views hold equal folds at quiescence. */
    fun viewsConverge(check: ViewsConverge, ctx: CheckContext): CheckResult {
        if (check.views.size < 2) return CheckResult.Passed
        val ref = check.views.first()
        val refType = viewType(ctx.scenario, ref)
        val refVal = ctx.driver.readView(ref)
        for (other in check.views.drop(1)) {
            val v = ctx.driver.readView(other)
            if (!Values.equalForView(refVal, v, refType)) {
                return CheckResult.Failed("views-converge: $ref=${Values.render(refVal)} but $other=${Values.render(v)}")
            }
        }
        return CheckResult.Passed
    }

    /** View equals the harness-side batch oracle over the accepted-op multiset. */
    fun incrementalEqualsBatch(check: IncrementalEqualsBatch, ctx: CheckContext): CheckResult {
        val oracle = try {
            BatchOracle(ctx.scenario)
        } catch (e: OracleUnsupported) {
            return CheckResult.Failed("incremental-equals-batch: oracle cannot model this scenario — ${e.message}")
        }
        val targets = if (check.view == "*") oracle.allViewValues().keys.toList() else listOf(check.view)
        for (viewId in targets) {
            val expected = try {
                oracle.view(viewId)
            } catch (e: OracleUnsupported) {
                return CheckResult.Failed("incremental-equals-batch($viewId): ${e.message}")
            }
            val actual = ctx.driver.readView(viewId)
            if (!Values.equalForView(actual, expected, viewType(ctx.scenario, viewId))) {
                return CheckResult.Failed(
                    "incremental-equals-batch($viewId): oracle=${Values.render(expected)} but read=${Values.render(actual)}",
                )
            }
        }
        return CheckResult.Passed
    }

    /**
     * Late-linked and early-linked views hold equal folds. When [check] names the
     * two views explicitly they are compared; otherwise the two view cells of the
     * graph are inferred (a two-view late-join scenario).
     */
    fun lateJoinEqualsEarly(check: LateJoinEqualsEarly, ctx: CheckContext): CheckResult {
        val views = viewCells(ctx.scenario)
        val earlyId = check.early ?: views.getOrNull(0)
        val lateId = check.late ?: views.getOrNull(1)
        if (earlyId == null || lateId == null) {
            return CheckResult.Failed("late-join-equals-early: could not identify early/late views (name them explicitly)")
        }
        val e = ctx.driver.readView(earlyId)
        val l = ctx.driver.readView(lateId)
        return if (Values.equalForView(e, l, viewType(ctx.scenario, earlyId))) {
            CheckResult.Passed
        } else {
            CheckResult.Failed("late-join-equals-early: early $earlyId=${Values.render(e)} but late $lateId=${Values.render(l)}")
        }
    }

    /**
     * Every event on the observation stream satisfies the catalog predicate.
     *
     * Fails — rather than passes — when the named view produced no observation
     * at all. An empty stream satisfies "every event passes" vacuously, and that
     * is "the check had nothing to look at", which must never read as "the
     * property held": a stream that stopped being recorded would otherwise
     * silently disarm the check (observed on `CTL-GF-01`, computenet-qaz).
     */
    fun observationsAllSatisfy(check: ObservationsAllSatisfy, ctx: CheckContext): CheckResult {
        val predicate = Functions.predicate(check.fn)
        val log = ctx.driver.observationLog(check.view)
        nothingObserved("observations-all-satisfy(${check.view}, ${check.fn})", check.view, log)?.let { return it }
        val offending = log.withIndex().firstOrNull { !predicate(it.value) }
            ?: return CheckResult.Passed
        return CheckResult.Failed(
            "observations-all-satisfy(${check.view}, ${check.fn}): event #${offending.index} ${Values.render(offending.value)} fails the predicate",
        )
    }

    /**
     * The observation stream never regresses under the stated order.
     *
     * Fails — rather than passes — when the named view produced no observation
     * at all, for the same reason as [observationsAllSatisfy]: an empty stream
     * never regresses, so passing on it would be passing on nothing observed.
     */
    fun observationsMonotone(check: ObservationsMonotone, ctx: CheckContext): CheckResult {
        val log = ctx.driver.observationLog(check.view)
        nothingObserved("observations-monotone(${check.view})", check.view, log)?.let { return it }
        val decreasing = check.order?.lowercase()?.let { it.startsWith("desc") || it.startsWith("non-inc") } ?: false
        for (i in 1 until log.size) {
            val step = Values.compare(log[i - 1], log[i])
            val regressed = if (decreasing) step < 0 else step > 0
            if (regressed) {
                return CheckResult.Failed(
                    "observations-monotone(${check.view}): event #$i ${Values.render(log[i])} regresses from ${Values.render(log[i - 1])}",
                )
            }
        }
        return CheckResult.Passed
    }

    /**
     * The set-shaped glitch-freedom check (spec 22 `22-GF-01`/`22-GF-02`,
     * DISPUTES.md `22-GF-DIAMOND-01`/`22-GF-NESTED-01`/`22-WAVE-FANIN-01`):
     * every value on [check]'s view's observation stream must equal [check]'s
     * source's own `add`/`remove` fold at *some whole prefix* of its accepted op
     * sequence. A torn fork-join delivery — one arm's contribution landed
     * without its sibling's — would show up as an observed set that is not any
     * whole-prefix fold (e.g. missing or containing exactly one arm's half of a
     * paired admission), so this is a real, checkable stand-in for "no
     * observation mixes pre-wave and post-wave inputs" over a SET stream, where
     * the frozen (scalar) function catalog has nothing to offer
     * `observations-all-satisfy`.
     *
     * The prefix folds are computed locally (harness-only `add`/`remove`
     * semantics over [check]'s source's `ApplyStep`s) rather than via
     * [BatchOracle] — the oracle only exposes the *final* fold (`view`/
     * `allViewValues`), not per-prefix history, and this check needs the whole
     * chain of intermediate states, not just the end of it.
     *
     * Fails — rather than passes — when the named view produced no observation
     * at all: with no observed state there is no torn delivery to find, and "the
     * check had nothing to look at" must never read as "the property held".
     */
    fun observationsWholeWaves(check: ObservationsWholeWaves, ctx: CheckContext): CheckResult {
        val ops = ctx.scenario.script.filterIsInstance<ApplyStep>().filter { it.on == check.source }
        val prefixes = LinkedHashSet<Set<Value>>()
        val running = LinkedHashSet<Value>()
        prefixes += LinkedHashSet(running)
        for (op in ops) repeat(op.times ?: 1) {
            when (op.op) {
                "add" -> running.add(
                    op.value ?: return CheckResult.Failed(
                        "observations-whole-waves(${check.view}): '${check.source}' add with no value",
                    ),
                )
                "remove" -> running.remove(op.value)
                else -> return CheckResult.Failed(
                    "observations-whole-waves(${check.view}): '${check.source}' op '${op.op}' is not " +
                        "add/remove (only a set-source's own vocabulary is modeled)",
                )
            }
            prefixes += LinkedHashSet(running)
        }
        val log = ctx.driver.observationLog(check.view)
        nothingObserved("observations-whole-waves(${check.view})", check.view, log)?.let { return it }
        val offending = log.withIndex().firstOrNull { (_, v) ->
            val observed = (v as? Value.ListVal)?.items?.toSet()
                ?: return@firstOrNull true // not set-shaped — cannot be a whole-prefix state either
            observed !in prefixes
        } ?: return CheckResult.Passed
        return CheckResult.Failed(
            "observations-whole-waves(${check.view}): event #${offending.index} ${Values.render(offending.value)} " +
                "is not a whole-prefix state of '${check.source}' — a torn fork-join delivery",
        )
    }

    /**
     * All *live* replicas of the logical id hold equal folds (spec 42 §G-45
     * departed-stream rule, `42-REPL-06`). The scenario graph only names the
     * *declared* replica set — it says nothing about which of them are still
     * live after the run, so a replica that departed mid-run (`despawn`/evict)
     * must be excluded from the comparison rather than compared against its
     * frozen last fold (that comparison is exactly the false-positive G-45
     * forbids: survivors are expected to keep advancing past whatever the
     * departed replica last held).
     *
     * The neutral [Driver] SPI has no "list live replicas" verb (adding one is
     * a driver change out of this check's scope — see DISPUTES.md
     * `42-REPL-DEPART-01`), so liveness is read off the SPI verb that already
     * carries the signal: [Driver.readView] is documented as "the current
     * materialized value of a view cell" — a cell `despawn` has retired is no
     * longer one, and the in-process binding's own bookkeeping (a plain cell
     * table keyed by id) throws [NoSuchElementException] reading a removed
     * key. Catching *only* that — not every exception — excludes a genuinely
     * departed replica while still surfacing any other failure as a real bug.
     * This mirrors the kernel's own harness equivalent
     * (`cell.verify.ReplicaConvergence.liveRefs`, which intersects the
     * attached set against `LocationRegistry.replicasOf`) using only the
     * neutral SPI this package is allowed to touch.
     */
    fun replicasConverge(check: ReplicasConverge, ctx: CheckContext): CheckResult {
        val declared = ctx.scenario.graph?.cells.orEmpty().filter { it.replicaOf == check.logical }.map { it.id }
        val live = declared.mapNotNull { id ->
            try {
                id to ctx.driver.readView(id)
            } catch (e: NoSuchElementException) {
                null // departed (despawned/evicted) — excluded, not compared (G-45)
            }
        }
        if (live.size < 2) return CheckResult.Passed
        val (refId, refVal) = live.first()
        val refType = viewType(ctx.scenario, refId)
        for ((otherId, v) in live.drop(1)) {
            if (!Values.equalForView(refVal, v, refType)) {
                return CheckResult.Failed(
                    "replicas-converge(${check.logical}): $refId=${Values.render(refVal)} but $otherId=${Values.render(v)}",
                )
            }
        }
        return CheckResult.Passed
    }

    /** Zero dead letters across all hosts. */
    fun noDeadLetters(ctx: CheckContext): CheckResult {
        val dls = ctx.driver.deadLetters()
        return if (dls.isEmpty()) {
            CheckResult.Passed
        } else {
            CheckResult.Failed("no-dead-letters: ${dls.size} dead letter(s), first: ${dls.first().reason}")
        }
    }

    /**
     * An effectful sink acted exactly N times per key. When [EffectCount.key] is
     * given, only that key is asserted; otherwise every distinct key the sink
     * produced must have exactly the stated count.
     */
    fun effectCount(check: EffectCount, ctx: CheckContext): CheckResult {
        val effects = ctx.driver.effectLog(check.sink)
        val byKey: Map<String?, Int> = effects.groupingBy { it.key }.eachCount()
        val relevant = if (check.key != null) mapOf(check.key to (byKey[check.key] ?: 0)) else byKey
        if (relevant.isEmpty() && check.exactly != 0) {
            return CheckResult.Failed("effect-count(${check.sink}): expected ${check.exactly} per key but the sink produced no effects")
        }
        for ((key, count) in relevant) {
            if (count != check.exactly) {
                return CheckResult.Failed("effect-count(${check.sink}, key=$key): expected ${check.exactly} but observed $count")
            }
        }
        return CheckResult.Passed
    }

    /**
     * A bounded read perturbed nothing (spec 21 `[21-PULL-02]`): across every
     * recorded walk on the named cell, that cell's wave plane is identical
     * before and after.
     *
     * Fails — rather than passes — when the scenario recorded no walk on that
     * cell at all, or when a recorded walk produced no page. Both are "the
     * check had nothing to look at", which must never read as "the property
     * held": the whole hazard of this check is that it is trivially satisfiable
     * by not reading anything.
     */
    fun wavePlaneUnchanged(check: WavePlaneUnchanged, ctx: CheckContext): CheckResult {
        val walks = ctx.reads.filter { it.cell == check.cell }
        if (walks.isEmpty()) {
            return CheckResult.Failed(
                "wave-plane-unchanged(${check.cell}): the scenario performed no bounded read on " +
                    "'${check.cell}' — nothing was observed, so nothing is asserted (add a read-state step)",
            )
        }
        walks.forEachIndexed { i, walk ->
            if (walk.pages.isEmpty()) {
                return CheckResult.Failed("wave-plane-unchanged(${check.cell}): walk #$i returned no page at all")
            }
            if (walk.waveBefore != walk.waveAfter) {
                return CheckResult.Failed(
                    "wave-plane-unchanged(${check.cell}): walk #$i (limit ${walk.limit}) advanced the wave plane " +
                        "from ${walk.waveBefore.positions} to ${walk.waveAfter.positions} — the read emitted",
                )
            }
        }
        return CheckResult.Passed
    }

    /**
     * Every recorded walk on the named cell is complete, non-duplicating and
     * faithful (spec 24 `[24-BOUND-01]`/`[24-BOUND-02]`): each page carried a
     * frontier stamp, all the stamps of one walk were equal, no entry key
     * appeared twice in one walk, and the union of the walk's live entries
     * equals the named view's fold.
     *
     * A walk whose stamps differ is a *smeared* read: `[21-PULL-03]`'s
     * antecedent is false, so the union is claimed to equal nothing, and
     * reporting that as a failure is the honest outcome — passing instead would
     * be passing on a false antecedent.
     */
    fun pagesEqualView(check: PagesEqualView, ctx: CheckContext): CheckResult {
        val walks = ctx.reads.filter { it.cell == check.cell }
        if (walks.isEmpty()) {
            return CheckResult.Failed(
                "pages-equal-view(${check.cell} → ${check.view}): the scenario performed no bounded read on " +
                    "'${check.cell}' — nothing was observed, so nothing is asserted (add a read-state step)",
            )
        }
        val expected = ctx.driver.readView(check.view)
        val viewType = viewType(ctx.scenario, check.view)
        walks.forEachIndexed { i, walk ->
            val where = "pages-equal-view(${check.cell} → ${check.view}) walk #$i (limit ${walk.limit})"
            if (walk.pages.isEmpty()) return CheckResult.Failed("$where: the walk returned no page at all")
            walk.pages.forEachIndexed { p, page ->
                if (page.frontier == null) {
                    return CheckResult.Failed("$where: page #$p carries no frontier stamp")
                }
            }
            val stamps = walk.pages.mapNotNull { it.frontier }.distinct()
            if (stamps.size != 1) {
                return CheckResult.Failed(
                    "$where: the walk's frontier stamps are not all equal ($stamps) — the union is a smeared " +
                        "read and equality with a fold is not claimed for it",
                )
            }
            val seen = LinkedHashSet<String>()
            walk.pages.forEachIndexed { p, page ->
                page.entries.forEach { entry ->
                    if (!seen.add(Values.render(entry.key))) {
                        return CheckResult.Failed(
                            "$where: entry key '${Values.render(entry.key)}' is returned twice in one walk " +
                                "(seen again on page #$p)",
                        )
                    }
                }
            }
            val union = unionOf(walk)
            if (!Values.equalForView(expected, union, viewType)) {
                return CheckResult.Failed(
                    "$where: the union of ${walk.pages.size} page(s) is ${Values.render(union)} but " +
                        "'${check.view}' holds ${Values.render(expected)}",
                )
            }
        }
        return CheckResult.Passed
    }

    /**
     * The union of a walk's **live** entries in the neutral value model. A
     * convergent family pages entries its own algebra has retracted (a
     * tombstoned set element is a real entry with a real tag set), so an entry
     * that does not contribute to current state is dropped here rather than
     * compared against a fold that never held it.
     *
     * Shape follows the entries: key-only entries fold to a set (a list, which
     * `set-view` comparison canonicalizes order-insensitively), keyed entries to
     * a map.
     */
    private fun unionOf(walk: ReadWalk): Value {
        val live = walk.pages.flatMap { it.entries }.filter { it.present }
        return if (live.any { it.value != null }) {
            Value.MapVal(live.associate { Values.render(it.key) to (it.value ?: Value.NullVal) })
        } else {
            Value.ListVal(Values.sortedList(live.map { it.key }))
        }
    }

    // --- helpers ------------------------------------------------------------

    /**
     * The empty-observation-log guard shared by the three `observations-*`
     * evaluators: [CheckResult.Failed] when [log] is empty, `null` when there is
     * something to assert over.
     *
     * Every one of those three quantifies over the events of a stream ("all
     * satisfy", "never regresses", "each is a whole prefix"), so every one of
     * them is vacuously true on an empty stream — and a vacuous truth is "the
     * check had nothing to look at", never "the property held". Concord is the
     * executable arbiter of spec↔code agreement (AGENTS.md); an arbiter that
     * reads *nothing was observed* as *the requirement holds* can be disarmed by
     * any future defect that empties a log, which is exactly what happened to
     * `CTL-GF-01` (a control that must fail, passing on 17 of 20 runs purely
     * because its log was empty — computenet-qaz). This mirrors the same refusal
     * already made by [wavePlaneUnchanged] and [pagesEqualView].
     */
    private fun nothingObserved(where: String, view: String, log: List<Value>): CheckResult.Failed? =
        if (log.isNotEmpty()) {
            null
        } else {
            CheckResult.Failed(
                "$where: the view '$view' produced no observation at all — nothing was observed, " +
                    "so nothing is asserted",
            )
        }

    /** The catalog type of a cell (used to pick order-sensitive vs order-insensitive comparison). */
    private fun viewType(scenario: Scenario, cellId: String): String? =
        scenario.graph?.cells?.firstOrNull { it.id == cellId }?.type

    /** The ids of the graph's terminal view cells, in declaration order. */
    private fun viewCells(scenario: Scenario): List<String> =
        scenario.graph?.cells.orEmpty().filter { it.type in Values.VIEW_TYPES }.map { it.id }
}

/**
 * Everything a check evaluator reads for one run of the sweep: the [driver]
 * (already advanced past the run's script and quiesced), the [scenario] (for
 * the batch oracle, which folds catalog semantics over the script's accepted-op
 * multiset), and the [reads] the run's script produced. W1-B may widen this —
 * it is the check layer's own type.
 *
 * [reads] is the one thing here that is *not* re-derivable from the driver at
 * check time, and that is exactly why it lives on this interface (V1C-CONCORD).
 * A bounded read is an event with a before and an after — the pages it returned,
 * and the wave plane on either side of it — and by the time checks run, the
 * "before" is gone. Recording it belongs to the step that performed it; asking
 * the *driver* SPI to remember its own past reads would put harness bookkeeping
 * into the per-implementation surface, where a second binding would have to
 * reimplement it identically for no conformance reason.
 */
interface CheckContext {
    val driver: Driver
    val scenario: Scenario

    /** The bounded-read walks this run's `read-state` steps performed, in script order. */
    val reads: List<ReadWalk> get() = emptyList()
}

/**
 * One `read-state` step's whole walk, as observed by the harness
 * (V1C-CONCORD): every page the driver returned, plus the read cell's wave
 * plane immediately before and immediately after the walk.
 *
 * The before/after pair is captured by the runner rather than by a check
 * because only the runner is present at the moment of the read; a check that
 * asked the driver afterwards could only ever see the "after".
 *
 * **Why the wave plane and not an observation stream.** "Nothing was delivered"
 * looks like the more direct observation, and it is not available honestly: an
 * observation stream is materialized off the host's own execution context, so
 * its length immediately after a read is a statement about notifier timing, not
 * about the graph. The wave plane is read synchronously and is exactly what the
 * model makes load-bearing — every delivery carries a fresh per-source wave
 * position minted by the emitting outlet (spec 20/22), so a plane that did not
 * move is a delivery that did not happen. A scenario that also wants the
 * settled end state pinned adds a `final-view`.
 *
 * @property cell the cell that was read (a scenario-local id).
 * @property limit the per-page entry cap the step requested.
 * @property pages the walk's pages, in order; the last one has no resume token.
 * @property waveBefore / @property waveAfter the read cell's wave plane on
 *   either side of the whole walk.
 */
data class ReadWalk(
    val cell: String,
    val limit: Int,
    val pages: List<ReadPage>,
    val waveBefore: WavePlane,
    val waveAfter: WavePlane,
)

/** The outcome of evaluating one [Check] on one run. */
sealed interface CheckResult {
    /** The check held. */
    data object Passed : CheckResult

    /** The check was violated; [message] states how (for the failure report). */
    data class Failed(val message: String) : CheckResult

    /** W0/W1 placeholder: the evaluator is not yet implemented ([check] names it). */
    data class NotImplemented(val check: String) : CheckResult
}
