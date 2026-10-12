package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import java.security.MessageDigest
import java.util.UUID

/** Deterministic kernel-test signing; deliberately independent of `:identity`. */
internal class StubWriteSigning(vararg peers: PeerId) {
    private val secrets = peers.associateWith { "secret:${it.name}".encodeToByteArray() }

    val verifier = SignatureVerifier { author, _, value, signature ->
        val write = value as? SignedWrite
        val secret = secrets[author]
        write != null && secret != null && signature.contentEquals(digest(secret, write.signingInput()))
    }

    fun signer(peer: PeerId): WriteSigner {
        val secret = requireNotNull(secrets[peer]) { "unknown stub peer $peer" }
        return object : WriteSigner {
            override val peerId: PeerId = peer
            override fun sign(input: ByteArray): ByteArray = digest(secret, input)
        }
    }

    fun signed(
        logicalId: UUID,
        author: PeerId,
        counter: Long,
        payload: Any?,
        signedBy: PeerId = author,
    ): SignedWrite {
        val bytes = WriteAuthorityBytes.encodePayload(payload)
        val input = SignedWrite.signingInput(logicalId, author, counter, bytes)
        return SignedWrite(logicalId, author, counter, bytes, signer(signedBy).sign(input))
    }

    private fun digest(secret: ByteArray, input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(secret)
            digest(input)
        }
}

internal interface AuthoritySetInletProxy {
    val inlet: Use<SetOps<String>>
}

internal interface AuthorityMapInletProxy {
    val inlet: Use<MapOps<String, String>>
}

internal class AuthorityTestPeer(
    val controller: SimulationController,
    val id: PeerId,
    private val signing: StubWriteSigning,
) {
    val registry = LocationRegistry()
    val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
    val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
    val side = Peering.Side(registry, bridgeHost, peer = id)
    val replication = Replication(registry)
    val signer = signing.signer(id)
    val deadLetters = mutableListOf<DeadLetter>()

    init {
        host.deadLetterOutlet.subscribe(
            Use.fixed(Propagate { deadLetters += it }, PortRef.generate()),
        )
    }

    fun replica(
        logicalId: UUID,
        instanceId: Long,
        authority: WriteAuthority,
    ): SetCell<String> = SetCell<String>(CellRef(logicalId, instanceId)).also { cell ->
        if (authority == WriteAuthority.Open) replication.replicate(cell, host)
        else replication.replicate(cell, host, authority, signer, signing.verifier)
    }

    fun ops(cell: SetCell<String>): SetOps<String> =
        (HostedCellProxy.create(cell.ref, registry, AuthoritySetInletProxy::class.java)
            as AuthoritySetInletProxy).inlet.call

    fun delta(target: CellRef): Propagate<Any?> =
        (HostedCellProxy.create(target, registry, Replication.ReplicaDeltaInlet::class.java)
            as Replication.ReplicaDeltaInlet).deltaInlet.call

    fun mapOps(cell: OrMapCell<String, String>): MapOps<String, String> =
        (HostedCellProxy.create(cell.ref, registry, AuthorityMapInletProxy::class.java)
            as AuthorityMapInletProxy).inlet.call

    fun denialReasons(): List<civictech.cell.DenialReason> =
        deadLetters.mapNotNull { it.denial?.reason }
}
