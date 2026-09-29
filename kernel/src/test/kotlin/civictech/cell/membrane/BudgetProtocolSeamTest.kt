package civictech.cell.membrane

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
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetCell
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One mediated outlet whose `protocolAuthority` declares an Attention
 * ceiling of [AttentionBand.LOW] — the seam-3 site the ECO1 budget arm lives
 * in. Property name equals the exposure name (G-17).
 */
private class AttentionMembrane(
    val organelle: SetCell<String> = SetCell(),
) : CompositeCell() {
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelle.outlet,
        policy = BoundaryPolicy(
            protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW)),
        ),
    )
}

/** A ledger that forwards to [inner] and counts the [BudgetOutcome.Refused] answers it returned. */
private class CountingLedger(private val inner: BudgetLedger) : BudgetLedger {
    val refusals = AtomicInteger()

    override fun charge(claim: BudgetClaim): BudgetOutcome =
        inner.charge(claim).also { if (it is BudgetOutcome.Refused) refusals.incrementAndGet() }
}

/**
 * ECO1 F3's Attention arm in `CompositeCell.asProtocolFilter` (task
 * `computenet-5o1rf.2`, decisions `5o1rf-D6`/`D7`), driven **in-process**: a
 * membrane hosted on a `ManagedHost(budget = …)`, frames handed straight to
 * the exposure's [ProtocolSupport] under `CurrentPeer.with(...)` — the
 * bridge-ingress stamp simulated, as `BoundaryPolicyTest` does. The wire
 * halves (BS-04 and BS-21 over `Peering.loopback`) are in
 * `civictech.cell.wire.BridgeBoundaryPolicyTest`.
 *
 * Every test asserts what the handler **observed** and the denial counter,
 * not only that nothing threw: `ThrowingLedger` throws an `AssertionError`
 * that `chargeOrFail` would turn into a silent-looking `LEDGER_FAILURE`
 * refusal, so "nothing throws" alone could not tell a skipped charge from a
 * swallowed one.
 */
class BudgetProtocolSeamTest {

