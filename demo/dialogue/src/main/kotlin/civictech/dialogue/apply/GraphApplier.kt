package civictech.dialogue.apply

import civictech.agora.AgoraService
import civictech.cell.CellRef
import civictech.cell.host.ManagedHost
import civictech.cell.observe.Observation
import civictech.cell.observe.ObservationFrame
import civictech.cell.observe.get
import civictech.cell.observe.observation
import civictech.dialogue.ClaimKey
import civictech.dialogue.DialogueRuntime
import civictech.dialogue.DialoguePipeline
import civictech.dialogue.RelationKey
import civictech.dialogue.mint.ClaimAggregate
import civictech.dialogue.mint.RelationAggregate
import civictech.dialogue.mint.StanceAggregate

@Suppress("UNCHECKED_CAST")
private fun <T> ObservationFrame.view(name: String): T = views.getValue(name) as T

/**
 * Pipeline stage 8 (epic computenet-2aw §2.2, §2.5 "Stage 8 sink",
 * [AGO1-APPLY-01]..[AGO1-APPLY-07], DESIGN D3 / 2aw.F4-D1): the **sole
 * writer** into the agora graph.
 *
 * It observes the pipeline's three canonical folds — `canonicalClaims`,
 * `canonicalRelations`, `projectedStances` — and, when the driver says the
 * graph is at rest, reconciles those snapshots against a fold-backed
 * [BindingTable] by issuing `AgoraService.createClaim` / `createEdge` /
 * `remove` / `setStance`. Nothing else in `:demo:dialogue` holds an
 * [AgoraService].
 *
 * ### Pull at quiescence, never push mid-wave ([AGO1-APPLY-04], 2aw.F4-D3)
 *
 * This class registers **no** `onChange` listener. The three sinks are read
 * only from [reconcile], which the driver calls after the simulation has
 * quiesced; between reconciles the applier is inert no matter what the
 * pipeline emits.
 *
 * That is a correctness property, not an ergonomic one. `computenet-23bf`
 * measured that the relation leg's semijoins (stages 5d/5e) ship at the
 * ungated `emitOnFrontier` default because gating wedges this graph, so
 * admitting the utterance that mints a relation's last endpoint can flicker
 * that relation into and *out of* the canonical fold **within one wave**.
 * Reacting to the delta stream would turn that transient into a real
 * create-then-retract against the agora graph — and since this applier is the
 * sole writer, there is nobody to correct it. Pulling a settled snapshot
 * instead makes the flicker two folds into a `MapView` and zero agora ops:
 * unobservable by construction. **Do not add an `onChange` subscription to
 * the write path**; it would silently reintroduce the defect the design
 * exists to exclude.
 *
 * ### Never spawning ClaimCell/EdgeCell ([AGO1-APPLY-03], DESIGN D3)
 *
 * Every write goes through [AgoraService]. `createEdge` is what runs
 * `reaches(...)`, designates cycle heads, and records the topology delta;
 * spawning `EdgeCell` directly would silently lose both. This file imports
 * nothing from `civictech.agora.cell`.
 *
 * ### Idempotence ([AGO1-APPLY-01]/-02)
 *
 * A key the [BindingTable] reports bound is never created again, so a
 * reconcile over an unchanged canonical set issues zero structure ops. The
 * kernel topology delta is written ahead of the spawn/despawn operation, and
 * the binding view changes with that fold; there is no second bind/unbind
 * write and therefore no application-level crash window between the two.
 *
 * ### Claim text is written once, at create (computenet-0d5e)
 *
 * The claim-creates block below skips an already-bound key wholesale, so
 * `ClaimAggregate.text` is read exactly once per key — at
 * [AgoraService.createClaim] — and later contributions change a claim's
 * *provenance*, never its display. A claim contributed by two utterances
 * whose texts differ only in case therefore shows whichever text was live at
 * first bind, not
 * [civictech.dialogue.mint.ClaimMint.ClaimAggregator]'s lexicographically
 * least representative, and that displayed text is admission-order
 * dependent.
 *
 * **This is intended.** It was decided on computenet-0d5e against the
 * alternative — reconciling a bound claim's text when its representative
 * changes — for three reasons:
 *
 * - **Identity and content are not the same kind of thing, and their purity
 *   requirements point in opposite directions.** [BindingTable.refFor] is a
 *   pure function of the canonical key because identity must *converge
 *   without coordination*: two independent tables, a fresh directory, a
 *   replayed journal and a restarted process all have to agree on a claim's
 *   ref with nothing to consult. Purity
 *   there buys **stability** — the same key is the same ref forever, and
 *   recomputing it changes nothing observable. Display text could never be a
 *   pure function of the canonical key at all (the key is lowercased and
 *   whitespace-collapsed; it does not contain the text). The only purity on
 *   offer is in the *live contributing set*, which is exactly what changes
 *   over time — so making display text pure in it would make the text
 *   **churn** under a reader on every admission and retraction. That is the
 *   opposite of what `refFor`'s purity provides, not the same property
 *   extended.
 * - **The tie-break is arbitrary.** Lexicographic least is UTF-16 code-unit
 *   order, under which `'T'`(84) precedes `'t'`(116). It picks a
 *   capitalization, not a better claim. Both candidates are texts a speaker
 *   actually uttered, and computenet-9bip already settled the division of
 *   labour this sits in: normalize the KEY, preserve the TEXT.
 * - **There is no write surface for it, and the substitute is destructive.**
 *   [AgoraService] exposes no text update, and its topology vocabulary has
 *   no op for one; adding one is a format change bought with an arbitrary
 *   tie-break. The only re-text available to the sole writer today is
 *   `remove` + `createClaim` at the same deterministic ref — and
 *   [AgoraService.remove] cascades over every edge that becomes dangling, so
 *   the graph would delete a claim's whole relational neighbourhood, its
 *   stances and its credence to change one letter.
 *
 * `GraphApplierTest`'s `INTENDED - a claim merged from two case-differing
 * texts displays the first-bound one ...` pins this, including that the ref
 * does not churn while the text order-depends.
 *
 * ### Fixed op order
 *
 * (1) relation removals, (2) claim removals, (3) claim creates, (4) relation
 * creates, (5) stances. Removing edges before their endpoint claims means the
 * applier never leans on agora's dangling-edge cascade to clean up after its
 * own writes — if it did, its subsequent `remove` of the cascaded edge would
 * be rejected and recorded as a spurious failure. Creating claims before
 * relations means an edge's endpoints always exist when `createEdge` runs.
 *
 * @param host the host the pipeline lives on; the three observation sinks are
 *   spawned into it under deterministic refs.
 * @param refs the pipeline handles to observe.
 * @param service the agora graph. Held by this class alone (2aw.F4-D1).
 * @param bindings the read-only Key -> CellRef view of the context used by
 *   [service].
 */
