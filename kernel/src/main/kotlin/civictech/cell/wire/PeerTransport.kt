package civictech.cell.wire

import java.util.ServiceLoader

/**
 * The kernel's transport seam (feature `computenet-gyvli`, decision gyvli-D1):
 * how one [Peering.Side] reaches another, stated once so a caller names a
 * scheme and an address rather than a socket type.
 *
 * A binding — `loopback` here ([LoopbackPeerTransport]), `ws` in `:wire`,
 * `iroh` in `:iroh` — owns its address model ([PeerAddress] is open for that
 * reason) and its framing; what it shares with every other binding is the
 * endpoint contract below and the policy types [ReconnectPolicy] and
 * [HelloCredentialLimits]. The seam hoists *policy*, not framing: each
 * binding's hello grammar stays its own.
 *
 * The contract every binding is held to is executable:
 * `civictech.testkit.PeerTransportContract`, which a binding's test subclasses.
 *
 * **Refusals happen before endpoints exist.** A [Peering.Side] whose
 * credentials the binding's hello cannot carry is refused by [listen] and
 * [dial] with [UnsendableHelloCredentialsException] before anything is bound
 * or dialled — a configuration fault surfaces at the call that configured it.
 */
interface PeerTransport {

    /** The scheme this binding serves: `"loopback"`, `"ws"`, `"iroh"`; a binding may add its own. */
    val scheme: String

    /** Parse [text] into this binding's address type; refuses an address of a foreign scheme. */
    fun parseAddress(text: String): PeerAddress

    /**
     * Serve peerings on [address] for [side]. The returned listener's
     * [PeerListener.boundAddress] is what it actually bound, which a dialler
     * must be handed (an "any free port" request is only resolved there).
     */
    fun listen(address: PeerAddress, side: Peering.Side): PeerListener

    /**
     * Establish a peering from [side] to the listener at [address]. Returns
     * once the link carries.
     */
    fun dial(address: PeerAddress, side: Peering.Side): PeerConnection
}

/**
 * A binding's address. Deliberately **open**, not sealed: an iroh node id and
 * a `ws://host:port` URI have nothing in common but a scheme and a printable
 * form, and the kernel must not enumerate the bindings that exist.
 */
interface PeerAddress {
    val scheme: String

    /** The address as text, parseable again by the owning binding's [PeerTransport.parseAddress]. */
    val text: String
}

/**
 * Counters every endpoint reports, as a snapshot. [framesSent] and
 * [framesReceived] count bridge frames crossing this endpoint;
 * [unadmittedOpens] is the dialling end's current refused-dial run
 * ([ReconnectPolicy.unadmittedOpens]), always 0 on a listener;
 * [refusedAnnouncements] counts announcements this endpoint's ingress refused
 * at its admission gate.
 */
data class PeerStats(
    val framesSent: Long,
    val framesReceived: Long,
    val unadmittedOpens: Int,
    val refusedAnnouncements: Long,
)

/**
 * One end of a peering. [close] is always **deliberate** (gyvli-D4): it
 * disarms any reconnect before the link goes down, so no re-dial follows it.
 * Only a close the binding did not initiate — a peer close, a network drop —
 * re-arms.
 */
interface PeerEndpoint : AutoCloseable {
    val side: Peering.Side
    val stats: PeerStats
}

/** A serving end. */
interface PeerListener : PeerEndpoint {
    /** What this listener bound — for a "port 0" request, the port the OS granted. */
    val boundAddress: PeerAddress
}

/**
 * A dialled end — the end that can sever the peering and restore it
 */
interface PeerConnection : PeerEndpoint {

    /**
     * Sever the link and **hold it severed**: no re-dial until [heal]. The
     * peer sees what it sees of a dropped link — refs learned through it are
     * retracted, senders park (spec 33).
     */
    fun partition()

    /**
     * Re-establish what [partition] severed, returning once the link carries
     * again. The healed link is a fresh connection instance that supersedes
     * the severed one; convergence follows through the ordinary
     * re-announcement catch-up. Refused after [close].
     */
    fun heal()

    /** True while the link is up and carrying. */
    val isCarrying: Boolean
}

/**
 * A module-provided binding, discovered by [PeerTransports] through
 * `java.util.ServiceLoader` — the repo's pattern for kernel-declared,
 * module-provided extension ([WireSerializers] is the other). A module ships
 * one by listing its implementation in
 * `META-INF/services/civictech.cell.wire.PeerTransportProvider`.
 */
interface PeerTransportProvider {
    /** The scheme [create]'s transports serve; unique across the classpath. */
    val scheme: String

    /** A fresh transport. [config] is binding-specific; unknown keys are the binding's to refuse or ignore. */
    fun create(config: Map<String, String>): PeerTransport
}

/** Resolves a scheme to a binding among the [PeerTransportProvider]s on the classpath. */
object PeerTransports {

    /** Every provider visible to the kernel's class loader, keyed by scheme. */
    fun providers(): Map<String, PeerTransportProvider> {
        val found = ServiceLoader.load(PeerTransportProvider::class.java, PeerTransportProvider::class.java.classLoader)
            .toList()
        val byScheme = found.groupBy { it.scheme }
        val clashes = byScheme.filterValues { it.size > 1 }
        check(clashes.isEmpty()) {
            "more than one PeerTransportProvider serves the same scheme: " +
                clashes.entries.joinToString { (scheme, ps) -> "$scheme -> ${ps.map { it::class.java.name }}" }
        }
        return byScheme.mapValues { it.value.single() }
    }

    /**
     * A fresh transport for [scheme], created with [config]. Fails naming the
     * schemes that *were* found, so a missing module on the classpath reads as
     * such rather than as a typo.
     */
    fun forScheme(scheme: String, config: Map<String, String> = emptyMap()): PeerTransport {
        val providers = providers()
        val provider = providers[scheme]
            ?: throw IllegalArgumentException(
                "no PeerTransportProvider serves scheme '$scheme'; schemes found: " +
                    providers.keys.sorted().joinToString(prefix = "[", postfix = "]"),
            )
        return provider.create(config)
    }
}
