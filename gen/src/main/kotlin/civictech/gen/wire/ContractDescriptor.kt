package civictech.gen.wire

/**
 * Wire identity of one contract method:
 * `methodId = StableHash.of("<contract fqn>#<name><jvmDescriptor>")` —
 * derived from name + erased signature, so it survives method reordering
 * and needs no cross-module coordination.
 */
data class MethodDescriptor(
    val methodId: Long,
    val name: String,
    /** Erased JVM signature, e.g. `(Ljava/lang/Object;)V` — the stable half of identity. */
    val jvmDescriptor: String,
    /** Ownership slot (G-21): true when a parameter type carries exclusive ownership. */
    val exclusive: Boolean = false,
    /** A parameter implements the cycle-throttling Magnitude contract. */
    val magnitude: Boolean = false,
    /** A parameter declares the idempotent Replicable merge class. */
    val idempotentMerge: Boolean = false,
    /** Index of the @Key argument, or -1 for an unkeyed/broadcast invocation. */
    val keyIndex: Int = -1,
)

/** Wire identity of a contract: `contractId = StableHash.of(fqn)`. */
data class ContractDescriptor(
    val contractId: Long,
    val fqn: String,
    /** Management contracts may return/block; data contracts are push-only (spec 12). */
    val management: Boolean,
    /** World-touching boundary contract (orthogonal to management/data). */
    val effect: Boolean = false,
    val methods: List<MethodDescriptor>,
)

enum class CellColor { PURE, BLOCKING, SUSPENDING }

/**
 * The irreducible composition axes (COMPOSITION-PLAN.md, Appendix B §"The
 * irreducible remainder"): the ~4 natures whose mismatch fails *silently* today
 * and so must become a loud typed refusal — nothing here is adaptable, so there
 * is deliberately no negotiation/auto-insertion vocabulary. Every other axis
 * from Appendix A (consistency, delivery, durability, replication, partition,
 * locality) was found *dissolvable* by the Phase-2 probe and is out of scope.
 */
enum class NatureAxis {
    OWNERSHIP, MERGE_IDEMPOTENCE, COLOR, MONOTONICITY,
    /**
     * PN-12 refusing axis. A producer participates in the wave protocol (stamps
     * aligned waves) or does not. An ALIGN-tier (`WaveFrontier`) inlet requires
     * WAVED — an UNWAVED producer streamed onto it is dropped *silently* today
     * (plan §2 F1); as a link-flow axis it becomes a loud typed refusal.
     */
    WAVE_PARTICIPATION,
    /**
     * PN-12 refusing axis. A delta is interest-scopable (`Scoped`) or not. A
     * partial-interest inlet requires INTEREST_SCOPED — a non-`Scoped` delta rides
     * whole to it and *over-delivers silently* today (plan §2 F7); as a link-flow
     * axis it becomes a loud typed refusal.
     */
    INSTANCE_SCOPING,
    /**
     * FU-5 refusing axis. A producer either serves on-demand pulls (registers a
     * [civictech.cell.port.Protocols.StateRequest] handler via
     * `FanOutlet.pullServe`) or it does not. A consumer that pulls-on-open
     * (installs `PullOnOpen`) requires BASELINE_SERVING — wired onto a
     * non-serving producer today its StateRequest is answered by no one and the
     * consumer *starves silently* (its state never arrives). As a link-flow axis
     * it becomes a loud typed refusal at handshake (in-process).
     */
    PULL_SERVICE,
}

/**
 * One level on one [NatureAxis]. The per-axis enums are the only inhabitants;
 * the first constant of each is the axis DEFAULT (absent axis ⇒ this level ⇒
 * today's behavior), and later constants are *stronger* — offering a stronger
 * level satisfies a weaker requirement (see `NatureNegotiation.reconcile`).
 */
sealed interface NatureLevel {
    val axis: NatureAxis
    /** Rank within the axis lattice; higher subsumes lower. */
    val rank: Int
}

/** Exclusive-ownership class of the payload a port carries (spec 23, G-21). */
enum class Ownership : NatureLevel {
    /** Fan-out-safe (no `Owned`/`Leased`) — the DEFAULT. */
    SHARED,
    /** Carries an exclusive `Owned`/`Leased` payload — single-consumer only. */
    EXCLUSIVE;

    override val axis get() = NatureAxis.OWNERSHIP
    override val rank get() = ordinal
}

/** Merge class of a replicated/gossiped delta stream (spec 42). */
enum class MergeClass : NatureLevel {
    /** Merge is not idempotent (e.g. plain addition) — the DEFAULT. */
    NON_IDEMPOTENT,
    /** Idempotent merge (tag union, pointwise max) — gossip/catch-up safe. */
    IDEMPOTENT;

    override val axis get() = NatureAxis.MERGE_IDEMPOTENCE
    override val rank get() = ordinal
}

/** Execution color a cell demands of its host (spec 32, G-3). */
enum class Color : NatureLevel {
    /** No blocking/suspension — spawns on any host color; the DEFAULT. */
    PURE,
    BLOCKING,
    SUSPENDING;

