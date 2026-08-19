package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
import civictech.oracle.model.Membership
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import kotlin.random.Random

/**
 * A seeded, writer-tagged op script over one [CaseTopology]'s source nodes — the script half
 * of a generated case (computenet-4ru.6.3), paired with the audit saying which of its removes
 * were generated *observed* and which *deliberately unobserved* (`[ORA1-GEN-06]`).
 *
 * ## What it emits
 *
 * A [CaseScript] of exactly `config.scriptLength` [CaseStep.Op] steps, plus — WHERE
 * `config.lateJoiner` is set — exactly one [CaseStep.Barrier] spliced in at an
 * [rng]-chosen **strictly interior** position (`[ORA1-GEN-09]`): at least one `Op` precedes it
 * and at least one follows, so it is never before the first step nor after the last. With
 * `lateJoiner` unset, no [CaseStep.Barrier] is ever emitted. [insertBarrier] is the whole of
 * this — it runs once, after every `Op` is generated, and shifts every affected
 * [RemoveRecord.stepIndex] so the audit keeps naming the same `Op` once the Barrier is spliced
 * in. The barrier is data the runner (computenet-4ru.8) interprets as a quiesce point before it
 * links the late-joiner terminal (`GraphGenerator.chooseLateTerminal`); this class never links
 * or applies anything itself. Each `Op` step drives one `civictech.oracle.model.ScriptEvent`
 * into one source's slice; the interleaving across sources is this generator's choice from
 * [rng], and the per-source subsequence is what the reference model consumes
 * ([CaseScript.toScript]).
 *
 * The model's sealed `ScriptEvent` is reused verbatim and deliberately not extended — the
 * gen-level [CaseStep] already wraps it with the total order and the barrier.
 *
 * ## Which events a source accepts
 *
 * From its catalog id, per [SourceKind]: the arity-0 vocabulary entries are `set`,
 * `keyedSet`, `map`, `counter` and `pnCounter` (`CoreOperators.Ids`). An event a source cell
 * could not execute is never emitted for it — a set source never sees a `Put`, a counter
 * never sees an `Add`. This `when` over ids is the one place the generator knows operator
 * names rather than reading `ShapeRule` data: an operator's *shape* says what flows along its
 * edges, which is all `[ORA1-API-03]` needs for linking, while what a **source** can be
 * *driven with* is a property of its ops interface that no shape carries. Adding a sixth
 * source kind is therefore a [SourceKind] entry, and a consumer-registered *operator* still
 * needs no edit here at all.
 *
 * ## The single-writer guarantee, by construction (`[ORA1-MODEL-09]`)
 *
 * An order-dependent source — today exactly `map`, whose `MapCellSourceModel` throws
 * `MultiWriterMapSliceException` on a multi-writer slice — is assigned **one** writer for the
 * whole case, up front in [init], before a single step is generated. There is no
 * emit-then-check-then-retry anywhere in this class: a violating script is not rejected, it
 * is unconstructable. [assertOrderDependentSingleWriter] runs before the script is returned,
 * but it is a defensive assertion on an already-correct construction, not the mechanism —
 * producing the distribution by rejection sampling would bias every *other* knob toward
 * whatever a surviving sample looks like.
 *
 * Non-order-dependent sources draw a fresh writer per event from the full pool of
 * `config.writerCount` writers, so a set source genuinely exercises the multi-writer
 * (BS-2-shaped) configurations the reference model is checked on.
 *
 * ## Observed and unobserved removes (`[ORA1-GEN-06]`)
 *
 * `config.unobservedRemoveRatio` of removes name an element the removing writer has neither
 * added nor observed. Under `Membership`'s rule such a remove covers no add and is a model
 * no-op (`[ORA1-MODEL-05]`) — the case that separates an implementation with real
 * observed-remove semantics from one that removes by value.
 *
 * The remainder are genuinely observed **at their step position**, which for a cross-writer
 * remove means an explicit `ScriptEvent.Observe` is emitted first: `Membership` grants a
 * writer observation of its own adds automatically and of nothing else. Both paths are
 * exercised — a writer removing what it added itself, and a writer that observes first.
 *
 * **An unobserved remove also never names a *live* element (`computenet-qcm1`).** "Not added
 * nor observed by this writer" alone is only half of what makes the step a no-op: it is a
 * `Membership` no-op, but the kernel's `SetCell.inletHandler.remove` retracts
 * `liveTags(element)` unconditionally without consulting the removing writer's causal history,
 * so a cross-writer remove of a *live* element takes effect in the kernel while the model
 * ignores it — a Mismatch manufactured by the generator rather than found in the kernel. The
 * draw in [emitUnobservedRemove] therefore also excludes everything live at that position of
 * the slice, read from [Membership.live] over the events emitted into that source so far
 * (never added, added and already covered, or added only later are all admissible). Liveness
 * is *called* from the reference model, not re-derived here, so the generator cannot come to
 * hold a second and subtly different notion of it. Before the fix, on this suite's own fixture
 * (two set sources, `writerCount = 2`, domain 64, length 200, ratio 0.3, seeds 1..25), 20 of
 * 699 unobserved removes (2.9%) named a live element — roughly one per seed; it is 0 of 699
 * now, and `ScriptGeneratorTest` asserts that zero.
 *
 * The narrowing leaves the *bias* untouched: an unobserved remove records nothing into a
 * writer's known set, so restricting which element it names changes no later candidate set and
 * consumes no different amount of [rng]. Measured on that same fixture, before and after are
 * identical to every digit — aggregate unobserved fraction 699/2201 = 0.3176 over seeds 1..25,
 * and 27/87 = 0.3103 for seed 42 — so the `unobservedRemoveRatio` tolerances
 * `ScriptGeneratorTest` asserts stand as written and needed no re-statement. What the narrowing
 * *can* do is exhaust the candidate pool sooner at a **small `elementDomainSize`**, where a
 * writer's known set plus the live set may cover the whole domain; that case falls back to an
 * add exactly as the pre-existing no-candidate path does, which biases the measured unobserved
 * fraction downward rather than resampling.
 *
 * Scope note, so the next reader does not mistake this for a cure: it removes only the
 * generator's *manufactured* divergence. A cross-writer **observed** remove path diverges
 * independently of it — at `unobservedRemoveRatio = 0.0`, nine of sixty cases of
 * `WavePrefixTest`'s sweep still mismatch at quiescence and six still violate the wave-prefix
 * check (measured 2026-08-19, computenet-4ru.8.5). That residual is `computenet-eeys`, not
 * this.
 *
 * Every remove — `Remove` on a set source and `RemoveKey` on a keyed one — is recorded in a
 * [RemoveRecord]. `RemoveKey`'s "observed" reading is the one its source model supports:
 * `KeyedSetSourceModel`/`MapCellSourceModel` fold puts and removes by key with no observation
 * relation, so an observed `RemoveKey` is one whose key the same writer had put earlier, and
 * an unobserved one names a key that writer never put. Counter events produce no audit
 * entries: they have no elements and no observation relation.
 *
 * ## `addRemoveRatio`
 *
 * Read as **the fraction of generated element events that are adds** (`Add`, `Put`,
 * `Increment`), the rest being removes (`Remove`, `RemoveKey`, `Decrement`) — the reading the
 * task's acceptance criterion checks ("add:remove proportion within stated tolerance"), the
 * epic's own "add/remove ratio" knob name, and (since computenet-4ru.6's feature review) what
 * [GeneratorConfig.addRemoveRatio]'s own field KDoc states.
 *
 * Emitted `Observe` steps count toward `scriptLength` like any other op but are neither an
 * add nor a remove, so the ratio is a property of the add/remove events, not of every step.
 *
 * ## Determinism (`[ORA1-GEN-01]`)
 *
 * Every choice comes from [rng]; every collection iterated is a `List` or a `LinkedHash*`;
 * element values come from [ElementDomains]' static tables. Nothing reads a `hashCode`, an
 * identity, or the clock. Two [ScriptGenerator]s over equal `(config, topology, seed)` emit
 * equal [CaseScript]s and equal audits.
 *
 * @param config the knobs: `scriptLength`, `addRemoveRatio`, `unobservedRemoveRatio`,
 *   `elementDomainSize`, `writerCount`.
 * @param topology the case shape whose `source`-bearing nodes this script drives.
 * @param rng the single source of nondeterminism; seed it from the case seed.
 */
