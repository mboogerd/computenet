package civictech.cell.host

import civictech.cell.BoundarySeam
import civictech.cell.BudgetCharging
import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.BudgetRefusedException
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.port.PortRef
import civictech.cell.Propagate
import civictech.cell.RecordingLedger
import civictech.cell.ThrowingLedger
import civictech.cell.port.Use
import civictech.cell.graph.CellFactory
import civictech.cell.graph.IdentityBinding
import civictech.cell.host.HierarchyTest.FlagCell
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.link.PeerStamp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.*

/**
 * ECO1 F3 T1 (`computenet-5o1rf.1`, decisions `5o1rf-D1`..`D6`, `D10`): a
 * remote-driven spawn is charged, per scope, against each host's own budget
 * in the same ancestor ascent as the G-28 quota walk; any refusal — budget or
 * quota — undoes every debit already taken; a budget refusal is accounted
 * once through the host's own `HOST_ADMISSION` sink before the spawn throws;
 * and a host attaches its hierarchy ledger to a [BudgetCharging] cell only
 * when some scope on its chain has a real budget.
 *
 * Unless a test says otherwise the hosts run under [SimulationController],
 * whose `await` steps the queue on the calling thread. The one test that
 * must NOT run that way — the stamp-carriage test — uses a real
 * [CoroutineScheduler] and says why.
 */
class BudgetHierarchyWalkTest {

    private val p = PeerId("p")

    private fun <R> asP(block: () -> R): R = CurrentPeer.with(p, AuthLevel.Authenticated, block = block)

    private class Tree(val root: ManagedHost, val mid: ManagedHost, val leaf: ManagedHost)

    private fun tree(
        controller: SimulationController,
        root: BudgetLedger,
        mid: BudgetLedger,
        leaf: BudgetLedger,
        rootQuota: Int? = null,
    ): Tree {
        val r = ManagedHost(scheduler = controller.scheduler(), quota = rootQuota, budget = root)
        val m = ManagedHost(scheduler = controller.scheduler(), budget = mid)
        val l = ManagedHost(scheduler = controller.scheduler(), budget = leaf)
        // local (unstamped) spawns: the tree's own construction is never charged
        r.managementInlet.call.spawn(m)
        m.managementInlet.call.spawn(l)
        controller.runToIdle()
        return Tree(r, m, l)
    }

    /** A cell that charges at its own seams — records what the host attached. */
    private class ChargingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, BudgetCharging {
        val attached = mutableListOf<BudgetLedger>()
        override fun attachBudget(ledger: BudgetLedger) {
            attached += ledger
        }
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

    /** BS-01 / [ECO1-CHG-01], [ECO1-CHG-02]. */
    @Test
    fun `a stamped spawn at the leaf debits every scope once for Spawn and activates the cell`() {
        val controller = SimulationController()
        val (root, mid, leaf) = listOf(RecordingLedger("root"), RecordingLedger("mid"), RecordingLedger("leaf"))
        val t = tree(controller, root, mid, leaf)

        val cell = FlagCell()
        asP { t.leaf.managementInlet.call.spawn(cell) }
        controller.runToIdle()

        leaf.balance(p, ClaimClass.Spawn) shouldBe 9
        mid.balance(p, ClaimClass.Spawn) shouldBe 9
        root.balance(p, ClaimClass.Spawn) shouldBe 9
        for (ledger in listOf(root, mid, leaf)) {
            ledger.charges.map { it.claimClass } shouldBe listOf(ClaimClass.Spawn)
            ledger.charges.single().stamp shouldBe PeerStamp(p, AuthLevel.Authenticated)
        }
        cell.active.shouldBeTrue()
        t.leaf.boundaryDenialCount() shouldBe 0L
    }

    /** BS-09 / [ECO1-CHG-03], [ECO1-CHG-04] first clause, [ECO1-DEN-01]. */
    @Test
    fun `a refusing ancestor undoes every debit taken in the ascent and accounts one HOST_ADMISSION denial`() {
        val controller = SimulationController()
        val root = RecordingLedger("root", refuse = {
            BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "root", shortfall = 1)
        })
        val mid = RecordingLedger("mid")
        val leaf = RecordingLedger("leaf")
        val t = tree(controller, root, mid, leaf)
        val letters = collectDeadLetters(t.leaf)

