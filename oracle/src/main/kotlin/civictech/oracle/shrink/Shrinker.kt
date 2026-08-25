package civictech.oracle.shrink

import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseDelivery
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.GraphGenerator
import civictech.oracle.gen.RemoveRecord
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.WavePrefixOption

/**
 * Reduces a failing [GeneratedCase] to a smaller case that still fails the same way
 * (`ORA1 §SHRINK-01`..`ORA1 §SHRINK-03`, `ORA1 §SHRINK-05`; epic computenet-4ru design D7).
 *
 * ## The passes, in this order
 *
 * 1. **Delete op-script steps.** Chunk-wise, halving the chunk each round, so a 200-step script
 *    collapses in tens of candidates rather than hundreds.
 * 1a. **Drop a writer.** One [WriterId] at a time, from the last distinct writer named in the
 *    script towards the first: every step that writer issued is removed in **one** candidate (the
 *    multi-writer dimension `ORA2 §GEN-01` adds — a script step is the unit pass 1 reduces by, a
 *    *writer* is the unit this reduces by). Stops once one writer remains — dropping the last
 *    writer would empty the source's own slice.
 *
 *    **What this buys over pass 1 is ATOMICITY, not candidate count.** Pass 1 runs to completion
 *    before this pass starts and descends to single-step chunks, so wherever the failure survives
 *    step-by-step removal it has already reached the single-writer floor and pass 1a finds nothing
 *    to drop — a candidate-count saving is not something a pass sequenced *after* pass 1 can
 *    deliver. What pass 1 cannot express is a removal that is only valid taken TOGETHER: a failure
 *    that survives "all of `w1`'s steps" and "none of `w1`'s steps" but not any proper subset is
 *    rejected at every chunk size and left standing. That is the reduction this pass adds, and it
 *    is what `ShrinkerTest`'s "pass 1a drops a whole writer's steps" fixture injects — deliberately,
 *    because a monotone predicate leaves this pass unconstrained (see that test's KDoc).
 * 2. **Narrow the element domain.** Remap the script's element payloads onto fewer distinct
 *    values — first the whole domain onto one element, then, if that fails, one element at a time
 *    onto its predecessor.
 * 3. **Remove operator cells and terminals.** Drop every terminal but the failing one, then drop
 *    nodes nothing reads any more, then splice out unary operators whose consumers can take their
 *    input directly, then drop whatever that orphaned.
 * 4. **Drop a replica.** One gossiping [SourceId] at a time — a "replica" here is any source
 *    named on either side of a [CaseDelivery], the ORA2 sense (`ORA2 §GEN-03`/`ORA2 §MODEL-06`)
 *    of a source that participates in a mesh rather than driving in isolation. Every step that
 *    source issued, and every delivery naming it on either side, is removed in one candidate —
 *    dropping the SOURCE's role in the mesh, never a [TopologyNode]: the node stays linked and
 *    simply goes quiet, so this move is orthogonal to pass 3's topology reduction and reachable
 *    on a topology pass 3 would never touch (a source still read by an operator is never
 *    "unreferenced"). This is the script/delivery-level half of what dropping a replica means;
 *    a [ReplicaPlan]-replicated case cannot reach this shrinker at all today because
 *    [DifferentialRunner] has no runner path for one (`civictech.oracle.run.OracleSweep`'s own
 *    KDoc), so there is nothing here to drop the topology-level plan out from under.
 *
 *    **Runs LAST, after pass 3, not alongside pass 1's other script-level moves.** Pass 3's own
 *    `without()` already drops a delivery naming a source it topologically retires, unconditional
 *    on any floor. Running this pass earlier let it collapse the mesh down to the one delivery a
 *    LATER pass-3 retirement then had no floor to protect, zeroing every delivery in a case that
 *    still needed one (a real regression this item's own tests caught: `ShrinkerTest`'s
 *    computenet-r38y pins over exactly the empty-deliveries shape). Running it after pass 3 means
 *    it only ever trims gossip among sources pass 3 already decided to keep.
 *
 * The order is the requirement's and it is also the cheap-first order: a script step (or a whole
 * writer's steps) is the smallest unit and deleting it never invalidates a topology, while a
 * dropped node can only usefully go once the terminal reading it has gone, and a replica's mesh
 * role is only meaningful to drop once the topology it is inert against has settled. Each pass
 * runs on the smallest case the previous pass left.
 *
 * ## Every candidate is re-executed; nothing is retained on a rule (`ORA1 §SHRINK-02`)
 *
 * A candidate is executed through [DifferentialRunner.run] with the **same** `reference` and
 * `stepBudget` the shrink was given, and retained only if its [FailureSignature] — outcome
 * variant plus terminal — equals the original failure's. There is no static reasoning anywhere
 * about which reduction "should" preserve a failure: the runner is the only judge, which is what
 * makes the shrinker safe to point at a failure kind it was never designed for.
 *
 * Candidates are built by reducing the [CaseTopology] and the [CaseScript] and **re-lowering**
 * through [GraphGenerator.lower]. A `GraphSpec`'s factory lambdas are opaque, so a spec cannot be
 * edited — only lowered again from the topology it describes, by the same code the generated path
 * uses.
 *
 * ## The final result is re-executed before it is reported (`ORA1 §SHRINK-05`)
 *
 * After the passes, the smallest retained case is executed **once more**. If that execution does
 * not reproduce the signature, the case is not reported: the shrink walks back through every case
 * it retained, and finally the original, re-executing each, and reports the first that still
 * fails. If none does — the failure is not reproducible any more at all — it throws rather than
 * naming a passing case "minimal". A generated case is deterministic, so this path is not
 * expected to fire; it exists because a "minimal counterexample" that passes is worse than no
 * counterexample, and because a substituted [Reference] is arbitrary caller code that nothing
 * here can assume is a pure function of the script.
 *
 * ## Budget and truncation (`ORA1 §SHRINK-03`)
 *
 * [ShrinkBudget] bounds candidates and wall clock. On exhaustion the passes stop where they are
 * and the smallest case found so far is reported with [Counterexample.truncated] set — a field, so
 * a caller matches on it rather than parsing a message. The final re-execution above is **not**
 * charged to the budget and is never skipped: it is the difference between a reported
 * counterexample and a guess.
 *
 * ## Glitch discipline
 *
 * A [RunOutcome.WavePrefixViolation] exists only *while* a case is driven, so shrinking one must
 * preserve the intermediate driving. Re-execution through [DifferentialRunner.run] does that by
 * construction — it replays the total order with the same seed-derived partial drains — and this
 * class adds exactly one rule on top: **[CaseStep.Barrier] steps are never deleted.** Pass 1
 * deletes [CaseStep.Op] steps only, so the quiesce points a case was generated with (and the
 * late-joiner terminal linked behind one) survive every reduction. Nothing else is special-cased
 * by kind; a glitch shrinks by the same three passes, matched on
 * `WavePrefixViolation` + terminal.
 *
 * Note that a caller shrinking a glitch has to pass the [WavePrefixOption] that produced it:
 * [DifferentialRunner.run] defaults prefix checking OFF for a substituted reference, and a
 * candidate run without the check cannot report the violation it is being matched on.
 *
 * ## Non-goals
 *
 * Rendering a counterexample as pasteable Kotlin is `ORA1 §SHRINK-04`, a sibling item that
 * amends [Counterexample]; nothing here emits source. Fault injection, corpus pinning and any
 * kernel or generator semantic change are elsewhere by design (epic D10).
 */
