package civictech.oracle.model

import java.io.Serializable

/**
 * `MapCell` — the last operator computenet-4ru.5.3 adds to `[ORA1-MODEL-02]`'s vocabulary —
 * and the honesty ledger (`[ORA1-HONEST-02]`) for everything the vocabulary deliberately does
 * NOT cover. Both halves close the model out: computenet-4ru.5.1 built the set-source, unary
 * and fan-in slice, computenet-4ru.5.2 the binary/keyed-join/group-by family, and this file is
 * where the reference model *stops growing* and says why.
 *
 * ## Exclusion ledger (`[ORA1-HONEST-02]`)
 *
 * Every operator named in epic computenet-4ru §3.1's inventory that does **not** appear in
 * `[ORA1-MODEL-02]`'s minimum coverage list, or does appear there but cannot be modelled
 * honestly, is listed here with a reason verified against its own kernel source — never an
 * approximation offered so a scenario passes.
 *
 * **This is one half of the honesty ledger; the other half is
 * [civictech.oracle.run.OracleSweep]'s `[ORA1-HONEST-01]` KDoc section**, which states at the
 * module's entry point that the reference model's own correctness is *defended, not proven*,
 * and names the four defenses (independence, the divergence control, the mutation check, the
 * corpus cross-check) with their landed test classes and their weaknesses. Read that section
 * before reading a green sweep as a statement about the kernel; read this one before reading
 * the vocabulary as complete. Both are pinned by `civictech.oracle.HonestyLedgerTest`.
 *
 * ### Two kinds of absence, and only one of them is this ledger's subject
 *
 * An operator can be missing from what the sweep actually exercises for two structurally
 * different reasons, and conflating them would let a cheap defect hide behind an expensive
 * one:
 *
 * - **Uncheckable by a batch reference** — *this ledger's subject*. The operator's semantics
 *   cannot be honestly expressed as a pure fold over one local [Script], so no reference model
 *   written under `[ORA1-MODEL-03]`'s constraints could certify it at all. Every entry below is
 *   of this kind, and each says which faculty the script vocabulary lacks (a position-
 *   renumbering rule, a replication event, an edge/wave completion signal).
 * - **Checkable by a batch reference but unreachable by shape-typed generation** — *not this
 *   ledger's subject, and deliberately not recorded here*. The model could certify the
 *   operator; the case generator simply never builds a graph that reaches it. That is a
 *   generator-coverage defect, not a modelling-honesty one, and it is repaired by changing the
 *   generator rather than by writing a reason down. The known instance — the pair-shaped
 *   `joinSet`/`semiJoin`/`antiJoin`/`groupBy*` family — is **computenet-4ru.16, parked for a
 *   human and undecided**, including the question of whether `[ORA1-HONEST-02]` is even the
 *   right home for it. Nothing here decides that; the distinction exists so that recording
 *   4ru.16's eventual outcome is one entry in whichever home is chosen, not a re-design of this
 *   ledger.
 *
 * A third combination exists and is recorded below, because it is not either of those: an entry
 * that is registered and honestly modelled, and unreachable *because* of an exclusion this
 * ledger already made. See the counter coverage note.
 *
 * ### `counter` / `pnCounter`: REGISTERED but NOT EXERCISED (computenet-gff7)
 *
 * `[ORA1-HONEST-02]`'s subject is what the vocabulary does not cover, and coverage has two
 * separable halves — whether an operator is *bound* and whether it is ever *run*. For every
 * other entry in `civictech.oracle.bind.CoreOperators` the two coincide. For these two they do
 * not, so **"registered" does not imply "exercised" here, and this note is what stops it
 * reading that way.**
 *
 * `counter` (`CounterCell`) and `pnCounter` (`PnCounterCell`) are registered with both halves
 * bound and are modelled honestly by `CounterSourceModel`/`PnCounterSourceModel`. But they are
 * the only entries emitting a bare [ElementShape.Scalar], and **no registered operator consumes
 * a bare scalar on any port**. `GraphGenerator.Builder.chooseRootShape` draws a case's root
 * shape only among source shapes something in the vocabulary can consume, and `[ORA1-GEN-03]`
 * forbids a source standing as a terminal itself, so no generated case can spawn either one.
 * Every existing completeness test says they are covered; no differential sweep has ever run
 * them. `civictech.oracle.bind.CatalogReachabilityTest` computes that from the registrations
 * rather than asserting it.
 *
 * **The outcome that holds is: not fixed, and deliberately so** — computenet-gff7's first
 * acceptance clause offered "register a `Scalar`-consuming operator, or record the decision not
 * to", and this is that record. A scalar edge in this catalog carries
 * `Propagate<CounterDelta>`, and exactly ONE cell in the kernel serves that type on an inlet:
 * `CoalescingCombineCell` (`kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt`,
 * `inlet: Serve<Propagate<CounterDelta>>`). Every other operator inlet under
 * `civictech.cell.data.op` serves `SetDelta` or `MapDelta`; `CounterCell.inlet` is
 * `Use<CounterOps>` and `PnCounterCell.deltaInlet` is the replication seam, not an operator
 * input. And that one cell is **excluded by this very ledger**, in its own entry below, for a
 * reason that has not weakened: its observable is a wave-completion fold the script vocabulary
 * cannot name.
 *
 * That is why this note belongs here rather than under "unreachable by shape-typed generation"
 * above. That class is a generator-coverage defect repaired by changing the generator; this one
 * cannot be repaired that way at all. The generator is behaving correctly — there is genuinely
 * nothing to link a counter into — and the only in-catalog repairs are to register the cell
 * this ledger excludes (writing the batch model the exclusion exists to forbid) or to add a new
 * kernel cell to `civictech.cell.data.op` existing solely so the oracle has a scalar consumer.
 * It is an exclusion's consequence, not an independent oversight, which makes it this ledger's
 * subject by transitivity. Contrast the pair-shaped hole, which *was* repairable in-catalog:
 * `keyBy` reuses an already-registered cell (`FlatMapSetCell`) and an already-registered model,
 * adding only a function. No such reuse exists for `Scalar`.
 *
 * *DISPUTES audit: no filing.* Nothing normative goes unchecked. `[24-OP-COUNTER-01]` and
 * `[24-OP-PNCOUNTER-01]` are checked where counters actually run — `concord/corpus` and the
 * kernel's own suites. Note also what is NOT missing: the scalar *shape* is swept, as a
 * terminal, through `count` (`CountCell`, registered and emittable), so it is these two source
 * cells that go unrun, not scalar-valued observation as such. What is absent is a *batch
 * differential* check of `CounterCell`/`PnCounterCell` themselves, which is this ledger's
 * subject and not a gap in the requirements' coverage.
 *
 * ### The `DISPUTES.md` audit (`concord/corpus/DISPUTES.md`)
 *
 * `DISPUTES.md` files a requirement that cannot be checked honestly **anywhere**, not one this
 * oracle merely does not cover. Each entry below was audited against that reading, and the
 * per-entry conclusion is stated in the entry itself: every exclusion remains honestly checked
 * by some other instrument, so **no exclusion below produced a `DISPUTES.md` filing.**
 *
 * The feature *did* produce one filing, and it is not an exclusion: `[ORA1-DIFF-09]`/BS-12, the
 * divergence control that cannot be built against today's kernel because the reference model
 * and the kernel disagree about `[24-SET-03]`'s observer. See the `ORA1 (divergence control)`
 * section of `concord/corpus/DISPUTES.md` and [civictech.oracle.run.DivergenceControlTest].
 *
 * - **`ListCell` / `ListDelta`.** Outside `[ORA1-MODEL-02]`'s minimum list entirely (unlike
 *   `MapCell`, `ListCell` is not named in the requirement), and excluded rather than
 *   partially modelled for a stronger reason than `MapCell`'s: `ListCell`'s operations
 *   (`kernel/src/main/kotlin/civictech/cell/data/ListCell.kt`) are **index**-addressed
 *   (`add(index, element)`, `set(index, element)`, `removeAt(index)`), not key-addressed, so
 *   even a single-writer restriction would leave a script silent on the one thing that
 *   decides the result: whether a later index-addressed edit is stated against positions
 *   *before* or *after* an earlier edit shifted them. `MapCell`'s single-writer FIFO
 *   restriction removes exactly one ambiguity (which of two concurrent same-key puts wins);
 *   `ListCell` would need that ambiguity removed *and* an explicit position-renumbering rule
 *   the script format has no way to state. `[24-OP-LIST-01]` confirms the kernel treats
 *   `ListDelta`'s index-addressed edits as single-stream semantics only, same as `MapCell`'s
 *   `MapDelta` — the difference is that `MapCell`'s single remaining ambiguity is exactly what
 *   `[ORA1-MODEL-08]`'s single-writer restriction resolves, and `ListCell`'s is not.
 *   *DISPUTES audit: no filing.* `[24-OP-LIST-01]` is honestly checked by the hand-authored
 *   scenario `concord/corpus/24-data-cells/24-OP-LIST-01.yaml`, which states the index-addressed
 *   sequence explicitly instead of generating one — the faculty this reference lacks.
 *
 * - **`OrMapCell` / `TaggedMapDelta`.** The tagged/keyed convergence family is ORA2's scope
 *   (computenet-4ru.1 depends on ORA1; epic computenet-4ru §6: "It does not model `OrMapCell`
 *   / `TaggedMapDelta` semantics… its KE1-era semantics are ORA2's"). Not modelled here.
 *   *DISPUTES audit: no filing.* This is a scope boundary between two epics, not a requirement
 *   nothing can check: ORA2 (computenet-4ru.1) takes it with the same machinery, and until then
 *   the corpus carries it.
 *
 * - **`MergeableGroupByCell`.** Verified against its own KDoc
 *   (`kernel/src/main/kotlin/civictech/cell/data/op/MergeableGroupByCell.kt`): *"unlike
 *   `GroupByCell` there is no `retract` — a merge cannot be un-applied in general … The
 *   accumulator itself must encode removal (a peer's `MapDelta.removals` on `deltaInlet` drop
 *   a key); element-level retraction on `inlet` belongs to the non-replicated
 *   `GroupByCell`."* `[ORA1-MODEL-06]` requires the reference model to reproduce aggregator
 *   retraction exactly, including group-death on last-member retraction — and this cell has
 *   **no local retraction path at all**: the only way a key is ever removed is a peer's
 *   gossiped `MapDelta.removals`, a *replication* mechanism this epic explicitly does not
 *   model or exercise (§6: "Replication, partition, crash-restart, membership churn" belong to
 *   `CHA1`/`CHA3`). A batch reference over one local script therefore cannot honestly express
 *   this cell's removal semantics at all — not "removal is different here", but "removal has
 *   no expression in what this reference is allowed to read." Modelling it as grow-only would
 *   be a silent approximation of exactly the retraction property `[ORA1-MODEL-06]` exists to
 *   check; excluded instead. (It is also not named in `[ORA1-MODEL-02]`'s minimum list.)
 *   *DISPUTES audit: no filing.* The removal path is a replication mechanism, and replication
 *   is `CHA1`/`CHA3`'s decided scope (epic §6) — a scope assignment, not an absence of any
 *   honest check.
 *
 * - **Window close / eviction.** `Windows` (`kernel/src/main/kotlin/civictech/cell/data/
 *   Windows.kt`) is not a cell — it is two pure key-assignment functions, `tumbling`/`sliding`,
 *   consumed by `FlatMapSetCell`/`GroupByCell`, both already in this vocabulary. Its own KDoc
 *   states plainly: *"Windows never close: late elements are ordinary adds and retractions
 *   flow… Watermark-driven eviction is deferred."* There is therefore no window-close/eviction
 *   *behaviour in the kernel itself* to model or to exclude — windowing-as-key-derivation is
 *   already fully expressible with the registered `flatMapSet`/`groupBy*` entries, and eviction
 *   is `KE4` (epic §5/§9 risk 7), unbuilt on the kernel side, out of scope here.
 *   *DISPUTES audit: no filing* — and this one could not produce a filing even in principle:
 *   there is no landed behaviour to check, so there is no requirement going unchecked.
 *   `24-OP-WINDOW-01`/`24-OP-WINDOW-02` cover windowing-as-key-derivation, the part that does
 *   exist.
 *
 * - **`CoalescingCombineCell`.** Named in epic §3.1's inventory but not in `[ORA1-MODEL-02]`'s
 *   minimum list; **excluded**, per the bead's default direction. Verified against its own
 *   KDoc (`kernel/src/main/kotlin/civictech/cell/data/op/CoalescingCombineCell.kt`): the cell's
 *   whole reason to exist is a **wave-completion fold** — buffering each in-flight wave's
 *   per-arm contributions against a completeness condition computed from open/closed
 *   `Consume` edges, per-edge per-source watermarks, and per-source flushed high-water marks —
 *   none of which the reference model's script vocabulary ([Script], [ScriptEvent]) can even
 *   *name*: there is no edge, no wave, no link-open/close event a script can carry. That is
 *   not merely "the model ignores WHEN, same as `emitOnFrontier`" (the reasoning
 *   `CoreOperators` gives for `SemiJoinCell`/`CombineLatestCell`, where WHEN and WHAT are
 *   genuinely independent): here WHETHER a contribution is ever counted depends on completion
 *   the model has no way to verify, and the cell's own KDoc documents a case where completion
 *   never happens for a given contribution — `onDeactivate` "drops" the transient version
 *   buffer on restart, so *"a partially collected wave was never observed downstream"*. A
 *   script-only reference has no restart, no edge, and no wave-completion event to decide
 *   whether a given contribution's wave ever completes, so it cannot honestly certify what
 *   this cell's quiescent total is — approximating it as a plain sum-of-contributions would be
 *   silently assuming the very completeness condition the model cannot check.
 *   *DISPUTES audit: no filing.* The cell's wave-completion behaviour is checked where waves
 *   exist — `concord/corpus`'s `24-OP-COMBINE-02` asserts scalar glitch-freedom positively over
 *   the real `CoalescingCombineCell` (see the `24-OP-COMBINE-01`/`CTL-GF-01` entry in
 *   `DISPUTES.md`, resolved). What is absent here is a *batch* check of it, which is this
 *   ledger's subject and not a gap in the requirement's coverage.
 *
 * - **`WatermarkCell` — NOT CLASSIFIED HERE; the question is open and owned elsewhere.**
 *   `civictech.cell.data.WatermarkCell` (`kernel/src/main/kotlin/civictech/cell/data/Watermark.kt`)
 *   is in neither `OperatorCatalog` nor this ledger. That gap is real and pre-existing; it is
 *   recorded by the source-cell drift guard (`oracle/src/test/resources/source-cell-inventory.txt`,
 *   computenet-y9p4) and filed as **computenet-fx5b**, which owns the decision of whether the
 *   cell is registered into the vocabulary or excluded with a written reason. It is named here
 *   so the omission is visible rather than silent — this bullet is a pointer, **not** a verdict,
 *   and does not close fx5b. Note also that `WatermarkCell` is not named in epic
 *   computenet-4ru §3.1's operator inventory, so whether it is even in this ledger's stated
 *   population is part of what fx5b has to settle.
 */