        val cell = FlagCell()
        val ex = shouldThrow<BudgetRefusedException> { asP { t.leaf.managementInlet.call.spawn(cell) } }
        (ex as Throwable).shouldBeInstanceOfIse()
        controller.runToIdle()

        // both admitting scopes charged, then refunded to exactly their pre-attempt values
        leaf.charges.size shouldBe 1
        mid.charges.size shouldBe 1
        leaf.balance(p, ClaimClass.Spawn) shouldBe 10
        mid.balance(p, ClaimClass.Spawn) shouldBe 10

        t.leaf.boundaryDenialCount() shouldBe 1L
        val denial = ex.denial
        denial.seam shouldBe BoundarySeam.HOST_ADMISSION
        denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        denial.principal shouldBe p
        denial.exposure shouldBe "host"
        denial.subject shouldBe "FlagCell"
        denial.detail!! shouldContain "class=Spawn"
        denial.detail!! shouldContain "scope=root"
        denial.detail!! shouldContain "shortfall=1"
        ex.refused.reason shouldBe DenialReason.BUDGET_EXHAUSTED
        ex.message!! shouldContain "class=Spawn"
        // the record reached the host's dead-letter channel, once
        letters.mapNotNull { it.denial } shouldBe listOf(denial)

        // nothing was registered: the same instance spawns fine locally afterwards
        cell.active.shouldBeFalse()
        t.leaf.managementInlet.call.spawn(cell)
        controller.runToIdle()
        cell.active.shouldBeTrue()
    }

    /** [ECO1-CHG-04] second clause: a quota refusal after an admitted debit. */
    @Test
    fun `a quota refusal after an admitted debit refunds it, keeps the G-28 message and accounts no denial`() {
        val controller = SimulationController()
        val ledger = RecordingLedger("root")
        val root = ManagedHost(scheduler = controller.scheduler(), quota = 1, budget = ledger)
        root.managementInlet.call.spawn(FlagCell()) // local: fills the quota, uncharged
        controller.runToIdle()

        val ex = shouldThrow<IllegalStateException> { asP { root.managementInlet.call.spawn(FlagCell()) } }
        ex.shouldNotBeInstanceOf<BudgetRefusedException>()
        ex.message shouldBe "quota exceeded: host ${root.ref} allows 1 cells in its subtree (G-28)"
        ledger.charges.size shouldBe 1 // debited …
        ledger.balance(p, ClaimClass.Spawn) shouldBe 10 // … then refunded
        root.boundaryDenialCount() shouldBe 0L
    }

    /** [ECO1-CHG-04] second clause, across scopes: an ANCESTOR's quota refuses after the leaf and mid debited. */
    @Test
    fun `an ancestor quota refusal refunds the debits taken below it`() {
        val controller = SimulationController()
        val (root, mid, leaf) = listOf(RecordingLedger("root"), RecordingLedger("mid"), RecordingLedger("leaf"))
        // root's subtree already holds mid and leaf: quota 2 is full
        val t = tree(controller, root, mid, leaf, rootQuota = 2)

        val ex = shouldThrow<IllegalStateException> { asP { t.leaf.managementInlet.call.spawn(FlagCell()) } }
        ex.message shouldBe "quota exceeded: host ${t.root.ref} allows 2 cells in its subtree (G-28)"
        leaf.balance(p, ClaimClass.Spawn) shouldBe 10
        mid.balance(p, ClaimClass.Spawn) shouldBe 10
        // budget before quota per scope (5o1rf-D2): root was charged, then its quota refused
        root.balance(p, ClaimClass.Spawn) shouldBe 10
        t.leaf.boundaryDenialCount() shouldBe 0L
    }