    private class Rig(budget: BudgetLedger, seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), budget = budget)
        val letters = CopyOnWriteArrayList<DeadLetter>()
        val membrane = AttentionMembrane()
        val observed = CopyOnWriteArrayList<Attention>()
        val sink get() = membrane.boundaryDenials["exposedOutlet"]!!

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
                            letters += value
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

        /** A remote assertion: the frame arrives under q's stamp, as `ManagedHost.deliver` installs it. */
        fun assertFrom(peer: PeerId, attention: Attention) {
            CurrentPeer.with(peer) {
                ProtocolSupport.of(membrane.exposedOutlet).deliver(Protocols.Attention, edge, attention)
            }
            controller.runToIdle()
        }

        /** A local assertion: no stamp, `Principal.LocalTrusted`. */
        fun assertLocally(attention: Attention) {
            ProtocolSupport.of(membrane.exposedOutlet).deliver(Protocols.Attention, edge, attention)
            controller.runToIdle()
        }
    }

    private val q = PeerId("q")
    private val high = Attention(AttentionBand.HIGH.level, version = 7)

    @Test
    fun `BS-04 admitted - the clamp still applies, the ledger is debited once for (q, Attention), no record`() {
        val ledger = RecordingLedger("p-host")
        val rig = Rig(ledger, seed = 1)

        rig.assertFrom(q, high)

        rig.observed shouldContainExactly listOf(Attention(AttentionBand.LOW.level, 7))
        ledger.balance(q, ClaimClass.Attention) shouldBe 9L
        val claim = ledger.charges.single()
        claim.stamp.id shouldBe q
        claim.claimClass shouldBe ClaimClass.Attention
        claim.key shouldBe (Protocols.Attention to 7L)
        // a clamp is not a charge, a charge is not a clamp: admission records nothing
        rig.sink.denialCount shouldBe 0L
        rig.letters.shouldBeEmpty()
    }

    @Test
    fun `BS-04 refused - the handler observes nothing and one PROTOCOL_AUTHORITY budget record is accounted`() {
        val ledger = RecordingLedger(
            "p-host",
            refuse = { BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "p-host", shortfall = 1) },
        )
        val rig = Rig(ledger, seed = 2)

        rig.assertFrom(q, high)

        rig.observed.shouldBeEmpty()
        rig.sink.denialCount shouldBe 1L
        ledger.charges.size shouldBe 1
        ledger.balance(q, ClaimClass.Attention) shouldBe 10L
        val denial = rig.letters.single().denial!!
        denial.seam shouldBe BoundarySeam.PROTOCOL_AUTHORITY
        denial.exposure shouldBe "exposedOutlet"
        denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        denial.principal shouldBe q
        denial.subject shouldBe Protocols.Attention.name
        denial.detail!! shouldContain "class=Attention"
        denial.detail!! shouldContain "scope=p-host"
        denial.detail!! shouldContain "shortfall=1"
    }

    @Test
    fun `BS-21 site half - the same frame twice presents the same (protocol, version) key and a deduplicating ledger debits once`() {
        val ledger = RecordingLedger("p-host", dedupKeys = true)
        val rig = Rig(ledger, seed = 3)

        rig.assertFrom(q, high)
        rig.assertFrom(q, high)

        ledger.charges.size shouldBe 2
        ledger.charges.map { it.key } shouldContainExactly listOf(Protocols.Attention to 7L, Protocols.Attention to 7L)
        ledger.balance(q, ClaimClass.Attention) shouldBe 9L

        // a new version is a new assertion, so a new key and a second debit
        rig.assertFrom(q, Attention(AttentionBand.HIGH.level, version = 8))
        ledger.charges.last().key shouldBe (Protocols.Attention to 8L)
        ledger.balance(q, ClaimClass.Attention) shouldBe 8L
        rig.sink.denialCount shouldBe 0L
    }

    @Test
    fun `ECO1-BUD-05 - a local assertion reaches no ledger - unclamped, no record - while a remote one on the same host does`() {
        val rig = Rig(ThrowingLedger, seed = 4)

        rig.assertLocally(Attention(AttentionBand.HIGH.level))

        // unclamped exactly as today, and no LEDGER_FAILURE refusal was swallowed
        rig.observed shouldContainExactly listOf(Attention(AttentionBand.HIGH.level))
        rig.sink.denialCount shouldBe 0L
        rig.letters.shouldBeEmpty()

        // The control: the ThrowingLedger IS attached — a stamped assertion
        // reaches it and is refused fail-closed. Without this half the local
        // check could pass merely because nothing was attached.
        rig.assertFrom(q, high)
        rig.observed.size shouldBe 1
        rig.sink.denialCount shouldBe 1L
        rig.letters.single().denial!!.reason shouldBe DenialReason.LEDGER_FAILURE
    }

    @Test
    fun `BS-20 at the filter - a throwing ledger refuses with LEDGER_FAILURE and a null shortfall and drops the frame`() {
        val ledger = RecordingLedger("p-host", throwOnCharge = true)
        val rig = Rig(ledger, seed = 5)

        rig.assertFrom(q, high)

        rig.observed.shouldBeEmpty()
        rig.sink.denialCount shouldBe 1L
        val denial = rig.letters.single().denial!!
        denial.seam shouldBe BoundarySeam.PROTOCOL_AUTHORITY
        denial.reason shouldBe DenialReason.LEDGER_FAILURE
        denial.principal shouldBe q
        denial.subject shouldBe Protocols.Attention.name
        denial.detail!! shouldContain "class=Attention"
        denial.detail!! shouldContain "shortfall=null"
        denial.detail!! shouldContain "IllegalStateException"
    }

    @Test
    fun `ECO1-DEN-02 - the denial counter moves by exactly the number of refusals the ledger returned`() {
        val ledger = CountingLedger(RecordingLedger("p-host", initial = 3))
        val rig = Rig(ledger, seed = 6)

        repeat(5) { i -> rig.assertFrom(q, Attention(AttentionBand.HIGH.level, version = i.toLong())) }

        ledger.refusals.get() shouldBe 2
        rig.sink.denialCount shouldBe ledger.refusals.get().toLong()
        rig.observed.size shouldBe 3
        rig.letters.map { it.denial!!.reason } shouldContainExactly
            listOf(DenialReason.BUDGET_EXHAUSTED, DenialReason.BUDGET_EXHAUSTED)
    }

    @Test
    fun `ECO1-POL-06 - an all-Unlimited host attaches no ledger, and a remote assertion is only clamped`() {
        val rig = Rig(BudgetLedger.Unlimited, seed = 7)

        val field = CompositeCell::class.java.getDeclaredField("budget").apply { isAccessible = true }
        field.get(rig.membrane) shouldBe null

        rig.assertFrom(q, high)
        rig.observed shouldContainExactly listOf(Attention(AttentionBand.LOW.level, 7))
        rig.sink.denialCount shouldBe 0L
        rig.letters.shouldBeEmpty()
    }
}
