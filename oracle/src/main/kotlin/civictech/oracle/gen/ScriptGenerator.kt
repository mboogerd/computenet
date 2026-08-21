package civictech.oracle.gen

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.model.DotModel
import civictech.oracle.model.DotOrder
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
    /**
     * `null` for an ORA1 case, and then nothing below this line runs: the emitted script, the
     * audit and every [rng] draw are what they were before the replicated dimension existed.
     */
    private val plan: ReplicaPlan? = null,
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
        .filter { it.source != null && it.handle != plan?.handle }
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

    /**
     * The replicated node's replicas, in [ReplicaPlan.replicas] order — empty for an ORA1 case.
     *
     * A replicated node must be a *tagged* source: the whole point of the dimension is the dot
     * algebra, and `[ORA2-GEN-01]`'s convergent-vocabulary gate (`GeneratorConfig.validateReplication`)
     * has already excluded the untagged order-dependent families at configuration time. What is
     * checked here is the remaining case the gate cannot see — a convergent but *un-keyed* source
     * (a set or a counter) drawn as the replicated node — and it is checked rather than silently
     * driven, because the replicated write path below emits keyed events only.
     */
    private val replicas: List<ReplicaState> = plan?.let { p ->
        val node = topology.nodes.single { it.handle == p.handle }
        require(node.catalogId == TaggedOperators.Ids.OR_MAP) {
            "Replica placement is a tagged-family dimension: handle '${p.handle}' names catalog id " +
                "'${node.catalogId}', which mints no dots. [ORA2-GEN-01]/[ORA2-GEN-03] replicate " +
                "'${TaggedOperators.Ids.OR_MAP}'."
        }
        p.replicas.indices.map { i -> ReplicaState(p.replicas[i], WriterId(p.writers[i])) }
    }.orEmpty()

    init {
        require(sources.isNotEmpty() || replicas.isNotEmpty()) {
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

        val deliveries = mutableListOf<CaseDelivery>()

        while (steps.size < config.scriptLength) {
            val replicated = replicas.isNotEmpty() && (sources.isEmpty() || rng.nextInt(sources.size + 1) == 0)
            if (replicated) {
                emitReplicaStep(steps, deliveries, audit)
            } else {
                val source = sources[rng.nextInt(sources.size)]
                emitOne(source, remaining = config.scriptLength - steps.size, steps = steps, audit = audit)
            }
        }
        // One closing round, so the last writes are gossiped where the rules allow it. It is not
        // forced: a round at prefixes a previous round already used composes with that round into
        // exactly the cycle `DotModel` refuses, and nothing about being last exempts it. Nor does
        // it need to be forced — `DotModel.converged` merges every replica's final state anyway,
        // so an ungossiped tail costs the reference nothing; what a closing round buys is causal
        // depth in the script, not correctness of the converged answer.
        if (replicas.isNotEmpty()) openGossipRound(steps.size, deliveries)

        val spliced = if (config.lateJoiner) insertBarrier(steps, audit, deliveries) else null
        val finalSteps = spliced?.first ?: steps
        val finalAudit = spliced?.second ?: audit
        val script = CaseScript(finalSteps, spliced?.third ?: deliveries)
        assertOrderDependentSingleWriter(script)
        return GeneratedScript(script, finalAudit, replicationAudit(script))
    }

    // ---------------------------------------------------------------------------------------
    // The replicated dimension ([ORA2-GEN-01]..[ORA2-GEN-05]). Everything below is unreachable
    // for `replicaCount == 1`.
    // ---------------------------------------------------------------------------------------

    /**
     * One replica's step: a gossip round if one is due, then one keyed write.
     *
     * `config.concurrencyRatio` is the knob and it acts *here*, on whether the gossip that would
     * order this write against its peers happens before it or not — which is the only place
     * concurrency can be created. It is not a post-hoc filter and it is not a retry: a write is
     * emitted either way, and whether it turned out genuinely concurrent is **measured**
     * ([ConcurrencyAudit]), never assumed from the draw.
     */
    private fun emitReplicaStep(
        steps: MutableList<CaseStep>,
        deliveries: MutableList<CaseDelivery>,
        audit: MutableList<RemoveRecord>,
    ) {
        if (rng.nextDouble() >= config.concurrencyRatio) openGossipRound(steps.size, deliveries)

        val replica = replicas[rng.nextInt(replicas.size)]
        val key = chooseReplicaKey(replica)
        val add = rng.nextDouble() < config.addRemoveRatio || replica.view.isEmpty()

        recordWrite(replica, key)
        if (add) {
            replica.ownPuts += 1
            replica.view.add(key)
            append(replica, ScriptEvent.Put(replica.writer, key, pick(elementDomain)), steps)
        } else {
            audit += RemoveRecord(stepIndex = steps.size, observed = key in replica.view)
            replica.view.remove(key)
            append(replica, ScriptEvent.RemoveKey(replica.writer, key), steps)
        }
    }

    /**
     * `[ORA2-GEN-05]`: which key a replica writes, biased toward keys that are already populated
     * — and, when concurrency is being sought, toward a key another replica wrote that this one
     * has NOT absorbed, which is what makes the write causally unordered rather than merely
     * simultaneous-looking.
     *
     * `[ORA2-GEN-04]`/BS-2's counter tie is targeted *inside* that first branch: among the
     * unabsorbed peer writes, one whose peer minted it at the same 1-based put counter this
     * replica is about to mint at is preferred. Two live dots at one key then share a counter and
     * are separated only by instance rank — the tie-break `[24-TMAP-03]` exists for. It is a bias,
     * exactly like `unobservedRemoveRatio`: the tie is not forced, it is made frequent, and
     * whether one actually survived to the converged state is measured afterwards
     * ([replicationAudit]) rather than assumed here.
     */
    private fun chooseReplicaKey(replica: ReplicaState): Any? {
        if (rng.nextDouble() < config.concurrencyRatio) {
            val unabsorbed = keyWrites.entries
                .filter { (_, writes) -> writes.any { it.replica != replica.id && replica.absorbed(it.replica) < it.opIndex } }
            if (unabsorbed.isNotEmpty()) {
                val nextCounter = replica.ownPuts + 1
                val tying = unabsorbed.filter { (_, writes) ->
                    writes.any { it.replica != replica.id && replica.absorbed(it.replica) < it.opIndex && it.putCounter == nextCounter }
                }
                return pick((if (tying.isNotEmpty()) tying else unabsorbed).map { it.key })
            }
        }
        val populated = replica.view.toList()
        if (populated.isNotEmpty() && rng.nextDouble() < config.populatedKeyBias) return pick(populated)
        return pick(keyDomain)
    }

    /**
     * A gossip round: a chain of [CaseDelivery]s along a fresh permutation of the replicas, so
     * every replica after the head absorbs everything ahead of it.
     *
     * ## Why a chain along a permutation, and not an all-to-all sync
     *
     * `DotModel` refuses a script whose deliveries are cyclic, and a *full* all-to-all round at
     * one point of the drive order is exactly such a cycle: two replicas each claiming to have
     * absorbed the other at their current event counts describe no reachable state
     * (computenet-4ru.1.4's finding). A chain is a DAG within the round.
     *
     * ## Why that is enough to make a cycle unconstructable, not merely rare
     *
     * A chain per round is not sufficient on its own — two rounds using different permutations
     * at the SAME event counts compose into a cycle. The second rule is what closes it: a round
     * only opens when **every** replica has emitted at least one own event since the previous
     * round ([force] excepted, for the closing round after the last step, which is the last thing
     * emitted and so has no successor to close a cycle with).
     *
     * With both rules, every replica's event count strictly increases between rounds, so the
     * deliveries into one replica at one event count all come from one round; within that round
     * they follow the permutation strictly downward; and the permutation-head receives nothing.
     * A dependency edge therefore strictly decreases `(round, position in that round's
     * permutation)` lexicographically, and a cycle would have to be a strictly decreasing loop.
     * `MultiWriterGenerationTest` folds every case of the default replicated sweep through
     * `DotModel` and asserts no `CyclicDeliveryException`, so the argument is checked and not
     * merely argued.
     */
    private fun openGossipRound(atStep: Int, deliveries: MutableList<CaseDelivery>) {
        if (replicas.size < 2) return
        if (replicas.any { it.opsSinceRound == 0 }) return

        val order = replicas.toMutableList()
        for (i in order.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val swap = order[i]; order[i] = order[j]; order[j] = swap
        }
        for (i in 1 until order.size) {
            val into = order[i]
            val from = order[i - 1]
            deliveries += CaseDelivery(atStep, into.id, from.id)
            into.absorb(from)
        }
        replicas.forEach { it.opsSinceRound = 0 }
    }

    /** Every keyed write any replica has issued, by key, in emission order. */
    private val keyWrites = LinkedHashMap<Any?, MutableList<KeyWrite>>()

    private var concurrentWrites = 0
    private var comparableWrites = 0
    private var totalWrites = 0

    /**
     * Measures one write's concurrency against the peers' prior writes to the same key, then
     * records it.
     *
     * Concurrency is decided by causality alone: the write is concurrent iff some peer's earlier
     * write to this key has not been absorbed by this replica. The converse half of "neither
     * observed the other" needs no separate test — the peer's write is earlier in the total drive
     * order, so it cannot have absorbed this one.
     */
    private fun recordWrite(replica: ReplicaState, key: Any?) {
        val prior = keyWrites[key].orEmpty()
        val peers = prior.filter { it.replica != replica.id }
        totalWrites += 1
        if (peers.isNotEmpty()) {
            comparableWrites += 1
            if (peers.any { replica.absorbed(it.replica) < it.opIndex }) concurrentWrites += 1
        }
        keyWrites.getOrPut(key) { mutableListOf() } += KeyWrite(
            replica = replica.id,
            opIndex = replica.ownOps + 1,
            putCounter = replica.ownPuts + 1,
        )
    }

    /**
     * The replication half of the audit — what this case ACHIEVED, folded off the emitted script.
     *
     * The counter ties are read from `DotModel`'s converged state rather than predicted from the
     * generation bias, because the two genuinely differ: a tie the generator arranged can still be
     * tombstoned by a later reset-remove, and only the fold knows. Ranking the replicas in plan
     * order here is sound *for this question* even though the kernel's rank order is unknown until
     * apply time: whether two live dots share a counter is a property of the dots, and no ordering
     * of the instances changes it. Which of them WINS does depend on the order, and that is
     * exactly why nothing here reports a winner.
     */
    private fun replicationAudit(script: CaseScript): ReplicationAudit? {
        val p = plan ?: return null
        val state = DotModel(DotOrder.ranked(p.replicas)).converged(script.toScript())
        val ties = state.membership().filter { key ->
            state.liveDots(key).keys.groupingBy { it.counter }.eachCount().any { it.value > 1 }
        }
        return ReplicationAudit(
            plan = p,
            concurrency = ConcurrencyAudit(config.concurrencyRatio, concurrentWrites, comparableWrites, totalWrites),
            counterTieKeys = ties,
            deliveryCount = script.deliveries.size,
        )
    }

    private fun append(replica: ReplicaState, event: ScriptEvent, steps: MutableList<CaseStep>) {
        replica.ownOps += 1
        replica.opsSinceRound += 1
        steps += CaseStep.Op(replica.id, event)
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
    private fun insertBarrier(
        steps: List<CaseStep>,
        audit: List<RemoveRecord>,
        deliveries: List<CaseDelivery>,
    ): Triple<List<CaseStep>, List<RemoveRecord>, List<CaseDelivery>> {
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
        // Same shift, same reason: a delivery names a *position*, and the splice moved the step it
        // named. Shifting at `>= position` keeps the gossip before the same `Op` it was before,
        // which is what makes the derived event counts unchanged.
        val shiftedDeliveries = deliveries.map { if (it.atStep >= position) it.copy(atStep = it.atStep + 1) else it }
        return Triple(spliced, shiftedAudit, shiftedDeliveries)
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
            SourceKind.KEYED_SET, SourceKind.MAP, SourceKind.OR_MAP ->
                if (add) emitPut(source, steps) else emitRemoveKey(source, steps, audit)
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
    /** `null` for a non-replicated case; `GeneratedCase.replication` otherwise. */
    val replication: ReplicationAudit? = null,
)

/**
 * One replica's generation state: what it has issued, what it has absorbed from its peers, and
 * the keys it currently believes populated.
 *
 * [view] is a *local* belief, not the converged truth, and that is the point: `[ORA2-GEN-05]`'s
 * bias toward populated keys has to be biased by what the WRITING replica can see, or a
 * "re-put" would name a key that replica has never heard of.
 */
private class ReplicaState(val id: SourceId, val writer: WriterId) {
    /** Own events emitted so far — the count a `Delivery.throughEvents` refers to. */
    var ownOps: Int = 0

    /** Own `Put`s so far — the 1-based dot counter the next put will mint (`ModelDot`). */
    var ownPuts: Int = 0

    /** Own events since the last gossip round; the strict-increase rule reads this. */
    var opsSinceRound: Int = 0

    /** Keys this replica believes populated, from its own writes and the gossip it absorbed. */
    val view: MutableSet<Any?> = LinkedHashSet()

    private val seen = LinkedHashMap<SourceId, Int>()

    fun absorbed(peer: SourceId): Int = seen[peer] ?: 0

    /** Absorbs [from]'s emissions AND, transitively, everything [from] had itself absorbed. */
    fun absorb(from: ReplicaState) {
        from.seen.forEach { (peer, through) -> if (peer != id) seen[peer] = maxOf(seen[peer] ?: 0, through) }
        seen[from.id] = maxOf(seen[from.id] ?: 0, from.ownOps)
        view.addAll(from.view)
    }
}

/** One keyed write, as the concurrency measurement reads it back. */
private data class KeyWrite(val replica: SourceId, val opIndex: Int, val putCounter: Int)

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

    /**
     * `OrMapCell` — `Put` / `RemoveKey`, convergent under concurrent writers (`[24-TMAP-03]`).
     *
     * Registered by `TaggedOperators`, and drivable through the ordinary keyed path as well as
     * the replicated one: without this entry `SourceKind.of` would reject an `orMap` source node
     * by name, and the catalog entry would be unreachable from generation altogether.
     */
    OR_MAP(TaggedOperators.Ids.OR_MAP, orderDependent = false),
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