class ScriptGenerator(
    private val config: GeneratorConfig,
    private val topology: CaseTopology,
    private val rng: Random,
) {

    /** The writer pool every non-order-dependent source draws from. */
    private val writerPool: List<WriterId> = (0 until config.writerCount).map { WriterId("w$it") }

    private val elementDomain: List<String> = ElementDomains.elements(config.elementDomainSize)
    private val keyDomain: List<String> = ElementDomains.keys(config.elementDomainSize)
    private val amountDomain: List<Long> = ElementDomains.amounts(config.elementDomainSize)

    /**
     * One entry per source node, in topology order — with each source's writers **already
     * decided**: exactly one for an order-dependent source, the whole pool otherwise.
     */
    private val sources: List<SourceState> = topology.nodes
        .filter { it.source != null }
        .map { node ->
            val kind = SourceKind.of(node.catalogId)
            SourceState(
                id = node.source!!,
                kind = kind,
                // [ORA1-MODEL-09], construct-correct: the single writer is picked here, once,
                // before any event exists. Nothing downstream can widen it.
                writers = if (kind.orderDependent) listOf(writerPool[rng.nextInt(writerPool.size)]) else writerPool,
            )
        }

    init {
        require(sources.isNotEmpty()) {
            "ScriptGenerator needs at least one source node (a TopologyNode with a non-null source); " +
                "topology has ${topology.nodes.size} node(s), none of them a source."
        }
        val duplicated = sources.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "Two topology nodes name the same SourceId: ${duplicated.map { it.id }.sorted()}"
        }
    }

    /**
     * Generates the case's script and its remove audit.
     *
     * @throws IllegalStateException if the finished script somehow places an order-dependent
     *   source's events under more than one writer — unreachable by construction, asserted
     *   anyway, and never repaired by regenerating.
     */
    fun generate(): GeneratedScript {
        val steps = mutableListOf<CaseStep>()
        val audit = mutableListOf<RemoveRecord>()

        while (steps.size < config.scriptLength) {
            val source = sources[rng.nextInt(sources.size)]
            emitOne(source, remaining = config.scriptLength - steps.size, steps = steps, audit = audit)
        }

        val (finalSteps, finalAudit) = if (config.lateJoiner) insertBarrier(steps, audit) else steps to audit
        val script = CaseScript(finalSteps)
        assertOrderDependentSingleWriter(script)
        return GeneratedScript(script, finalAudit)
    }

    /**
     * Splices the case's single quiesce [CaseStep.Barrier] into [steps] at an [rng]-chosen
     * strictly interior position (`[ORA1-GEN-09]`) — never before the first `Op`, never after
     * the last — and shifts every [RemoveRecord.stepIndex] in [audit] that lands at or past the
     * insertion point by one, so each record keeps naming the same `Op` step after the splice.
     *
     * @throws IllegalArgumentException if [steps] holds fewer than two elements: a strictly
     *   interior position needs at least one `Op` on each side, which `config.scriptLength >= 2`
     *   is required to offer.
     */
    private fun insertBarrier(steps: List<CaseStep>, audit: List<RemoveRecord>): Pair<List<CaseStep>, List<RemoveRecord>> {
        require(steps.size >= 2) {
            "lateJoiner needs a strictly interior Barrier position, which requires scriptLength " +
                ">= 2; got ${steps.size}"
        }
        // position in 1 until steps.size: at least one step before it (indices 0 until position)
        // and at least one after (indices position until steps.size).
        val position = 1 + rng.nextInt(steps.size - 1)
        val spliced = ArrayList<CaseStep>(steps.size + 1)
        spliced.addAll(steps.subList(0, position))
        spliced += CaseStep.Barrier
        spliced.addAll(steps.subList(position, steps.size))
        val shiftedAudit = audit.map { if (it.stepIndex >= position) it.copy(stepIndex = it.stepIndex + 1) else it }
        return spliced to shiftedAudit
    }

    /**
     * Appends one *logical* event for [source] — one step, or two when a cross-writer observed
     * remove needs its `Observe` first. Always appends at least one step, and never more than
     * [remaining], so the loop terminates and the op-step count lands exactly on
     * `config.scriptLength`.
     */
    private fun emitOne(source: SourceState, remaining: Int, steps: MutableList<CaseStep>, audit: MutableList<RemoveRecord>) {
        val add = rng.nextDouble() < config.addRemoveRatio
        when (source.kind) {
            SourceKind.SET -> if (add) emitAdd(source, steps) else emitSetRemove(source, remaining, steps, audit)
            SourceKind.KEYED_SET, SourceKind.MAP -> if (add) emitPut(source, steps) else emitRemoveKey(source, steps, audit)
            SourceKind.COUNTER, SourceKind.PN_COUNTER -> emitCounter(source, add, steps)
        }
    }

    private fun emitAdd(source: SourceState, steps: MutableList<CaseStep>) {
        val writer = pick(source.writers)
        val element = pick(elementDomain)
        source.recordAdd(writer, element)
        append(source, ScriptEvent.Add(writer, element), steps)
    }

    /**
     * Appends one op step AND records the event in [source]'s own slice, which is what
     * [SourceState.liveElements] folds through [Membership.live]. Every op step goes through
     * here: a step appended straight to [steps] would be invisible to the liveness fold and
     * would silently reintroduce `computenet-qcm1`.
     */
    private fun append(source: SourceState, event: ScriptEvent, steps: MutableList<CaseStep>) {
        source.recordEvent(event)
        steps += CaseStep.Op(source.id, event)
    }

    /**
     * A set-source remove, biased by `unobservedRemoveRatio`. Falls back to an add when the
     * chosen flavour has no candidate at all — early in a script nothing has been added yet,
     * and (for the unobserved flavour) a writer that has come to know the entire element
     * domain has no unobserved element left to name. Falling back keeps the ratio honest
     * about what was *possible*; it is not a retry of a rejected sample.
     */
    private fun emitSetRemove(source: SourceState, remaining: Int, steps: MutableList<CaseStep>, audit: MutableList<RemoveRecord>) {
        val unobserved = rng.nextDouble() < config.unobservedRemoveRatio
        val emitted = if (unobserved) emitUnobservedRemove(source, steps, audit) else emitObservedRemove(source, remaining, steps, audit)
        if (!emitted) emitAdd(source, steps)
    }

    /**
     * A remove naming an element its writer has neither added nor observed **and which is not
     * live at this position of the source's slice** (`computenet-qcm1`).
     *
     * Both conditions are needed for the step to be a no-op on *both* sides of the differential.
     * "Not added nor observed" makes it a `Membership` no-op; "not live" is what makes it a
     * kernel no-op, because `SetCell.inletHandler.remove` retracts `liveTags(element)`
     * unconditionally and does not consult the removing writer's causal history. Without the
     * second condition a cross-writer remove of a live element takes effect in the kernel while
     * the model ignores it — a Mismatch manufactured by the generator rather than found in the
     * kernel.
     *
     * Liveness comes from [Membership.live] over the events emitted into this source so far
     * ([SourceState.liveElements]) — the model's own fold, called rather than re-derived, so the
     * generator cannot hold a second and subtly different notion of "live" from the one the
     * runner compares against.
     */
    private fun emitUnobservedRemove(source: SourceState, steps: MutableList<CaseStep>, audit: MutableList<RemoveRecord>): Boolean {
        val live = source.liveElements()
        val candidates = source.writers.mapNotNull { writer ->
            elementDomain
                .filterNot { it in source.known(writer) || it in live }
                .takeIf { it.isNotEmpty() }
                ?.let { writer to it }
        }
        if (candidates.isEmpty()) return false
        val (writer, unknown) = pick(candidates)
        val element = pick(unknown)
        audit += RemoveRecord(stepIndex = steps.size, observed = false)
        append(source, ScriptEvent.Remove(writer, element), steps)
        return true
    }

    /**
     * A remove its writer genuinely observed at that position — either an element the writer
     * added itself, or (with an explicit `Observe` emitted first) an element another writer
     * added. Both paths are offered whenever both have candidates, so a population contains
     * self-removes *and* cross-writer observed removes.
     */
    private fun emitObservedRemove(source: SourceState, remaining: Int, steps: MutableList<CaseStep>, audit: MutableList<RemoveRecord>): Boolean {
        val direct = source.writers.mapNotNull { writer ->
            source.known(writer).takeIf { it.isNotEmpty() }?.let { writer to it.toList() }
        }
        val cross = if (remaining < 2) {
            emptyList()
        } else {
            source.writers.mapNotNull { writer ->
                source.addedAnywhere.filterNot { it in source.known(writer) }.takeIf { it.isNotEmpty() }?.let { writer to it }
            }
        }

        val useCross = when {
            direct.isEmpty() && cross.isEmpty() -> return false
            direct.isEmpty() -> true
            cross.isEmpty() -> false
            else -> rng.nextBoolean()
        }

        if (useCross) {
            val (writer, unseen) = pick(cross)
            // Observe states that `writer` has seen every add at an earlier position — exactly
            // Membership's condition for a cross-writer remove to cover them.
            source.recordObserve(writer)
            append(source, ScriptEvent.Observe(writer), steps)
            val element = pick(unseen)
            audit += RemoveRecord(stepIndex = steps.size, observed = true)
            append(source, ScriptEvent.Remove(writer, element), steps)
        } else {
            val (writer, known) = pick(direct)
            val element = pick(known)
            audit += RemoveRecord(stepIndex = steps.size, observed = true)
            append(source, ScriptEvent.Remove(writer, element), steps)
        }
        return true
    }

    private fun emitPut(source: SourceState, steps: MutableList<CaseStep>) {
        val writer = pick(source.writers)
        val key = pick(keyDomain)
        source.recordAdd(writer, key)
        append(source, ScriptEvent.Put(writer, key, pick(elementDomain)), steps)
    }

    /**
     * A keyed remove. "Observed" here means the removing writer had put that key earlier —
     * the only observation relation a keyed source model defines; there is no `Observe` event
     * for a keyed source, so the cross-writer path of [emitObservedRemove] has no analogue.
     */
    private fun emitRemoveKey(source: SourceState, steps: MutableList<CaseStep>, audit: MutableList<RemoveRecord>) {
        val unobserved = rng.nextDouble() < config.unobservedRemoveRatio
        val writer = pick(source.writers)
        val put = source.known(writer).toList()
        val unput = keyDomain.filterNot { it in source.known(writer) }

        val pool = if (unobserved) unput.ifEmpty { put } else put.ifEmpty { unput }
        if (pool.isEmpty()) {
            emitPut(source, steps)
            return
        }
        val key = pick(pool)
        audit += RemoveRecord(stepIndex = steps.size, observed = key in source.known(writer))
        append(source, ScriptEvent.RemoveKey(writer, key), steps)
    }

    private fun emitCounter(source: SourceState, increment: Boolean, steps: MutableList<CaseStep>) {
        val writer = pick(source.writers)
        val amount = pick(amountDomain)
        append(
            source,
            if (increment) ScriptEvent.Increment(writer, amount) else ScriptEvent.Decrement(writer, amount),
            steps,
        )
    }

    /**
     * The defensive half of `[ORA1-MODEL-09]`: no order-dependent source's slice may carry
     * events from two writers. A failure here is a generator bug, not a case to discard —
     * hence a `check`, and no regeneration.
     */
    private fun assertOrderDependentSingleWriter(script: CaseScript) {
        sources.filter { it.kind.orderDependent }.forEach { source ->
            val writers = script.steps
                .filterIsInstance<CaseStep.Op>()
                .filter { it.source == source.id }
                .mapTo(LinkedHashSet()) { it.event.writer }
            check(writers.size <= 1) {
                "Order-dependent source '${source.id.id}' (${source.kind.catalogId}) carries events from " +
                    "${writers.size} writers (${writers.map { it.id }.sorted()}); [ORA1-MODEL-09] assigns it " +
                    "exactly one writer at generation time."
            }
        }
    }

    private fun <T> pick(from: List<T>): T = from[rng.nextInt(from.size)]

    /** Per-source generation state: its decided writers, and what each writer has come to know. */
    private class SourceState(
        val id: SourceId,
        val kind: SourceKind,
        val writers: List<WriterId>,
    ) {
        /** Elements (or keys) each writer added itself, in emission order. */
        private val added = LinkedHashMap<WriterId, LinkedHashSet<Any?>>()

        /** Elements each writer has come to know through an emitted `Observe`. */
        private val observed = LinkedHashMap<WriterId, LinkedHashSet<Any?>>()

        /** Every element added into this source by anyone, in emission order. */
        val addedAnywhere: LinkedHashSet<Any?> = LinkedHashSet()

        /**
         * This source's slice as emitted so far, in order — the same list
         * `CaseScript.toScript()` will project for it. Kept so liveness can be read from the
         * reference model's own fold instead of a second bookkeeping notion of it.
         */
        private val events = mutableListOf<ScriptEvent>()

        fun recordEvent(event: ScriptEvent) {
            events += event
        }

        /**
         * The elements live at this position of the slice, per [Membership.live] — literally the
         * model's definition, over the prior events only, which is the set a remove has to avoid
         * to be a no-op in the kernel as well (`computenet-qcm1`).
         *
         * Recomputed from scratch on each call rather than maintained incrementally: `Membership`
         * is the single definition of liveness in this system, and an incremental mirror of it
         * here would be a second one, free to drift. Scripts are hundreds of events long, so the
         * fold's cost is irrelevant beside that.
         */
        fun liveElements(): Set<Any?> = Membership.live(events)

        /**
         * What [writer] has added or observed so far. Monotone on purpose: a writer that has
         * once observed an element has an `Add`/`Observe` of it *earlier in the slice* forever
         * after, which is exactly the condition an audit re-derivation reads.
         */
        fun known(writer: WriterId): Set<Any?> = added[writer].orEmpty() + observed[writer].orEmpty()

        fun recordAdd(writer: WriterId, element: Any?) {
            added.getOrPut(writer) { LinkedHashSet() } += element
            addedAnywhere += element
        }

        /** `Observe` grants its writer every add at an earlier position (`Membership`'s rule). */
        fun recordObserve(writer: WriterId) {
            observed.getOrPut(writer) { LinkedHashSet() }.addAll(addedAnywhere)
        }
    }
}