class GraphApplier(
    private val host: ManagedHost,
    refs: DialoguePipeline.Refs,
    private val service: AgoraService,
    private val bindings: BindingTable,
) {
    /** The cumulative ledger ([AGO1-APPLY-06]/-07). */
    val accounting = ApplyAccounting()

    /**
     * Last stance value applied per (speaker, claim key), so an unchanged
     * stance is not re-issued.
     *
     * Deliberately **not** durable: after a restart every live stance is
     * re-issued once. Those are data writes, not structure ops, they are
     * journaled by the host like any other, and `setStance` is idempotent in
     * its effect — so the cost of forgetting is one redundant propagate per
     * stance, against the cost of a second durable log.
     */
    private val appliedStances = mutableMapOf<Pair<String, ClaimKey>, Double>()

    private val observation: Observation = host.observation(groupRef = DialogueRuntime::sinkRef) {
        map("claims", refs.canonicalClaims.ref)
        map("relations", refs.canonicalRelations.ref)
        map("stances", refs.projectedStances.ref)
    }

    internal val observationGroups: Map<String, String>
        get() = observation.current().groupOf

    /** The claim keys currently bound — [AGO1-APPLY-07]'s "bound" half. */
    fun boundClaims(): Set<ClaimKey> = bindings.boundClaims()

    /** The relation keys currently bound. */
    fun boundRelations(): Set<RelationKey> = bindings.boundRelations()

    /** The canonical claim snapshot the next [reconcile] would read. */
    fun observedClaims(): Map<ClaimKey, ClaimAggregate> = observation.get("claims")

    /** The canonical relation snapshot the next [reconcile] would read. */
    fun observedRelations(): Map<RelationKey, RelationAggregate> = observation.get("relations")

    /** The projected-stance snapshot the next [reconcile] would read. */
    fun observedStances(): Map<Pair<String, ClaimKey>, StanceAggregate> = observation.get("stances")

    /**
     * Apply the current canonical snapshots to the agora graph and return
     * what this call did.
     *
     * Call this **at quiescence only** — the snapshots are read once, up
     * front, so a call made mid-wave applies a half-settled graph (which is
     * legal for the sinks and wrong for agora). A rejected write is recorded
     * against its key and the remaining keys still apply ([AGO1-APPLY-06]);
     * this method does not throw on a rejection.
     */
    fun reconcile(): ReconcileReport {
        val frame = observation.current()
        val claims: Map<ClaimKey, ClaimAggregate> = frame.view("claims")
        val relations: Map<RelationKey, RelationAggregate> = frame.view("relations")
        val stances: Map<Pair<String, ClaimKey>, StanceAggregate> = frame.view("stances")

        val ops = mutableListOf<ApplyOp>()
        val failures = mutableListOf<ApplyFailure>()

        // (1) relation removals — before claim removals, so the applier never
        //     has to reason about agora's dangling-edge cascade having already
        //     taken an edge it was about to remove itself.
        (bindings.boundRelations() - relations.keys).forEach { key ->
            val ref = bindings.refOf(key) ?: return@forEach
            remove(ApplyKind.RELATION, ApplyOp.OpKind.REMOVE_RELATION, key.value, ref, ops, failures)
        }

        // (2) claim removals.
        (bindings.boundClaims() - claims.keys).forEach { key ->
            val ref = bindings.refOf(key) ?: return@forEach
            remove(ApplyKind.CLAIM, ApplyOp.OpKind.REMOVE_CLAIM, key.value, ref, ops, failures) {
                // A claim that is gone has no stance; drop the memo so a
                // re-minted claim re-issues rather than being suppressed.
                appliedStances.keys.removeAll { (_, claimKey) -> claimKey == key }
            }
        }

        // (3) claim creates — before relation creates, so an edge's endpoints
        //     exist by the time createEdge runs.
        claims.forEach { (key, aggregate) ->
            // Bound means done: the text is NOT reconciled against the
            // aggregate's current representative. See the class doc's "Claim
            // text is written once, at create" (computenet-0d5e).
            if (bindings.isBound(key)) return@forEach
            val ref = BindingTable.refFor(key)
            try {
                service.createClaim(aggregate.text, ref, handle = BindingTable.handleFor(key))
                check(bindings.refOf(key) == ref) {
                    "claim ${key.value} spawned without its dialogue topology handle"
                }
                ops += ApplyOp(ApplyOp.OpKind.CREATE_CLAIM, key.value, ref)
                accounting.recordOp()
            } catch (e: IllegalArgumentException) {
                fail(ApplyKind.CLAIM, key.value, e, failures)
            }
        }

        // (4) relation creates.
        relations.forEach { (key, aggregate) ->
            if (bindings.isBound(key)) return@forEach
            val ref = BindingTable.refFor(key)
            val source = bindings.refOf(aggregate.source)
            val target = bindings.refOf(aggregate.target)
            if (source == null || target == null) {
                // The endpoint claim itself failed to apply this reconcile.
                // Recording rather than attempting keeps [AGO1-APPLY-07] true
                // without issuing a write agora would certainly reject.
                failures += ApplyFailure(
                    ApplyKind.RELATION,
                    key.value,
                    "endpoint claim not bound: ${if (source == null) aggregate.source.value else aggregate.target.value}",
                ).also(accounting::record)
                return@forEach
            }
            try {
                service.createEdge(
                    source,
                    target,
                    aggregate.polarity,
                    ref,
                    handle = BindingTable.handleFor(key),
                )
                check(bindings.refOf(key) == ref) {
                    "relation ${key.value} spawned without its dialogue topology handle"
                }
                ops += ApplyOp(ApplyOp.OpKind.CREATE_RELATION, key.value, ref)
                accounting.recordOp()
            } catch (e: IllegalArgumentException) {
                fail(ApplyKind.RELATION, key.value, e, failures)
            }
        }

        // (5) stances — data writes, not structure ops, so they are counted
        //     separately and never appear in [ReconcileReport.ops].
        var stanceWrites = 0
        (appliedStances.keys - stances.keys).toList().forEach { entry ->
            // Group death IS the "stance cleared" signal (StanceProject's
            // KDoc): an entry that disappeared means no stance, which agora
            // spells `setStance(..., null)`.
            val ref = bindings.refOf(entry.second) ?: run { appliedStances.remove(entry); return@forEach }
            if (setStance(ref, entry, null, failures)) stanceWrites++
            appliedStances.remove(entry)
        }
        stances.forEach { (entry, aggregate) ->
            if (appliedStances[entry] == aggregate.value) return@forEach
            val ref = bindings.refOf(entry.second)
            if (ref == null) {
                failures += ApplyFailure(
                    ApplyKind.STANCE,
                    stanceKey(entry),
                    "claim not bound: ${entry.second.value}",
                ).also(accounting::record)
                return@forEach
            }
            if (setStance(ref, entry, aggregate.value, failures)) {
                appliedStances[entry] = aggregate.value
                stanceWrites++
            }
        }

        return ReconcileReport(ops = ops, failures = failures, stanceWrites = stanceWrites)
    }

    /**
     * Issue one removal. [AgoraService.remove] journals and applies the
     * despawn, so the binding view is already unbound when it returns.
     */
    private inline fun remove(
        kind: ApplyKind,
        opKind: ApplyOp.OpKind,
        key: String,
        ref: CellRef,
        ops: MutableList<ApplyOp>,
        failures: MutableList<ApplyFailure>,
        afterRemove: () -> Unit = {},
    ) {
        try {
            service.remove(ref)
            check(bindings.keyOf(ref) == null) { "$kind $key remained bound after remove" }
            afterRemove()
            ops += ApplyOp(opKind, key, ref)
            accounting.recordOp()
        } catch (e: IllegalArgumentException) {
            fail(kind, key, e, failures)
        }
    }

    /** @return whether the write returned normally. */
    private fun setStance(
        ref: CellRef,
        entry: Pair<String, ClaimKey>,
        value: Double?,
        failures: MutableList<ApplyFailure>,
    ): Boolean = try {
        service.setStance(ref, entry.first, value)
        accounting.recordStance()
        true
    } catch (e: IllegalArgumentException) {
        fail(ApplyKind.STANCE, stanceKey(entry), e, failures)
        false
    }

    private fun fail(
        kind: ApplyKind,
        key: String,
        cause: IllegalArgumentException,
        failures: MutableList<ApplyFailure>,
    ) {
        val failure = ApplyFailure(kind, key, cause.message ?: cause.toString())
        failures += failure
        accounting.record(failure)
    }

    private fun stanceKey(entry: Pair<String, ClaimKey>) = "${entry.first}@${entry.second.value}"
}
