package civictech.cell.link

import civictech.cell.port.Port
import civictech.cell.port.PortRef
import civictech.cell.port.ProtocolId
import java.util.*

/**
 * A first-class, directional connection from an outlet to an inlet (G-12).
 * The set of links is the topology.
 */
interface Link {
    val id: UUID
    val from: PortRef

    val to: PortRef

    /**
     * Consume vs Observe (spec 20/23 §Taps, 10/12 §Cardinality rule 2; PN-10).
     * A **Consume** link bears the consume-once/release obligation and is
     * counted by the completeness frontier ([civictech.cell.consistency.WaveFrontier]);
     * an **Observe** link (a negotiated `tap`/`streamTo`) announces itself so it
     * negotiates like any edge, yet is **never** an expected sibling — it must
     * not gate a wave's release. Defaults to [LinkRole.Consume]: zero caller
     * churn, byte-for-byte today's behavior for every existing link.
     */
    val role: LinkRole get() = LinkRole.Consume

    /**
     * Endpoint objects where known in-process (G-13 minimal): the producer-
     * and consumer-side ports, used by generic protocols (12) to travel along
     * the link. Null for endpoints that are not reachable as objects
     * (`Use.fixed` ad-hoc endpoints, bridged links across the wire).
     */
    val fromPort: Port? get() = null
    val toPort: Port? get() = null

    /**
     * Peer-negotiated protocol ids reachable across this link when its
     * counterpart is not a local [Port] object (spec 41 point 4, G-35 phase
     * B). Empty for ordinary in-process links, whose [ProtocolSupport] relay
     * already reaches every locally-registered handler directly.
     */
    val protocolCapabilities: Set<ProtocolId> get() = emptySet()

    /**
     * Wire realization of a protocol send when [fromPort]/[toPort] is null
     * (G-35 phase B): bridged links populate this so [Protocols] can route a
     * message across the bridge instead of relaying through [ProtocolSupport]
     * — a plain callback slot, so `cell.port` stays transport-neutral (no
     * wire dependency lives here, only in whoever constructs the link).
     */
    val protocolBridge: ProtocolBridge? get() = null

    /** Idempotent: detaches both sides and fires the target port's onUnlink once. */
    fun unlink()

    /** Infrastructure lifecycle observer; invoked once after a successful detach. */
    fun onUnlink(listener: (Link) -> Unit) {}
}

sealed interface LinkResult {
    data class Connected(val link: Link) : LinkResult

    /**
     * A refused handshake. [reason] is the human string every existing call
     * site and test already asserts on; [mismatch] is the CP-F1 typed slot,
     * non-null only when a scoped-axis nature conflict caused the refusal
     * (CP-F3). The secondary constructor keeps every legacy `Rejected(reason)`
     * site — and their asserted strings — byte-for-byte intact.
     */
    data class Rejected(
        val mismatch: civictech.nature.NatureMismatch?,
        val reason: String,
    ) : LinkResult {
        constructor(reason: String) : this(null, reason)
    }

    /**
     * The handshake runs on the target's host (cross-host proxy link); the outcome
     * is not observable by the initiator until the wire layer exists (M5).
     * A rejection on the target host is emitted to its dead-letter outlet.
     */
    data object Deferred : LinkResult
}

data class LinkRequest(
    val from: PortRef,
    val to: PortRef,
    /** Identity slot from day one (G-14); verification is future work (G-29). */
    val identity: Identity? = null,
    /** Consume vs Observe (spec 20/23 §Taps, 10/12 §Cardinality rule 2). */
    val role: LinkRole = LinkRole.Consume,
)

/**
 * The role a downstream attachment plays on a link's handshake (spec 20/23
 * §Taps; 10/12 §Cardinality rule 2 extension): a **Consume** link receives
 * the outlet's declared contract form and bears the consume-once/release
 * obligation, counted by the SPSC funnel; an **Observe** link (a tap)
 * receives a read-only `Borrowed` projection, valid only for the emitting
 * invocation, and is never counted — always admitted regardless of the
 * exclusive bit.
 */
enum class LinkRole { Consume, Observe }

internal class PortLink(
    override val from: PortRef,
    override val to: PortRef,
    override val fromPort: Port? = null,
    override val toPort: Port? = null,
    override val role: LinkRole = LinkRole.Consume,
    private val doUnlink: (PortLink) -> Unit,
) : Link {
    override val id: UUID = UUID.randomUUID()
    private var active = true
    private val unlinkListeners = mutableListOf<(Link) -> Unit>()

    override fun onUnlink(listener: (Link) -> Unit) {
        if (active) unlinkListeners += listener else listener(this)
    }

    override fun unlink() {
        if (!active) return
        active = false
        doUnlink(this)
        unlinkListeners.toList().forEach { it(this) }
        unlinkListeners.clear()
    }
}