object Shrinker {

    /**
     * Shrinks [case], which must fail, and reports the confirmed smallest failing case found.
     *
     * @param case the failing case, generated or hand-built.
     * @param budget candidate and wall-clock bounds; see [ShrinkBudget] for the defaults' sizing.
     * @param reference the substitutable oracle, passed through unchanged to every candidate
     *   execution. `null` (the default) resolves the catalog reference per candidate, exactly as
     *   [DifferentialRunner.run] does — which is the right behaviour when the topology shrinks,
     *   because the resolved model then describes the *reduced* topology.
     * @param stepBudget each candidate's simulation-step budget, identical for every candidate so
     *   a `NonQuiescence` signature stays comparable.
     * @param wavePrefix the wave-prefix knob, passed through unchanged. A glitch shrink needs the
     *   option that produced the glitch — see this object's "Glitch discipline" KDoc.
     * @throws IllegalArgumentException if [case] does not fail — shrinking a passing case has no
     *   meaning, and answering it with "no reduction found" would read like a completed shrink.
     * @throws IllegalStateException if, at the end, neither the reduced case nor any case retained
     *   on the way nor the original reproduces the failure on re-execution.
     */
    fun run(
        case: GeneratedCase,
        budget: ShrinkBudget = ShrinkBudget(),
        reference: Reference? = null,
        stepBudget: Int = DifferentialRunner.DEFAULT_STEP_BUDGET,
        wavePrefix: WavePrefixOption? = null,
    ): Counterexample {
        val session = Session(reference, stepBudget, wavePrefix, budget.meter())
        val first = session.execute(case)
        val signature = FailureSignature.of(first)
            ?: throw IllegalArgumentException(
                "Shrinker.run was given a case that does not fail: seed=${case.seed} ran to " +
                    "${RunOutcome.Success}. There is nothing to reduce, and reporting a " +
                    "'smallest failing case' for it would name a passing case as a counterexample.",
            )
        session.signature = signature
        val original = Attempt(case, first)
        session.best = original

        deleteScriptSteps(session)
        dropWriters(session)
        narrowElementDomain(session)
        reduceTopology(session)
        dropReplicas(session)

        // ORA1 §SHRINK-05: the reported case is one that failed on ITS OWN last execution,
        // never one that was merely retained earlier. Deliberately outside the budget.
        val confirmed = confirm(session, original)
        return Counterexample(
            case = confirmed.case,
            outcome = confirmed.outcome,
            originalSize = CaseSize.of(case),
            truncated = session.truncated,
        )
    }

