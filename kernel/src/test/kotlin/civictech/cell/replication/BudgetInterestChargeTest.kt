package civictech.cell.replication

import civictech.cell.BoundarySeam
import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.RecordingLedger
import civictech.cell.ThrowingLedger
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeClosedException
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.AuthLevel
import civictech.cell.link.IdentityResolution
import civictech.cell.link.IdentityStatement
import civictech.cell.link.IssuerId
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerIdentityBinding
import civictech.cell.link.PeerStamp
import civictech.cell.link.UnboundReason
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.PeerCredentials
import civictech.cell.wire.Peering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ECO1 F5 T2 (`computenet-jtof7.2`): a fresh remote data-replica link charges
 * Interest and holds Retention on the local host. Delivered-watermark companion
 * links are deliberately free, so every admitted data pair produces exactly two
 * ledger calls. Refusals are host boundary denials and never form the refused
 * outbound link.
 */
class BudgetInterestChargeTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Credentials(
        override val keyId: KeyId,
        override val peerId: PeerId,
        override val statements: List<IdentityStatement> = emptyList(),
    ) : PeerCredentials {
        override val publicKey: ByteArray = "public-key-of-${keyId.name}".toByteArray()
        override fun sign(message: ByteArray): ByteArray = message
    }

    private class CountingLedger(private val inner: BudgetLedger) : BudgetLedger {
        val undoCalls = AtomicInteger()
        val undoneClasses = CopyOnWriteArrayList<ClaimClass>()

        override fun charge(claim: BudgetClaim): BudgetOutcome =
            when (val outcome = inner.charge(claim)) {
                is BudgetOutcome.Refused -> outcome
                is BudgetOutcome.Admitted -> BudgetOutcome.Admitted {
                    undoCalls.incrementAndGet()
                    undoneClasses += claim.claimClass
                    outcome.undo()
                }
            }
    }

    private class BlockingRetentionLedger(private val inner: BudgetLedger) : BudgetLedger {
        val retentionCharged = CountDownLatch(1)
        val finishRetentionCharge = CountDownLatch(1)

        override fun charge(claim: BudgetClaim): BudgetOutcome {
            val outcome = inner.charge(claim)
            if (claim.claimClass == ClaimClass.Retention && outcome is BudgetOutcome.Admitted) {
                retentionCharged.countDown()
                check(finishRetentionCharge.await(20, TimeUnit.SECONDS)) {
                    "the concurrent unpublish never reached its callback"
                }
            }
            return outcome
        }
    }

    private class Peer(
        val controller: SimulationController,
        peer: PeerId?,
        budget: BudgetLedger = BudgetLedger.Unlimited,
        credentials: PeerCredentials? = null,
        identityBinding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = budget)
        private val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(
            registry,
            bridgeHost,
            peer = peer,
            credentials = credentials,
            identityBinding = identityBinding,
        )
        val replication = Replication(registry)
        val letters = CopyOnWriteArrayList<DeadLetter>()

        init {
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    Propagate<DeadLetter> { letters += it },
                    PortRef.generate(),
                ),
            )
        }

        fun replica(logicalId: UUID, instanceId: Long): SetCell<String> =
            SetCell<String>(civictech.cell.CellRef(logicalId, instanceId)).also {
                replication.replicate(it, host)
            }

        fun ops(replica: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(replica.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
    }

    private class Rig(
        qBudget: BudgetLedger,
        pPeer: PeerId? = P,
        pCredentials: PeerCredentials? = null,
        qCredentials: PeerCredentials? = null,
        qBinding: PeerIdentityBinding = PeerIdentityBinding.Interim,
    ) {
        val controller = SimulationController(0)
        val p = Peer(controller, pPeer, credentials = pCredentials)
        val q = Peer(controller, Q, budget = qBudget, credentials = qCredentials, identityBinding = qBinding)
        val link = Peering.loopback(p.side, q.side)

        fun formPair(logicalId: UUID = UUID.randomUUID()): Pair<SetCell<String>, SetCell<String>> {
            val onQ = q.replica(logicalId, 1)
            val onP = p.replica(logicalId, 0)
            controller.runToIdle()
            return onQ to onP
        }
    }

    private companion object {
        const val N = 10L
        val P = PeerId("p")
        val Q = PeerId("q")
        val P_KEY = KeyId("p-key-1")
        val Q_KEY = KeyId("q-key-1")
        val ANCHOR_A = IssuerId("anchor-A")

        fun pStatement() = IdentityStatement(P, P_KEY, ANCHOR_A, 1, 0, Long.MAX_VALUE, ByteArray(0))

        val statementReading = PeerIdentityBinding { key, presented ->
            presented.firstOrNull { it.keyId == key }
                ?.let { IdentityResolution.Bound(it.name, it.issuer, it) }
                ?: IdentityResolution.Unbound(UnboundReason.NO_STATEMENT)
        }
    }

    @Test
    fun `formation charges Interest then holds Retention and gossip still delivers`() {
        val ledger = RecordingLedger("q-host", initial = N)
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()

        ledger.balance(P, ClaimClass.Interest) shouldBe N - 1
        ledger.balance(P, ClaimClass.Retention) shouldBe N - 1
        ledger.charges.size shouldBe 2
        ledger.charges[0] shouldBe BudgetClaim(
            PeerStamp(P),
            ClaimClass.Interest,
            key = onQ.ref to onP.ref,
        )
        ledger.charges[1].claimClass shouldBe ClaimClass.Retention
        ledger.charges[1].hold shouldBe true
        ledger.charges[1].key shouldBe null
        rig.q.replication.linkCountAmong(setOf(onQ.ref, onP.ref)) shouldBe 1
        rig.q.host.boundaryDenialCount() shouldBe 0L

        rig.q.ops(onQ).add("from-q")
        rig.controller.runToIdle()
        onP.membership() shouldBe setOf("from-q")
    }

    @Test
    fun `the retained authenticated issuer stamp is charged without re-resolution`() {
        val ledger = RecordingLedger("q-host", initial = N)
        val rig = Rig(
            qBudget = ledger,
            pCredentials = Credentials(P_KEY, P, listOf(pStatement())),
            qCredentials = Credentials(Q_KEY, Q),
            qBinding = statementReading,
        )
        val (_, onP) = rig.formPair()
        val retained = (rig.q.registry.location(onP.ref) as LocationRegistry.Remote).announcer

        ledger.charges.first().stamp shouldBe PeerStamp(P, AuthLevel.Authenticated, ANCHOR_A)
        ledger.charges.first().stamp shouldBe retained
    }

    @Test
    fun `eviction releases the Retention hold exactly once and keeps the Interest debit`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val rig = Rig(counting)
        val (onQ, _) = rig.formPair()

        rig.q.replication.evict(onQ, rig.q.host) shouldBe true
        rig.controller.runToIdle()

        recording.balance(P, ClaimClass.Retention) shouldBe N
        recording.balance(P, ClaimClass.Interest) shouldBe N - 1
        recording.charges.size shouldBe 2
        counting.undoCalls.get() shouldBe 1
    }

    @Test
    fun `unpublish overlapping Retention admission releases the pending hold exactly once`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val blocking = BlockingRetentionLedger(counting)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = blocking)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        replication.replicate(local, host)
        controller.runToIdle()

        val remote = CellRef(logicalId, 0)
        val sink = InvocationSink { }
        val releaseCharge = registry.onUnpublish { ref ->
            if (ref == remote) blocking.finishRetentionCharge.countDown()
        }
        val publishing = FutureTask {
            check(
                registry.publishFromPeer(
                    remote,
                    sink,
                    peer = P,
                    announcer = PeerStamp(P),
                ) == null,
            ) { "the remote publication was unexpectedly refused" }
        }
        Thread.ofVirtual().start(publishing)

        check(blocking.retentionCharged.await(20, TimeUnit.SECONDS)) {
            "the concurrent publication never reached its Retention charge"
        }
        registry.unpublishFromPeer(remote, P, sink) shouldBe null
        publishing.get(20, TimeUnit.SECONDS)
        releaseCharge.close()

        recording.balance(P, ClaimClass.Retention) shouldBe N
        recording.balance(P, ClaimClass.Interest) shouldBe N
        counting.undoCalls.get() shouldBe 2
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 0
    }

    @Test
    fun `link formation failure releases a fresh Retention hold and refunds Interest`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = counting)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        val failure = IllegalStateException("link formation failed")
        val remote = CellRef(logicalId, 0)
        val sink = InvocationSink { }
        replication.beforeLinkInstall = { candidate, target ->
            if (candidate === local && target == remote) throw failure
        }
        registry.publishFromPeer(remote, sink, peer = P, announcer = PeerStamp(P)) shouldBe null

        shouldThrow<IllegalStateException> {
            replication.replicate(local, host)
        } shouldBe failure
        controller.runToIdle()

        recording.balance(P, ClaimClass.Interest) shouldBe N
        recording.balance(P, ClaimClass.Retention) shouldBe N
        recording.charges.size shouldBe 2
        counting.undoCalls.get() shouldBe 2
        counting.undoneClasses.count { it == ClaimClass.Interest } shouldBe 1
        counting.undoneClasses.count { it == ClaimClass.Retention } shouldBe 1
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 0

        // The failed call must not leave a hidden hold-map entry that makes the
        // next announcement re-link for free.
        replication.beforeLinkInstall = null
        registry.publishFromPeer(remote, sink, peer = P, announcer = PeerStamp(P)) shouldBe null
        controller.runToIdle()

        recording.charges.map { it.claimClass } shouldBe
            listOf(ClaimClass.Interest, ClaimClass.Retention, ClaimClass.Interest, ClaimClass.Retention)
        recording.balance(P, ClaimClass.Interest) shouldBe N - 1
        recording.balance(P, ClaimClass.Retention) shouldBe N - 1
        counting.undoCalls.get() shouldBe 2
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 1
    }

    @Test
    fun `link formation failure preserves a Retention hold carried into rebind`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val rig = Rig(counting)
        val (incumbent, remote) = rig.formPair()
        val candidate = SetCell<String>(incumbent.ref)
        val failure = IllegalStateException("rebind formation failed")
        rig.q.replication.beforeLinkInstall = { local, target ->
            if (local === candidate && target == remote.ref) throw failure
        }

        shouldThrow<IllegalStateException> {
            rig.q.replication.rebind(incumbent, candidate, rig.q.host)
        } shouldBe failure
        rig.controller.runToIdle()

        recording.balance(P, ClaimClass.Interest) shouldBe N - 1
        recording.balance(P, ClaimClass.Retention) shouldBe N - 1
        recording.charges.size shouldBe 2
        counting.undoCalls.get() shouldBe 0
        rig.q.replication.linkCountAmong(setOf(candidate.ref, remote.ref)) shouldBe 0

        // The carried hold remains the pair's reservation, so a later
        // announcement re-links without charging either class again.
        rig.q.replication.beforeLinkInstall = null
        val location = rig.q.registry.location(remote.ref) as LocationRegistry.Remote
        rig.q.registry.publish(remote.ref, location.sink, peer = P)
        rig.controller.runToIdle()

        recording.charges.size shouldBe 2
        counting.undoCalls.get() shouldBe 0
        recording.balance(P, ClaimClass.Interest) shouldBe N - 1
        recording.balance(P, ClaimClass.Retention) shouldBe N - 1
        rig.q.replication.linkCountAmong(setOf(candidate.ref, remote.ref)) shouldBe 1
    }

    @Test
    fun `reannouncement skipped by a failing formation retries after rollback`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = counting)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        val remote = CellRef(logicalId, 0)
        val firstSink = InvocationSink { }
        val secondSink = InvocationSink { }
        registry.publishFromPeer(remote, firstSink, peer = P, announcer = PeerStamp(P)) shouldBe null

        val formationEntered = CountDownLatch(1)
        val releaseFormation = CountDownLatch(1)
        val attempts = AtomicInteger()
        val failure = IllegalStateException("first formation failed")
        replication.beforeLinkInstall = { candidate, target ->
            if (candidate === local && target == remote && attempts.incrementAndGet() == 1) {
                formationEntered.countDown()
                check(releaseFormation.await(20, TimeUnit.SECONDS)) {
                    "the reannouncement never released the failing formation"
                }
                throw failure
            }
        }

        val forming = FutureTask<Throwable> {
            try {
                replication.replicate(local, host)
                AssertionError("the first formation did not throw")
            } catch (thrown: Throwable) {
                thrown
            }
        }
        Thread.ofVirtual().start(forming)
        check(formationEntered.await(20, TimeUnit.SECONDS)) { "the first formation never reached installLink" }

        registry.publishFromPeer(remote, secondSink, peer = P, announcer = PeerStamp(P)) shouldBe null
        releaseFormation.countDown()

        forming.get(20, TimeUnit.SECONDS) shouldBe failure
        controller.runToIdle()
        attempts.get() shouldBe 2
        recording.charges.map { it.claimClass } shouldBe
            listOf(ClaimClass.Interest, ClaimClass.Retention, ClaimClass.Interest, ClaimClass.Retention)
        counting.undoCalls.get() shouldBe 2
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 1
    }

    @Test
    fun `cancelled failing formation retries the replacement after rollback`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = counting)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val incumbent = SetCell<String>(CellRef(logicalId, 1))
        val replacement = SetCell<String>(incumbent.ref)
        val remote = CellRef(logicalId, 0)
        registry.publishFromPeer(remote, InvocationSink { }, peer = P, announcer = PeerStamp(P)) shouldBe null

        val formationEntered = CountDownLatch(1)
        val releaseFormation = CountDownLatch(1)
        val failure = IllegalStateException("incumbent formation failed")
        replication.beforeLinkInstall = { candidate, target ->
            if (candidate === incumbent && target == remote) {
                formationEntered.countDown()
                check(releaseFormation.await(20, TimeUnit.SECONDS)) {
                    "the replacement never released the failing formation"
                }
                throw failure
            }
        }

        val forming = FutureTask<Throwable> {
            try {
                replication.replicate(incumbent, host)
                AssertionError("the incumbent formation did not throw")
            } catch (thrown: Throwable) {
                thrown
            }
        }
        Thread.ofVirtual().start(forming)
        check(formationEntered.await(20, TimeUnit.SECONDS)) { "the incumbent formation never reached installLink" }

        val replacementFailure = IllegalStateException("stop replacement before reservation")
        replication.beforeLinkLifecycle = { candidate, target ->
            if (candidate === replacement && target == remote) throw replacementFailure
        }
        shouldThrow<IllegalStateException> {
            replication.rebind(incumbent, replacement, host)
        } shouldBe replacementFailure
        replication.beforeLinkLifecycle = null
        releaseFormation.countDown()

        forming.get(20, TimeUnit.SECONDS) shouldBe failure
        controller.runToIdle()
        recording.charges.map { it.claimClass } shouldBe
            listOf(ClaimClass.Interest, ClaimClass.Retention, ClaimClass.Interest, ClaimClass.Retention)
        counting.undoCalls.get() shouldBe 2
        replication.linkCountAmong(setOf(replacement.ref, remote)) shouldBe 1
    }

    @Test
    fun `retry after a failing formation does not block concurrent retirement`() {
        val recording = RecordingLedger("q-host", initial = N)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = recording)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        val remote = CellRef(logicalId, 0)
        registry.publishFromPeer(remote, InvocationSink { }, peer = P, announcer = PeerStamp(P)) shouldBe null

        val formationEntered = CountDownLatch(1)
        val releaseFormation = CountDownLatch(1)
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val attempts = AtomicInteger()
        val failure = IllegalStateException("first formation failed")
        // The first install fails after a re-announcement was skipped; the
        // retry that failure triggers then parks inside its own install, where
        // a real transport would be blocked on the catch-up send.
        replication.beforeLinkInstall = { candidate, target ->
            if (candidate === local && target == remote) {
                when (attempts.incrementAndGet()) {
                    1 -> {
                        formationEntered.countDown()
                        check(releaseFormation.await(20, TimeUnit.SECONDS)) {
                            "the reannouncement never released the failing formation"
                        }
                        throw failure
                    }
                    2 -> {
                        retryEntered.countDown()
                        check(releaseRetry.await(20, TimeUnit.SECONDS)) {
                            "the retirement path never released the retry"
                        }
                    }
                }
            }
        }

        val forming = FutureTask<Throwable> {
            try {
                replication.replicate(local, host)
                AssertionError("the first formation did not throw")
            } catch (thrown: Throwable) {
                thrown
            }
        }
        Thread.ofVirtual().start(forming)
        check(formationEntered.await(20, TimeUnit.SECONDS)) { "the first formation never reached installLink" }
        registry.publishFromPeer(remote, InvocationSink { }, peer = P, announcer = PeerStamp(P)) shouldBe null
        releaseFormation.countDown()
        check(retryEntered.await(20, TimeUnit.SECONDS)) { "the failed formation never retried" }

        // Retirement takes the link-lifecycle monitor; a retry running under
        // that monitor would hold it for as long as its install is blocked.
        val retiring = FutureTask<Unit> {
            registry.unpublish(remote)
            Unit
        }
        Thread.ofVirtual().start(retiring)
        try {
            retiring.get(2, TimeUnit.SECONDS)
        } finally {
            releaseRetry.countDown()
        }

        forming.get(20, TimeUnit.SECONDS) shouldBe failure
        controller.runToIdle()
        attempts.get() shouldBe 2
        registry.location(remote) shouldBe null
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 0
        recording.balance(P, ClaimClass.Retention) shouldBe N
    }

    @Test
    fun `a send that fails during link catch-up releases the hold and leaves no uncharged link`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = counting)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        replication.replicate(local, host)
        // non-empty state, so installing the link sends a catch-up delta
        (HostedCellProxy.create(local.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call.add("x")
        controller.runToIdle()

        val remote = CellRef(logicalId, 0)
        val failedSends = AtomicInteger()
        // A transport egress that finds its socket dead: it unpublishes the
        // peer's refs on the sending thread, then fails the send (the shape of
        // WsTransport's and IrohTransport's send-failure path).
        lateinit var dead: InvocationSink
        dead = InvocationSink { invocation ->
            failedSends.incrementAndGet()
            registry.unpublishRemotes(via = dead)
            throw IntakeClosedException(invocation.cellRef)
        }
        registry.publishFromPeer(remote, dead, peer = P, announcer = PeerStamp(P)) shouldBe null
        controller.runToIdle()

        failedSends.get() shouldBe 1
        registry.location(remote) shouldBe null
        recording.balance(P, ClaimClass.Retention) shouldBe N
        recording.balance(P, ClaimClass.Interest) shouldBe N - 1
        counting.undoCalls.get() shouldBe 1
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 0

        // the peer reconnects: the re-announced pair is charged like any fresh link
        registry.publishFromPeer(remote, InvocationSink { }, peer = P, announcer = PeerStamp(P)) shouldBe null
        controller.runToIdle()

        recording.charges.map { it.claimClass } shouldBe
            listOf(ClaimClass.Interest, ClaimClass.Retention, ClaimClass.Interest, ClaimClass.Retention)
        recording.balance(P, ClaimClass.Retention) shouldBe N - 1
        counting.undoCalls.get() shouldBe 1
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 1
    }

    @Test
    fun `a send that fails while a rebind re-links a carried hold releases it and leaves no link`() {
        val recording = RecordingLedger("q-host", initial = N)
        val counting = CountingLedger(recording)
        val controller = SimulationController(0)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = counting)
        val replication = Replication(registry)
        val logicalId = UUID.randomUUID()
        val local = SetCell<String>(CellRef(logicalId, 1))
        replication.replicate(local, host)
        (HostedCellProxy.create(local.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call.add("x")
        controller.runToIdle()

        val remote = CellRef(logicalId, 0)
        var socketDead = false
        lateinit var sink: InvocationSink
        sink = InvocationSink { invocation ->
            if (socketDead) {
                registry.unpublishRemotes(via = sink)
                throw IntakeClosedException(invocation.cellRef)
            }
        }
        registry.publishFromPeer(remote, sink, peer = P, announcer = PeerStamp(P)) shouldBe null
        controller.runToIdle()
        recording.balance(P, ClaimClass.Retention) shouldBe N - 1
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 1

        // the rebind carries the hold over and re-links; its catch-up meets the dead socket
        socketDead = true
        replication.rebind(local, SetCell<String>(local.ref), host)
        controller.runToIdle()

        registry.location(remote) shouldBe null
        recording.balance(P, ClaimClass.Retention) shouldBe N
        counting.undoCalls.get() shouldBe 1
        recording.charges.size shouldBe 2
        replication.linkCountAmong(setOf(local.ref, remote)) shouldBe 0
    }

    @Test
    fun `refused Interest records one denial forms no outbound link and changes no remote state`() {
        val ledger = RecordingLedger(
            "q-host",
            initial = N,
            refuse = {
                if (it.claimClass == ClaimClass.Interest) {
                    BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "q-host", shortfall = 1)
                } else {
                    null
                }
            },
        )
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()

        rig.q.replication.linkCountAmong(setOf(onQ.ref, onP.ref)) shouldBe 0
        rig.q.host.boundaryDenialCount() shouldBe 1L
        val denial = rig.q.letters.mapNotNull { it.denial }.single()
        denial.seam shouldBe BoundarySeam.LINK_AUTHORITY
        denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        denial.principal shouldBe P
        denial.detail!! shouldContain "class=Interest"
        ledger.balance(P, ClaimClass.Retention) shouldBe N
        ledger.charges.size shouldBe 1

        rig.q.ops(onQ).add("q-only")
        rig.controller.runToIdle()
        onP.membership() shouldBe emptySet()
        rig.p.ops(onP).add("p-only")
        rig.controller.runToIdle()
        onP.membership() shouldBe setOf("p-only")
        onQ.membership() shouldBe setOf("q-only", "p-only")
    }

    @Test
    fun `refused Retention refunds Interest records one denial and forms no outbound link`() {
        val ledger = RecordingLedger(
            "q-host",
            initial = N,
            refuse = {
                if (it.claimClass == ClaimClass.Retention) {
                    BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "q-host", shortfall = 1)
                } else {
                    null
                }
            },
        )
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()

        ledger.balance(P, ClaimClass.Interest) shouldBe N
        ledger.balance(P, ClaimClass.Retention) shouldBe N
        ledger.charges.size shouldBe 2
        rig.q.replication.linkCountAmong(setOf(onQ.ref, onP.ref)) shouldBe 0
        rig.q.host.boundaryDenialCount() shouldBe 1L
        val denial = rig.q.letters.mapNotNull { it.denial }.single()
        denial.seam shouldBe BoundarySeam.LINK_AUTHORITY
        denial.principal shouldBe P
        denial.detail!! shouldContain "class=Retention"
    }

    @Test
    fun `re-announcing an already linked pair charges nothing`() {
        val ledger = RecordingLedger("q-host", initial = N)
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()
        val remote = rig.q.registry.location(onP.ref) as LocationRegistry.Remote

        rig.q.registry.publish(onP.ref, remote.sink, peer = P)
        rig.controller.runToIdle()

        ledger.charges.size shouldBe 2
        rig.q.replication.linkCountAmong(setOf(onQ.ref, onP.ref)) shouldBe 1
    }

    @Test
    fun `local and anonymous instances form links without constructing claims`() {
        run {
            val controller = SimulationController(0)
            val registry = LocationRegistry()
            val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = ThrowingLedger)
            val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = ThrowingLedger)
            val replicationA = Replication(registry)
            val replicationB = Replication(registry)
            val logicalId = UUID.randomUUID()
            val onA = SetCell<String>(civictech.cell.CellRef(logicalId, 0))
            val onB = SetCell<String>(civictech.cell.CellRef(logicalId, 1))

            replicationA.replicate(onA, hostA)
            replicationB.replicate(onB, hostB)
            controller.runToIdle()

            replicationA.linkCountAmong(setOf(onA.ref, onB.ref)) shouldBe 1
            replicationB.linkCountAmong(setOf(onA.ref, onB.ref)) shouldBe 1
        }

        run {
            val rig = Rig(qBudget = ThrowingLedger, pPeer = null)
            val (onQ, onP) = rig.formPair()

            rig.q.replication.linkCountAmong(setOf(onQ.ref, onP.ref)) shouldBe 1
        }
    }

    @Test
    fun `same-ref rebind carries the hold and bypasses an exhausted ledger`() {
        var refuseAll = false
        val ledger = RecordingLedger(
            "q-host",
            initial = N,
            refuse = {
                if (refuseAll) BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "q-host", shortfall = 1)
                else null
            },
        )
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()
        refuseAll = true

        val candidate = SetCell<String>(onQ.ref)
        rig.q.replication.rebind(onQ, candidate, rig.q.host)
        rig.controller.runToIdle()

        rig.q.replication.linkCountAmong(setOf(candidate.ref, onP.ref)) shouldBe 1
        rig.q.host.boundaryDenialCount() shouldBe 0L
        ledger.charges.size shouldBe 2
        ledger.balance(P, ClaimClass.Retention) shouldBe N - 1
    }

    @Test
    fun `peer departure releases the hold and heal takes a fresh hold with the same Interest key`() {
        val ledger = RecordingLedger("q-host", initial = N)
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()

        rig.link.partition()
        rig.controller.runToIdle()
        ledger.balance(P, ClaimClass.Retention) shouldBe N
        ledger.balance(P, ClaimClass.Interest) shouldBe N - 1

        rig.link.heal()
        rig.controller.runToIdle()

        ledger.balance(P, ClaimClass.Retention) shouldBe N - 1
        ledger.balance(P, ClaimClass.Interest) shouldBe N - 2
        val interestClaims = ledger.charges.filter { it.claimClass == ClaimClass.Interest }
        interestClaims.size shouldBe 2
        interestClaims.map { it.key } shouldBe listOf(onQ.ref to onP.ref, onQ.ref to onP.ref)
        rig.q.replication.linkCountAmong(setOf(onQ.ref, onP.ref)) shouldBe 1
    }

    @Test
    fun `crash supersede at the same ref carries the hold and bypasses an exhausted ledger`() {
        var refuseAll = false
        val ledger = RecordingLedger(
            "q-host",
            initial = N,
            refuse = {
                if (refuseAll) BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "q-host", shortfall = 1)
                else null
            },
        )
        val rig = Rig(ledger)
        val (onQ, onP) = rig.formPair()
        refuseAll = true

        // the crash: Q's host is discarded and a DIFFERENT object returns at the same ref
        val rebuiltHost = ManagedHost(scheduler = rig.controller.scheduler(), registry = rig.q.registry, budget = ledger)
        val rebuilt = SetCell<String>(onQ.ref)
        rig.q.replication.replicate(rebuilt, rebuiltHost)
        rig.controller.runToIdle()

        rig.q.replication.linkCountAmong(setOf(rebuilt.ref, onP.ref)) shouldBe 1
        rig.q.host.boundaryDenialCount() shouldBe 0L
        rebuiltHost.boundaryDenialCount() shouldBe 0L
        ledger.charges.size shouldBe 2
        ledger.balance(P, ClaimClass.Retention) shouldBe N - 1

        rig.q.registry.locate(onQ.ref) shouldBe rebuiltHost
        rig.q.ops(rebuilt).add("after-rebuild")
        rig.controller.runToIdle()
        onP.membership() shouldBe setOf("after-rebuild")
    }
}