/**
 * `MapCell` — untagged last-writer-wins map ([24-OP-MAP-01]), modelled ONLY for a
 * single-writer FIFO script slice (`[ORA1-MODEL-08]`): the last [ScriptEvent.Put] per key in
 * script order wins, and [ScriptEvent.RemoveKey] deletes the key. `MapCell`'s outlet is
 * `Subscribe<Propagate<MapDelta<K, V>>>`
 * (kernel/src/main/kotlin/civictech/cell/data/MapCell.kt) — an untagged, arrival-order delta
 * — so a terminal downstream of it observes exactly the key→value table this fold computes.
 *
 * ## Why single-writer, given the slice is already one arrival order
 *
 * [Script]'s own KDoc states that a source cell is a single serialization point, so its slice
 * is *always* one definite arrival order regardless of how many writers wrote into it — which
 * is exactly why [KeyedSetSourceModel] and [SetSourceModel] fold a multi-writer slice with no
 * restriction at all. `MapCell` is different in kind, not degree: `[24-OP-MAP-01]` states that
 * `MapDelta`'s arrival-order key puts are single-stream semantics ONLY and NOT a convergent
 * merge under concurrent writers — i.e. the *script's own event order* is not guaranteed to be
 * the order the kernel's actual message delivery would produce when more than one writer is
 * involved, because nothing links two different writers' send order to one another the way a
 * single writer's own calls are FIFO-ordered along one path. A script with two writer ids
 * therefore states an order this reference has no warrant to treat as the arrival order a
 * live run would actually see, and an "expected" LWW winner picked from it would be a guess
 * dressed as a fact. `[ORA1-MODEL-09]`'s generation-time rejection is the corresponding
 * generator-side enforcement (computenet-4ru.6, not built here); this model enforces the same
 * restriction on the evaluation side, so an out-of-band multi-writer slice (hand-built, or a
 * future generator defect) fails loudly rather than returning a plausible-looking guess.
 *
 * ## Serialized form
 *
 * The output is [ModelState.MapState] over the key→value bindings the slice leaves behind, in
 * script order — mirroring [KeyedSetSourceModel.liveBindings]'s shape but, unlike that model,
 * exposed as the primary observable: `MapCell`'s outlet carries the whole table, not a derived
 * element set.
 */
