package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
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
 * A [CaseScript] of exactly `config.scriptLength` [CaseStep.Op] steps and **no**
 * [CaseStep.Barrier]: barrier placement is the late-joiner sibling's (`[ORA1-GEN-09]`). Each
 * step drives one `civictech.oracle.model.ScriptEvent` into one source's slice; the
 * interleaving across sources is this generator's choice from [rng], and the per-source
 * subsequence is what the reference model consumes ([CaseScript.toScript]).
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
 * task's acceptance criterion checks ("add:remove proportion within stated tolerance") and
 * the epic's own "add/remove ratio" knob name. `GeneratorConfig`'s field KDoc describes it
 * instead as the fraction of removes targeting an added element, which is what
 * [GeneratorConfig.unobservedRemoveRatio]'s complement already is; that doc is a sibling
 * task's file and is left untouched here.
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

        val script = CaseScript(steps)
        assertOrderDependentSingleWriter(script)
        return GeneratedScript(script, audit)
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
        steps += CaseStep.Op(source.id, ScriptEvent.Add(writer, element))
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

    /** A remove naming an element its writer has neither added nor observed. */
    private fun emitUnobservedRemove(source: SourceState, steps: MutableList<CaseStep>, audit: MutableList<RemoveRecord>): Boolean {
        val candidates = source.writers.mapNotNull { writer ->
            elementDomain.filterNot { it in source.known(writer) }.takeIf { it.isNotEmpty() }?.let { writer to it }
        }
        if (candidates.isEmpty()) return false
        val (writer, unknown) = pick(candidates)
        val element = pick(unknown)
        audit += RemoveRecord(stepIndex = steps.size, observed = false)
        steps += CaseStep.Op(source.id, ScriptEvent.Remove(writer, element))
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
            steps += CaseStep.Op(source.id, ScriptEvent.Observe(writer))
            val element = pick(unseen)
            audit += RemoveRecord(stepIndex = steps.size, observed = true)
            steps += CaseStep.Op(source.id, ScriptEvent.Remove(writer, element))
        } else {
            val (writer, known) = pick(direct)
            val element = pick(known)
            audit += RemoveRecord(stepIndex = steps.size, observed = true)
            steps += CaseStep.Op(source.id, ScriptEvent.Remove(writer, element))
        }
        return true
    }

    private fun emitPut(source: SourceState, steps: MutableList<CaseStep>) {
        val writer = pick(source.writers)
        val key = pick(keyDomain)
        source.recordAdd(writer, key)
        steps += CaseStep.Op(source.id, ScriptEvent.Put(writer, key, pick(elementDomain)))
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
        steps += CaseStep.Op(source.id, ScriptEvent.RemoveKey(writer, key))
    }

    private fun emitCounter(source: SourceState, increment: Boolean, steps: MutableList<CaseStep>) {
        val writer = pick(source.writers)
        val amount = pick(amountDomain)
        steps += CaseStep.Op(
            source.id,
            if (increment) ScriptEvent.Increment(writer, amount) else ScriptEvent.Decrement(writer, amount),
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
