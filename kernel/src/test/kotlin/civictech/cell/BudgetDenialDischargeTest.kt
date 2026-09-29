package civictech.cell

import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import civictech.cell.membrane.CompositeCell
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * BS-06/07/08 (`[ECO1-DEN-03]`..`[ECO1-DEN-05]`), `[ECO1-DEN-11]` and the
 * `[ECO1-DEN-06]` audit — feature `computenet-2zasa` (F4 of epic
 * `computenet-66m`, ECO1), task `computenet-2zasa.3`.
 *
 * The one budget-denial helper is [BoundaryDenialSink.denyBudget]
 * (`kernel/src/main/kotlin/civictech/cell/BudgetDenial.kt`); this class
 * drives it directly on both of [BoundaryDenialSink.deny]'s mutually
 * exclusive discharge routes — hosted (a reporter sanitizes) and unattached
 * (`dischargeRefusedArgs` discharges) — asserting that an [Owned]/[Leased]
 * argument is discharged **exactly once** on each, and that the record never
 * names another principal's balance.
 *
 * **`[ECO1-DEN-06]` audit (`2zasa-D4`):** no-state-change at the three real
 * call sites is already pinned by ECO1 F3's own tests, cited here rather than
 * re-asserted:
 * - `kernel/src/test/kotlin/civictech/cell/host/BudgetHierarchyWalkTest.kt`,
 *   "a refusing ancestor undoes every debit taken in the ascent and accounts
 *   one HOST_ADMISSION denial" (the refused instance spawns locally
 *   afterwards; nothing was registered).
 * - `kernel/src/test/kotlin/civictech/cell/membrane/BudgetProtocolSeamTest.kt`,
 *   "BS-04 refused - the handler observes nothing…" (`observed.shouldBeEmpty()`).
 * - `kernel/src/test/kotlin/civictech/cell/membrane/BudgetProtocolSeamTest.kt`,
 *   "Link rule refused - … no subscriber entry"
 *   (`exposedInlet.linking.links.shouldBeEmpty()`).
 *
 * All three were re-read at implementation time (2026-09-29, tree `195d92ad`)
 * and are present with the assertions named above; nothing was found missing
 * and no widened claim is reported.
 */
class BudgetDenialDischargeTest {

    private val beta = PeerId("principal-beta")
    private val alpha = PeerId("principal-alpha")

