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
    /** Raw port Api interface, e.g. `civictech.cell.data.Propagate`. */
    val contractFqn: String,
    /** `StableHash.of(contractFqn)` — joins to [ContractDescriptor.contractId]. */
    val contractId: Long,
)

/** Placement metadata for one concrete Cell implementation. */
data class CellDescriptor(
    val fqn: String,
    val color: CellColor,
    val ports: List<PortDescriptor> = emptyList(),
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
