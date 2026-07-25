package civictech.cell.port

import civictech.cell.Cell
import civictech.gen.wire.ContractRegistry
import civictech.gen.wire.NatureAxis
import civictech.gen.wire.NatureMismatch
import civictech.gen.wire.NatureVector
import java.util.Collections
import java.util.WeakHashMap

/**
 * CP-F3 — the whole type system in one pure function.
 *
 * Two port nature-vectors are [reconcile]d at link time. The result is *only*
 * ever [Direct] (compose) or [Refuse] (a loud, typed refusal): there is
 * **deliberately no `Adapt` arm, no planner, no auto-insertion**. The
 * COMPOSITION-PLAN gates adapter synthesis on post-CP-E2 evidence of repeated
 * manual adapter stacks, and the Phase-2 probe surfaced none — so the scoped
 * axes' only job is to turn today's *silent* mismatch drops into a link-time
 * refusal that names the offending axis.
 *
 * Direction: [offered] is the producer/outlet's vector, [required] is the
 * consumer/inlet's. An axis refuses iff the consumer *declares* a level the
 * producer cannot meet (`offered.rank < required.rank`) — a strictly stronger
 * producer always composes. Because the check reads only the consumer's
 * *explicitly declared* levels, a default (empty) requirement never refuses,
 * which is what preserves today's behavior verbatim on every existing link.
 */
sealed interface Reconciliation {
    /** Identical or subsumed natures — zero-cost, same as today's silent accept. */
    data object Direct : Reconciliation

    /** A scoped-axis conflict — the producer cannot satisfy the consumer. */
    data class Refuse(val mismatch: NatureMismatch) : Reconciliation
}

object NatureNegotiation {

    /**
     * Axes that are a **link-flow** property and can refuse a link. COLOR is
     * deliberately excluded: execution color is a *placement/co-host* property
     * (spec 32, validated at spawn by the `BlockingCell`/`SuspendingCell`
     * markers), and a link legitimately crosses colors (a blocking producer
     * feeding a pure consumer on another host is normal) — reconciling it at
     * the link would be a false refusal, not a real silent drop.
     */
    private val LINK_FLOW_AXES = setOf(
        NatureAxis.OWNERSHIP,
        NatureAxis.MERGE_IDEMPOTENCE,
        NatureAxis.MONOTONICITY,
    )

    fun reconcile(offered: NatureVector, required: NatureVector): Reconciliation {
        // Same-nature fast path: two default (empty) vectors are literally
        // today's behavior — a single reference/emptiness check, zero work.
        if (offered.isDefault && required.isDefault) return Reconciliation.Direct
        for ((axis, requiredLevel) in required.levels) {
            if (axis !in LINK_FLOW_AXES) continue
            val offeredLevel = offered.level(axis)
            if (offeredLevel.rank < requiredLevel.rank) {
                return Reconciliation.Refuse(NatureMismatch(axis, offeredLevel, requiredLevel))
            }
        }
        return Reconciliation.Direct
    }
}

/**
 * JVM-global weak port → [NatureVector] table, mirroring [PortIdentities]: the
 * KSP-generated descriptor is the authority, this only records the vector it
 * projected onto a live [Port] so the handshake can read it back off a bare
 * port. Absent ⇒ [NatureVector.DEFAULT] ⇒ today's behavior.
 */
internal object PortNatures {
    private val table = Collections.synchronizedMap(WeakHashMap<Port, NatureVector>())

    /**
     * Projects the generated [civictech.gen.wire.PortDescriptor.natures] of
     * [name] onto [port] when it is registered on a [Cell] with a generated
     * descriptor (CP-F2). Ports on a non-cell owner, or cells the processor
     * never saw, carry no descriptor and stay DEFAULT — [of] returns DEFAULT.
     */
    fun project(owner: Any?, name: String, port: Port) {
        if (owner !is Cell) return
        val natures = ContractRegistry.cellDescriptor(owner.javaClass)
            ?.ports?.firstOrNull { it.name == name }
            ?.natures ?: return
        if (!natures.isDefault) table[port] = natures
    }

    /** Test/infra seam: stamp a vector directly onto a port. */
    fun stamp(port: Port, natures: NatureVector) {
        if (natures.isDefault) table.remove(port) else table[port] = natures
    }

    fun of(port: Port): NatureVector = table[port] ?: NatureVector.DEFAULT
}

/** The declared natures projected onto this port (CP-F2/F3), or DEFAULT. */
val Port.natures: NatureVector get() = PortNatures.of(this)