    /** BS-20 / [ECO1-CHG-10] at spawn. */
    @Test
    fun `a throwing ledger refuses the spawn with LEDGER_FAILURE and leaves no debit anywhere`() {
        val controller = SimulationController()
        val root = RecordingLedger("root")
        val mid = RecordingLedger("mid", throwOnCharge = true)
        val leaf = RecordingLedger("leaf")
        val t = tree(controller, root, mid, leaf)

        val ex = shouldThrow<BudgetRefusedException> { asP { t.leaf.managementInlet.call.spawn(FlagCell()) } }
        ex.refused.reason shouldBe DenialReason.LEDGER_FAILURE
        ex.refused.shortfall shouldBe null
        ex.denial.reason shouldBe DenialReason.LEDGER_FAILURE
        ex.denial.detail!! shouldContain "shortfall=null"
        ex.denial.detail!! shouldContain "IllegalStateException"
        leaf.balance(p, ClaimClass.Spawn) shouldBe 10 // debited, then refunded
        root.charges shouldBe emptyList() // the ascent stopped at mid
        t.leaf.boundaryDenialCount() shouldBe 1L
    }

    /** [ECO1-BUD-05] at spawn. */
    @Test
    fun `a local spawn touches no ledger even when every scope's ledger throws`() {
        val controller = SimulationController()
        val t = tree(controller, ThrowingLedger, ThrowingLedger, ThrowingLedger)
        val cell = FlagCell()
        t.leaf.managementInlet.call.spawn(cell) // no CurrentPeer stamp
        controller.runToIdle()
        cell.active.shouldBeTrue()
        t.leaf.boundaryDenialCount() shouldBe 0L
    }

    /** [ECO1-BUD-04] (enforcement half) / [ECO1-POL-06] / BS-02, and 5o1rf-D6. */
    @Test
    fun `with every scope Unlimited nothing is attached, and with any real budget the hierarchy ledger is`() {
        val controller = SimulationController()
        val u = BudgetLedger.Unlimited
        val off = tree(controller, u, u, u)
        val offCell = ChargingCell()
        asP { off.leaf.managementInlet.call.spawn(offCell) }
        offCell.attached shouldBe emptyList()

        val on = tree(controller, RecordingLedger("root"), u, u)
        val onCell = ChargingCell()
        on.leaf.managementInlet.call.spawn(onCell) // attach does not depend on the stamp
        onCell.attached.single() shouldBeSameInstanceAs on.leaf.hierarchyLedger
    }

    /** [ECO1-DEN-02] (spawn site): boundaryDenialCount delta equals the number of Refused outcomes. */
    @Test
    fun `the host's denial count moves exactly once per budget refusal`() {
        val controller = SimulationController()
        var refusals = 0
        var n = 0
        val ledger = RecordingLedger("root", initial = 100, refuse = {
            if (n++ % 3 == 0) {
                refusals++
                BudgetOutcome.Refused(DenialReason.BUDGET_NOT_GRANTED, "root", shortfall = null)
            } else {
                null
            }
        })
        val host = ManagedHost(scheduler = controller.scheduler(), budget = ledger)
        repeat(7) {
            runCatching { asP { host.managementInlet.call.spawn(FlagCell()) } }
        }
        controller.runToIdle()
        refusals shouldBe 3
        host.boundaryDenialCount() shouldBe refusals.toLong()
    }

    /** spawnBound reaches the charge through spawn, and its budget refusal dead-letters as any rejected step. */
    @Test
    fun `a stamped spawnBound is charged through spawn`() {
        val controller = SimulationController()
        val ledger = RecordingLedger("host")
        val host = ManagedHost(scheduler = controller.scheduler(), budget = ledger)
        asP { host.managementInlet.call.spawnBound(CellFactory { FlagCell(it) }, IdentityBinding.FreshLogical, null) }
        controller.runToIdle()
        ledger.charges.map { it.claimClass } shouldBe listOf(ClaimClass.Spawn)
        ledger.balance(p, ClaimClass.Spawn) shouldBe 9
    }

