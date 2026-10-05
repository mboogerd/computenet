package civictech.economy.persist

import civictech.cell.BoundarySeam
import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetCell
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.AuthLevel.Authenticated
import civictech.cell.link.CurrentPeer
import civictech.cell.link.IssuerId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.CellRef
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BS-14, `[ECO1-PAR-07]`: a checkpoint is the conservative state of a crashed
 * in-process host, not a refill event. The restart below deliberately uses two
 * hosts and two ledgers in one JVM; it does not launch a second JVM. The
 * expected balances are derived from the fixture arithmetic in each test:
 * prices are explicit, holds consume their price, and restore starts refill at
 * the injected restore instant.
 */
class BudgetCheckpointRestartTest {

    private var now = 0L
    private var wallMillis = 0L

    private val peer = PeerId("p")
    private val attentionStamp = PeerStamp(peer)

    private fun attentionPolicy(
        capacity: Long = 10,
        bootstrap: Long = 5,
        label: String = "attention-policy",
    ) = EconomicPolicy.placeholder().copy(
        prices = mapOf(ClaimClass.Attention to 1L),
        capacities = mapOf(ClaimClass.Attention to capacity),
        refill = mapOf(ClaimClass.Attention to EconomicPolicy.Refill(1, 1_000)),
        checkpoint = EconomicPolicy.Checkpoint(10_000, 60_000_000_000),
        unvouchedBootstrap = mapOf(ClaimClass.Attention to bootstrap),
        label = label,
    ).applied()

    private fun retentionPolicy(
        bootstrap: Long = 5,
        issuers: Map<String, EconomicPolicy.IssuerBudget> = emptyMap(),
        unvouchedBootstrap: Long? = bootstrap,
        label: String = "retention-policy",
    ) = EconomicPolicy.placeholder().copy(
        prices = mapOf(ClaimClass.Retention to 2L),
        capacities = mapOf(ClaimClass.Retention to 10L),
        refill = mapOf(ClaimClass.Retention to EconomicPolicy.Refill(1, 1_000)),
        checkpoint = EconomicPolicy.Checkpoint(10_000, 60_000_000_000),
        issuers = issuers,
        unvouchedBootstrap = unvouchedBootstrap?.let { mapOf(ClaimClass.Retention to it) } ?: emptyMap(),
        label = label,
    ).applied()

    private fun store(directory: Path) = FileBudgetCheckpointStore(directory) { wallMillis }

    private fun ledger(policy: civictech.economy.AppliedPolicy, scope: String = "budget-scope") =
        TokenBucketLedger(policy, { now }, scope)

