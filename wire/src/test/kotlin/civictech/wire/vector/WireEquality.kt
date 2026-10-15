package civictech.wire.vector

import civictech.cell.Borrowed
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.link.Link
import civictech.cell.port.PortRef
import civictech.cell.wire.DecodedWireFrame
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.nature.NatureVector
import java.util.UUID

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
 *
 * Decision ncz.4-D4 adds the same treatment for decoded `WireEdgeLink`s:
 * identity of a reconstructed link is not a wire observable, so the helper
 * compares the six fields that `WireCodec.invocation` reconstructs from the
 * bytes. `protocolCapabilities`, `sink`, endpoint ports, and
 * `suppressEdgeOpen` are not part of the view because the decoder never sets
 * them.
 */
object WireEquality {
    /** The wrapper kind and its recursively normalized wire-observable value. */
    data class WrapperView(val kind: String, val value: Any?)

    /** Wire-observable projection of a decoded [WireEdgeLink]. */
    data class LinkView(
        override val id: UUID,
        override val from: PortRef,
        override val to: PortRef,
        val fromAddr: PortAddress,
        val toAddr: PortAddress,
        val natures: NatureVector,
    ) : Link {
        override fun unlink() = Unit

        override fun onUnlink(listener: (Link) -> Unit) = Unit
    }

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

    /** Replaces a decoded link with its wire-observable structural projection. */
    fun normalize(link: Link?): Link? = when (link) {
        is WireEdgeLink -> LinkView(link.id, link.from, link.to, link.fromAddr, link.toAddr, link.natures)
        null -> null
        else -> link
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
            protocolLink = normalize(decoded.invocation.protocolLink),
            protocolMessage = normalize(decoded.invocation.protocolMessage),
        ),
    )
}
