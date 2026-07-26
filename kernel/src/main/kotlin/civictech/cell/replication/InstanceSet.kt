package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.Propagate
import civictech.cell.data.Replicable
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.NatureNegotiation
import civictech.cell.port.Reconciliation
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.catchUpOnLinked
import civictech.cell.port.registerPort
import java.io.Serializable

/**
 * One instance's interest assignment (PN-6, spec 40/42 §Interest-scoped instance
 * sets): the [interest] it is assigned at routing [epoch]. This is the value of
 * the per-instance max-register the [InstanceSet] holds, and — carried on a
 * journaled, ref-addressed hosted invocation to a
 * [civictech.cell.data.ShardCell]'s `assignInlet` — the durable, replayable form
 * of an interest reassignment. Before PN-6 the router narrowed a shard's interest
 * by a direct in-process call: unjournaled, so a non-checkpointed shard's shed
 * was invisible to recovery (PN-4's residual). An `Assignment` invocation lands
 * in the shard host's WAL, so replay re-applies the shed — the shed is now
 * journaled-durable.
 *
 * [interest] is [@Polymorphic] so it rides the wire codec's polymorphic value
 * channel (the same channel a routed `SetDelta` uses); every [Interest] arm is a
 * registered `@Serializable` subclass (see `WireCodec`).
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("Assignment")
data class Assignment(
    @kotlinx.serialization.Polymorphic val interest: Interest,
    val epoch: Long,
) : Serializable

/**
 * The gossip lattice delta of an [InstanceSet] (PN-6): the per-instance
 * assignments a peer learned, merged pointwise by [InstanceSet.merge]. Idempotent
 * under the default epoch-max merge (a re-delivered delta changes nothing), so it
 * gossips over the ordinary replica mesh with no second protocol — echoes die out
 * exactly as a `SetDelta`'s tag union makes them.
 */
data class AssignmentDelta(val entries: Map<CellRef, Assignment>) : Serializable

/**
 * The interest-assignment table as a **[Replicable] max-register lattice** (PN-6,
 * plan §3 "assignment as a hosted Replicable lattice"): one entry per instance
 * `ref`, its value the [Assignment] `(interest, epoch)`. Its epoch-max [merge]
 * *is* the admission rule the single routing authority applies when it reassigns
 * interest (via the journaled [Assignment] invocations to each shard's
 * `assignInlet`), and its overlap predicate ([overlapCount]) is the one the
 * gossip linker ([Replication.maybeLink]) realizes — replication and partitioning
 * are the same instance set under different interest assignments (spec 42), so
 * there is one assignment model, not two.
 *
 * Scope (PN-6): under the single-routing-authority window the router and linker
 * still derive *live* interest from the registry / per-shard register; a runtime
 * lattice that every peer reads and reassigns concurrently is the **leaderless
 * R1** case, out of scope (see spec §Interest-scoped instance sets). This class
 * is therefore the assignment *lattice model* — the oracle the substrate tests
 * check the operational router/linker against, and the convergent form a future
 * leaderless instance set would gossip — not a struct read on the live path today.
 *
 * **Admission rule** (the same everywhere, plan §3): a delivered assignment whose
 * epoch is *older* than what we hold is filtered through the current interest (it
 * cannot widen a shed range back); one whose epoch is *newer* is adopted, then
 * applied. [merge] encodes this. The default merge is a genuine join-semilattice
 * value — commutative, idempotent, associative — so the table converges
 * regardless of delivery order; the non-commutative [lastWriterWins] merge is the
 * control that makes order matter (a flip then forks by apply order).
 *
 * The register gossips as a [Replicable]: its effective merges re-emit on
 * [outlet] and peers' deltas merge on [deltaInlet], over the existing mesh.
 */
