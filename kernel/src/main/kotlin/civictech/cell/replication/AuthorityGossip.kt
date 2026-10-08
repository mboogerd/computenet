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
import civictech.cell.ReplayProvenance
import civictech.cell.Stateful
import civictech.cell.Timestamp
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
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.util.ArrayList
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
 * The retained log is intentionally unbounded. When the guarded replica is
 * journaled, this adapter inherits that journal and snapshots every retained
 * author envelope verbatim; journal compaction therefore compacts the record
 * stream, not this security history. Folding the retained history itself would
 * still need an author-signed checkpoint (a relay cannot create one). A
 * volatile adapter has no snapshot and rebuilds from peers' catch-up.
 * Following the repository's additive-`Stateful` precedent, this did not bump
 * the journal format: an older build cannot decode a new checkpoint containing
 * [SignedWrite], so downgrade across that checkpoint is unsupported.
 *
 * A locally signed envelope is also written ahead as an ordinary frame through
 * the guarded cell's journaled `deltaInlet` before [CountingWriteSigner] returns
 * it. `[24-DUR-02]` therefore replays the original envelope after the local
 * operation that produced it. A replayed local operation waits behind the
 * host's recovery-aware quiescence fence: its marked envelope frame cancels
 * that pending operation when present, while a crash-window operation with no
 * envelope is signed after replay using a counter above every retained counter
 * for this author. The marked frame restores the retained pair and transfer
 * chain without applying the idempotent data delta a second time.
 *
 * A live journal append can still fail after the guarded data cell has mutated.
 * The adapter then records an `UNSIGNED` refusal, publishes no envelope, and
 * latches closed against later local writes; restart/recovery is required to
 * turn the already-journaled operation into a signed envelope. This is the
 * existing two-frame ceiling rather than an atomic data-cell/envelope commit.
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
    private val writeAhead: (SignedWrite) -> Unit,
    private val afterRecoveryApplied: (() -> Unit) -> Unit,
    override val ref: CellRef = adapterRef(cell.ref),
) : Cell, Stateful, BoundaryDenialAccounting {
    private data class WriteKey(val author: PeerId, val counter: Long)
    private sealed interface FirstCrossing {
        data object Local : FirstCrossing
        data class Inbound(val sourcePort: PortRef?) : FirstCrossing
    }
    private data class PendingReplay(val encodedPayload: ByteArray)
    private data class ReplayBatch(
        val pending: MutableList<PendingReplay> = mutableListOf(),
        var flushScheduled: Boolean = false,
    )

    override val boundaryDenials: BoundaryDenials = BoundaryDenials()
    private val sink = boundaryDenials.sinkFor("write-authority")
    private val state = AuthorityState(authority)
    private val inbound = ThreadLocal<SignedWrite?>()
    private val firstCrossing = ConcurrentHashMap<WriteKey, FirstCrossing>()
    private val localWriteAheadPort = cell.outlet.ref
    private val replayBatches = java.util.IdentityHashMap<Any, ReplayBatch>()

    @Volatile
    private var writeAheadFailure: Throwable? = null

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
        writeAheadFailure?.let { failure ->
            denyDurabilityFailure("transfer", payload, payload, failure)
            return
        }
        if (!state.authorizesLocal(signer.peerId, payload)) {
            denyLocal("transfer", to, payload)
            return
        }
        signApplyAndForward(payload)
    }

    internal fun retained(): List<SignedWrite> = state.retained()

    override fun snapshot(): Serializable = ArrayList(state.retained())

    override fun restore(snapshot: Serializable) {
        val writes = (snapshot as? List<*>)?.mapIndexed { index, value ->
            requireNotNull(value as? SignedWrite) {
                "write-authority snapshot entry $index is not a SignedWrite"
            }
        } ?: throw IllegalArgumentException(
            "write-authority snapshot for ${cell.ref} is not a retained-write list",
        )
        writes.forEach { write ->
            require(write.logicalId == cell.ref.id) {
                "write-authority snapshot for ${cell.ref} contains write for ${write.logicalId}"
            }
            when (val admission = state.admit(write, verifier)) {
                is Admission.Admitted -> {
                    require(acceptsPayload(admission.payload)) {
                        "write-authority snapshot payload type " +
                            "${admission.payload?.javaClass?.name ?: "null"} does not match ${cell.javaClass.name}"
                    }
                    state.apply(write, admission.payload)
                }
                is Admission.Denied -> require(admission.reason == DenialReason.REPLAY) {
                    "write-authority snapshot for ${cell.ref} contains refused write: " +
                        "${admission.reason}${admission.detail?.let { ": $it" } ?: ""}"
                }
            }
        }
    }

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
        val crossing = if (CurrentContext.get()?.sourcePort == localWriteAheadPort) {
            FirstCrossing.Local
        } else {
            FirstCrossing.Inbound(CurrentContext.get()?.sourcePort)
        }
        when (val admission = state.admit(write, verifier)) {
            is Admission.Denied -> {
                if (admission.reason == DenialReason.REPLAY && crossing == FirstCrossing.Local) {
                    completePendingReplay(write)
                    return
                }
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
                if (crossing == FirstCrossing.Local) {
                    completePendingReplay(write)
                    CurrentContext.with(null) { outlet.call.propagate(write) }
                    return
                }
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
        ReplayProvenance.get()?.let { replay ->
            deferReplaySigning(replay, delta)
            return
        }
        writeAheadFailure?.let { failure ->
            denyDurabilityFailure("outlet", delta, delta, failure)
            return
        }
        if (!state.authorizesLocal(signer.peerId, delta)) {
            denyLocal("outlet", delta, delta)
            return
        }
        signApplyAndForward(delta)
    }

    private fun deferReplaySigning(replay: Any, payload: Any?) {
        val batch = replayBatches.getOrPut(replay) { ReplayBatch() }
        batch.pending += PendingReplay(WriteAuthorityBytes.encodePayload(payload))
        if (!batch.flushScheduled) {
            batch.flushScheduled = true
            afterRecoveryApplied { flushReplayBatch(replay) }
        }
    }

    private fun completePendingReplay(write: SignedWrite) {
        val replay = ReplayProvenance.get() ?: return
        val pending = replayBatches[replay]?.pending ?: return
        val index = pending.indexOfFirst { it.encodedPayload.contentEquals(write.payload) }
        if (index >= 0) pending.removeAt(index)
    }

    private fun flushReplayBatch(replay: Any) {
        val batch = replayBatches.remove(replay) ?: return
        if (batch.pending.isEmpty()) return

        val highestRetained = state.retained()
            .asSequence()
            .filter { it.author == signer.peerId }
            .maxOfOrNull { it.counter }
        if (highestRetained != null) {
            try {
                signer.continueAfter(cell.ref.id, highestRetained)
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                writeAheadFailure = failure
                batch.pending.forEach { pending ->
                    denyDurabilityFailure("recovery", pending.encodedPayload, pending.encodedPayload, failure)
                }
                return
            }
        }
        batch.pending.forEach { pending ->
            val payload = WriteAuthorityBytes.decodePayload(pending.encodedPayload)
            if (!state.authorizesLocal(signer.peerId, payload)) {
                denyLocal("recovery", payload, payload)
            } else {
                signApplyAndForward(payload, pending.encodedPayload)
            }
        }
    }

    private fun signApplyAndForward(
        payload: Any?,
        encodedPayload: ByteArray = WriteAuthorityBytes.encodePayload(payload),
    ) {
        val write = try {
            signer.signWriteAhead(cell.ref.id, encodedPayload) { signed ->
                val current = CurrentContext.get()
                val marked = current?.copy(sourcePort = localWriteAheadPort)
                    ?: civictech.cell.MessageContext(
                        timestamp = Timestamp(ref.id, signed.counter),
                        sourcePort = localWriteAheadPort,
                    )
                CurrentContext.with(marked) { writeAhead(signed) }
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            writeAheadFailure = failure
            denyDurabilityFailure("write-ahead", payload, payload, failure)
            return
        }
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

    private fun denyDurabilityFailure(op: String, subject: Any?, denied: Any?, failure: Throwable) {
        sink.deny(
            seam = BoundarySeam.INTEGRITY,
            reason = DenialReason.UNSIGNED,
            principal = signer.peerId,
            subject = "${cell.javaClass.simpleName}#$op",
            detail = "local write could not be durably signed for $subject: " +
                "${failure.javaClass.simpleName}${failure.message?.let { ": $it" } ?: ""}",
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
