package civictech.wire.vector

import civictech.cell.Borrowed
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.wire.DecodedWireFrame

/**
 * Structural equality adapter for the wire-vector driver (decision ncz.3-D6).
 * [Owned], [Frozen], and [Borrowed] intentionally retain identity equality, so
 * independently decoded wrappers cannot be compared through their enclosing
 * data classes even when their wire-observable contents are equal.
 *
 * This normalizes wrappers and collection shapes reached directly from frame
 * arguments or protocol messages. It deliberately does not traverse arbitrary
 * payload classes: a wrapper nested inside one (for example,
 * `Stamped<Owned<...>>`) requires this normalizer to grow before such a vector
 * can make an honest structural-equality claim.
 */
object WireEquality {
    /** The wrapper kind and its recursively normalized wire-observable value. */
    data class WrapperView(val kind: String, val value: Any?)

    /** Normalizes ownership wrappers and the collection shapes that may contain them. */
    fun normalize(value: Any?): Any? = when (value) {
        is Owned<*> -> WrapperView("Owned", normalize(value.borrow().value))
        is Frozen<*> -> WrapperView("Frozen", normalize(value.value))
        is Borrowed<*> -> WrapperView("Borrowed", normalize(value.value))
        is List<*> -> value.map(::normalize)
        is Set<*> -> value.mapTo(LinkedHashSet(), ::normalize)
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (key, entryValue) ->
            normalize(key) to normalize(entryValue)
        }
        else -> value
    }

    /** Rebuilds the two decoded views with wrapper-bearing positions normalized. */
    fun normalize(decoded: DecodedWireFrame): DecodedWireFrame = decoded.copy(
        frame = decoded.frame.copy(
            args = decoded.frame.args.map(::normalize),
            protocolMessage = normalize(decoded.frame.protocolMessage),
        ),
        invocation = decoded.invocation.copy(
            invocation = decoded.invocation.invocation.copy(
                args = decoded.invocation.invocation.args.map(::normalize),
            ),
            protocolMessage = normalize(decoded.invocation.protocolMessage),
        ),
    )
}
