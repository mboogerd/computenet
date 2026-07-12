package civictech.cell

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Payload ownership contracts (spec 23, G-21). Encoded at the type level on
 * contract parameters; the KSP contract processor marks methods carrying
 * [Owned]/[Leased] as exclusive, and link handshakes enforce the SPSC rule
 * from that metadata — no runtime reflection.
 */

/** Read-only, temporary snapshot view; valid only during the invocation. Fan-out safe. */
@Serializable
@SerialName("Borrowed")
class Borrowed<T : Any>(val value: T)

/** Immutable form of a previously owned value. Fan-out safe. */
@Serializable
@SerialName("Frozen")
class Frozen<T : Any>(val value: T)

/**
 * Ownership transfer: consumed exactly once via [take]; the receiver may
 * mutate and retain. Crossing a machine boundary is move-by-serialize — the
 * bridge egress consumes the sender's reference as it encodes (spec 23).
 */
@Serializable
@SerialName("Owned")
class Owned<T : Any>(private val value: T) {
    @Transient
    private var consumed = false

    /** Take ownership; any later access is a use-after-move error. */
    fun take(): T {
        check(!consumed) { "Owned value already consumed (use after move)" }
        consumed = true
        return value
    }

    /** The typical fan-out path: consume, republish immutable. */
    fun freeze(): Frozen<T> = Frozen(take())

    /** Move-by-serialize seam for the bridge egress. */
    internal fun consume() {
        consumed = true
    }
}

/**
 * Exclusive mutable access from a shared pool; must be released. Never
 * crosses a machine boundary (a lease on a remote pool is meaningless) —
 * freeze or copy first. Pooling itself is G-21 phase 3, deliberately
 * unbuilt until profiling demands it.
 */
class Leased<T : Any>(val value: T, private val returnToPool: (T) -> Unit = {}) {
    private var released = false

    /** Return this value to its pool; a lease obligation is discharged exactly once. */
    fun release() {
        check(!released) { "Leased value already released" }
        released = true
        returnToPool(value)
    }
}
