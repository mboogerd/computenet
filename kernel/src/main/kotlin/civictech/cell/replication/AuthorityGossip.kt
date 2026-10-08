package civictech.cell.replication

import civictech.cell.BoundaryDenialAccounting
import civictech.cell.BoundaryDenials
import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.data.LocalWriteGate
import civictech.cell.data.OrMapCell
import civictech.cell.data.Replicable
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.link.catchUpOnLinked
import civictech.cell.membrane.SignatureVerifier
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Author-signed replication seam for one authority-bearing [cell].
 *
 * This is `[43-FLOW-01]`'s replication twin: replica gossip has no membrane
 * exposure to mediate, so the same verify-before-delivery rule interposes on
 * the replica's own `deltaInlet`. Refusals are ordinary boundary denials; like
 * `MediateProxy`'s `[SEC1-29]` path they never throw, consult supervision, or
 * mint a wave.
 *
 * Replay means exact `(author, counter)` membership in [state], never a
 * strictly-increasing high-water check. A multi-path mesh may legitimately
 * deliver counter 7 before counter 6. A repeated pair on the same crossing is
 * reported as `REPLAY`; an already-retained copy arriving over a different
 * relay is expected convergence traffic and is silently deduplicated. Every
 * admitted envelope is retained so a relay's catch-up preserves the original
 * author's signature.
 *
 * The retained log is intentionally unbounded and in-memory. Compaction needs
 * an author-signed folded checkpoint (a relay cannot create one), and rebind or
 * restart does not carry the log in the data cell's `Stateful` snapshot; a new
 * adapter rebuilds it from peers' catch-up.
 *
 * A new owner's write that reaches a replica before the signed transfer is
 * refused there. The next catch-up/anti-entropy re-fire carries both envelopes
 * and reconverges after the transfer is learned; this adapter adds no second
 * acknowledgement or ordering protocol.
 */
