package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal interface RacingAuthoritySetInletProxy {
    val inlet: Use<SetOps<RacingHashElement>>
}

internal class RacingHashElement(
    private val value: String,
    @Transient private val beforeFirstHash: (() -> Unit)? = null,
) : Serializable {
    @Transient
    private var firstHash = true

    override fun hashCode(): Int {
        if (firstHash) {
            firstHash = false
            beforeFirstHash?.invoke()
        }
        return value.hashCode()
    }

    override fun equals(other: Any?): Boolean = other is RacingHashElement && value == other.value

    override fun toString(): String = value
}

class OwnershipTransferTest {
    private val pA = PeerId("owner-a")
    private val pB = PeerId("owner-b")
    private val pC = PeerId("owner-c")
    private val pD = PeerId("late-d")

    @Test
    fun `transfer racing an accepted local write cannot leave a local-only element`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        Peering.loopback(a.side, b.side)
        val logicalId = UUID.randomUUID()
        val authority = WriteAuthority.Principal(pA)
        val onA = SetCell<RacingHashElement>(CellRef(logicalId, 0)).also {
            a.replication.replicate(it, a.host, authority, a.signer, signing.verifier)
        }
        val onB = SetCell<RacingHashElement>(CellRef(logicalId, 1)).also {
            b.replication.replicate(it, b.host, authority, b.signer, signing.verifier)
        }
        controller.runToIdle()

        val writeInsideCell = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val element = RacingHashElement("accepted-before-transfer") {
            writeInsideCell.countDown()
            check(releaseWrite.await(5, TimeUnit.SECONDS)) { "timed out releasing the paused local write" }
        }
        val ops = (HostedCellProxy.create(onA.ref, a.registry, RacingAuthoritySetInletProxy::class.java)
            as RacingAuthoritySetInletProxy).inlet.call
        ops.add(element)

        val writeStep = FutureTask<Boolean> { controller.step() }
        Thread.ofVirtual().start(writeStep)
        try {
            writeInsideCell.await(5, TimeUnit.SECONDS) shouldBe true
            a.replication.authorityOf(onA.ref)!!.transfer(pB)
        } finally {
            releaseWrite.countDown()
        }
        writeStep.get(5, TimeUnit.SECONDS) shouldBe true
        controller.runToIdle()

