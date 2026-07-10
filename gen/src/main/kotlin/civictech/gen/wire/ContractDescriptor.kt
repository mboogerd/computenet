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
)

/** Wire identity of a contract: `contractId = StableHash.of(fqn)`. */
data class ContractDescriptor(
    val contractId: Long,
    val fqn: String,
    /** Management contracts may return/block; data contracts are push-only (spec 12). */
    val management: Boolean,
    val methods: List<MethodDescriptor>,
)

/** Implemented by generated per-module tables; discovered via `ServiceLoader`. */
interface ContractModule {
    val contracts: List<ContractDescriptor>
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