    // ---------------------------------------------------------------- pass 1: script steps

    /**
     * Deletes [CaseStep.Op] steps, chunk-wise: chunks of half the script, then a quarter, and so
     * on to single steps. Every retained deletion restarts the scan at the same offset, because
     * the next chunk has shifted into it.
     *
     * Barriers are never in a chunk — see the object KDoc's "Glitch discipline".
     */
    private fun deleteScriptSteps(session: Session) {
        var chunk = maxOf(1, opIndices(session.best.case.script).size / 2)
        while (true) {
            var offset = 0
            while (true) {
                val ops = opIndices(session.best.case.script)
                if (offset >= ops.size) break
                val drop = ops.drop(offset).take(chunk).toSet()
                if (!session.canSpend()) return
                val script = CaseScript(
                    session.best.case.script.steps.filterIndexed { index, _ -> index !in drop },
                    carried(session.best.case.script.deliveries, drop),
                )
                if (!session.tryCandidate(withScript(session.best.case, script))) offset += chunk
            }
            if (chunk == 1) return
            chunk = maxOf(1, chunk / 2)
        }
    }

    // ---------------------------------------------------------------- pass 1a: drop a writer

    /**
     * Removes one [WriterId]'s steps at a time, from the last distinct writer named in the
     * script towards the first, stopping once a single writer remains — see the object KDoc's
     * "1a. Drop a writer".
     */
    private fun dropWriters(session: Session) {
        var index = writersOf(session.best.case.script).size - 1
        while (index >= 0) {
            val writers = writersOf(session.best.case.script)
            if (writers.size <= 1) return
            if (index >= writers.size) {
                index = writers.size - 1
                continue
            }
            val writer = writers[index]
            if (!session.canSpend()) return
            val dropped = opIndicesWhere(session.best.case.script) { it.event.writer == writer }
            if (dropped.isNotEmpty()) {
                val script = CaseScript(
                    session.best.case.script.steps.filterIndexed { i, _ -> i !in dropped },
                    carried(session.best.case.script.deliveries, dropped),
                )
                session.tryCandidate(withScript(session.best.case, script))
            }
            index -= 1
        }
    }

    /** Every distinct [WriterId] any [CaseStep.Op] in [script] names, in first-appearance order. */
    private fun writersOf(script: CaseScript): List<WriterId> =
        script.steps.filterIsInstance<CaseStep.Op>().map { it.event.writer }.distinct()

