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

    /**
     * Read **one bounded page** of [cellId]'s own state (spec 21 §Pull,
     * `[21-PULL-02]`/`[21-PULL-03]`; spec 24 `[24-BOUND-01]`/`[24-BOUND-02]`) —
     * resuming from [cursor] (`null` starts a fresh walk), returning at most
     * [limit] **whole** entries.
     *
     * This is not [snapshot] and not [readView]. [snapshot] captures an opaque,
     * restorable blob no check can inspect; [readView] reads a *view* cell's
     * settled fold, which exists only where a scenario linked one. A bounded
     * read is the instrument-facing read of a **state** cell: it is answered
     * without emitting, without linking, and without advancing the reader's or
     * the read cell's wave plane (see [wavePlane]), and it is bounded in size,
     * so reading a large cell is affordable.
     *
     * The scenario language never threads a cursor: a `read-state` step is a
     * *whole walk*, and the harness loops this verb until [ReadPage.next] is
     * null. A driver that cannot serve a bounded read of the named cell must
     * fail loudly rather than answer a whole copy — silently widening a bound
     * is the one failure the primitive exists to prevent.
     */
    fun readState(cellId: CellId, cursor: ReadCursor?, limit: Int): ReadPage

    /**
     * The **wave plane** [cellId] has reached: for every wave source visible at
     * that cell, the highest wave position that source has minted there (spec
     * 20/22 §Structural changes — wave ids are per-source monotonic counters
     * stamped by the emitting outlet).
     *
     * Only *equality of two readings* is ever asserted (`wave-plane-unchanged`);
     * the source handles are opaque and their values are never a golden, so
     * nothing about an implementation's identifiers, scheduling or frames leaks
     * into a check. Any implementation of this model already keeps this
     * bookkeeping — it is what stamps a delivery and what decides wave
     * completeness — so reporting it is not a kernel-specific capability.
     */
    fun wavePlane(cellId: CellId): WavePlane

    /** Capture [cellId]'s state as an opaque blob. */
    fun snapshot(cellId: CellId): Blob

    /** Re-materialize [cellId] on [hostId] from a [blob]. */
    fun restore(hostId: HostId, cellId: CellId, blob: Blob)

    /**
     * Restart [cellId]: recover it from its **freshest available checkpoint**
     * and reconcile its downstream consumers with the recovered state (spec 21
     * §RESTART re-baselines, `[21-REBASE-01]`; spec 30/31 rule 5; spec 20/24
     * §Tag continuity).
     *
     * A restart is *restore + re-baseline*, never a bare local rollback. Three
     * things are required of it, all boundary-observable:
     *
     * 1. the cell's state reverts to the recovered checkpoint;
     * 2. its outlets **succeed their emission epochs** — a post-restart wave
     *    position or merge tag never aliases a pre-restart one (spec 20/22
     *    §Source identity);
     * 3. the recovered state is re-announced downstream over the ordinary
     *    catch-up path, carrying the superseded epochs, so a convergent
     *    consumer drops what the restart did not re-assert and rejects later
     *    deltas from the superseded epochs.
     *
     * This is not [restore]. [restore] re-materializes a cell from a blob a
     * scenario captured with [snapshot] — a state-plane operation with no
     * downstream announcement, which is exactly right for despawn/migration and
     * exactly wrong here. A restart names no blob: which checkpoint is
     * "freshest" is the implementation's (durable tail, imported baseline,
     * peer catch-up, or the local one), and only the reconciliation is asserted.
     *
     * How the restart is *induced* is likewise the implementation's: this verb
     * asks for the recovery, not for a particular way of failing. A driver that
     * cannot restart the named cell must fail loudly rather than quietly
     * perform a plain [restore] — an unannounced rollback is precisely the
     * behaviour this verb exists to distinguish itself from.
     */
    fun restart(cellId: CellId)

    /**
     * Re-deliver [op] (with optional [value]) to [cellId]'s [inlet] **live**,
     * under the explicit wave position `([source], [counter])` — a duplicate
     * arrival of a message that inlet may already have processed (spec 24
     * `[24-DUR-05]`'s live half; `[24-DUR-02]`'s checkpoint-frontier half).
     *
     * This is not [apply]. [apply] drives an op through a cell's own outlet
     * along the graph's links, and the driver mints the next wave position for
     * that outlet in sequence; a duplicate can therefore never be expressed
     * that way. Here the position is **stated**, and the delivery is injected
     * at the named inlet directly — a re-arrival of the same message rather
     * than a second op newly driven through the topology.
     *
     * [source] is a scenario-local cell id, not an identifier the scenario
     * invents: the driver resolves it to that cell's own per-source wave
     * identity (spec 20/22 §Structural changes — wave ids are per-source
     * monotonic counters minted by the emitting outlet) and stamps the
     * delivery with `(that identity, counter)`. So the injected delivery
     * carries exactly what an ordinary delivery from [source] would have
     * carried, and no driver is asked to retain a log of prior invocations —
     * everything the position needs is in the step.
     *
     * [baseline] is the **optional catch-up anchor** (`computenet-yh6.1.12`).
     * Omitted — `null`, the default — the delivery is an ordinary live frame
     * and this verb means exactly what it meant before the parameter existed.
     * Present, the delivery is stamped as a **catch-up baseline**: the frame a
     * late-joining consumer receives in answer to a pull, which spec 24
     * §Effectful (`[24-DUR-07]`/`[24-DUR-08]`) gives its own rule — an
     * `Effectful` inlet acts on it, its timestamp never advances the
     * processed-frontier, and its exact position is recorded separately so a
     * replay or a live re-delivery of that same position is suppressed without
     * re-firing.
     *
     * Its shape is a **merge-tag frontier**: scenario-local cell ids (resolved
     * the same way [source] is — no scenario ever invents an implementation
     * identifier) mapped to tag counters. Its *contents* are stated so the
     * anchor is well-formed and the run reproducible; nothing asserts them.
     * What is asserted is the frame's *kind* — a receiver keys on a baseline
     * being present, and on the frame's own `([source], [counter])` position.
     * A driver whose model has no catch-up baseline at all must fail loudly on
     * a step that names one rather than deliver a plain live frame, by the same
     * rule as every other refusal here.
     *
     * A conforming driver **fails loudly** rather than approximate this: a
     * delivery that reaches the inlet without the stated position, or that
     * routes through the graph instead of injecting, asserts nothing about a
     * processed-frontier and would make a scenario built on it read as
     * coverage it does not have. Which cells can receive one is a driver
     * capability like any other (see [effectLog]); a target it cannot inject
     * at is an authoring error to report, never a silently weaker delivery.
     */
    fun retransmit(
        cellId: CellId,
        inlet: String?,
        source: CellId,
        counter: Long,
        op: String,
        value: Value? = null,
        baseline: Map<CellId, Long>? = null,
    )

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

