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

/** One source cell's ordered event log. Position in [events] is the source's arrival order. */
data class SourceScript(
    val source: SourceId,
    val events: List<ScriptEvent>,
) : Serializable

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