/**
 * A generated script and the provenance of its removes — what [ScriptGenerator] produces, and
 * the two fields a `GeneratedCase` takes verbatim (`GeneratedCase.script`/`removeAudit`).
 *
 * Not a `GeneratedCase` itself: the lowered `GraphSpec` and the case seed come from the
 * facade that composes a graph generator with this one.
 */
data class GeneratedScript(
    val script: CaseScript,
    val removeAudit: List<RemoveRecord>,
)

/**
 * The arity-0 catalog entries a script can drive, and the event vocabulary each accepts.
 *
 * @property catalogId the `OperatorCatalog` id (`CoreOperators.Ids`) this kind is registered under.
 * @property orderDependent whether the source's reference model is defined only for a
 *   single-writer slice — `map` alone today (`MapCellSourceModel.MultiWriterMapSliceException`,
 *   `[ORA1-MODEL-08]`). `ListCell` is not in the vocabulary at all: it is excluded by
 *   `MapCellModel.kt`'s `[ORA1-HONEST-02]` ledger, because its index-addressed edits cannot be
 *   stated in a script even under a single writer.
 */
enum class SourceKind(val catalogId: String, val orderDependent: Boolean) {
    /** `SetCell` — `Add` / `Remove` / `Observe`. */
    SET(CoreOperators.Ids.SET, orderDependent = false),