    private fun opIndicesWhere(script: CaseScript, predicate: (CaseStep.Op) -> Boolean): Set<Int> =
        script.steps.withIndex()
            .filter { (_, step) -> step is CaseStep.Op && predicate(step) }
            .mapTo(mutableSetOf()) { it.index }

    // ------------------------------------------------------- pass 2: element domain narrowing

    /**
     * Remaps the script's element payloads onto fewer distinct values: first the whole domain onto
     * its first element (one candidate, and the biggest possible win), then — if that is not
     * retained — each element onto its predecessor, from the last towards the second.
     *
     * Keys and counter amounts are untouched; [scriptElements] says why.
     */
    private fun narrowElementDomain(session: Session) {
        val initial = scriptElements(session.best.case.script)
        if (initial.size <= 1) return
        if (!session.canSpend()) return
        if (session.tryCandidate(withElements(session.best.case, initial.associateWith { initial.first() }))) return

        var index = scriptElements(session.best.case.script).size - 1
        while (index >= 1) {
            val elements = scriptElements(session.best.case.script)
            if (index >= elements.size) {
                index = elements.size - 1
                continue
            }
            if (!session.canSpend()) return
            session.tryCandidate(withElements(session.best.case, mapOf(elements[index] to elements[index - 1])))
            index -= 1
        }
    }

    // --------------------------------------------------------- pass 3: cells and terminals

    /** Terminals first, then whatever that orphaned, then splices, then whatever *that* orphaned. */
    private fun reduceTopology(session: Session) {
        dropTerminals(session)
        dropUnreferencedNodes(session)
        spliceUnaryNodes(session)
        dropUnreferencedNodes(session)
    }

    /**
     * Drops every terminal but the failing one, one candidate at a time, from the last towards the
     * first.
     *
     * The failing terminal is [FailureSignature.terminal]; for a signature that names none (a
     * dead letter, a non-quiescence, a broken oracle) every terminal is a candidate for dropping,
     * except that the last one never is: a case with no terminal compares nothing and therefore
     * cannot fail at all.
     */
    private fun dropTerminals(session: Session) {
        var index = session.best.case.topology.terminals.size - 1
        while (index >= 0) {
            val terminals = session.best.case.topology.terminals
            if (index >= terminals.size) {
                index = terminals.size - 1
                continue
            }
            val terminal = terminals[index]
            if (terminals.size > 1 && terminal.name != session.signature.terminal) {
                if (!session.canSpend()) return
                val topology = session.best.case.topology.copy(
                    terminals = terminals.filterIndexed { position, _ -> position != index },
                )
                session.tryCandidate(withTopology(session.best.case, topology))
            }
            index -= 1
        }
    }

    /**
     * Drops nodes nothing reads — no other node names them in [TopologyNode.inputs] and no
     * terminal reads their handle — repeating until a full scan retains nothing, because dropping
     * one node can orphan its own inputs.
     *
     * A dropped **source** node takes its script steps with it: [DifferentialRunner] fails loudly
     * on a script that drives a source the graph does not bind, and rightly so.
     */
    private fun dropUnreferencedNodes(session: Session) {
        while (true) {
            var dropped = false
            var index = session.best.case.topology.nodes.size - 1
            while (index >= 0) {
                val topology = session.best.case.topology
                if (index >= topology.nodes.size) {
                    index = topology.nodes.size - 1
                    continue
                }
                val node = topology.nodes[index]
                if (unreferenced(topology, node)) {
                    if (!session.canSpend()) return
                    if (session.tryCandidate(without(session.best.case, node))) dropped = true
                }
                index -= 1
            }
            if (!dropped) return
        }
    }

    private fun unreferenced(topology: CaseTopology, node: TopologyNode): Boolean =
        topology.nodes.none { node.handle in it.inputs } &&
            topology.terminals.none { it.handle == node.handle }

