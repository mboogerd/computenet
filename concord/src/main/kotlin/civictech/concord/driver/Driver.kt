package civictech.concord.driver

import civictech.concord.value.Value

/**
 * The Concord driver SPI (§1.4) — the entire per-implementation surface. ~12
 * verbs plus their result types; nothing here imports `civictech.cell.*`, and
 * nothing here presupposes a transport. Binding #1 (in-process, W1-A) implements
 * this in `civictech.concord.driver.kernel` — the only package allowed to touch
 * kernel types. Binding #2 (cross-process, W5) is the same verbs as JSON-lines
 * over stdio.
 *
 * Vocabulary is neutral by construction: cells are named by catalog id (a driver
 * binds id → its own cell), values are the JSON-shaped [Value] model, and every
 * verb is boundary-observable (Concord P1). Handles ([CellId], [HostId],
 * [LinkRef]) are opaque strings the harness threads through from the scenario.
 */
interface Driver {

    /** Create a host. Single-host scenarios may rely on an implicit default host. */
    fun createHost(hostId: HostId)

    /**
     * Construct a cell of catalog [type] with [params] (the [CellSpec]'s
     * descriptor fields — `of`, `fn`, `glitch-free`, …) on [hostId], under the
     * scenario-local [cellId].
     */
    fun spawn(hostId: HostId, cellId: CellId, type: String, params: Map<String, Value>)

    /**
     * Add a link. [outlet]/[inlet] default to the cells' primary ports; [role]
     * selects consume vs observe. The [LinkResult] carries the admission outcome
     * so a scenario can assert `expect: rejected` without inspecting internals.
     */
    fun connect(
        from: CellId,
        to: CellId,
        inlet: String? = null,
        outlet: String? = null,
        role: String? = null,
    ): LinkResult

    /** Remove the link previously returned as [LinkResult.Connected.ref]. */
    fun disconnect(linkRef: LinkRef): LinkResult

    /** Apply operation [op] (with optional [value]) to [cellId]. */
    fun apply(cellId: CellId, op: String, value: Value? = null)

    /** Drive the graph to quiescence within [budget] steps; report what settled. */
    fun quiesce(budget: Int): QuiesceReport

    /** The current materialized value of a view cell. */
    fun readView(cellId: CellId): Value

    /** The ordered stream of values a view observed (glitch-freedom / monotonicity checks). */
    fun observationLog(cellId: CellId): List<Value>

    /** Capture [cellId]'s state as an opaque blob. */
    fun snapshot(cellId: CellId): Blob

    /** Re-materialize [cellId] on [hostId] from a [blob]. */
    fun restore(hostId: HostId, cellId: CellId, blob: Blob)

    /** Gracefully retire [cellId], unlinking it. */
    fun despawn(cellId: CellId)

    /** All dead letters across all hosts (the `no-dead-letters` check reads this). */
    fun deadLetters(): List<DeadLetter>

    /** The effect log an effectful [cellId] produced (the `effect-count` check reads this). */
    fun effectLog(cellId: CellId): List<Effect>
}

/** Opaque scenario-local cell handle. */
typealias CellId = String

/** Opaque host handle. */
typealias HostId = String

/** Opaque link handle returned by [Driver.connect], consumed by [Driver.disconnect]. */
typealias LinkRef = String

/** Opaque snapshot payload; only the same driver interprets it. */
typealias Blob = ByteArray

/** The outcome of a [Driver.connect]/[Driver.disconnect] (§1.4 inline `expect:`). */
sealed interface LinkResult {
    /** The link was admitted; [ref] identifies it for a later [Driver.disconnect]. */
    data class Connected(val ref: LinkRef) : LinkResult

    /** The link was refused by an admission policy; [reason] is stated but never asserted on (P4). */
    data class Rejected(val reason: String) : LinkResult
}

/**
 * The result of a [Driver.quiesce] barrier. [settled] is true when the graph
 * reached idle within budget; [steps] is the work performed (diagnostic, never a
 * conformance assertion — P1/P4). [parked] flags a genuine deadlock (a suspended
 * task with no work left, per `SimulationController`).
 */
data class QuiesceReport(
    val settled: Boolean,
    val steps: Int,
    val parked: Boolean = false,
)

/** A dead letter: an undeliverable/rejected message observed at a host boundary. */
data class DeadLetter(
    val host: HostId?,
    val cell: CellId?,
    val reason: String,
)

/**
 * One recorded effect of an effectful sink (durability dedup). [key] is the
 * dedup key the `effect-count` check groups by; [payload] is the effect value.
 */
data class Effect(
    val key: String?,
    val payload: Value,
)
