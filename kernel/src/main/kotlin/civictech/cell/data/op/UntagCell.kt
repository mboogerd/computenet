package civictech.cell.data.op

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.Serve
import civictech.cell.port.Subscribe
import civictech.gen.wire.CellBase
import java.io.Serializable
import java.util.*

/**
 * The adapter's port surface (spec 20/24 §Tagged maps + §Operator library, 96
 * §E1.5). Both payloads already exist — a [TaggedMapDelta] in, a [MapDelta]
 * out — so, exactly as for [civictech.cell.data.OrMapApi], there is no new
 * `@Contract` and no `gen/` descriptor work here.
 */
@CellBase
interface UntagApi<K, V> {
    val inlet: Serve<Propagate<TaggedMapDelta<K, V>>>
    val outlet: Subscribe<Propagate<MapDelta<K, V>>>
}

/**
 * **The G-23 adoption seam** (96 §E1.5, research `05-gap-mapping.md` §Gap 3):
 * a converged tagged map, spoken as the untagged [MapDelta] vocabulary the
 * whole deterministic join family already understands.
 *
 * G-23 — "delta merges are arrival-order biased; not replica-stable" — makes
 * every keyed edge into [CombineLatestCell]/[LookupJoinCell]/[JoinCell]/
 * [GroupByCell] single-writer-or-diverge. The prescribed fix is *not* to
 * rewrite each join: it is to feed convergent inputs to the deterministic
 * recompute cells. [civictech.cell.data.OrMapCell] supplies the convergence
 * (dots, add-wins membership, `[24-TMAP-03]` value resolution); this cell
 * projects that dot state down to the `puts`/`removals` vocabulary, so an
 * `OrMapCell → UntagCell → CombineLatestCell` chain is replica-stable while
 * the join itself is untouched (`[KE1-25]`, `[KE1-26]`).
 *
 * **Effective-only, presence-aware diffing** (21 effective-only emission).
 * The cell holds two things: the merged tag state — everything that has ever
 * arrived, folded by [TaggedMapDelta.merge] — and the *exposed map* it last
 * published. Each arriving delta merges into the tag state; the keys that
 * delta touched are then recomputed and diffed against the exposed map:
 *
 * - a key whose exposed value changed, or which just appeared, emits one put
 *   (`[KE1-18]`);
 * - a key whose **last live dot** died emits a removal — a key that still has
 *   a live dot after the tombstone emits a put with the surviving exposed
 *   value instead, never a removal (`[KE1-19]`, reset-remove's read side);
 * - a delta that leaves every touched key's presence **and** exposed value
 *   unchanged — a gossip echo, a re-delivered put, a tombstone for a dot
 *   already covered — emits nothing at all, and absorb-acks the swallowed
 *   wave (`[KE1-20]`, CP-A3, via [emitOrAbsorb]) so a downstream glitch-free
 *   join's per-source watermark still advances. This is [FilterCell]'s
 *   discipline verbatim.
 *
 * Presence and value are compared **separately**, never value alone:
 * [TaggedMapDelta.value] answers `null` both for an absent key and for a
 * present key whose exposed value is genuinely `null`, so on a `V` admitting
 * `null` a put that makes a key *appear* must still register as a change
 * (computenet-4d8k; the same guard [civictech.cell.data.view.TaggedMapView]
 * and [civictech.cell.data.view.MapView] carry).
 *
 * **The exposed value is [TaggedMapDelta.value], never a private
 * reimplementation** (feature decision j2x.3-D2). The adapter therefore
 * inherits the `[KE1-02]` embedded-[civictech.cell.MergeablePayload] fold and
 * the `[24-TMAP-03]` greatest-dot pick automatically, and adapter, view and
 * cell can never disagree about what a key exposes.
 *
 * **Re-put atomicity** (`[KE1-21]`). [civictech.cell.data.OrMapCell]'s `put`
 * ships the superseded dots' tombstones and the fresh dot in ONE
 * [TaggedMapDelta]; this cell emits exactly ONE [MapDelta] per input delta, so
 * that arrives downstream as a single put carrying the new value — never a
 * removal followed by a put, and no downstream fold ever observes the key
 * absent. [civictech.cell.data.KeyedSetCell]'s atomic retract+add invariant,
 * carried through the adapter.
 *
 * Note the boundary this pins on the way through: `OrMapCell.put` **always
 * mints**, even when the value is unchanged, because the fresh dot is the
 * evidence that wins a later `[24-TMAP-03]` comparison. That equal-value
 * re-put reaches this cell as a real delta with real new dots — and is
 * swallowed here, at the adapter, because the *exposed value* did not move.
 * Always-mint below, effective-only above.
 *
 * **Wave continuity by construction** (`[KE1-22]`, j2x.3-D4). The emission
 * happens inside the arriving message context — `outlet.call.propagate` from
 * within `onInlet` — so the [MapDelta] rides the arriving wave and this cell
 * originates none. The cell is **single-inlet**, so unlike a binary operator
 * it needs no wave-settling gate: there is exactly one input delta per wave
 * arrival and exactly one (or zero) emission for it, so the CC3/E2-SUITE
 * "ungated binary operator emits twice per wave" failure mode has no shape
 * here to occur in.
 *
 * **Stateful** (`[KE1-23]`). Both halves of the diff state are snapshotted —
 * the merged tag state and the exposed map — so a restored instance diffs
 * against what it had already published and does not re-emit the whole map as
 * novelty. Like the cell it adapts, the tag state grows monotonically:
 * reclamation of covered dots is KE3's (`[KE1-42]`), not this cell's.
 *
 * A late-linked consumer is seeded with the current exposed map as one
 * catch-up [MapDelta] ([catchUpOnLinked], G-22) — the same courtesy
 * [FilterCell] extends, and what keeps `UntagCell → MapView` usable when the
 * view links after the traffic.
 *
 * Not here: reclamation (KE3), bag/weighted semantics (96 §E6, 95 §R17), and
 * any change to the join family itself.
 */
