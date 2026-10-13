package civictech.economy.adversarial

import civictech.cell.BoundarySeam
import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.CellRef
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
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.economy.EconomicPolicy
import civictech.economy.LedgerSnapshot
import civictech.economy.TokenBucketLedger
import civictech.testkit.forEachSeed
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BS-18r records two deliberately narrow outcomes. The free-identity case makes the
 * decorative budget visible: when identities are free, per-identity bootstrap bounds
 * nothing across identities; it does not demonstrate Sybil resistance. The issuer case
 * is only a local bound over one issuer's vouches at one host and says nothing about
 * issuer diligence or Sybil sufficiency. Neither case measures wall-clock duration or
 * makes any assumption about how an identity was obtained.
 */
class SybilFloodTest {

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
        val host = ManagedHost(scheduler = controller.scheduler(), quota = 2, budget = budget)
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

    private fun attentionPolicy(bootstrap: Long, maxBuckets: Int) =
        EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Attention to 1L),
            capacities = mapOf(ClaimClass.Attention to bootstrap),
            refill = mapOf(ClaimClass.Attention to EconomicPolicy.Refill(1, 1_000)),
            retention = EconomicPolicy.Retention(
                idleNanos = 3_600_000_000_000,
                maxBuckets = maxBuckets,
                recentKeys = 64,
            ),
            unvouchedBootstrap = mapOf(ClaimClass.Attention to bootstrap),
            label = "BS-18r free-identity fixture",
        ).applied()

    @Test
    fun `n identities with free bootstrap B consume exactly n times B`() {
        forEachSeed(0L..4L) { seed ->
            val bootstrap = 3
            val maxBuckets = 64
            val identityCount = 2 + ((seed * 3) % 9).toInt()
            val ledger = TokenBucketLedger(
                attentionPolicy(bootstrap.toLong(), maxBuckets),
                clock = { 0L },
                scope = "sybil-host",
            )
            val rig = Rig(ledger, seed)
            val attempts = buildList {
                repeat(identityCount) { identityIndex ->
                    val peer = PeerId("sybil-$identityIndex")
                    repeat(bootstrap + 1) { attemptIndex ->
                        val version = (identityIndex * (bootstrap + 1) + attemptIndex + 1).toLong()
                        add(peer to version)
                    }
                }
            }.toMutableList()
            Collections.shuffle(attempts, Random(seed))

            attempts.forEach { (peer, version) ->
                rig.assertFrom(peer, Attention(AttentionBand.HIGH.level, version))
            }

            val snapshot = ledger.snapshot()
            val admitted = snapshot.admitted[ClaimClass.Attention] ?: 0L
            val denied = snapshot.denied[ClaimClass.Attention].orEmpty().values.sum()
            admitted shouldBe (identityCount * bootstrap).toLong()
            snapshot.denied[ClaimClass.Attention]?.get(DenialReason.BUDGET_EXHAUSTED) shouldBe identityCount.toLong()
            admitted + denied shouldBe (identityCount * (bootstrap + 1)).toLong()
            snapshot.bucketCount shouldBe identityCount
            (snapshot.bucketCount <= maxBuckets) shouldBe true

            rig.observed.size shouldBe identityCount * bootstrap
            rig.observed.all { it.level == AttentionBand.LOW.level } shouldBe true
            rig.denials.denialCount shouldBe identityCount.toLong()
            rig.deadLetters.size shouldBe identityCount
            rig.deadLetters.forEach { deadLetter ->
                val denial = requireNotNull(deadLetter.denial)
                denial.seam shouldBe BoundarySeam.PROTOCOL_AUTHORITY
                denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
                requireNotNull(denial.detail) shouldContain "shortfall=1"
            }

            val localCell = SetCell<String>()
            rig.host.managementInlet.call.spawn(localCell) shouldBe localCell.ref
            rig.controller.runToIdle()

            if (seed == 0L) {
                println("FINDINGS-ROW BS-18r")
                println(snapshot.findingsRow())
            }
        }
    }

    @Test
    fun `one issuer's holds at one host stay within its aggregate cap`() {
        val issuer = IssuerId("A")
        val policy = EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Retention to 1L),
            capacities = mapOf(ClaimClass.Retention to 4L),
            refill = mapOf(ClaimClass.Retention to EconomicPolicy.Refill(1, 1_000)),
            issuers = mapOf(
                issuer.name to EconomicPolicy.IssuerBudget(
                    bootstrap = mapOf(ClaimClass.Retention to 4L),
                    aggregateHoldCap = 10L,
                ),
            ),
            label = "BS-18r issuer aggregate fixture",
        ).applied()
        val ledger = TokenBucketLedger(policy, clock = { 0L }, scope = "issuer-host")
        val stamps = List(5) { index -> PeerStamp(PeerId("v$index"), Authenticated, issuer) }
        val outcomes = List(20) { attempt ->
            ledger.charge(
                BudgetClaim(
                    stamp = stamps[attempt % stamps.size],
                    claimClass = ClaimClass.Retention,
                    key = "hold-$attempt",
                    hold = true,
                ),
            )
        }
        val admitted = outcomes.filterIsInstance<BudgetOutcome.Admitted>()
        val refused = outcomes.filterIsInstance<BudgetOutcome.Refused>()

        admitted.size shouldBe 10
        refused.size shouldBe 10
        refused.forEach { outcome ->
            outcome.reason shouldBe DenialReason.BUDGET_EXHAUSTED
            outcome.detail shouldBe TokenBucketLedger.AGGREGATE_HOLD_CAP_DETAIL
        }
        ledger.snapshot().buckets
            .filter { it.claimClass == ClaimClass.Retention }
            .sumOf { it.held } shouldBe 10L

        admitted.first().undo()
        ledger.charge(
            BudgetClaim(stamps[0], ClaimClass.Retention, key = "hold-20", hold = true),
        ).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        ledger.charge(
            BudgetClaim(stamps[1], ClaimClass.Retention, key = "hold-21", hold = true),
        ).shouldBeInstanceOf<BudgetOutcome.Refused>().let { outcome ->
            outcome.reason shouldBe DenialReason.BUDGET_EXHAUSTED
            outcome.detail shouldBe TokenBucketLedger.AGGREGATE_HOLD_CAP_DETAIL
        }
    }

    @Test
    fun `findings row renders claim classes and sorted refusal reasons`() {
        val snapshot = LedgerSnapshot(
            scope = "renderer-scope",
            admitted = mapOf(
                ClaimClass.Spawn to 2L,
                ClaimClass.Attention to 3L,
            ),
            denied = mapOf(
                ClaimClass.Attention to linkedMapOf(
                    DenialReason.BUDGET_NOT_GRANTED to 2L,
                    DenialReason.BUDGET_EXHAUSTED to 1L,
                ),
            ),
            bucketCount = 2,
            buckets = emptyList(),
        )

        snapshot.findingsRow() shouldBe """
            | ClaimClass | admitted | denied |
            | --- | ---: | --- |
            | Spawn | 2 | - |
            | Interest | 0 | - |
            | Attention | 3 | BUDGET_EXHAUSTED=1, BUDGET_NOT_GRANTED=2 |
            | Link | 0 | - |
            | Retention | 0 | - |
            buckets: 2 scope: renderer-scope
        """.trimIndent()
    }
}