    /**
     * Splices out arity-1 operator nodes: a node `n` with a single input `u` is dropped and every
     * consumer that read `n` reads `u` instead.
     *
     * Three conditions, each of which the catalog answers rather than this code guessing:
     *
     * - **No terminal reads `n`.** A terminal reading `n` would have to be rewired onto `u`, which
     *   changes what the case *observes* rather than how it computes it — and, for a generated
     *   case, would drop below `ORA1 §GEN-03`'s "at least one operator between every source and
     *   every terminal". Terminals shrink by [dropTerminals]; nodes they read shrink once the
     *   terminal has gone.
     * - **Shape-satisfied.** For every consumer `c` reading `n` at port `p`, `u`'s registered
     *   output shape must equal `c`'s registered input shape at `p` (`ShapeRule.inputs[p]`). This
     *   is the same shape-equality rule `GraphGenerator` links by, read from the same catalog
     *   data, so a spliced graph is exactly as well-typed as a generated one.
     * - **Inputs stay distinct.** A consumer that already reads `u` would end up reading it twice,
     *   which is the same link drawn twice — `GraphGenerator.fillPorts` refuses it for the same
     *   reason.
     */
    private fun spliceUnaryNodes(session: Session) {
        var index = session.best.case.topology.nodes.size - 1
        while (index >= 0) {
            val topology = session.best.case.topology
            if (index >= topology.nodes.size) {
                index = topology.nodes.size - 1
                continue
            }
            val spliced = spliceCandidate(topology, topology.nodes[index])
            if (spliced != null) {
                if (!session.canSpend()) return
                session.tryCandidate(withTopology(session.best.case, spliced))
            }
            index -= 1
        }
    }

    private fun spliceCandidate(topology: CaseTopology, node: TopologyNode): CaseTopology? {
        if (node.inputs.size != 1) return null
        if (topology.terminals.any { it.handle == node.handle }) return null
        val upstream = node.inputs.single()
        val upstreamShape = shapeOf(topology, upstream)?.output ?: return null

        val consumers = topology.nodes.filter { node.handle in it.inputs }
        if (consumers.isEmpty()) return null
        consumers.forEach { consumer ->
            val rule = shapeOf(topology, consumer.handle) ?: return null
            consumer.inputs.forEachIndexed { port, from ->
                if (from == node.handle && rule.inputs[port] != upstreamShape) return null
            }
            val rewired = consumer.inputs.map { if (it == node.handle) upstream else it }
            if (rewired.distinct().size != rewired.size) return null
        }

        return topology.copy(
            nodes = topology.nodes
                .filter { it.handle != node.handle }
                .map { candidate ->
                    if (node.handle in candidate.inputs) {
                        candidate.copy(inputs = candidate.inputs.map { if (it == node.handle) upstream else it })
                    } else {
                        candidate
                    }
                },
            placement = topology.placement - node.handle,
        )
    }

    private fun shapeOf(topology: CaseTopology, handle: String) =
        topology.nodes.firstOrNull { it.handle == handle }
            ?.let { OperatorCatalog.entry(it.catalogId)?.shape }

    // --------------------------------------------------------------- pass 4: drop a replica

    /**
     * Removes one gossiping [SourceId]'s steps and every [CaseDelivery] naming it, one source at
     * a time — see the object KDoc's "4. Drop a replica", including why this pass runs last.
     * Recomputes the candidate list every iteration, the same discipline
     * [dropTerminals]/[dropUnreferencedNodes] use, because dropping one replica can retire a
     * delivery that made another source a candidate too.
     *
     * Refuses a drop that would leave the script with NO deliveries at all when it had some —
     * "drop A replica" reduces a mesh by one member, it does not de-mesh the case entirely. That
     * floor mirrors [dropTerminals]'s "except the last one": a mesh of one gossips with nothing,
     * exactly as a case with no terminal observes nothing, so both moves stop one short of that.
     * Content-blind reductions (an injected failure with no script predicate at all, the shape
     * every other pass's own worst case uses) would otherwise zero the mesh out from under a
     * failure that never needed it kept — which is a legitimate smaller counterexample, but a
     * DIFFERENT one than what a caller asked to shrink, and the floor keeps this move from being
     * the one place a generated case silently stops being the replicated shape it started as.
     */
    private fun dropReplicas(session: Session) {
        var index = replicasOf(session.best.case.script).size - 1
        while (index >= 0) {
            val replicas = replicasOf(session.best.case.script)
            if (index >= replicas.size) {
                index = replicas.size - 1
                continue
            }
            val replica = replicas[index]
            val script = session.best.case.script
            val dropped = opIndicesWhere(script) { it.source == replica }
            val survivingDeliveries = script.deliveries.filterNot { it.into == replica || it.from == replica }
            if (survivingDeliveries.isEmpty() && script.deliveries.isNotEmpty()) {
                index -= 1
                continue
            }
            if (!session.canSpend()) return
            if (dropped.isNotEmpty() || survivingDeliveries.size != script.deliveries.size) {
                val candidate = CaseScript(
                    script.steps.filterIndexed { i, _ -> i !in dropped },
                    carried(survivingDeliveries, dropped),
                )
                session.tryCandidate(withScript(session.best.case, candidate))
            }
            index -= 1
        }
    }