object MapCellSourceModel : SourceModel, Serializable {

    /**
     * Thrown when a [SourceScript] handed to [evaluate] carries [ScriptEvent.Put] or
     * [ScriptEvent.RemoveKey] events from more than one distinct [WriterId] — the case
     * `[ORA1-MODEL-08]` says has no defined expected value. Named so a multi-writer slice
     * fails as *this*, specifically, rather than surfacing as an unrelated
     * `IllegalStateException` a caller has to read the message of to diagnose.
     */
    class MultiWriterMapSliceException(message: String) : IllegalStateException(message)

    override fun evaluate(slice: SourceScript): ModelState {
        val writers = slice.events.mapNotNullTo(LinkedHashSet()) { event ->
            when (event) {
                is ScriptEvent.Put -> event.writer
                is ScriptEvent.RemoveKey -> event.writer
                else -> null
            }
        }
        if (writers.size > 1) {
            throw MultiWriterMapSliceException(
                "MapCellSourceModel is defined only for a single-writer FIFO script slice " +
                    "[ORA1-MODEL-08]; source '${slice.source.id}' carries puts/removes from " +
                    "${writers.size} distinct writers (${writers.map { it.id }.sorted()}), so " +
                    "the expected last-writer-wins value is undefined, not merely unknown.",
            )
        }

        val bindings = LinkedHashMap<Any?, Any?>()
        slice.events.forEach { event ->
            when (event) {
                is ScriptEvent.Put -> bindings[event.key] = event.element
                is ScriptEvent.RemoveKey -> bindings.remove(event.key)
                else -> Unit
            }
        }
        return ModelState.MapState(bindings)
    }

    override fun toString(): String = "MapCellSourceModel"
}
