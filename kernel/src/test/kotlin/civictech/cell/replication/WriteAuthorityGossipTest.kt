package civictech.cell.replication

import civictech.cell.BoundarySeam
import civictech.cell.DenialReason
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.OrMapCell
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class WriteAuthorityGossipTest {
    private val pA = PeerId("writer-a")
    private val pB = PeerId("writer-b")
    private val pC = PeerId("writer-c")

    @Test
    fun `principal owner converges and a foreign local write is refused before mutation`() {
        val mesh = threePeerMesh(WriteAuthority.Principal(pA))

        mesh.a.ops(mesh.onA).add("x")
        mesh.controller.runToIdle()
        mesh.memberships() shouldBe listOf(setOf("x"), setOf("x"), setOf("x"))
        val peersAndCells = listOf(mesh.a to mesh.onA, mesh.b to mesh.onB, mesh.c to mesh.onC)
        peersAndCells.map { (peer, cell) -> mesh.sink(peer, cell.ref).denialCount } shouldBe listOf(0L, 0L, 0L)
        peersAndCells.map { (peer, _) -> peer.host.supervisionAccounting().restarts } shouldBe listOf(0L, 0L, 0L)

        val beforeLetters = mesh.b.deadLetters.size
        val beforeDenials = mesh.sink(mesh.b, mesh.onB.ref).denialCount
        mesh.b.ops(mesh.onB).add("y")
        mesh.controller.runToIdle()

        mesh.memberships() shouldBe listOf(setOf("x"), setOf("x"), setOf("x"))
        mesh.sink(mesh.b, mesh.onB.ref).denialCount shouldBe beforeDenials + 1
        mesh.sink(mesh.a, mesh.onA.ref).denialCount shouldBe 0L
        mesh.sink(mesh.c, mesh.onC.ref).denialCount shouldBe 0L
        mesh.b.deadLetters.size shouldBe beforeLetters + 1
        mesh.b.deadLetters.last().denial!!.run {
            seam shouldBe BoundarySeam.INTEGRITY
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pB
            subject shouldBe "SetCell#add"
        }
        mesh.b.host.supervisionAccounting().restarts shouldBe 0L
    }

    @Test
    fun `an author's envelope reaches a late peer through a relay catch-up`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB, pC)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        val c = AuthorityTestPeer(controller, pC, signing)
        Peering.loopback(a.side, b.side)
        val ac = Peering.loopback(a.side, c.side)
        val bc = Peering.loopback(b.side, c.side)
        ac.partition()
        bc.partition()
        val logicalId = UUID.randomUUID()
        val onA = a.replica(logicalId, 0, WriteAuthority.Principal(pA))
        val onB = b.replica(logicalId, 1, WriteAuthority.Principal(pA))
        val onC = c.replica(logicalId, 2, WriteAuthority.Principal(pA))
        controller.runToIdle()

        a.ops(onA).add("z")
        controller.runToIdle()
        onB.membership() shouldBe setOf("z")
        onC.membership().shouldBeEmpty()

        bc.heal()
        controller.runToIdle()
        onC.membership() shouldBe setOf("z")
        c.replication.authorityOf(onC.ref)!!.boundaryDenials["write-authority"]!!.denialCount shouldBe 0L
    }

    @Test
    fun `forgery replay unsigned input and unauthorized batch member are independently refused`() {
        val mesh = threePeerMesh(WriteAuthority.Principal(pA))
        val logicalId = mesh.onC.ref.id
        val cSink = mesh.sink(mesh.c, mesh.onC.ref)

        val forged = mesh.signing.signed(logicalId, pA, 100, add("forged", 100), signedBy = pB)
        mesh.b.delta(mesh.onC.ref).propagate(forged)
        mesh.controller.runToIdle()
        mesh.onC.membership().shouldBeEmpty()
        mesh.c.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.BAD_SIGNATURE
            principal shouldBe pA
        }

        val admitted = mesh.signing.signed(logicalId, pA, 101, add("admitted", 101))
        mesh.b.delta(mesh.onC.ref).propagate(admitted)
        mesh.controller.runToIdle()
        mesh.onC.membership() shouldBe setOf("admitted")
        val beforeReplay = cSink.denialCount
        mesh.b.delta(mesh.onC.ref).propagate(admitted)
        mesh.controller.runToIdle()
        cSink.denialCount shouldBe beforeReplay + 1
        mesh.c.deadLetters.last().denial!!.reason shouldBe DenialReason.REPLAY

        mesh.b.delta(mesh.onC.ref).propagate(add("unsigned", 102))
        mesh.controller.runToIdle()
        mesh.onC.membership() shouldBe setOf("admitted")
        mesh.c.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNSIGNED
            principal shouldBe null
            detail!!.contains("relay=") shouldBe true
        }

        val unauthorized = mesh.signing.signed(logicalId, pB, 103, add("foreign", 103))
        val valid = mesh.signing.signed(logicalId, pA, 104, add("batch-ok", 104))
        mesh.b.delta(mesh.onC.ref).propagate(SignedWriteBatch(listOf(unauthorized, valid)))
        mesh.controller.runToIdle()
        mesh.onC.membership() shouldBe setOf("admitted", "batch-ok")
        mesh.c.deadLetters.mapNotNull { it.denial }.any {
            it.reason == DenialReason.UNAUTHORIZED_WRITER && it.principal == pB
        } shouldBe true
    }

    @Test
    fun `per-element owners admit their own elements and refuse foreign elements locally and remotely`() {
        val authority = WriteAuthority.PerElementOwner(OwnerOf { element ->
            when ((element as? String)?.substringBefore(':')) {
                "a" -> pA
                "b" -> pB
                else -> null
            }
        })
        val mesh = threePeerMesh(authority)

        mesh.a.ops(mesh.onA).add("a:one")
        mesh.b.ops(mesh.onB).add("b:one")
        mesh.controller.runToIdle()
        mesh.memberships() shouldBe List(3) { setOf("a:one", "b:one") }

        mesh.b.ops(mesh.onB).add("a:evil")
        mesh.controller.runToIdle()
        mesh.memberships() shouldBe List(3) { setOf("a:one", "b:one") }
        mesh.b.deadLetters.last().denial!!.reason shouldBe DenialReason.UNAUTHORIZED_WRITER

        val forgedOwnership = mesh.signing.signed(mesh.onA.ref.id, pB, 200, add("a:forged", 200))
        mesh.b.delta(mesh.onA.ref).propagate(forgedOwnership)
        mesh.controller.runToIdle()
        mesh.a.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pB
        }
    }

    @Test
    fun `or-map local gate refuses put and remove before either can mint a dot`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB)
        val b = AuthorityTestPeer(controller, pB, signing)
        val authority = WriteAuthority.PerElementOwner(OwnerOf { if (it == "owned-by-a") pA else null })
        val map = OrMapCell<String, String>()
        b.replication.replicate(map, b.host, authority, b.signer, signing.verifier)
        controller.runToIdle()

        b.mapOps(map).put("owned-by-a", "value")
        b.mapOps(map).remove("owned-by-a")
        controller.runToIdle()

        map.membership().shouldBeEmpty()
        b.replication.authorityOf(map.ref)!!.boundaryDenials["write-authority"]!!.denialCount shouldBe 2L
    }

    /**
     * Pre-change open-path baseline: after peering and replica announcements
     * settle, one SetCell add emits BASELINE_OPEN_A_TO_B_FRAMES A-to-B frames.
     * The authority overload must leave that count and the null adapter intact.
     */
    @Test
    fun `open authority constructs no adapter and preserves the baseline frame count`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        val frames = AtomicInteger()
        Peering.loopback(
            a.side,
            b.side,
            interposeAToB = Peering.FrameInterpose { frame ->
                frames.incrementAndGet()
                listOf(frame)
            },
        )
        val logicalId = UUID.randomUUID()
        val onA = a.replica(logicalId, 0, WriteAuthority.Open)
        val onB = b.replica(logicalId, 1, WriteAuthority.Open)
        controller.runToIdle()
        frames.set(0)

        a.ops(onA).add("open")
        controller.runToIdle()

        onB.membership() shouldBe setOf("open")
        a.replication.authorityOf(onA.ref) shouldBe null
        b.replication.authorityOf(onB.ref) shouldBe null
        a.registry.localRefs().none { a.registry.describe(it) == AuthorityGossip::class.java } shouldBe true
        b.registry.localRefs().none { b.registry.describe(it) == AuthorityGossip::class.java } shouldBe true
        frames.get() shouldBe BASELINE_OPEN_A_TO_B_FRAMES
    }

    private fun threePeerMesh(authority: WriteAuthority): Mesh {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB, pC)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        val c = AuthorityTestPeer(controller, pC, signing)
        Peering.loopback(a.side, b.side)
        Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side)
        val logicalId = UUID.randomUUID()
        val onA = a.replica(logicalId, 0, authority)
        val onB = b.replica(logicalId, 1, authority)
        val onC = c.replica(logicalId, 2, authority)
        controller.runToIdle()
        return Mesh(controller, signing, a, b, c, onA, onB, onC)
    }

    private fun add(element: String, counter: Long): SetDelta<String> =
        SetDelta(adds = mapOf(element to setOf(Timestamp(UUID(0, counter), counter))))

    private data class Mesh(
        val controller: SimulationController,
        val signing: StubWriteSigning,
        val a: AuthorityTestPeer,
        val b: AuthorityTestPeer,
        val c: AuthorityTestPeer,
        val onA: civictech.cell.data.SetCell<String>,
        val onB: civictech.cell.data.SetCell<String>,
        val onC: civictech.cell.data.SetCell<String>,
    ) {
        fun memberships(): List<Set<String>> = listOf(onA.membership(), onB.membership(), onC.membership())

        fun sink(peer: AuthorityTestPeer, ref: civictech.cell.CellRef) =
            peer.replication.authorityOf(ref)!!.boundaryDenials["write-authority"]!!
    }

    private companion object {
        const val BASELINE_OPEN_A_TO_B_FRAMES = 5
    }
}
