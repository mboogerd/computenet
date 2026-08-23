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

    /**
     * Read-only, temporary snapshot view for taps (spec 23 §Taps): does not
     * consume, does not require or check the consume-once state, and never
     * competes with the sole consumer's [take]. Valid only for the emitting
     * invocation — never retained, mutated, or released.
     */
    fun borrow(): Borrowed<T> = Borrowed(value)

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

    /**
     * Return this value to its pool; a lease obligation is discharged exactly once.
     *
     * [returnToPool] defaults to `{}` and pooling is G-21 phase 3, unbuilt — so today
     * a release transfers the value to nothing. `Proxy.discharge` relies on that: it
     * walks [value] after a successful release, because an exclusive held inside it
     * would otherwise have no consumer at all (computenet-zyg1). Giving `Leased` a
     * `returnToPool` that genuinely transfers ownership reopens that decision; see
     * `Proxy.discharge`'s KDoc.
     */
    fun release() {
        check(!released) { "Leased value already released" }
        released = true
        returnToPool(value)
    }

    /**
     * Read-only, temporary snapshot view for taps (spec 23 §Taps): does not
     * release the lease and never competes with the sole consumer's
     * [release]. Valid only for the emitting invocation.
     */
    fun borrow(): Borrowed<T> = Borrowed(value)
}

/**
 * Stand-in for an exclusive payload that degenerated off the happy path
 * (spec 23 R8, G-46): the dead-letter outlet is a fan-out, so a live
 * [Owned]/[Leased] reference MUST NOT enter it. `Owned` freezes instead
 * (see [Owned.freeze]); `Leased` releases and is represented by this
 * redacted marker — the outlet fans this, never the released value.
 */
@Serializable
@SerialName("Redacted")
class Redacted(val reason: String)