    /**
     * [ECO1-CHG-04] stamp carriage (`5o1rf-D1`). Deliberately a real
     * [CoroutineScheduler], not [SimulationController]: the simulation's
     * `await` runs the awaited spawn on the calling thread, where the
     * `CurrentPeer` thread-local is set anyway, so it cannot tell whether the
     * host carries the stamp. `CoroutineScheduler` runs the awaited task on
     * its own dispatcher thread; only the proxy handler's
     * `CurrentPeer.withStamp(stamp)` makes the stamp visible there. The test
     * is deterministic regardless: the charge completes inside the awaited
     * task, before `spawn` / `spawnBound` returns to this thread.
     */
    @Test
    fun `the delivering thread's stamp reaches the awaited spawn on a CoroutineScheduler host`() {
        val scheduler = CoroutineScheduler("budget-stamp-carriage")
        try {
            val ledger = RecordingLedger("host")
            val host = ManagedHost(scheduler = scheduler, budget = ledger)
            asP { host.managementInlet.call.spawn(FlagCell()) }
            asP { host.managementInlet.call.spawnBound(CellFactory { FlagCell(it) }, IdentityBinding.FreshLogical, null) }
            synchronized(ledger) {
                ledger.charges.map { it.stamp.id } shouldBe listOf(p, p)
            }
            ledger.balance(p, ClaimClass.Spawn) shouldBe 8
        } finally {
            scheduler.shutdown()
        }
    }

    /** [ECO1-CHG-03] through the ledger handed to membranes: the walk refunds on refusal. */
    @Test
    fun `the hierarchy ledger refunds every admitting scope when an ancestor refuses`() {
        val controller = SimulationController()
        val root = RecordingLedger("root", refuse = {
            BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "root", shortfall = 2)
        })
        val mid = RecordingLedger("mid")
        val leaf = RecordingLedger("leaf")
        val t = tree(controller, root, mid, leaf)
        val claim = BudgetClaim(PeerStamp(p), ClaimClass.Attention)

        val outcome = t.leaf.hierarchyLedger.charge(claim)
        outcome shouldBe BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "root", 2)
        leaf.balance(p, ClaimClass.Attention) shouldBe 10
        mid.balance(p, ClaimClass.Attention) shouldBe 10
        // the walk never accounts — the caller does
        t.leaf.boundaryDenialCount() shouldBe 0L
    }

    @Test
    fun `the hierarchy ledger's admission undoes every scope, and an all-Unlimited chain returns the shared admission`() {
        val controller = SimulationController()
        val root = RecordingLedger("root")
        val leaf = RecordingLedger("leaf")
        val t = tree(controller, root, BudgetLedger.Unlimited, leaf)
        val claim = BudgetClaim(PeerStamp(p), ClaimClass.Attention)

        val admitted = t.leaf.hierarchyLedger.charge(claim) as BudgetOutcome.Admitted
        root.balance(p, ClaimClass.Attention) shouldBe 9
        leaf.balance(p, ClaimClass.Attention) shouldBe 9
        admitted.undo()
        admitted.undo() // idempotent
        root.balance(p, ClaimClass.Attention) shouldBe 10
        leaf.balance(p, ClaimClass.Attention) shouldBe 10

        val u = BudgetLedger.Unlimited
        val off = tree(controller, u, u, u)
        off.leaf.hierarchyLedger.charge(claim) shouldBeSameInstanceAs u.charge(claim)
    }

    @Test
    fun `a throwing scope in the hierarchy ledger refuses with LEDGER_FAILURE after refunding below it`() {
        val controller = SimulationController()
        val leaf = RecordingLedger("leaf")
        val t = tree(controller, ThrowingLedger, BudgetLedger.Unlimited, leaf)
        val outcome = t.leaf.hierarchyLedger.charge(BudgetClaim(PeerStamp(p), ClaimClass.Attention))
        (outcome as BudgetOutcome.Refused).reason shouldBe DenialReason.LEDGER_FAILURE
        outcome.scope shouldBe t.root.ref.toString()
        outcome.shortfall shouldBe null
        leaf.balance(p, ClaimClass.Attention) shouldBe 10
    }

    private fun Throwable.shouldBeInstanceOfIse() {
        (this is IllegalStateException).shouldBeTrue()
    }

}