    /**
     * Every [SourceId] named on either side of a [CaseDelivery] in [script] — the ORA2 sense of
     * "replica": a source that participates in a mesh, distinct from [TopologyNode.source].
     */
    private fun replicasOf(script: CaseScript): List<SourceId> =
        script.deliveries.flatMap { listOf(it.into, it.from) }.distinct()

    // ------------------------------------------------------------------- candidate assembly

    private fun withScript(case: GeneratedCase, script: CaseScript): GeneratedCase =
        case.copy(script = script, removeAudit = auditFor(script))

    private fun withTopology(case: GeneratedCase, topology: CaseTopology): GeneratedCase =
        case.copy(topology = topology, spec = GraphGenerator.lower(topology))

    /** [case] without [node] — and, for a source node, without the steps that drove it. */
    private fun without(case: GeneratedCase, node: TopologyNode): GeneratedCase {
        val topology = case.topology.copy(
            nodes = case.topology.nodes.filter { it.handle != node.handle },
            placement = case.topology.placement - node.handle,
        )
        val script = if (node.source == null) {
            case.script
        } else {
            val dropped = case.script.steps.withIndex()
                .filter { (_, step) -> step is CaseStep.Op && step.source == node.source }
                .mapTo(mutableSetOf()) { it.index }
            // A delivery naming the departing source is DROPPED, not shifted: shifting it would
            // leave a delivery into (or from) a replica this case no longer has — a script that
            // still says "replica X absorbed replica Y" when Y is gone. `CaseScript` cannot catch
            // that (its `init` checks the step index, not the source names), and `toScript` would
            // dutifully mint a slice for the absent source, so the case would shrink into a
            // DIFFERENT replication shape while still looking like a faithful reduction. That is
            // the same silent wrongness computenet-r38y removes, just relocated to pass 3.
            CaseScript(
                case.script.steps.filterIndexed { index, _ -> index !in dropped },
                carried(
                    case.script.deliveries.filterNot { it.into == node.source || it.from == node.source },
                    dropped,
                ),
            )
        }
        return case.copy(
            topology = topology,
            spec = GraphGenerator.lower(topology),
            script = script,
            removeAudit = auditFor(script),
        )
    }

    /** [case] with every element payload rewritten through [remap] (absent keys unchanged). */
    private fun withElements(case: GeneratedCase, remap: Map<Any?, Any?>): GeneratedCase {
        fun mapped(element: Any?): Any? = if (remap.containsKey(element)) remap[element] else element
        val script = CaseScript(
            case.script.steps.map { step ->
                if (step !is CaseStep.Op) {
                    step
                } else {
                    when (val event = step.event) {
                        is ScriptEvent.Add -> step.copy(event = event.copy(element = mapped(event.element)))
                        is ScriptEvent.Remove -> step.copy(event = event.copy(element = mapped(event.element)))
                        is ScriptEvent.Put -> step.copy(event = event.copy(element = mapped(event.element)))
                        else -> step
                    }
                }
            },
            // A 1:1 rewrite of each step in place: no step is added or removed, so every
            // delivery's position still names the step it always named. Carried verbatim —
            // no shift, and nothing to drop, because remapping an element cannot retire a source.
            case.script.deliveries,
        )
        return withScript(case, script)
    }