    private class AttentionMembrane(
        val organelle: SetCell<String> = SetCell(),
    ) : CompositeCell() {
        val exposedOutlet = mediateOutlet(
            "exposedOutlet",
            "outlet",
            organelle.outlet,
            policy = BoundaryPolicy(
                protocolAuthority = mapOf(
                    Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW),
                ),
            ),
        )
    }

    private class Rig(budget: BudgetLedger, seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), budget = budget)
        val deadLetters = CopyOnWriteArrayList<DeadLetter>()
        val membrane = AttentionMembrane()
        val observed = CopyOnWriteArrayList<Attention>()
        val denials get() = membrane.boundaryDenials["exposedOutlet"]!!

        private val edge = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(membrane.ref),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(membrane.ref, "exposedOutlet"),
        )

        init {
            host.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            host.managementInlet.call.spawn(membrane)
            controller.runToIdle()
            ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
                observed += message as Attention
            }
        }

        fun assertFrom(peer: PeerId, attention: Attention) {
            CurrentPeer.with(peer) {
                ProtocolSupport.of(membrane.exposedOutlet).deliver(Protocols.Attention, edge, attention)
            }
            controller.runToIdle()
        }
    }

    @Test
    fun `host restart preserves exhausted attention budget and resumes only after one interval`(@TempDir directory: Path) {
        val checkpointStore = store(directory)
        val firstLedger = ledger(attentionPolicy())
        val firstHost = Rig(firstLedger, seed = 1)

        // Bootstrap is 5 and price is 1: versions 1..5 are admitted, while 6 is exhausted.
        (1L..5L).forEach { version ->
            firstHost.assertFrom(peer, Attention(AttentionBand.HIGH.level, version))
        }
        firstHost.observed.map { it.version } shouldBe (1L..5L).toList()
        firstLedger.snapshot().bucket(peer, ClaimClass.Attention)!!.balance shouldBe 0L

        firstHost.assertFrom(peer, Attention(AttentionBand.HIGH.level, 6L))
        firstHost.observed.size shouldBe 5
        firstHost.denials.denialCount shouldBe 1L
        firstHost.deadLetters.size shouldBe 1
        firstHost.deadLetters.single().denial!!.reason shouldBe DenialReason.BUDGET_EXHAUSTED

        firstLedger.checkpointTo(checkpointStore).sequence shouldBe 1L

        // Crash: firstHost/firstLedger are no longer used. This is intentionally an
        // in-process reconstruction rather than a second JVM or a durable host journal.
        now = 100_000L
        val restoredLedger = ledger(attentionPolicy())
        val restoredHost = Rig(restoredLedger, seed = 2)

        restoredLedger.restoreFrom(checkpointStore) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restoredHost.assertFrom(peer, Attention(AttentionBand.HIGH.level, 7L))

        // A naive refill from time zero would have admitted this claim. Restore's
        // lastRefillNanos is the restore instant, so the denial survives the restart.
        restoredHost.observed shouldBe emptyList()
        restoredHost.denials.denialCount shouldBe 1L
        restoredHost.deadLetters.size shouldBe 1
        restoredHost.deadLetters.single().denial!!.let { denial ->
            denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
            denial.principal shouldBe peer
            denial.seam shouldBe BoundarySeam.PROTOCOL_AUTHORITY
        }
        restoredLedger.snapshot().let { snapshot ->
            snapshot.bucket(peer, ClaimClass.Attention)!!.let { bucket ->
                bucket.balance shouldBe 0L
                bucket.held shouldBe 0L
                bucket.bootstrapLevel shouldBe 5L
            }
            snapshot.admitted[ClaimClass.Attention] shouldBe 0L
        }

        now += 1_000L
        restoredHost.assertFrom(peer, Attention(AttentionBand.HIGH.level, 8L))
        restoredHost.observed.single() shouldBe Attention(AttentionBand.LOW.level, 8L)
    }

    @Test
    fun `restore clamps a recorded balance and bootstrap to the new capacity`(@TempDir directory: Path) {
        val checkpointStore = store(directory)
        val original = ledger(attentionPolicy(capacity = 10, bootstrap = 10))
        repeat(3) { original.charge(BudgetClaim(attentionStamp, ClaimClass.Attention)) }
        original.snapshot().bucket(peer, ClaimClass.Attention)!!.balance shouldBe 7L
        original.checkpointTo(checkpointStore)

        val restored = ledger(attentionPolicy(capacity = 4, bootstrap = 4))
        restored.restoreFrom(checkpointStore) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restored.snapshot().bucket(peer, ClaimClass.Attention)!!.let { bucket ->
            // min(recorded balance 7, current capacity 4) and min(recorded bootstrap 10, 4).
            bucket.balance shouldBe 4L
            bucket.bootstrapLevel shouldBe 4L
            bucket.held shouldBe 0L
        }
    }

    @Test
    fun `restore spends recorded holds and a later undo on the old ledger cannot refund the new one`(@TempDir directory: Path) {
        val checkpointStore = store(directory)
        val original = ledger(retentionPolicy())
        val hold = original.charge(
            BudgetClaim(PeerStamp(peer), ClaimClass.Retention, hold = true),
        ).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        original.snapshot().bucket(peer, ClaimClass.Retention)!!.let { bucket ->
            // Bootstrap 5 minus hold price 2 leaves balance 3 and held 2.
            bucket.balance shouldBe 3L
            bucket.held shouldBe 2L
        }
        original.checkpointTo(checkpointStore)

        val restored = ledger(retentionPolicy())
        restored.restoreFrom(checkpointStore) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restored.snapshot().bucket(peer, ClaimClass.Retention)!!.let { bucket ->
            bucket.balance shouldBe 3L
            bucket.held shouldBe 0L
        }

        hold.undo()
        restored.snapshot().bucket(peer, ClaimClass.Retention)!!.let { bucket ->
            bucket.balance shouldBe 3L
            bucket.held shouldBe 0L
        }
    }

    @Test
    fun `restore gives zero elapsed refill credit even when the store is old`(@TempDir directory: Path) {
        val checkpointStore = store(directory)
        val original = ledger(attentionPolicy(capacity = 10, bootstrap = 10))
        repeat(10) { original.charge(BudgetClaim(attentionStamp, ClaimClass.Attention)) }
        original.checkpointTo(checkpointStore)

        now = 1_000_000L
        val restored = ledger(attentionPolicy(capacity = 10, bootstrap = 10))
        restored.restoreFrom(checkpointStore) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restored.charge(BudgetClaim(attentionStamp, ClaimClass.Attention))
            .shouldBeInstanceOf<BudgetOutcome.Refused>().reason shouldBe DenialReason.BUDGET_EXHAUSTED

        now += 1_000L
        restored.charge(BudgetClaim(attentionStamp, ClaimClass.Attention))
            .shouldBeInstanceOf<BudgetOutcome.Admitted>()
    }

    @Test
    fun `restore retains issuer row so aggregate hold cap still wins before balance`(@TempDir directory: Path) {
        val checkpointStore = store(directory)
        val issuer = EconomicPolicy.IssuerBudget(
            bootstrap = mapOf(ClaimClass.Retention to 10L),
            aggregateHoldCap = 4L,
        )
        val policy = retentionPolicy(bootstrap = 10, issuers = mapOf("A" to issuer), unvouchedBootstrap = null)
        val stamp = PeerStamp(peer, Authenticated, IssuerId("A"))
        val original = ledger(policy)

        original.charge(BudgetClaim(stamp, ClaimClass.Retention, hold = true))
            .shouldBeInstanceOf<BudgetOutcome.Admitted>()
        original.snapshot().bucket(peer, ClaimClass.Retention)!!.let { bucket ->
            bucket.balance shouldBe 8L
            bucket.held shouldBe 2L
        }
        original.checkpointTo(checkpointStore)

        val restored = ledger(policy)
        restored.restoreFrom(checkpointStore) shouldBe RestoreOutcome.Restored(sequence = 1, buckets = 1)
        restored.snapshot().bucket(peer, ClaimClass.Retention)!!.let { bucket ->
            // The recorded hold is spent, so the restored row starts at 8/0.
            bucket.balance shouldBe 8L
            bucket.held shouldBe 0L
        }

        val second = restored.charge(BudgetClaim(stamp, ClaimClass.Retention, hold = true))
        val third = restored.charge(BudgetClaim(stamp, ClaimClass.Retention, hold = true))
        second.shouldBeInstanceOf<BudgetOutcome.Admitted>()
        third.shouldBeInstanceOf<BudgetOutcome.Admitted>()
        restored.snapshot().bucket(peer, ClaimClass.Retention)!!.let { bucket ->
            // The first two post-restore holds make issuer sum 2 then 4, balance 4/held 4.
            bucket.balance shouldBe 4L
            bucket.held shouldBe 4L
        }

        val fourth = restored.charge(BudgetClaim(stamp, ClaimClass.Retention, hold = true))
            .shouldBeInstanceOf<BudgetOutcome.Refused>()
        fourth.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        fourth.detail shouldBe TokenBucketLedger.AGGREGATE_HOLD_CAP_DETAIL
        // The cap is checked before the bucket balance: balance 4 would cover price 2,
        // but issuer sum 6 would exceed the restored issuer row's cap 4.
    }
}