class InstanceSet(
    override val ref: CellRef,
    private val merge: (current: Assignment?, incoming: Assignment) -> Assignment = ::epochMaxUnion,
) : Cell, Stateful, Replicable<AssignmentDelta> {

    private val table = mutableMapOf<CellRef, Assignment>()

    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<AssignmentDelta>>())
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<AssignmentDelta>>())

    init {
        deltaInlet.serve(Propagate<AssignmentDelta> { delta -> onGossip(delta) })
        // late-join catch-up (G-22): the whole table as a delta-from-empty
        outlet.catchUpOnLinked { if (table.isEmpty()) null else AssignmentDelta(table.toMap()) }
    }

    /**
     * Assign [interest] to instance [ref] at routing [epoch] (a local write). The
     * merge admission rule decides whether it takes; if it changes the register it
     * re-emits on [outlet] so peers converge.
     */
    fun assign(ref: CellRef, interest: Interest, epoch: Long): Boolean =
        applyOne(ref, Assignment(interest, epoch))

    /** The interest instance [ref] is currently assigned (or [Interest.Total] when unknown). */
    fun interestOf(ref: CellRef): Interest = table[ref]?.interest ?: Interest.Total

    /** The routing epoch instance [ref] is currently at (or 0 when unknown). */
    fun epochOf(ref: CellRef): Long = table[ref]?.epoch ?: 0L

    /** The whole assignment table (the router's O(instances) routing state — no per-element ledger). */
    fun entries(): Map<CellRef, Assignment> = table.toMap()

    /** Ordered instance pairs whose assigned interests overlap — the links the gossip linker forms. */
    fun overlapCount(): Int {
        val refs = table.keys.toList()
        var n = 0
        for (i in refs.indices) for (j in refs.indices) {
            if (i != j && table.getValue(refs[i]).interest.overlaps(table.getValue(refs[j]).interest)) n++
        }
        return n
    }

    /**
     * PN-18: this assignment is **disjoint** iff no two instances' interests
     * overlap — the partitioning setting (each key covered by at most one
     * instance). The predicate the SPSC corollary consults at formation.
     */
    fun isDisjoint(): Boolean = overlapCount() == 0

    /**
     * PN-18 (spec 23 §SPSC corollary): admit an exclusive-carrying [routed] port
     * into this instance set. Delegates to the OWNERSHIP refusal rule — admitted
     * ([Reconciliation.Direct]) under a [disjoint][isDisjoint] assignment, refused
     * (typed, naming `OWNERSHIP`) under a Total/overlapping one; a `SHARED`
     * (DEFAULT) port always composes.
     */
    fun admitExclusive(routed: civictech.gen.wire.NatureVector): Reconciliation =
        NatureNegotiation.admitToInstanceSet(routed, isDisjoint())

    private fun onGossip(delta: AssignmentDelta) {
        val effective = mutableMapOf<CellRef, Assignment>()
        delta.entries.forEach { (ref, a) -> if (applyOne(ref, a)) effective[ref] = table.getValue(ref) }
        if (effective.isNotEmpty()) outlet.originate { propagate(AssignmentDelta(effective)) }
    }

    private fun applyOne(ref: CellRef, incoming: Assignment): Boolean {
        val current = table[ref]
        val merged = merge(current, incoming)
        if (merged == current) return false
        table[ref] = merged
        return true
    }

    override fun snapshot(): Serializable = HashMap(table)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        table.clear()
        table.putAll(state as Map<CellRef, Assignment>)
    }

    companion object {
        /**
         * The default join-semilattice merge (PN-6 admission rule): a strictly
         * newer epoch is adopted; an older epoch is *dropped* (it cannot resurrect
         * a shed range — the current, higher-epoch interest already reflects the
         * shed); an equal epoch unions the interests (commutative, idempotent,
         * associative — order cannot change the result).
         */
        fun epochMaxUnion(current: Assignment?, incoming: Assignment): Assignment = when {
            current == null -> incoming
            incoming.epoch > current.epoch -> incoming
            incoming.epoch < current.epoch -> current
            current.interest == incoming.interest -> current
            else -> Assignment(canonicalUnion(current.interest, incoming.interest), current.epoch)
        }

        /**
         * The commutative join of two interests: a [Interest.Union] of the flattened,
         * de-duplicated, canonically-ordered members. Order-independent so the
         * equal-epoch merge is a genuine semilattice value — `merge(a, b) ==
         * merge(b, a)` (a plain `Union(listOf(a, b))` would fork on list order).
         */
        private fun canonicalUnion(a: Interest, b: Interest): Interest {
            fun flatten(i: Interest): List<Interest> =
                if (i is Interest.Union) i.members.flatMap(::flatten) else listOf(i)
            return Interest.Union((flatten(a) + flatten(b)).distinct().sortedBy { it.toString() })
        }

        /**
         * The non-commutative control merge (control b): the incoming assignment
         * always wins, ignoring epoch. Delivery order then determines the final
         * interest, so two peers seeing the same assignments in different orders
         * fork — the merge is load-bearing.
         */
        fun lastWriterWins(@Suppress("UNUSED_PARAMETER") current: Assignment?, incoming: Assignment): Assignment = incoming
    }
}