    /**
     * [deliveries] carried across a deletion of the steps at [dropped].
     *
     * A [CaseDelivery] states its gossip as a **position** in the drive order — "just before step
     * `atStep`" — so deleting steps moves it left by however many deleted indices lie before it.
     * This is `ScriptGenerator.insertBarrier`'s shift rule run backwards: that one splices a step
     * in and shifts at `>= position`, this one takes steps out and shifts by the count below.
     * Either way the invariant is the same one, and it is the whole point of the position
     * representation — the delivery stays before the *same* `Op` it was before, so the
     * `afterEvents`/`throughEvents` [CaseScript.toScript] derives for the surviving prefix are
     * unchanged rather than re-stated.
     *
     * Reconstructing a [CaseScript] with the single-argument constructor instead — which every
     * site here did before computenet-r38y — silently drops the gossip, so a shrunk replicated
     * counterexample reproduces as a NON-replicated one: the reduction quietly removes the very
     * property that made the case a counterexample, and the rendered artifact then fails to
     * reproduce for a reason nothing reports.
     */
    private fun carried(deliveries: List<CaseDelivery>, dropped: Set<Int>): List<CaseDelivery> =
        if (dropped.isEmpty() || deliveries.isEmpty()) {
            deliveries
        } else {
            deliveries.map { delivery ->
                delivery.copy(atStep = delivery.atStep - dropped.count { index -> index < delivery.atStep })
            }
        }

    /**
     * [script]'s remove audit, re-derived from the script itself: one [RemoveRecord] per
     * `Remove`/`RemoveKey` step, `observed` saying whether the removing writer had added or
     * observed what it removed at that position.
     *
     * ## Why re-derived rather than index-shifted
     *
     * A [RemoveRecord] is a statement *about a script* — "the writer of the remove at this step
     * had observed that element". Every pass here can falsify it: deleting the `Add` a remove
     * covered makes an observed remove unobserved, and remapping elements moves a remove onto a
     * value its writer never touched. Carrying the generator's records forward, shifted, would
     * leave a reduced case whose audit describes a script it no longer has — and the audit's whole
     * purpose is to say which removes are the `ORA1 §MODEL-05` no-ops, which is exactly what a
     * reader of a counterexample needs to be true.
     *
     * ## The rule, and its one known divergence from the generator's own audit
     *
     * The observation rule is `civictech.oracle.model.Membership`'s, applied per remove: the
     * writer observed the add if it issued the add itself, or if the slice carries an
     * `Observe` by that writer between the add and the remove. It is re-derived here because
     * `Membership` exposes only `live` — a per-writer observation query is not on it, and adding
     * one is a change to a file this item does not own.
     *
     * The re-derivation agrees with `ScriptGenerator`'s own audit for every script generated with
     * `unobservedRemoveRatio = 0.0` (`ShrinkerTest` pins that equality at
     * `writerCount = 1` and `writerCount = 2`). It can disagree **above** that ratio, in one
     * direction only: the generator records `observed = false` for a remove it *drew* as
     * unobserved, while this rule reads a `ScriptEvent.Observe` that some earlier cross-writer
     * remove emitted as observation of the element — so it says `true` where the generator said
     * `false`. That is the rule being faithful to the script rather than to the draw, which is the
     * right answer for a reduced case; a reader wanting generation provenance reads the original
     * case's audit, which the shrinker never modifies.
     *
     * `RemoveKey`'s reading is `ScriptGenerator`'s: observed iff the same writer had `Put` that key
     * earlier in the slice, there being no observation relation on a keyed source. Counter events
     * produce no records — no elements, no observation.
     *
     * ## Stays `internal` (computenet-p5qy defect 1)
     *
     * `RenderKotlin.kt`'s emitted snippet once called this function directly, which does not
     * compile outside `:oracle` — `auditFor` is `internal`, and a module consuming `:oracle` via
     * `testImplementation` (e.g. `:kernel`) cannot reach it. The fix was to render
     * [GeneratedCase.removeAudit] as literal [RemoveRecord] values instead: [run] keeps that field
     * current through every reduction pass (see [withScript]/[withElements]/[without], all of
     * which call this function), so a [Counterexample]'s `case.removeAudit` already holds exactly
     * what a re-derivation would recompute. That means this function no longer needs to be
     * `public` for the renderer's sake, and it stays `internal` — widening `:oracle`'s API surface
     * was the weaker of the two candidate fixes precisely because it was avoidable.
     */
    internal fun auditFor(script: CaseScript): List<RemoveRecord> {
        val slices = LinkedHashMap<SourceId, MutableList<ScriptEvent>>()
        val records = mutableListOf<RemoveRecord>()
        script.steps.forEachIndexed { index, step ->
            if (step !is CaseStep.Op) return@forEachIndexed
            val preceding = slices.getOrPut(step.source) { mutableListOf() }
            when (val event = step.event) {
                is ScriptEvent.Remove -> records += RemoveRecord(index, observedRemove(preceding, event))
                is ScriptEvent.RemoveKey -> records += RemoveRecord(index, observedRemoveKey(preceding, event))
                else -> Unit
            }
            preceding += step.event
        }
        return records
    }