class AuthorityGossip internal constructor(
    private val cell: Replicable<*>,
    authority: WriteAuthority,
    private val signer: CountingWriteSigner,
    private val verifier: SignatureVerifier,
    override val ref: CellRef = adapterRef(cell.ref),
) : Cell, BoundaryDenialAccounting {
    private data class WriteKey(val author: PeerId, val counter: Long)
    private sealed interface FirstCrossing {
        data object Local : FirstCrossing
        data class Inbound(val sourcePort: PortRef?) : FirstCrossing
    }

    override val boundaryDenials: BoundaryDenials = BoundaryDenials()
    private val sink = boundaryDenials.sinkFor("write-authority")
    private val state = AuthorityState(authority)
    private val inbound = ThreadLocal<SignedWrite?>()
    private val firstCrossing = ConcurrentHashMap<WriteKey, FirstCrossing>()

    @Volatile
    private var hostContext: CellContext? = null

    /** The only outlet replication links for an authority-bearing replica. */
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<Any?>>())

    init {
        installLocalGate()
        interposeDeltaInlet()
        @Suppress("UNCHECKED_CAST")
        (cell.outlet as civictech.cell.port.Subscribe<Propagate<Any?>>).subscribe(
            Use.fixed(Propagate(::onCellEmission), PortRef.of(ref, "source")),
        )

        @Suppress("UNCHECKED_CAST")
        (outlet as FanOutlet<Propagate<Any>>).catchUpOnLinked {
            state.retained().takeIf { it.isNotEmpty() }?.let(::SignedWriteBatch)
        }
    }

    override fun onActivate(ctx: CellContext) {
        hostContext = ctx
    }

    override fun onDeactivate(ctx: CellContext) {
        if (hostContext === ctx) hostContext = null
    }

    /**
     * Sign and gossip a transfer by this node's current principal.
     *
     * The caller may be an application thread, while the guarded cell's local
     * writes run on its host. Enqueueing here gives transfer and those writes
     * one host order: a write task completes gate, mutation, emission and
     * signing before a later transfer can run, or the transfer runs first and
     * the write is refused by its local gate before mutation.
     */
    fun transfer(to: PeerId) {
        val context = checkNotNull(hostContext) {
            "write-authority adapter $ref is not active"
        }
        context.enqueueBarrier { applyTransfer(to) }
    }

    private fun applyTransfer(to: PeerId) {
        val payload = TransferAuthority(to)
        if (!state.authorizesLocal(signer.peerId, payload)) {
            denyLocal("transfer", to, payload)
            return
        }
        signApplyAndForward(payload)
    }

    internal fun retained(): List<SignedWrite> = state.retained()

    private fun installLocalGate() {
        val gate = LocalWriteGate { op, element ->
            state.authorizesLocal(signer.peerId, element).also { admitted ->
                if (!admitted) denyLocal(op, element, element)
            }
        }
        when (cell) {
            is SetCell<*> -> cell.localWriteGate = gate
            is OrMapCell<*, *> -> cell.localWriteGate = gate
            else -> throw IllegalArgumentException(
                "write authority requires a local-write gate on ${cell.javaClass.name}",
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun interposeDeltaInlet() {
        val inlet = requireNotNull(cell.deltaInlet as? FanInlet<*>) {
            "write authority requires ${cell.ref} deltaInlet to be a FanInlet"
        } as FanInlet<Propagate<Any?>>
        inlet.interpose { root -> Propagate { received -> receive(received, root) } }
    }

    private fun receive(received: Any?, root: Propagate<Any?>) {
        when (received) {
            is SignedWrite -> receiveOne(received, root)
            is SignedWriteBatch -> received.writes.forEach { receiveOne(it, root) }
            else -> sink.deny(
                seam = BoundarySeam.INTEGRITY,
                reason = DenialReason.UNSIGNED,
                principal = null,
                subject = "${cell.javaClass.simpleName}#deltaInlet",
                detail = "unsigned replication payload ${received?.javaClass?.name ?: "null"}; " +
                    "relay=${CurrentPeer.stamp()?.id?.name ?: "local"}",
                deniedArgs = listOf(received),
            )
        }
    }

    private fun receiveOne(write: SignedWrite, root: Propagate<Any?>) {
        if (write.logicalId != cell.ref.id) {
            deny(write, DenialReason.BAD_SIGNATURE, write.author, "signed write names another logical cell")
            return
        }
        // PeerStamp is intentionally ambient only at the host boundary and may
        // be gone after an inlet policy hop. The rewritten source port is the
        // durable per-hop identity that MessageContext carries through it.
        val crossing = FirstCrossing.Inbound(CurrentContext.get()?.sourcePort)
        when (val admission = state.admit(write, verifier)) {
            is Admission.Denied -> {
                val first = firstCrossing[WriteKey(write.author, write.counter)]
                if (admission.reason != DenialReason.REPLAY || first == null || first == crossing) {
                    deny(write, admission.reason, admission.principal, admission.detail)
                }
            }
            is Admission.Admitted -> {
                val payload = admission.payload
                if (!acceptsPayload(payload)) {
                    deny(
                        write,
                        DenialReason.UNSIGNED,
                        write.author,
                        "write-authority payload type ${payload?.javaClass?.name ?: "null"} does not match " +
                            cell.javaClass.name,
                    )
                    return
                }
                state.apply(write, payload)
                firstCrossing.putIfAbsent(WriteKey(write.author, write.counter), crossing)
                // A transfer has no data-cell emission to ride, so the adapter
                // relays the admitted envelope itself, as it relays a data write.
                if (payload is TransferAuthority) outlet.call.propagate(write)
                else withInbound(write) { root.propagate(payload) }
            }
        }
    }

    private fun acceptsPayload(payload: Any?): Boolean = payload is TransferAuthority || when (cell) {
        is SetCell<*> -> payload is SetDelta<*>
        is OrMapCell<*, *> -> payload is TaggedMapDelta<*, *>
        else -> false
    }

    private fun onCellEmission(delta: Any?) {
        val relayed = inbound.get()
        if (relayed != null) {
            outlet.call.propagate(relayed)
            return
        }
        if (!state.authorizesLocal(signer.peerId, delta)) {
            denyLocal("outlet", delta, delta)
            return
        }
        signApplyAndForward(delta)
    }

    private fun signApplyAndForward(payload: Any?) {
        val write = signer.sign(cell.ref.id, WriteAuthorityBytes.encodePayload(payload))
        state.apply(write, payload)
        firstCrossing.putIfAbsent(WriteKey(write.author, write.counter), FirstCrossing.Local)
        outlet.call.propagate(write)
    }

    private fun denyLocal(op: String, element: Any?, denied: Any?) {
        sink.deny(
            seam = BoundarySeam.INTEGRITY,
            reason = DenialReason.UNAUTHORIZED_WRITER,
            principal = signer.peerId,
            subject = "${cell.javaClass.simpleName}#$op",
            detail = "${signer.peerId.name} is not authorized for local $op of $element",
            deniedArgs = listOf(denied),
        )
    }

    private fun deny(write: SignedWrite, reason: DenialReason, principal: PeerId?, detail: String?) {
        sink.deny(
            seam = BoundarySeam.INTEGRITY,
            reason = reason,
            principal = principal,
            subject = "${cell.javaClass.simpleName}#deltaInlet",
            detail = detail,
            deniedArgs = listOf(write),
        )
    }

    private inline fun withInbound(write: SignedWrite, block: () -> Unit) {
        val previous = inbound.get()
        inbound.set(write)
        try {
            block()
        } finally {
            if (previous == null) inbound.remove() else inbound.set(previous)
        }
    }

    companion object {
        private fun adapterRef(cellRef: CellRef): CellRef = CellRef(
            UUID.nameUUIDFromBytes(
                "write-authority:${cellRef.id}".toByteArray(StandardCharsets.UTF_8),
            ),
            cellRef.instanceId,
        )
    }
}
