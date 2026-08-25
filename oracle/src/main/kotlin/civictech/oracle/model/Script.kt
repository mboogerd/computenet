package civictech.oracle.model

import java.io.Serializable

/**
 * The input side of a differential case: what every source cell was told to do, as data.
 *
 * A script is the *only* input `[ORA1-MODEL-01]` allows the reference model — the model
 * computes every terminal's state from a script alone, executing no kernel cell. It is also
 * what the generator feature (computenet-4ru.6) emits, which is why these types live in
 * `civictech.oracle.model` and are [Serializable]: a recorded case has to survive a JVM
 * boundary and a shrink loop without the kernel being involved.
 *
 * `[ORA1-MODEL-10]` binds this file: no `civictech.cell.data.op` type and no concrete
 * data-cell class appears here, and — a stronger property the whole package holds — no tag,
 * tag count, wave id or `SetDelta` internal does either (`[ORA1-MODEL-03]`). A script names
 * *what a writer asked for*, never how the kernel represented it.
 *
 * ## Shape
 *
 * A script is a set of per-source ordered event logs ([SourceScript]). Order matters
 * **within** a source and is meaningless across sources: a kernel source cell is a single
 * serialization point, so its own slice is exactly its arrival order, while two independent
 * sources are genuinely concurrent and the model must not invent an interleaving for them.
 *
 * ## Observation
 *
 * Every event names the [WriterId] that issued it, and a source's log may carry explicit
 * [ScriptEvent.Observe] events. That pair is what makes observed-remove semantics
 * (`[ORA1-MODEL-04]`, `[24-SET-01]`/`[24-SET-03]`) expressible on the script *without* tags:
 * see [Membership] for the exact rule.
 */
data class Script(
    /** One ordered log per source, in a deterministic order of their own. */
    val slices: List<SourceScript>,
) : Serializable {

    init {
        val duplicated = slices.groupingBy { it.source }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "A script holds at most one slice per source; duplicated: ${duplicated.map { it.id }.sorted()}"
        }
        val sizes = slices.associate { it.source to it.events.size }
        slices.forEach { slice ->
            slice.deliveries.forEach { delivery ->
                require(delivery.from != slice.source) {
                    "Source '${slice.source.id}' cannot be delivered its own emissions; " +
                        "an instance observes its own writes at issue time"
                }
                require(delivery.afterEvents <= slice.events.size) {
                    "Delivery into '${slice.source.id}' names afterEvents=${delivery.afterEvents}, " +
                        "past the end of its ${slice.events.size}-event log"
                }
                val emitted = sizes[delivery.from]
                require(emitted != null || delivery.throughEvents == 0) {
                    "Delivery into '${slice.source.id}' names sender '${delivery.from.id}', " +
                        "which this script does not drive, through ${delivery.throughEvents} events"
                }
                require(emitted == null || delivery.throughEvents <= emitted) {
                    "Delivery into '${slice.source.id}' names ${delivery.throughEvents} events of " +
                        "'${delivery.from.id}', whose log holds only $emitted"
                }
            }
        }
    }

    private val bySource: Map<SourceId, SourceScript> = slices.associateBy { it.source }

    /**
     * [source]'s log, or an **empty** log if the script says nothing about it.
     *
     * Absence is not an error: a graph may name a source the script never drove, and the
     * honest answer for it is the empty fold (an empty set, a zero counter), not a failure.
     */
    fun slice(source: SourceId): SourceScript = bySource[source] ?: SourceScript(source, emptyList())

    /** Every source this script drives, in slice order. */
    fun sources(): List<SourceId> = slices.map { it.source }

    companion object {
        val EMPTY: Script = Script(emptyList())

        /** A script over one source — the shape most single-source unit tests want. */
        fun of(source: SourceId, vararg events: ScriptEvent): Script =
            Script(listOf(SourceScript(source, events.toList())))
    }
}

/**
 * One source cell's ordered event log. Position in [events] is the source's arrival order.
 *
 * [deliveries] is the **second lane** ORA2 adds (`ORA2 §MODEL-06`): the gossip this instance
 * absorbed from its peers, and the only thing that ever advances what it has observed. It is
 * deliberately *not* a [ScriptEvent] variant. A `ScriptEvent` is "one thing a writer asked a
 * source cell to do" — an inlet call — and a delivery is nothing of the kind: it is a
 * replication event, arriving on `deltaInlet`, that no writer issued. Keeping the two lanes
 * apart also keeps `ScriptEvent`'s existing `when`s exhaustive without touching them.
 *
 * Empty for every ORA1 case, which is why the parameter carries a default: a non-replicated
 * script is one whose instances never hear from each other.
 */
data class SourceScript(
    val source: SourceId,
    val events: List<ScriptEvent>,
    val deliveries: List<Delivery> = emptyList(),
) : Serializable

