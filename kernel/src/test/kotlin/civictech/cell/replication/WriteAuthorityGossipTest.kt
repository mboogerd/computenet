package civictech.cell.replication

import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
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

    @Test
    fun `a rebound owner keeps signing above the counters its peers retained`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        Peering.loopback(a.side, b.side)
        val logicalId = UUID.randomUUID()
        val onA = a.replica(logicalId, 0, WriteAuthority.Principal(pA))
        val onB = b.replica(logicalId, 1, WriteAuthority.Principal(pA))
        controller.runToIdle()
        a.ops(onA).add("before-rebind")
        controller.runToIdle()

        val candidate = SetCell<String>(onA.ref)
        a.replication.rebind(onA, candidate, a.host)
        controller.runToIdle()
        a.ops(candidate).add("after-rebind")
        controller.runToIdle()

        candidate.membership() shouldBe setOf("before-rebind", "after-rebind")
        onB.membership() shouldBe setOf("before-rebind", "after-rebind")
        b.denialReasons().shouldBeEmpty()
    }

    @Test
    fun `an author checkpoint bounds every replica's retained log through a current relay`() {
        run {
            val controller = SimulationController()
            val signing = StubWriteSigning(pA, pB, pC)
            val a = AuthorityTestPeer(controller, pA, signing)
            val b = AuthorityTestPeer(controller, pB, signing)
            val c = AuthorityTestPeer(controller, pC, signing)
            Peering.loopback(a.side, b.side)
            val ac = Peering.loopback(a.side, c.side)
            Peering.loopback(b.side, c.side)
            val logicalId = UUID.randomUUID()
            val authority = WriteAuthority.Principal(pA)
            val onA = a.replica(logicalId, 0, authority)
            val onB = b.replica(logicalId, 1, authority)
            val onC = c.replica(logicalId, 2, authority)
            controller.runToIdle()

            repeat(3) { a.ops(onA).add("manual-$it") }
            controller.runToIdle()
            listOf(onA, onB, onC).map { it.membership() } shouldBe
                List(3) { setOf("manual-0", "manual-1", "manual-2") }
            listOf(a to onA, b to onB, c to onC).map { (peer, cell) ->
                peer.replication.authorityOf(cell.ref)!!.retained().size
            } shouldBe listOf(3, 3, 3)

            ac.partition()
            a.replication.authorityOf(onA.ref)!!.compact()
            controller.runToIdle()

            listOf(a to onA, b to onB, c to onC).map { (peer, cell) ->
                peer.replication.authorityOf(cell.ref)!!.retained().size
            } shouldBe listOf(1, 1, 1)
            listOf(onA, onB, onC).map { it.membership() } shouldBe
                List(3) { setOf("manual-0", "manual-1", "manual-2") }
            listOf(a, b, c).flatMap { it.denialReasons() }.shouldBeEmpty()
        }

        run {
            val controller = SimulationController()
            val signing = StubWriteSigning(pA, pB, pC)
            val a = AuthorityTestPeer(controller, pA, signing)
            val b = AuthorityTestPeer(controller, pB, signing)
            val c = AuthorityTestPeer(controller, pC, signing)
            Peering.loopback(a.side, b.side)
            val ac = Peering.loopback(a.side, c.side)
            Peering.loopback(b.side, c.side)
            val logicalId = UUID.randomUUID()
            val authority = WriteAuthority.Principal(pA)
            val onA = a.replica(logicalId, 0, authority)
            val onB = b.replica(logicalId, 1, authority)
            val onC = c.replica(logicalId, 2, authority)
            controller.runToIdle()

            repeat(AuthorityGossip.DEFAULT_COMPACT_EVERY - 1) { a.ops(onA).add("auto-$it") }
            controller.runToIdle()
            listOf(a to onA, b to onB, c to onC).map { (peer, cell) ->
                peer.replication.authorityOf(cell.ref)!!.retained().size
            } shouldBe List(3) { AuthorityGossip.DEFAULT_COMPACT_EVERY - 1 }

            ac.partition()
            a.ops(onA).add("auto-trigger")
            controller.runToIdle()
            a.ops(onA).add("auto-after")
            controller.runToIdle()

            val expectedMembership = buildSet {
                repeat(AuthorityGossip.DEFAULT_COMPACT_EVERY - 1) { add("auto-$it") }
                add("auto-trigger")
                add("auto-after")
            }
            listOf(onA, onB, onC).map { it.membership() } shouldBe List(3) { expectedMembership }
            listOf(a to onA, b to onB, c to onC).forEach { (peer, cell) ->
                val retained = peer.replication.authorityOf(cell.ref)!!.retained()
                retained.size shouldBe 2
                retained.all { it.author == pA } shouldBe true
                retained.map { WriteAuthorityBytes.decodePayload(it.payload) }.run {
                    count { it is AuthorCheckpoint } shouldBe 1
                    count { it is SetDelta<*> } shouldBe 1
                }
            }
            listOf(a, b, c).flatMap { it.denialReasons() }.shouldBeEmpty()
        }
    }

    @Test
    fun `a late joiner rebuilds data and authority chain from a compacted catch-up`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB, pC)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        val c = AuthorityTestPeer(controller, pC, signing)
        Peering.loopback(a.side, b.side)
        val logicalId = UUID.randomUUID()
        val authority = WriteAuthority.Principal(pA)
        val onA = a.replica(logicalId, 0, authority)
        val onB = b.replica(logicalId, 1, authority)
        val onC = c.replica(logicalId, 2, authority)
        controller.runToIdle()

        a.ops(onA).add("before-one")
        a.ops(onA).add("before-two")
        controller.runToIdle()
        a.replication.authorityOf(onA.ref)!!.compact()
        controller.runToIdle()
        onC.membership().shouldBeEmpty()

        Peering.loopback(b.side, c.side)
        controller.runToIdle()

        onC.membership() shouldBe onB.membership()
        val onBRetained = b.replication.authorityOf(onB.ref)!!.retained()
        val onCRetained = c.replication.authorityOf(onC.ref)!!.retained()
        onCRetained.map { it.author to it.counter }.toSet() shouldBe
            onBRetained.map { it.author to it.counter }.toSet()
        c.denialReasons().shouldBeEmpty()

        a.replication.authorityOf(onA.ref)!!.transfer(pB)
        controller.runToIdle()
        b.ops(onB).add("new-principal")
        a.ops(onA).add("former-principal")
        controller.runToIdle()

        listOf(onA, onB, onC).map { it.membership() } shouldBe
            List(3) { setOf("before-one", "before-two", "new-principal") }
        a.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
        }
        b.denialReasons().shouldBeEmpty()
        c.denialReasons().shouldBeEmpty()
    }

    @Test
    fun `covered replays are silently deduplicated while uncovered replays stay reported`() {
        val mesh = threePeerMesh(WriteAuthority.Principal(pA))
        val cAuthority = mesh.c.replication.authorityOf(mesh.onC.ref)!!
        val cSink = mesh.sink(mesh.c, mesh.onC.ref)

        mesh.a.ops(mesh.onA).add("covered")
        mesh.controller.runToIdle()
        val covered = mesh.a.replication.authorityOf(mesh.onA.ref)!!.retained().single()
        mesh.a.replication.authorityOf(mesh.onA.ref)!!.compact()
        mesh.controller.runToIdle()
        val compacted = cAuthority.retained()
        val membership = mesh.onC.membership()
        val beforeCoveredReplay = cSink.denialCount

        mesh.b.delta(mesh.onC.ref).propagate(covered)
        mesh.controller.runToIdle()

        cAuthority.retained() shouldBe compacted
        mesh.onC.membership() shouldBe membership
        cSink.denialCount shouldBe beforeCoveredReplay

        val checkpointCounter = compacted.single().counter
        val uncovered = mesh.signing.signed(
            mesh.onC.ref.id,
            pA,
            checkpointCounter + 1,
            add("uncovered", checkpointCounter + 1),
        )
        mesh.b.delta(mesh.onC.ref).propagate(uncovered)
        mesh.controller.runToIdle()
        val beforeUncoveredReplay = cSink.denialCount
        mesh.b.delta(mesh.onC.ref).propagate(uncovered)
        mesh.controller.runToIdle()
        cSink.denialCount shouldBe beforeUncoveredReplay + 1
        mesh.c.deadLetters.last().denial!!.reason shouldBe DenialReason.REPLAY

        val forgedCovered = mesh.signing.signed(
            mesh.onC.ref.id,
            pA,
            covered.counter,
            add("forged-covered", covered.counter),
            signedBy = pB,
        )
        mesh.b.delta(mesh.onC.ref).propagate(forgedCovered)
        mesh.controller.runToIdle()
        mesh.c.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.BAD_SIGNATURE
            principal shouldBe pA
        }
    }

    @Test
    fun `a forging relay cannot fold or drop another author's history`() {
        val mesh = threePeerMesh(WriteAuthority.Principal(pA))
        val cAuthority = mesh.c.replication.authorityOf(mesh.onC.ref)!!
        val forged = mesh.signing.signed(
            mesh.onC.ref.id,
            pA,
            200,
            AuthorCheckpoint(199, add("forged-a", 199)),
            signedBy = pB,
        )
        val beforeForged = cAuthority.retained()
        val beforeForgedMembership = mesh.onC.membership()
        mesh.b.delta(mesh.onC.ref).propagate(forged)
        mesh.controller.runToIdle()
        cAuthority.retained() shouldBe beforeForged
        mesh.onC.membership() shouldBe beforeForgedMembership
        mesh.c.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.BAD_SIGNATURE
            principal shouldBe pA
        }

        // The forged checkpoint did not establish coverage through 199.
        val afterForgedProbe = mesh.signing.signed(mesh.onC.ref.id, pA, 199, add("a-probe", 199))
        mesh.b.delta(mesh.onC.ref).propagate(afterForgedProbe)
        mesh.controller.runToIdle()
        mesh.onC.membership() shouldBe setOf("a-probe")

        val unauthorized = mesh.signing.signed(
            mesh.onC.ref.id,
            pB,
            300,
            AuthorCheckpoint(299, add("unauthorized-b", 299)),
        )
        val beforeUnauthorized = cAuthority.retained()
        val beforeUnauthorizedMembership = mesh.onC.membership()
        mesh.b.delta(mesh.onC.ref).propagate(unauthorized)
        mesh.controller.runToIdle()
        cAuthority.retained() shouldBe beforeUnauthorized
        mesh.onC.membership() shouldBe beforeUnauthorizedMembership
        mesh.c.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pB
        }

        // Once B legitimately becomes principal, its counter 299 is still live.
        val transfer = mesh.signing.signed(mesh.onC.ref.id, pA, 250, TransferAuthority(pB))
        mesh.b.delta(mesh.onC.ref).propagate(transfer)
        mesh.controller.runToIdle()
        val afterUnauthorizedProbe = mesh.signing.signed(
            mesh.onC.ref.id,
            pB,
            299,
            add("b-probe", 299),
        )
        mesh.b.delta(mesh.onC.ref).propagate(afterUnauthorizedProbe)
        mesh.controller.runToIdle()
        mesh.onC.membership() shouldBe setOf("a-probe", "b-probe")

        val perElement = WriteAuthority.PerElementOwner(OwnerOf { element ->
            when ((element as? String)?.substringBefore(':')) {
                "a" -> pA
                "b" -> pB
                else -> null
            }
        })
        val owned = threePeerMesh(perElement)
        owned.a.ops(owned.onA).add("a:kept")
        owned.b.ops(owned.onB).add("b:folded")
        owned.controller.runToIdle()
        val aEnvelopes = owned.a.replication.authorityOf(owned.onA.ref)!!.retained()
            .filter { it.author == pA }
        owned.b.replication.authorityOf(owned.onB.ref)!!.compact()
        owned.controller.runToIdle()

        listOf(owned.a to owned.onA, owned.b to owned.onB, owned.c to owned.onC).forEach { (peer, cell) ->
            val retained = peer.replication.authorityOf(cell.ref)!!.retained()
            retained.filter { it.author == pA } shouldBe aEnvelopes
            retained.count { it.author == pB } shouldBe 1
            WriteAuthorityBytes.decodePayload(retained.single { it.author == pB }.payload)
                .let { it is AuthorCheckpoint } shouldBe true
        }
        owned.memberships() shouldBe List(3) { setOf("a:kept", "b:folded") }
        listOf(owned.a, owned.b, owned.c).flatMap { it.denialReasons() }.shouldBeEmpty()
    }

    @Test
    fun `compact after transfer-out is refused like any local op`() {
        val mesh = threePeerMesh(WriteAuthority.Principal(pA))
        mesh.a.ops(mesh.onA).add("before-transfer")
        mesh.controller.runToIdle()
        mesh.a.replication.authorityOf(mesh.onA.ref)!!.transfer(pB)
        mesh.controller.runToIdle()
        val before = listOf(mesh.a to mesh.onA, mesh.b to mesh.onB, mesh.c to mesh.onC).map { (peer, cell) ->
            peer.replication.authorityOf(cell.ref)!!.retained()
        }
        val beforeRemoteDenials = listOf(mesh.b, mesh.c).map { it.denialReasons() }

        mesh.a.replication.authorityOf(mesh.onA.ref)!!.compact()
        mesh.controller.runToIdle()

        listOf(mesh.a to mesh.onA, mesh.b to mesh.onB, mesh.c to mesh.onC).map { (peer, cell) ->
            peer.replication.authorityOf(cell.ref)!!.retained()
        } shouldBe before
        listOf(mesh.b, mesh.c).map { it.denialReasons() } shouldBe beforeRemoteDenials
        mesh.a.deadLetters.last().denial!!.run {
            seam shouldBe BoundarySeam.INTEGRITY
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
            subject shouldBe "SetCell#compact"
        }
    }

    @Test
    fun `compaction survives journal checkpoint and recovery`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA)
        val journal = InMemoryJournal()
        val defaultJournal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID(), 0)
        val authority = WriteAuthority.Principal(pA)

        val originalRegistry = LocationRegistry()
        val originalHost = ManagedHost(
            scheduler = controller.scheduler(),
            registry = originalRegistry,
            journalFor = { selected -> if (selected == ref) journal else defaultJournal },
        )
        val originalReplication = Replication(originalRegistry)
        val original = SetCell<String>(ref).also {
            originalReplication.replicate(it, originalHost, authority, signing.signer(pA), signing.verifier)
        }
        val originalOps = (HostedCellProxy.create(ref, originalRegistry, AuthoritySetInletProxy::class.java)
            as AuthoritySetInletProxy).inlet.call
        originalOps.add("before-checkpoint")
        controller.runToIdle()
        val originalAuthority = originalReplication.authorityOf(ref)!!
        val covered = originalAuthority.retained().single()
        originalAuthority.compact()
        controller.runToIdle()
        val beforeCrash = originalAuthority.retained()
        beforeCrash.size shouldBe 1
        originalHost.checkpoint(journal)

        val recoveredRegistry = LocationRegistry()
        val recoveredHost = ManagedHost(
            scheduler = controller.scheduler(),
            registry = recoveredRegistry,
            journalFor = { selected -> if (selected == ref) journal else defaultJournal },
        )
        val recoveredReplication = Replication(recoveredRegistry)
        val recoveredDeadLetters = mutableListOf<DeadLetter>()
        recoveredHost.deadLetterOutlet.subscribe(
            Use.fixed(Propagate { recoveredDeadLetters += it }, PortRef.generate()),
        )
        val recovered = SetCell<String>(ref).also {
            recoveredReplication.replicate(it, recoveredHost, authority, signing.signer(pA), signing.verifier)
        }
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()

        val recoveredAuthority = recoveredReplication.authorityOf(ref)!!
        recovered.membership() shouldBe setOf("before-checkpoint")
        recoveredAuthority.retained() shouldBe beforeCrash
        val recoveredDelta = (HostedCellProxy.create(
            ref,
            recoveredRegistry,
            Replication.ReplicaDeltaInlet::class.java,
        ) as Replication.ReplicaDeltaInlet).deltaInlet.call
        val beforeReplayDenials = recoveredDeadLetters.size
        recoveredDelta.propagate(covered)
        controller.runToIdle()
        recoveredAuthority.retained() shouldBe beforeCrash
        recoveredDeadLetters.size shouldBe beforeReplayDenials

        val recoveredOps = (HostedCellProxy.create(ref, recoveredRegistry, AuthoritySetInletProxy::class.java)
            as AuthoritySetInletProxy).inlet.call
        recoveredOps.add("after-recovery")
        controller.runToIdle()

        recovered.membership() shouldBe setOf("before-checkpoint", "after-recovery")
        val recoveredWrites = recoveredAuthority.retained()
        recoveredWrites.size shouldBe 2
        val checkpointCounter = beforeCrash.single().counter
        (
            recoveredWrites.single {
                WriteAuthorityBytes.decodePayload(it.payload) is SetDelta<*>
            }.counter > checkpointCounter
        ) shouldBe true
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
