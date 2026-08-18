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
 *
 * - **`OrMapCell` / `TaggedMapDelta`.** The tagged/keyed convergence family is ORA2's scope
 *   (computenet-4ru.1 depends on ORA1; epic computenet-4ru §6: "It does not model `OrMapCell`
 *   / `TaggedMapDelta` semantics… its KE1-era semantics are ORA2's"). Not modelled here.
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
 *
 * - **Window close / eviction.** `Windows` (`kernel/src/main/kotlin/civictech/cell/data/
 *   Windows.kt`) is not a cell — it is two pure key-assignment functions, `tumbling`/`sliding`,
 *   consumed by `FlatMapSetCell`/`GroupByCell`, both already in this vocabulary. Its own KDoc
 *   states plainly: *"Windows never close: late elements are ordinary adds and retractions
 *   flow… Watermark-driven eviction is deferred."* There is therefore no window-close/eviction
 *   *behaviour in the kernel itself* to model or to exclude — windowing-as-key-derivation is
 *   already fully expressible with the registered `flatMapSet`/`groupBy*` entries, and eviction
 *   is `KE4` (epic §5/§9 risk 7), unbuilt on the kernel side, out of scope here.
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