    /** `KeyedSetCell` — `Put` / `RemoveKey`. */
    KEYED_SET(CoreOperators.Ids.KEYED_SET, orderDependent = false),

    /** `MapCell` — `Put` / `RemoveKey`, under exactly one writer. */
    MAP(CoreOperators.Ids.MAP, orderDependent = true),

    /** `CounterCell` — `Increment` / `Decrement`. */
    COUNTER(CoreOperators.Ids.COUNTER, orderDependent = false),

    /** `PnCounterCell` — `Increment` / `Decrement`. */
    PN_COUNTER(CoreOperators.Ids.PN_COUNTER, orderDependent = false),
    ;

    companion object {
        private val byCatalogId: Map<String, SourceKind> = entries.associateBy { it.catalogId }

        /**
         * The kind registered under [catalogId].
         *
         * @throws IllegalArgumentException if [catalogId] is not an arity-0 vocabulary entry —
         *   a topology node bearing a `SourceId` but naming an operator is a topology bug, and
         *   silently emitting no events for it would produce a green case that drove nothing.
         */
        fun of(catalogId: String): SourceKind = byCatalogId[catalogId]
            ?: throw IllegalArgumentException(
                "'$catalogId' is not a drivable source kind; a source node names one of " +
                    "${entries.map { it.catalogId }}.",
            )
    }
}