    /** Minimal organelle: an inlet that swallows whatever it is given. */
    private class SinkOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Any?>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Any?>>())

        init {
            inlet.serve(object : Consumer<Any?> {
                override fun provide(input: Any?) {
                    received += input
                }
            })
        }
    }

    /** A membrane with one mediated inlet exposure, i.e. one [BoundaryDenialSink]. */
    private class AccountedMembrane(
        val organelle: SinkOrganelle = SinkOrganelle(),
    ) : CompositeCell() {
        val exposedInlet = mediate("exposedInlet", "inlet", organelle.inlet)
    }

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
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
        return letters
    }

    private fun claimFor(peer: PeerId, claimClass: ClaimClass = ClaimClass.Attention): BudgetClaim =
        BudgetClaim(PeerStamp(peer, AuthLevel.Authenticated, null), claimClass)

    @Test
    fun `hosted path - BS-06,07,08 in one call - discharges Owned and Leased exactly once, never throws`() {
        val controller = SimulationController(seed = 41)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = AccountedMembrane()
        host.managementInlet.call.spawn(membrane)
        // RESTART, so "a denial is not a fault" is a real check: were the
        // helper's call classified as a cell failure anywhere on this path,
        // this is the policy that would fire.
        host.managementInlet.call.supervise(membrane.ref, SupervisionPolicy.RESTART)
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedInlet"]!!
        sink.denialCount shouldBe 0L

        val owned = Owned("secret")
        val leased = Leased("pooled") { returns++ }
        val consumed = Owned("gone").also { it.take() }
        val claim = claimFor(beta)
        val refused = BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "host", shortfall = 1)

        val denial = sink.denyBudget(
            refused = refused,
            claim = claim,
            seam = BoundarySeam.PROTOCOL_AUTHORITY,
            subject = "attention",
            deniedArgs = listOf(owned, leased, consumed, "plain"),
        )
        controller.runToIdle()

        // the counter moved by exactly one
        sink.denialCount shouldBe 1L

        // exactly one dead letter, and it is a report carrying this exact record
        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.denial shouldBe denial
        denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        denial.principal shouldBe beta
        denial.detail!! shouldContain "class=Attention"
        denial.detail!! shouldContain "scope=host"
        denial.detail!! shouldContain "shortfall=1"

        // sanitized: Owned -> Frozen, Leased -> Redacted, plain untouched,
        // no live exclusive handle enters the fan-out outlet
        val args = letter.invocation!!.invocation.args
        args.size shouldBe 4
        args[0].shouldBeInstanceOf<Frozen<*>>().value shouldBe "secret"
        args[1].shouldBeInstanceOf<Redacted>()
        args[2].shouldBeInstanceOf<Redacted>()
        args[3] shouldBe "plain"
        args.none { it is Owned<*> || it is Leased<*> } shouldBe true

        // the discharge happened on the originals, exactly once
        assertThrows<IllegalStateException> { owned.take() }.message shouldContain "use after move"
        returns shouldBe 1
        assertThrows<IllegalStateException> { leased.release() }

        // not a fault: no RESTART fired
        host.supervisionAccounting().restarts shouldBe 0L

        // the organelle received nothing: accounting a denial delivers nothing
        membrane.organelle.received shouldBe emptyList()
    }

    private var returns = 0

    @Test
    fun `hosted path - BUDGET_NOT_GRANTED renders a null shortfall`() {
        val controller = SimulationController(seed = 42)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val membrane = AccountedMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()
        val sink = membrane.boundaryDenials["exposedInlet"]!!

        val refused = BudgetOutcome.Refused(DenialReason.BUDGET_NOT_GRANTED, "host", shortfall = null)
        val denial = sink.denyBudget(refused, claimFor(beta), BoundarySeam.PROTOCOL_AUTHORITY, "attention")
        controller.runToIdle()

        denial.reason shouldBe DenialReason.BUDGET_NOT_GRANTED
        denial.detail!! shouldContain "shortfall=null"
        letters.single().denial shouldBe denial
    }

    @Test
    fun `hosted path - LEDGER_FAILURE renders a null shortfall`() {
        val controller = SimulationController(seed = 43)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)
        val membrane = AccountedMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()
        val sink = membrane.boundaryDenials["exposedInlet"]!!

        val refused = BudgetOutcome.Refused(DenialReason.LEDGER_FAILURE, "host", shortfall = null, detail = "IllegalStateException")
        val denial = sink.denyBudget(refused, claimFor(beta), BoundarySeam.PROTOCOL_AUTHORITY, "attention")
        controller.runToIdle()

        denial.reason shouldBe DenialReason.LEDGER_FAILURE
        denial.detail!! shouldContain "shortfall=null"
        denial.detail!! shouldContain "IllegalStateException"
        letters.single().denial shouldBe denial
    }

    @Test
    fun `unattached path - dischargeRefusedArgs discharges Owned and Leased exactly once, never throws`() {
        val sink = BoundaryDenials().sinkFor("x")

        val owned = Owned("secret")
        val leased = Leased("pooled") { unattachedReturns++ }
        val consumed = Owned("gone").also { it.take() }
        val claim = claimFor(beta)
        val refused = BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "host", shortfall = 1)

        sink.denyBudget(
            refused = refused,
            claim = claim,
            seam = BoundarySeam.PROTOCOL_AUTHORITY,
            subject = "attention",
            deniedArgs = listOf(owned, leased, consumed, "plain"),
        )

        sink.denialCount shouldBe 1L
        assertThrows<IllegalStateException> { owned.take() }.message shouldContain "use after move"
        unattachedReturns shouldBe 1
        assertThrows<IllegalStateException> { leased.release() }
    }

    private var unattachedReturns = 0

    @Test
    fun `ECO1-DEN-11 - a denial record and the spawn refusal never name another principal's balance`() {
        // alpha holds 7, beta holds nothing: initial = 0 so every bucket beta
        // ever touches starts empty, and alpha's balance is set explicitly.
        val ledger = RecordingLedger("host", initial = 0)
        ledger.balances[alpha to ClaimClass.Attention] = 7

        // 1. denyBudget driven directly against beta's refused Attention claim
        val sink = BoundaryDenials().sinkFor("x")
        val refused = BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "host", shortfall = 1)
        val denial = sink.denyBudget(refused, claimFor(beta, ClaimClass.Attention), BoundarySeam.PROTOCOL_AUTHORITY, "attention")

        denial.principal shouldBe beta
        denial.detail!! shouldContain "class=Attention"
        denial.detail!! shouldContain "scope=host"
        denial.detail!! shouldContain "shortfall=1"
        denial.detail!!.shouldNotContain("alpha")
        denial.detail!!.shouldNotContain("7")

        // 2. the real spawn site, driven under beta's stamp: same ledger, so
        // beta's Spawn bucket is also empty and the host refuses the spawn.
        val controller = SimulationController(seed = 44)
        val host = ManagedHost(scheduler = controller.scheduler(), budget = ledger)
        val cell = object : Cell {
            override val ref: CellRef = CellRef(UUID.randomUUID())
        }
        val ex = shouldThrow<BudgetRefusedException> {
            CurrentPeer.with(beta, AuthLevel.Authenticated) {
                host.managementInlet.call.spawn(cell)
            }
        }
        controller.runToIdle()

        ex.denial.principal shouldBe beta
        ex.message!!.shouldNotContain("alpha")
        ex.message!!.shouldNotContain("7")
        ex.denial.detail!!.shouldNotContain("alpha")
        ex.denial.detail!!.shouldNotContain("7")
    }
}