/**
 * One gossip delivery into the slice that carries it: *"after my first [afterEvents] own
 * events, I absorbed everything [from] had emitted through its first [throughEvents] events."*
 *
 * This is the script-level statement of causality that `ORA2 §MODEL-06` requires and the
 * whole reason the dot model can be a *second implementation* rather than a mirror: the model
 * learns what a replica had seen from the script, never by reading a kernel cell, a delta, or
 * a `Timestamp`. It is the cross-instance sibling of [ScriptEvent.Observe], which states
 * observation *within* one slice for one writer and cannot reach another source at all.
 *
 * Both indices are **event counts, not positions**: `afterEvents = 0` is "before my first
 * event", `throughEvents = 0` is "nothing of theirs yet". Counting rather than pointing is what
 * makes the referenced prefix well defined without a global interleaving — a [Script] orders
 * events *within* a slice only ([Script]'s KDoc), so "everything they had emitted so far" would
 * otherwise name nothing.
 *
 * Two deliveries into one slice at one [afterEvents] are unordered with respect to each other,
 * and a conforming model must be indifferent to that: merge is commutative and associative
 * (`ORA2 §MODEL-02`).
 *
 * A delivery may name a prefix that itself contains deliveries, so the relation is transitive
 * — that is exactly a multi-hop mesh. It must not be **cyclic**: two slices that each claim to
 * have absorbed a prefix of the other containing that very claim describe no reachable state,
 * and [DotModel] refuses such a script by name rather than folding it to something plausible.
 */
data class Delivery(
    /** How many of the receiving slice's own events had been applied when this arrived. */
    val afterEvents: Int,
    /** The instance whose emissions arrived. */
    val from: SourceId,
    /** How many of [from]'s own events had been applied at the moment it emitted them. */
    val throughEvents: Int,
) : Serializable {
    init {
        require(afterEvents >= 0) { "Delivery.afterEvents must not be negative, got $afterEvents" }
        require(throughEvents >= 0) { "Delivery.throughEvents must not be negative, got $throughEvents" }
    }
}

/**
 * A source cell's name in the script. A plain wrapper rather than a raw `String` so a
 * source id and a writer id can never be passed for one another.
 */
data class SourceId(val id: String) : Serializable {
    init {
        require(id.isNotBlank()) { "SourceId must not be blank" }
    }
}

/**
 * Who issued an event. Writers are the *causal* actors of `[ORA1-MODEL-04]`: a remove
 * retracts only the adds its writer had observed, so two writers into one source is exactly
 * the BS-2 configuration.
 */
data class WriterId(val id: String) : Serializable {
    init {
        require(id.isNotBlank()) { "WriterId must not be blank" }
    }
}

/**
 * One thing a writer asked a source cell to do.
 *
 * Element, key and value payloads are `Any?` on purpose, mirroring [ElementShape]'s decision
 * to be structural and untyped in the element domain: a script says "add this value", not
 * "add this `String`". Typing the script would make every registration a type-level
 * negotiation with the generator, which is exactly the generator edit `[ORA1-API-03]` exists
 * to avoid. Payloads must be `equals`/`hashCode`-sound, because membership is set membership.
 */
sealed interface ScriptEvent : Serializable {

    /** The writer that issued this event. */
    val writer: WriterId

    /** `SetOps.add(element)`. The issuing writer observes its own add at issue time. */
    data class Add(override val writer: WriterId, val element: Any?) : ScriptEvent

    /**
     * `SetOps.remove(element)`. Retracts only the adds of [element] this [writer] had
     * observed at this position; an add it had not observed survives (`[ORA1-MODEL-05]`,
     * `[24-SET-03]`), and a remove that observed no add at all is a no-op.
     */
    data class Remove(override val writer: WriterId, val element: Any?) : ScriptEvent

    /**
     * "[writer] has now observed every add present at this position of this source's log."
     *
     * This is the script-level stand-in for delta delivery, and it is the whole reason the
     * model needs no tags: causality is *stated* rather than reconstructed from tag sets
     * (`[ORA1-MODEL-03]`). A writer always observes its own adds without one.
     */
    data class Observe(override val writer: WriterId) : ScriptEvent

    /** `KeyedSetOps.put(key, element)` — a keyed upsert; re-putting a key replaces its element. */
    data class Put(override val writer: WriterId, val key: Any?, val element: Any?) : ScriptEvent

    /** `KeyedSetOps.remove(key)` — drops whatever element [key] currently binds. */
    data class RemoveKey(override val writer: WriterId, val key: Any?) : ScriptEvent

    /** `CounterOps.increment(amount)` / `PnCounterOps.increment(amount)`. */
    data class Increment(override val writer: WriterId, val amount: Long) : ScriptEvent

    /**
     * `CounterOps.decrement(amount)` / `PnCounterOps.decrement(amount)`.
     *
     * The kernel's `CounterCell` implements `decrement(a)` as `increment(-a)`
     * (kernel/src/main/kotlin/civictech/cell/data/CounterCell.kt), so it is a distinct event
     * here only so a script reads like the calls that produced it.
     */
    data class Decrement(override val writer: WriterId, val amount: Long) : ScriptEvent
}