/**
 * Opaque resume token minted by [Driver.readState]; only the same driver
 * interprets one, exactly as with [Blob]. The harness threads it back verbatim
 * and never inspects it — a scenario cannot name a cursor at all.
 */
typealias ReadCursor = Any

/**
 * One page of a bounded state read ([Driver.readState]).
 *
 * @property entries the page's whole entries, in the cell's own enumeration
 *   order. Never a partial entry, and never the same entry twice within one
 *   walk (spec 24 `[24-BOUND-01]`).
 * @property next the resume token for the following page; `null` — and only
 *   `null` — terminates a walk. A page may be short, or even empty, and still
 *   carry a non-null [next].
 * @property frontier the cell's tag frontier as a **stamp**: an opaque string
 *   whose only asserted property is equality with another page's stamp from the
 *   same walk. `null` for a state family that carries no tag frontier at all,
 *   which is itself the signal that the stability check of `[21-PULL-03]` is
 *   unavailable for that family.
 * @property exclusivesElided how many entries were replaced by a presence
 *   descriptor because their value is an exclusive payload that may not be
 *   copied (spec 23). A non-zero count is an honest signal, never a silent gap.
 */
data class ReadPage(
    val entries: List<ReadEntry>,
    val next: ReadCursor? = null,
    val frontier: String? = null,
    val exclusivesElided: Int = 0,
)

/**
 * One whole entry of a bounded read, in the neutral value model.
 *
 * @property key the entry's identity within the cell's state — the element of a
 *   set, the key of a map. Duplicate detection across a walk is by this.
 * @property value what the state associates with [key], or `null` when the key
 *   *is* the state (a set has no separate value component).
 * @property present whether this entry contributes to the cell's current state.
 *   A convergent state family pages entries its own algebra has retracted — a
 *   tombstoned set element is a real entry with a real tag set — so a walk's
 *   union is only comparable with a fold once the retracted entries are dropped.
 */
data class ReadEntry(
    val key: Value,
    val value: Value? = null,
    val present: Boolean = true,
)

/**
 * The wave plane a cell has reached ([Driver.wavePlane]): opaque source handle →
 * the highest wave position that source has minted at that cell.
 *
 * Compared only against another reading of the same cell's plane. Two readings
 * that differ mean something was emitted between them; two equal readings mean
 * nothing was.
 */
data class WavePlane(val positions: Map<String, Long>)

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