    private fun observedRemove(preceding: List<ScriptEvent>, remove: ScriptEvent.Remove): Boolean {
        preceding.forEachIndexed { position, event ->
            if (event is ScriptEvent.Add && event.element == remove.element) {
                if (event.writer == remove.writer) return true
                val observedSince = (position + 1 until preceding.size).any {
                    val later = preceding[it]
                    later is ScriptEvent.Observe && later.writer == remove.writer
                }
                if (observedSince) return true
            }
        }
        return false
    }

    private fun observedRemoveKey(preceding: List<ScriptEvent>, remove: ScriptEvent.RemoveKey): Boolean =
        preceding.any { it is ScriptEvent.Put && it.key == remove.key && it.writer == remove.writer }

    private fun opIndices(script: CaseScript): List<Int> =
        script.steps.indices.filter { script.steps[it] is CaseStep.Op }

    // ------------------------------------------------------------------ confirmation

    /**
     * The `ORA1 §SHRINK-05` gate: re-execute the smallest retained case, and if it no longer
     * fails, walk back through every case retained on the way and finally [original], reporting
     * the first that does.
     */
    private fun confirm(session: Session, original: Attempt): Attempt {
        val chain = (listOf(original) + session.retained).asReversed()
        chain.forEach { attempt ->
            val outcome = session.execute(attempt.case)
            if (FailureSignature.of(outcome) == session.signature) return Attempt(attempt.case, outcome)
        }
        error(
            "Shrinker re-executed the reduced case, every one of the ${session.retained.size} " +
                "case(s) retained on the way, and the original (seed=${original.case.seed}), and " +
                "none reproduced ${session.signature}. The failure is not reproducible, so there " +
                "is no counterexample to report — reporting the smallest one anyway would name a " +
                "passing case minimal ORA1 §SHRINK-05.",
        )
    }

    /** One case and the outcome it produced. */
    private data class Attempt(val case: GeneratedCase, val outcome: RunOutcome)

    /** One shrink's mutable state: what it is matching, what it has spent, what it has kept. */
    private class Session(
        private val reference: Reference?,
        private val stepBudget: Int,
        private val wavePrefix: WavePrefixOption?,
        private val meter: ShrinkBudget.Meter,
    ) {
        lateinit var signature: FailureSignature
        lateinit var best: Attempt

        /** Every retained candidate, oldest first — [confirm]'s fallback chain. */
        val retained = mutableListOf<Attempt>()

        /** Whether the budget ran out with a reduction still untried (`ORA1 §SHRINK-03`). */
        var truncated: Boolean = false
            private set

        fun execute(candidate: GeneratedCase): RunOutcome =
            DifferentialRunner.run(
                case = candidate,
                reference = reference,
                stepBudget = stepBudget,
                wavePrefix = wavePrefix,
            )

        /**
         * Whether one more candidate may be executed — and, when it may not, the point at which
         * [truncated] becomes true. Called only once a candidate has actually been chosen, so a
         * shrink that simply ran out of reductions is never reported as truncated.
         */
        fun canSpend(): Boolean {
            if (meter.exhausted) {
                truncated = true
                return false
            }
            return true
        }

        /** Executes [candidate] and keeps it iff it reproduces [signature]. */
        fun tryCandidate(candidate: GeneratedCase): Boolean {
            meter.spend()
            val outcome = execute(candidate)
            if (FailureSignature.of(outcome) != signature) return false
            best = Attempt(candidate, outcome)
            retained += best
            return true
        }
    }
}