    override val axis get() = NatureAxis.COLOR
    override val rank get() = ordinal
}

/** Whether a delta stream is monotone (safe to re-origination on a cycle). */
enum class Monotonicity : NatureLevel {
    /** Not known monotone — the DEFAULT. */
    NON_MONOTONE,
    /** Monotone / magnitude-bounded — safe on a feedback cycle (spec 21 §Cycles). */
    MONOTONE;

    override val axis get() = NatureAxis.MONOTONICITY
    override val rank get() = ordinal
}

/** Whether a port participates in the wave protocol (PN-12, spec 20/21 §Waves). */
enum class WaveParticipation : NatureLevel {
    /** Emits plain, un-timestamped traffic — the DEFAULT (an ALIGN inlet drops it). */
    UNWAVED,
    /** Stamps aligned waves — satisfies an ALIGN-tier (`WaveFrontier`) inlet. */
    WAVED;

    override val axis get() = NatureAxis.WAVE_PARTICIPATION
    override val rank get() = ordinal
}

/** Whether a delta can be restricted to an [Interest]'s slice (PN-12, spec 42). */
enum class InstanceScoping : NatureLevel {
    /** Rides links whole (a non-`Scoped` delta over-delivers to a partial peer) — the DEFAULT. */
    SINGLETON,
    /** `Scoped`: the linker can slice it to a partial-interest target's admitted keys. */
    INTEREST_SCOPED;

    override val axis get() = NatureAxis.INSTANCE_SCOPING
    override val rank get() = ordinal
}

/** Whether a producer answers on-demand pulls with a state baseline (FU-5, spec 20/21 §Pull). */
enum class PullService : NatureLevel {
    /** Registers no StateRequest handler — a pull goes unanswered — the DEFAULT. */
    NONE,
    /** `pullServe`d: answers a StateRequest with a single-wave state baseline. */
    BASELINE_SERVING;

    override val axis get() = NatureAxis.PULL_SERVICE
    override val rank get() = ordinal
}

/**
 * PN-12 — the **structural** natures of a whole cell, sparse. Unlike a
 * [NatureAxis] (a *link-flow* property reconciled at a link), these describe
 * what a cell *is* (glitch-free board, durable writer, replicated union,
 * partitioned shard, …). They are **deliberately never consulted by
 * [civictech.cell.port.NatureNegotiation.reconcile]**: a volatile consumer of a
 * durable producer is normal (the exchange demo is exactly that), so making them
 * refuse at links would repeat the COLOR mistake. They are consumed by spawn
 * checks, diagnostics, and drift assertions.
 */
enum class Manifest { GLITCH_FREE, DURABLE, REPLICATED, PARTITIONED, PULL_SERVING, GATED }

/**
 * A **sparse** vector of declared natures. Absent axes read as their DEFAULT
 * level, so [DEFAULT] (the empty vector) is *exactly today's behavior* — and,
 * being empty, is the zero-cost same-nature fast path (see [isDefault]).
 */
@JvmInline
value class NatureVector(val levels: Map<NatureAxis, NatureLevel>) {
    /** The declared level on [axis], or its DEFAULT (first-constant) level. */
    fun level(axis: NatureAxis): NatureLevel = levels[axis] ?: defaultOf(axis)

    /** No axis declared ⇒ literally today's behavior ⇒ the reconcile fast path. */
    val isDefault: Boolean get() = levels.isEmpty()

    /** Folds another vector in; [other]'s declared levels win on shared axes. */
    fun with(other: NatureVector): NatureVector =
        if (other.isDefault) this
        else if (isDefault) other
        else NatureVector(levels + other.levels)

    fun with(level: NatureLevel): NatureVector = NatureVector(levels + (level.axis to level))

    companion object {
        /** Shared, zero-alloc empty singleton — the same-nature fast-path sentinel. */
        val DEFAULT = NatureVector(emptyMap())

        fun of(vararg levels: NatureLevel): NatureVector =
            if (levels.isEmpty()) DEFAULT else NatureVector(levels.associateBy { it.axis })

        /** The DEFAULT (first-constant) level of each axis — "absent ⇒ today". */
        fun defaultOf(axis: NatureAxis): NatureLevel = when (axis) {
            NatureAxis.OWNERSHIP -> Ownership.SHARED
            NatureAxis.MERGE_IDEMPOTENCE -> MergeClass.NON_IDEMPOTENT
            NatureAxis.COLOR -> Color.PURE
            NatureAxis.MONOTONICITY -> Monotonicity.NON_MONOTONE
            NatureAxis.WAVE_PARTICIPATION -> WaveParticipation.UNWAVED
            NatureAxis.INSTANCE_SCOPING -> InstanceScoping.SINGLETON
            NatureAxis.PULL_SERVICE -> PullService.NONE
        }
    }
}

/** A single-axis nature conflict — the only failure a scoped-axis link can raise. */
data class NatureMismatch(val axis: NatureAxis, val offered: NatureLevel, val required: NatureLevel)

