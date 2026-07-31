package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.Propagate
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Timestamp
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Shared rig for V1C-OPS's bounded-read suites — the harness style of
 * [OperatorAbsorbAckTest] (raw sources, real links, a [SimulationController] so
 * a page is an observable event) crossed with
 * `civictech.cell.host.BoundedStateReadTest`'s page/walk driver.
 *
 * Real links throughout, rather than direct `onLeft`/`onInlet` calls:
 * [QuorumSetCell] and [PresenceCountCell] only grow lanes from an `EdgeOpen`,
 * so a lane-bearing test needs genuine topology, and using it everywhere keeps
 * the wave-neutrality assertions meaningful.
 */
internal class OpRig {
    val controller = SimulationController()
    val host = ManagedHost(scheduler = controller.scheduler())

    fun <C : Cell> spawn(cell: C): C = cell.also { host.managementInlet.call.spawn(it) }

    fun settle() = controller.runToIdle()

    /** One page, driven the way a real caller drives it: submit, let the host run, read. */
    fun page(cell: Cell, request: StateRead): StatePage {
        val pending = host.readState(cell.ref, request)
        // routed, not executed inline: one page is one scheduler task on the
        // cell's own execution context, which has not run yet
        pending.isDone.shouldBeFalse()
        controller.runToIdle()
        val result = pending.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        result.shouldBeInstanceOf<StateReadResult.Page>()
        return result.page
    }

    /** Walk to completion, one page per `runToIdle()`, with [between] applied after each page. */
    fun walk(
        cell: Cell,
        limit: Int = 200,
        byteBudget: Int = 50_000,
        between: (Int) -> Unit = {},
    ): List<StatePage> {
        val pages = mutableListOf<StatePage>()
        var cursor: Cursor? = null
        var guard = 0
        do {
            val produced = page(cell, StateRead(cursor = cursor, limit = limit, byteBudget = byteBudget))
            pages += produced
            cursor = produced.next
            between(pages.size)
            check(guard++ < WALK_GUARD) { "walk did not terminate within $WALK_GUARD pages" }
        } while (cursor != null)
        return pages
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val WALK_GUARD = 2_000
    }
}

// ------------------------------------------------------------------ sources

/**
 * A hand-fed tagged-set producer with full control over tags. Typed `Any` so one
 * source drives every element type under test (including `Owned` payloads);
 * links are cast at the seam, as [OperatorAbsorbAckTest] does, because
 * `Propagate` erases identically either way.
 */
internal class SetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Any>>>())

    private val source = UUID.randomUUID()
    private var counter = 0L
    private val minted = mutableMapOf<Any, MutableSet<Timestamp>>()

    /**
     * Hand deltas straight to a served handler instead of over a link, for a
     * consumer whose link-time nature refuses a bare producer —
     * [MergeableGroupByCell] requires `MERGE_IDEMPOTENCE = IDEMPOTENT` and a raw
     * source offers `NON_IDEMPOTENT` (CP-F3), which its own existing suite works
     * around the same way (`inlet.call.propagate`).
     */
    private var direct: Propagate<SetDelta<Any>>? = null

    private fun emit(delta: SetDelta<Any>) {
        direct?.propagate(delta) ?: outlet.call.propagate(delta)
    }

    /** A fresh tag per element per call, so a remove-then-re-add really is a new tag. */
    fun add(vararg elements: Any) {
        val adds = elements.associateWith { setOf(Timestamp(source, ++counter)) }
        adds.forEach { (element, tags) -> minted.getOrPut(element) { mutableSetOf() } += tags }
        emit(SetDelta(adds = adds))
    }

    /** Retract exactly the tags this source minted — the effective-only del path. */
    fun remove(vararg elements: Any) {
        val dels = elements.mapNotNull { element -> minted.remove(element)?.let { element to it.toSet() } }.toMap()
        if (dels.isNotEmpty()) emit(SetDelta(dels = dels))
    }

    @Suppress("UNCHECKED_CAST")
    fun feed(inlet: Any) {
        outlet.linkTo(inlet as LinkFrom<Propagate<SetDelta<Any>>>)
    }

    @Suppress("UNCHECKED_CAST")
    fun feedDirect(inlet: FanInlet<*>) {
        direct = (inlet as FanInlet<Propagate<SetDelta<Any>>>).call
    }
}

/** A hand-fed single-writer map producer, typed `Any` for the same reason as [SetSource]. */
internal class MapSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<Any, Any>>>())

    fun put(vararg entries: Pair<Any, Any>) =
        outlet.call.propagate(MapDelta(entries.toMap(), emptySet()))

    fun removeKeys(vararg keys: Any) =
        outlet.call.propagate(MapDelta(emptyMap(), keys.toSet()))

    @Suppress("UNCHECKED_CAST")
    fun feed(inlet: Any) {
        outlet.linkTo(inlet as LinkFrom<Propagate<MapDelta<Any, Any>>>)
    }
}

// ----------------------------------------------------------------- decoders

internal fun List<StatePage>.allEntries(): List<OperatorEntry> =
    flatMap { it.entries }.filterIsInstance<OperatorEntry>()

internal fun List<StatePage>.of(subState: String): List<OperatorEntry> =
    allEntries().filter { it.subState == subState }

internal fun List<StatePage>.tagged(subState: String): List<TaggedEntry> =
    of(subState).filterIsInstance<TaggedEntry>()

internal fun List<StatePage>.keyed(subState: String): List<KeyedEntry> =
    of(subState).filterIsInstance<KeyedEntry>()

internal fun List<StatePage>.groups(subState: String): List<GroupEntry> =
    of(subState).filterIsInstance<GroupEntry>()

/** A tagged sub-state's union, in the shape `TagState.snapshot()` returns. */
internal fun List<StatePage>.taggedMap(subState: String): Map<Any?, Set<Timestamp>> =
    tagged(subState).associate { it.element to it.tags }

/** A map sub-state's union, in the shape the cell's `snapshot()` slot returns. */
internal fun List<StatePage>.keyedMap(subState: String): Map<Any?, Any?> =
    keyed(subState).associate { it.key to it.value }

/**
 * The `(subState, lane, key)` identity Decision A gives an entry — what "no
 * entry twice in one walk" is counted over, and what makes the same element in
 * `"left"`, `"right"` and `"ledger"` three things rather than one.
 */
internal fun OperatorEntry.identity(): Any = when (this) {
    is TaggedEntry -> Triple(subState, lane, element)
    is KeyedEntry -> Triple(subState, null, key)
    is GroupEntry -> Triple(subState, null, key)
}

/** The order the walk actually returned entries in, as identities. */
internal fun List<StatePage>.identities(): List<Any> = allEntries().map { it.identity() }

/** Slot [index] of a composite `snapshot()`'s `arrayListOf(...)`. */
internal fun Serializable.slot(index: Int): Any? = (this as List<*>)[index]

@Suppress("UNCHECKED_CAST")
internal fun Any?.asTagMap(): Map<Any?, Set<Timestamp>> = this as Map<Any?, Set<Timestamp>>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<Any?, Any?> = this as Map<Any?, Any?>