class UntagCell<K, V>(ref: CellRef = CellRef(UUID.randomUUID())) :
    UntagCellBase<K, V>(ref), Stateful {

    /**
     * Everything that has arrived, folded by pointwise dot union. Needed in
     * full — not merely the exposed map — because deciding "did this key's
     * *last live dot* die" (`[KE1-19]`) is a question about dots, and because
     * a tombstone may arrive for a dot whose put has not yet been seen.
     *
     * ponytail: grows monotonically, exactly as `OrMapCell`'s does; compaction
     * is KE3 (G-25/G-42), deliberately not attempted here.
     */
    private var tags: TaggedMapDelta<K, V> = TaggedMapDelta()

    /** The map last published downstream — the diff baseline. */
    private val exposed = LinkedHashMap<K, V>()

    init {
        outlet.catchUpOnLinked {
            if (exposed.isEmpty()) null else MapDelta(LinkedHashMap(exposed), emptySet())
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun onInlet(value: TaggedMapDelta<K, V>) {
        val touched = value.keys()
        tags = tags.merge(value)

        val puts = LinkedHashMap<K, V>()
        val removals = LinkedHashSet<K>()
        touched.forEach { key ->
            val present = tags.liveDots(key).isNotEmpty()
            val wasPresent = exposed.containsKey(key)
            when {
                // presence is checked BEFORE the value, so a key that appears
                // with a genuinely null exposed value still emits a put.
                present -> {
                    val now = tags.value(key) as V
                    if (!wasPresent || exposed[key] != now) {
                        exposed[key] = now
                        puts[key] = now
                    }
                }
                // `[KE1-19]`: only here — the key's last live dot is gone.
                wasPresent -> {
                    exposed.remove(key)
                    removals += key
                }
            }
        }

        emitOrAbsorb(
            puts.isEmpty() && removals.isEmpty(),
            // ONE MapDelta for the whole input delta (`[KE1-21]`), inside the
            // arriving context (`[KE1-22]`).
            emit = { outlet.call.propagate(MapDelta(puts, removals)) },
            // nothing effective changed — ack the swallowed wave (CP-A3)
            absorbAck = { outlet.absorbAck() },
        )
    }

    /** The exposed map this cell has published — the read side of its diff state. */
    fun current(): Map<K, V> = LinkedHashMap(exposed)

    // snapshot/restore (G-25 seam, `[KE1-23]`): BOTH halves of the diff state.
    // The exposed map is derivable from the tag state, but it is what the diff
    // compares against, so it is persisted rather than recomputed — a restored
    // instance then answers "has this already been published?" from the same
    // bytes the original did.
    override fun snapshot(): Serializable = HashMap(
        mapOf(
            "tags" to tags,
            "exposed" to LinkedHashMap(exposed),
        )
    )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val saved = state as Map<String, Any>
        tags = saved.getValue("tags") as TaggedMapDelta<K, V>
        exposed.clear()
        exposed.putAll(saved.getValue("exposed") as Map<K, V>)
    }

    companion object {
        fun <K, V> create(): UntagApi<K, V> = UntagCell()
    }
}
