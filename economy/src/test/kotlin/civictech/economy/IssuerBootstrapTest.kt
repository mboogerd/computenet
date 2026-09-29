package civictech.economy

import civictech.cell.BudgetClaim
import civictech.cell.BudgetOutcome
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.link.AuthLevel.Authenticated
import civictech.cell.link.AuthLevel.TransportVouched
import civictech.cell.link.IssuerId
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * BS-17r, `[ECO1-BUD-08]`/`[ECO1-BUD-10]`, `[ECO1-MINT-02r/03r/04r]`, `kwhw6-D4`: a first
 * charge's bucket is bootstrapped from the stamp alone — the named issuer row when
 * `Authenticated` and configured, else `unvouchedBootstrap`; a zero bootstrap is
 * `BUDGET_NOT_GRANTED` with no bucket created.
 */
class IssuerBootstrapTest {

    private var now = 0L

    private fun ledger(unvouched: Map<ClaimClass, Long> = emptyMap()): TokenBucketLedger {
        val policy = EconomicPolicy.placeholder().copy(
            issuers = mapOf(
                "A" to EconomicPolicy.IssuerBudget(bootstrap = mapOf(ClaimClass.Spawn to 5L)),
                "B" to EconomicPolicy.IssuerBudget(bootstrap = mapOf(ClaimClass.Spawn to 0L)),
            ),
            unvouchedBootstrap = unvouched,
        ).applied()
        return TokenBucketLedger(policy, { now }, "bootstrap-scope")
    }

    private fun spawn(stamp: PeerStamp) = BudgetClaim(stamp, ClaimClass.Spawn)

    @Test
    fun `only an authenticated stamp naming a configured issuer row bootstraps`() {
        val l = ledger()

        l.charge(spawn(PeerStamp(PeerId("pA"), Authenticated, IssuerId("A")))).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(PeerId("pA"), ClaimClass.Spawn)!!.let {
            it.balance shouldBe 4
            it.bootstrapLevel shouldBe 5
        }

        val refusedStamps = listOf(
            PeerStamp(PeerId("pB"), Authenticated, IssuerId("B")), // row grants 0
            PeerStamp(PeerId("pC"), Authenticated, IssuerId("C")), // no such row
            PeerStamp(PeerId("pI"), Authenticated, null), // no issuer
            PeerStamp(PeerId("pT"), TransportVouched, IssuerId("A")), // not authenticated
        )
        refusedStamps.forEach { stamp ->
            val refused = l.charge(spawn(stamp)).shouldBeInstanceOf<BudgetOutcome.Refused>()
            refused.reason shouldBe DenialReason.BUDGET_NOT_GRANTED
            refused.shortfall.shouldBeNull()
            refused.scope shouldBe "bootstrap-scope"
        }

        val snap = l.snapshot()
        snap.bucketCount shouldBe 1
        snap.denied[ClaimClass.Spawn]!![DenialReason.BUDGET_NOT_GRANTED] shouldBe 4
        snap.admitted[ClaimClass.Spawn] shouldBe 1
    }

    @Test
    fun `unvouchedBootstrap admits an issuer-less stamp`() {
        val l = ledger(unvouched = mapOf(ClaimClass.Spawn to 3L))
        l.charge(spawn(PeerStamp(PeerId("pI"), Authenticated, null))).shouldBeInstanceOf<BudgetOutcome.Admitted>()
        l.snapshot().bucket(PeerId("pI"), ClaimClass.Spawn)!!.balance shouldBe 2
    }
}