/**
 * CP-G2 — the **sparse** wire form of a [NatureVector]: a flat
 * `[axisOrdinal, levelRank, …]` int-pair list carrying *only declared axes*,
 * so [NatureVector.DEFAULT] projects to the empty list (zero bytes on the wire,
 * absent field ⇒ DEFAULT ⇒ today's behavior verbatim). The actual JSON
 * encoding is the `WireFrame.natures: List<Int>` field in the kernel codec;
 * this pure `NatureVector`↔`List<Int>` mapping is the serialization proper and
 * lives with the vocabulary it encodes.
 */
fun NatureVector.toWire(): List<Int> =
    if (isDefault) emptyList()
    else levels.entries.flatMap { (axis, level) -> listOf(axis.ordinal, level.rank) }

/**
 * Inverse of [toWire], **forward-compatible by construction**: an axis ordinal
 * or level rank a newer peer names that this build cannot resolve is *ignored*
 * for that axis — never a refusal (an old peer must not reject a new peer's link
 * over an axis it cannot name). An empty/odd-tailed list ⇒ [NatureVector.DEFAULT].
 */
fun natureVectorFromWire(wire: List<Int>): NatureVector {
    if (wire.isEmpty()) return NatureVector.DEFAULT
    val axes = NatureAxis.entries
    val levels = LinkedHashMap<NatureAxis, NatureLevel>()
    var i = 0
    while (i + 1 < wire.size) {
        val axis = axes.getOrNull(wire[i])
        val level = axis?.let { natureLevelOf(it, wire[i + 1]) }
        if (axis != null && level != null) levels[axis] = level
        i += 2
    }
    return if (levels.isEmpty()) NatureVector.DEFAULT else NatureVector(levels)
}

/** The [NatureLevel] on [axis] with the given [rank] (== enum ordinal), or null if a newer peer's rank. */
private fun natureLevelOf(axis: NatureAxis, rank: Int): NatureLevel? = when (axis) {
    NatureAxis.OWNERSHIP -> Ownership.entries.getOrNull(rank)
    NatureAxis.MERGE_IDEMPOTENCE -> MergeClass.entries.getOrNull(rank)
    NatureAxis.COLOR -> Color.entries.getOrNull(rank)
    NatureAxis.MONOTONICITY -> Monotonicity.entries.getOrNull(rank)
    NatureAxis.WAVE_PARTICIPATION -> WaveParticipation.entries.getOrNull(rank)
    NatureAxis.INSTANCE_SCOPING -> InstanceScoping.entries.getOrNull(rank)
    NatureAxis.PULL_SERVICE -> PullService.entries.getOrNull(rank)
}

enum class PortDirection { IN, OUT }

/**
 * One declared port of a cell (the G-60 port-metadata slot). The name equals
 * the property name, which equals the registry key (G-17). Ownership /
 * management / exclusivity bits are NOT duplicated here — join [contractId]
 * into the contract table at runtime.
 */
data class PortDescriptor(
    val name: String,
    val direction: PortDirection,
    /** Raw port Api interface, e.g. `civictech.cell.Propagate`. */
    val contractFqn: String,
    /** `StableHash.of(contractFqn)` — joins to [ContractDescriptor.contractId]. */
    val contractId: Long,
    /**
     * Declared natures of this port (the G-60 slot for composition typing).
     * [NatureVector.DEFAULT] (the KSP default) ⇒ today's behavior verbatim;
     * a non-default vector is projected onto the live [civictech.cell.port.Port]
     * at `registerPort` and reconciled at link time (CP-F2/CP-F3).
     */
    val natures: NatureVector = NatureVector.DEFAULT,
)

/** Placement metadata for one concrete Cell implementation. */
data class CellDescriptor(
    val fqn: String,
    val color: CellColor,
    val ports: List<PortDescriptor> = emptyList(),
    /**
     * PN-12 — the cell's structural natures ([Manifest]), KSP-derived from the
     * marker interfaces it implements. Empty ⇒ a plain cell. Consumed by spawn
     * checks / diagnostics / drift assertions; **never** by `reconcile`.
     */
    val manifest: Set<Manifest> = emptySet(),
)

data class ProtocolDescriptor(
    val protocolId: String,
    val contractId: Long,
    val direction: ProtocolDirection,
    val band: Int,
    val lane: String,
    val cardinality: ProtocolCardinality,
)

/** Implemented by generated per-module tables; discovered via `ServiceLoader`. */
interface ContractModule {
    val contracts: List<ContractDescriptor>
    val cells: List<CellDescriptor> get() = emptyList()
    val protocols: List<ProtocolDescriptor> get() = emptyList()
}

/**
 * FNV-1a 64 over UTF-8 — stable across JVMs and compilations by construction
 * (`String.hashCode` is 32-bit and too collision-prone for wire ids).
 */
object StableHash {
    private const val OFFSET = -0x340d631b7bdddcdbL // 14695981039346656037
    private const val PRIME = 0x100000001b3L

    fun of(s: String): Long {
        var h = OFFSET
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h *= PRIME
        }
        return h
    }
}