        listOf(onA.membership(), onB.membership()) shouldBe List(2) { setOf(element) }
        a.denialReasons().shouldBeEmpty()
        b.denialReasons().shouldBeEmpty()
    }

    @Test
    fun `checkpoint recovery retains replay defense and authority before peer catch-up`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB)
        val journal = InMemoryJournal()
        val defaultJournal = InMemoryJournal()
        val logicalId = UUID.randomUUID()
        val recoveredRef = CellRef(logicalId, 0)
        val donorRef = CellRef(logicalId, 1)
        val authority = WriteAuthority.Principal(pA)

        val originalRegistry = LocationRegistry()
        val originalHost = ManagedHost(
            scheduler = controller.scheduler(),
            registry = originalRegistry,
            journalFor = { ref -> if (ref == recoveredRef) journal else defaultJournal },
        )
        val originalReplication = Replication(originalRegistry)
        val original = SetCell<String>(recoveredRef).also {
            originalReplication.replicate(it, originalHost, authority, signing.signer(pA), signing.verifier)
        }
        originalHost.hosts(recoveredRef) shouldBe true
        originalHost.hosts(originalReplication.authorityOf(recoveredRef)!!.ref) shouldBe true

        val donorRegistry = LocationRegistry()
        val donorHost = ManagedHost(scheduler = controller.scheduler(), registry = donorRegistry)
        val donorBridge = ManagedHost(scheduler = controller.scheduler(), registry = donorRegistry)
        val donorSide = Peering.Side(donorRegistry, donorBridge, peer = pB)
        val donorReplication = Replication(donorRegistry)
        val donor = SetCell<String>(donorRef).also {
            donorReplication.replicate(it, donorHost, authority, signing.signer(pB), signing.verifier)
        }

        val beforeTransfer = signing.signed(logicalId, pA, 9, add("before", 9))
        val transfer = signing.signed(logicalId, pA, 10, TransferAuthority(pB))
        val afterTransfer = signing.signed(logicalId, pB, 11, add("after", 11))
        listOf(originalRegistry to recoveredRef, donorRegistry to donorRef).forEach { (registry, ref) ->
            val delta = (HostedCellProxy.create(ref, registry, Replication.ReplicaDeltaInlet::class.java)
                as Replication.ReplicaDeltaInlet).deltaInlet.call
            delta.propagate(beforeTransfer)
            delta.propagate(transfer)
            delta.propagate(afterTransfer)
        }
        controller.runToIdle()
        original.membership() shouldBe setOf("before", "after")
        donor.membership() shouldBe original.membership()
        originalReplication.authorityOf(recoveredRef)!!.retained().size shouldBe 3

        // Compaction removes the admitted envelopes from the frame tail. Recovery can
        // preserve replay defense only if the authority companion shares this exact
        // per-cell journal and contributes its own Stateful snapshot.
        originalHost.checkpoint(journal)

        val recoveredRegistry = LocationRegistry()
        val recoveredHost = ManagedHost(
            scheduler = controller.scheduler(),
            registry = recoveredRegistry,
            journalFor = { ref -> if (ref == recoveredRef) journal else defaultJournal },
        )
        val recoveredBridge = ManagedHost(scheduler = controller.scheduler(), registry = recoveredRegistry)
        val recoveredSide = Peering.Side(recoveredRegistry, recoveredBridge, peer = pA)
        val recoveredReplication = Replication(recoveredRegistry)
        val recoveredDeadLetters = mutableListOf<DeadLetter>()
        recoveredHost.deadLetterOutlet.subscribe(
            Use.fixed(Propagate { recoveredDeadLetters += it }, PortRef.generate()),
        )
        val recovered = SetCell<String>(recoveredRef).also {
            recoveredReplication.replicate(it, recoveredHost, authority, signing.signer(pA), signing.verifier)
        }
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()

        val recoveredAuthority = recoveredReplication.authorityOf(recoveredRef)!!
        recovered.membership() shouldBe setOf("before", "after")
        recoveredAuthority.retained().size shouldBe 3

        val recoveredDelta = (HostedCellProxy.create(
            recoveredRef,
            recoveredRegistry,
            Replication.ReplicaDeltaInlet::class.java,
        ) as Replication.ReplicaDeltaInlet).deltaInlet.call
        recoveredDelta.propagate(beforeTransfer)
        controller.runToIdle()
        recoveredDeadLetters.last().denial!!.reason shouldBe DenialReason.REPLAY
        recoveredAuthority.retained().size shouldBe 3

        val recoveredOps = (HostedCellProxy.create(
            recoveredRef,
            recoveredRegistry,
            AuthoritySetInletProxy::class.java,
        ) as AuthoritySetInletProxy).inlet.call
        recoveredOps.add("former-owner")
        controller.runToIdle()
        recovered.membership() shouldBe setOf("before", "after")
        recoveredDeadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
        }

        Peering.loopback(recoveredSide, donorSide)
        controller.runToIdle()
        recoveredAuthority.retained().size shouldBe 3
        donorReplication.authorityOf(donorRef)!!.retained().size shouldBe 3

        val donorOps = (HostedCellProxy.create(donorRef, donorRegistry, AuthoritySetInletProxy::class.java)
            as AuthoritySetInletProxy).inlet.call
        donorOps.add("later-by-b")
        controller.runToIdle()
        recovered.membership() shouldBe setOf("before", "after", "later-by-b")
        donor.membership() shouldBe recovered.membership()
        recoveredAuthority.retained().size shouldBe 4
        donorReplication.authorityOf(donorRef)!!.retained().size shouldBe 4
    }

    @Test
    fun `failed durability companion spawn rolls back the data replica and authority registration`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA)
        val registry = LocationRegistry()
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            quota = 1,
        )
        val replication = Replication(registry)
        val replica = SetCell<String>(CellRef(UUID.randomUUID(), 0))

        shouldThrow<IllegalStateException> {
            replication.replicate(
                replica,
                host,
                WriteAuthority.Principal(pA),
                signing.signer(pA),
                signing.verifier,
            )
        }

        host.hosts(replica.ref) shouldBe false
        host.subtreeCellCount() shouldBe 0
        registry.instances.replicasOf(replica.ref.id).shouldBeEmpty()
        registry.locate(replica.ref) shouldBe null
        replication.authorityOf(replica.ref) shouldBe null
    }

    @Test
    fun `transfer changes the writer on every replica and late join catch-up teaches the chain`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB, pC, pD)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        val c = AuthorityTestPeer(controller, pC, signing)
        Peering.loopback(a.side, b.side)
        Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side)
        val logicalId = UUID.randomUUID()
        val authority = WriteAuthority.Principal(pA)
        val onA = a.replica(logicalId, 0, authority)
        val onB = b.replica(logicalId, 1, authority)
        val onC = c.replica(logicalId, 2, authority)
        controller.runToIdle()

        a.replication.authorityOf(onA.ref)!!.transfer(pB)
        controller.runToIdle()
        b.ops(onB).add("by-b")
        controller.runToIdle()
        listOf(onA, onB, onC).map { it.membership() } shouldBe List(3) { setOf("by-b") }

        a.ops(onA).add("old-local")
        controller.runToIdle()
        onA.membership() shouldBe setOf("by-b")
        a.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
        }

        val oldRemote = signing.signed(logicalId, pA, 50, add("old-remote", 50))
        a.delta(onB.ref).propagate(oldRemote)
        a.delta(onC.ref).propagate(oldRemote)
        controller.runToIdle()
        listOf(b, c).forEach { peer ->
            peer.deadLetters.last().denial!!.run {
                reason shouldBe DenialReason.UNAUTHORIZED_WRITER
                principal shouldBe pA
            }
        }

        val beforeSecondTransfer = a.deadLetters.size
        a.replication.authorityOf(onA.ref)!!.transfer(pC)
        controller.runToIdle()
        a.deadLetters.size shouldBe beforeSecondTransfer + 1
        a.deadLetters.last().denial!!.reason shouldBe DenialReason.UNAUTHORIZED_WRITER

        val d = AuthorityTestPeer(controller, pD, signing)
        Peering.loopback(b.side, d.side)
        val onD = d.replica(logicalId, 3, authority)
        controller.runToIdle()
        onD.membership() shouldBe setOf("by-b")

        b.delta(onD.ref).propagate(signing.signed(logicalId, pA, 51, add("late-old", 51)))
        controller.runToIdle()
        onD.membership() shouldBe setOf("by-b")
        d.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
        }
    }

    @Test
    fun `former owner writes compare with its transfer counter in either arrival order`() {
        val signing = StubWriteSigning(pA, pB)
        val logicalId = UUID.randomUUID()
        val before = signing.signed(logicalId, pA, 9, add("before", 9))
        val transfer = signing.signed(logicalId, pA, 10, TransferAuthority(pB))
        val after = signing.signed(logicalId, pA, 11, add("after", 11))

        val writeThenTransfer = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(writeThenTransfer, before, signing).shouldBeInstanceOf<Admission.Admitted>()
        admitApply(writeThenTransfer, transfer, signing).shouldBeInstanceOf<Admission.Admitted>()

        val transferThenWrite = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(transferThenWrite, transfer, signing).shouldBeInstanceOf<Admission.Admitted>()
        admitApply(transferThenWrite, before, signing).shouldBeInstanceOf<Admission.Admitted>()

        listOf(writeThenTransfer, transferThenWrite).forEach { state ->
            state.admit(after, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
                DenialReason.UNAUTHORIZED_WRITER
            state.authorizesLocal(pB, add("new", 12)) shouldBe true
            state.authorizesLocal(pA, add("old", 12)) shouldBe false
        }
    }

    @Test
    fun `re-acquisition is refused and reordered competing transfers select the lower counter`() {
        val signing = StubWriteSigning(pA, pB, pC)
        val logicalId = UUID.randomUUID()
        val pToB = signing.signed(logicalId, pA, 10, TransferAuthority(pB))
        val bToP = signing.signed(logicalId, pB, 5, TransferAuthority(pA))
        val pToC = signing.signed(logicalId, pA, 20, TransferAuthority(pC))

        val causal = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(causal, pToB, signing).shouldBeInstanceOf<Admission.Admitted>()
        causal.admit(bToP, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER
        causal.admit(pToC, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER

        val reordered = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(reordered, pToC, signing).shouldBeInstanceOf<Admission.Admitted>()
        admitApply(reordered, pToB, signing).shouldBeInstanceOf<Admission.Admitted>()
        reordered.admit(bToP, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER

        val fullyReversed = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(fullyReversed, pToC, signing).shouldBeInstanceOf<Admission.Admitted>()
        fullyReversed.admit(bToP, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER
        admitApply(fullyReversed, pToB, signing).shouldBeInstanceOf<Admission.Admitted>()

        listOf(causal, reordered, fullyReversed).forEach { state ->
            state.authorizesLocal(pA, add("a", 30)) shouldBe false
            state.authorizesLocal(pB, add("b", 30)) shouldBe true
            state.authorizesLocal(pC, add("c", 30)) shouldBe false
        }
    }

    private fun admitApply(
        state: AuthorityState,
        write: SignedWrite,
        signing: StubWriteSigning,
    ): Admission = state.admit(write, signing.verifier).also { admission ->
        if (admission is Admission.Admitted) state.apply(write, admission.payload)
    }

    private fun add(element: String, counter: Long): SetDelta<String> =
        SetDelta(adds = mapOf(element to setOf(Timestamp(UUID(1, counter), counter))))
}
