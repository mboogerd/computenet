package civictech.cell.host

import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.BudgetRefusedException
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.RecordingLedger
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.evolve.Promotion
import civictech.cell.host.DrainAndMigrateTest.CounterProxy
import civictech.cell.host.DrainAndMigrateTest.StatefulCounterCell
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.replication.Replication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * ECO1 F4 T2 (`computenet-2zasa.2`), `[ECO1-DEN-08]` / BS-10: management
 * operations are band 0 — always-open, unconditional, and they outrank
 * admission (spec `30-execution-model/34-scheduling.md` decision 5, §34.5).
 * So supervise, suspend, resume, drainHost, resumeHost, despawn, migrate and a
 * replicated promotion swap requested for a principal whose budget is refused
 * at EVERY scope still succeed and take effect, move no host's
 * `boundaryDenialCount`, and construct no [BudgetClaim] at all.
 *
 * - **Behavioural half.** Every scope's ledger is a [RecordingLedger] that
 *   refuses every claim (`BUDGET_EXHAUSTED`) and records every call. A control
 *   first proves the ledger is live: a stamped `spawn` on a host with the same
 *   ledger shape IS refused and IS accounted. Then each management operation
 *   runs under `principal-q`'s stamp; the ledgers must record nothing.
 * - **Structural half.** Same operations, with a ledger that throws
 *   `AssertionError` for any non-Spawn claim, wrapped in a counting decorator
 *   that must stay at zero calls. A throw alone would be swallowed into a
 *   `LEDGER_FAILURE` refusal by `chargeOrFail`, which is why the decorator's
 *   count — not the absence of an exception — is the discriminating assertion.
 *
 * **Stamps under [SimulationController]** (the AMENDS comment on
 * `computenet-2zasa`, from the `computenet-5o1rf.1` review): the controller
 * steps queued tasks on the calling thread, so a `CurrentPeer.with` block
 * around `runToIdle()` stamps EVERY task stepped inside it. `migrate`
 * re-spawns each cell on the target through the target's management inlet,
 * so a migrate stepped inside a stamped block would charge the target's
 * Spawn budget under a stamp that belongs to the test thread, not to the
 * management request — a harness artefact, not a management-path charge. So
 * `migrate` and the `runToIdle()` that steps it are driven OUTSIDE any stamped
 * block. Every other operation, and its `runToIdle()`, runs INSIDE the stamp,
 * so the leak is present there and the no-claim assertion is the stronger one.
 *
 * **The promotion fixture establishes no downstream link under a remote
 * stamp.** Link re-authorization/accounting for remote-established links is
 * `computenet-8aboz`'s (decision `5o1rf-D8`), out of scope here.
 *
 * **Promotion COMMIT is internal management work** (`computenet-4yvsx`). A
 * stamped `promoteReplica` reaches `Replication.rebind`, whose candidate
 * re-spawn must not inherit the requesting principal's ambient stamp. The
 * regression tests cover both claim-free completion and the former half-done
 * state in which the incumbent was despawned before the candidate's Spawn
 * claim was refused.
 */
class BudgetManagementExemptionTest {

    private val q = PeerId("principal-q")

    private fun <R> asQ(block: () -> R): R = CurrentPeer.with(q, AuthLevel.Authenticated, block = block)

    private fun refuseAll(scope: String) = RecordingLedger(scope, refuse = {
        BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, scope, shortfall = 1)
    })

    /** Counts every call and forwards — the "ledger never called" probe (after `BudgetProtocolSeamTest`). */
    private class CountingLedger(private val inner: BudgetLedger) : BudgetLedger {
        val calls = AtomicInteger()
        override fun charge(claim: BudgetClaim): BudgetOutcome {
            calls.incrementAndGet()
            return inner.charge(claim)
        }
    }

    /** The structural probe: any claim other than Spawn is a management path constructing one. */
    private fun structural() = CountingLedger(object : BudgetLedger {
        override fun charge(claim: BudgetClaim): BudgetOutcome =
            if (claim.claimClass == ClaimClass.Spawn) BudgetOutcome.Admitted {}
            else throw AssertionError("management path constructed $claim")
    })

    private class Hosts(
        val controller: SimulationController,
        val registry: LocationRegistry,
        val host: ManagedHost,
        val target: ManagedHost,
        val promo: ManagedHost,
    ) {
        val all get() = listOf(host, target, promo)
    }

    /**
     * Runs every management operation for `principal-q` against [hosts],
     * calling [clean] after each step — the per-step "no claim, no denial"
     * assertion — and asserting that each operation took effect.
     */
    private fun runManagementOps(hosts: Hosts, clean: () -> Unit) {
        val controller = hosts.controller
        val host = hosts.host
        val target = hosts.target

        // Cells are spawned LOCALLY (no stamp): they must exist before q acts on them.
        val a = StatefulCounterCell()
        val b = StatefulCounterCell()
        host.managementInlet.call.spawn(a)
        host.managementInlet.call.spawn(b)
        controller.runToIdle()
        clean()
        val api = host.lookup<CounterProxy>(a.ref)!!.inlet.call

        asQ {
            host.managementInlet.call.supervise(a.ref, SupervisionPolicy.RESTART)
            controller.runToIdle()
            clean()

            host.managementInlet.call.suspend(a.ref)
            controller.runToIdle()
            clean()
            api.provide(1)
            controller.runToIdle()
            a.received.shouldBeEmpty() // parked while suspended

            host.managementInlet.call.resume(a.ref)
            controller.runToIdle()
            clean()
            a.received shouldBe listOf(1) // replayed on resume

            host.managementInlet.call.drainHost()
            controller.runToIdle()
            clean()
            a.deactivations shouldBe 1

            host.managementInlet.call.resumeHost()
            controller.runToIdle()
            clean()
            a.activations shouldBe 2

            host.managementInlet.call.despawn(b.ref)
            controller.runToIdle()
            clean()
            host.lookup<CounterProxy>(b.ref).shouldBeNull()
        }

        // OUTSIDE any stamped block — see the class KDoc and the AMENDS comment on
        // computenet-2zasa: under SimulationController a stamp on this thread would
        // stamp the target-side re-spawns migrate enqueues, a harness artefact.
        CurrentPeer.stamp().shouldBeNull()
        host.managementInlet.call.migrate(target.managementInlet)
        controller.runToIdle()
        clean()
        host.lookup<CounterProxy>(a.ref).shouldBeNull()
        target.lookup<CounterProxy>(a.ref).shouldNotBeNull()
        a.restores shouldBe 1 // moved through the snapshot round-trip
    }

    private fun hosts(ledger: (String) -> BudgetLedger): Hosts {
        val controller = SimulationController()
        val registry = LocationRegistry()
        return Hosts(
            controller,
            registry,
            ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = ledger("host")),
            ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = ledger("target")),
            ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = ledger("promo")),
        )
    }

    /** BS-10 / [ECO1-DEN-08], behavioural half. */
    @Test
    fun `management operations for an exhausted principal succeed, record no claim and move no denial count`() {
        // Control: the same ledger shape really refuses a stamped spawn and accounts it,
        // so the exemption below is not an everything-Unlimited no-op.
        val controlController = SimulationController()
        val controlLedger = refuseAll("control")
        val control = ManagedHost(scheduler = controlController.scheduler(), budget = controlLedger)
        shouldThrow<BudgetRefusedException> { asQ { control.managementInlet.call.spawn(StatefulCounterCell()) } }
        controlController.runToIdle()
        control.boundaryDenialCount() shouldBe 1L
        controlLedger.charges.map { it.claimClass } shouldBe listOf(ClaimClass.Spawn)

        val ledgers = mutableMapOf<String, RecordingLedger>()
        val hosts = hosts { scope -> refuseAll(scope).also { ledgers[scope] = it } }
        runManagementOps(hosts) {
            for (h in hosts.all) h.boundaryDenialCount() shouldBe 0L
            for ((scope, l) in ledgers) (scope to l.charges.toList()) shouldBe (scope to emptyList<BudgetClaim>())
        }
    }

    /** BS-10 / [ECO1-DEN-08], structural half. */
    @Test
    fun `management operations never call a ledger that throws for any non-Spawn claim`() {
        val ledgers = mutableMapOf<String, CountingLedger>()
        val hosts = hosts { scope -> structural().also { ledgers[scope] = it } }
        runManagementOps(hosts) {
            for (h in hosts.all) h.boundaryDenialCount() shouldBe 0L
            for ((scope, l) in ledgers) (scope to l.calls.get()) shouldBe (scope to 0)
        }
    }

    /**
     * The promotion swap for `principal-q`: an incumbent replica spawned LOCALLY
     * on `promo`, then `promoteReplica` under q's stamp with a same-ref
     * candidate. No downstream link is established under a remote stamp (class
     * KDoc). Everything after the setup is observed directly: no failure may
     * escape and the candidate must be the hosted replica after COMMIT.
     */
    private data class PromotionAttempt(
        val logicalId: UUID,
        val candidate: SetCell<String>,
        val failure: Throwable?,
    )

    private fun attemptPromotion(hosts: Hosts, clean: () -> Unit): PromotionAttempt {
        val controller = hosts.controller
        val promo = hosts.promo
        val replication = Replication(hosts.registry)
        val logicalId = UUID.randomUUID()
        val incumbent = SetCell<String>(CellRef(logicalId, 0)).also { replication.replicate(it, promo) }
        controller.runToIdle()
        clean()

        val candidate = SetCell<String>(CellRef(logicalId, 0))
        val failure = asQ {
            val result = runCatching {
                Promotion.promoteReplica(promo, replication, incumbent, candidate)
                controller.runToIdle()
            }.exceptionOrNull()
            CurrentPeer.get() shouldBe q // rebind's local reset restores its caller
            result
        }
        return PromotionAttempt(logicalId, candidate, failure)
    }

    private fun assertCandidateHosted(hosts: Hosts, attempt: PromotionAttempt) {
        hosts.registry.instances.replicasOf(attempt.logicalId) shouldBe setOf(attempt.candidate.ref)
        val api = hosts.promo.lookup<SetApi<String>>(attempt.candidate.ref).shouldNotBeNull()
        api.inlet.call.add("candidate-hosted")
        hosts.controller.runToIdle()
        attempt.candidate.membership() shouldBe setOf("candidate-hosted")
    }

    private fun runPromotion(hosts: Hosts, clean: () -> Unit) {
        val attempt = attemptPromotion(hosts, clean)
        attempt.failure.shouldBeNull()
        clean()
        assertCandidateHosted(hosts, attempt)
        clean()
    }

    /**
     * BS-10 / [ECO1-DEN-08], the promotion swap, behavioural half. COMMIT
     * re-spawns through `Replication.rebind`; that internal management work
     * must not inherit the CALLER's stamp or construct a Spawn claim.
     */
    @Test
    fun `a promotion swap for an exhausted principal succeeds and records no claim`() {
        val ledgers = mutableMapOf<String, RecordingLedger>()
        val hosts = hosts { scope -> refuseAll(scope).also { ledgers[scope] = it } }
        runPromotion(hosts) {
            for (h in hosts.all) h.boundaryDenialCount() shouldBe 0L
            for ((scope, l) in ledgers) (scope to l.charges.toList()) shouldBe (scope to emptyList<BudgetClaim>())
        }
    }

    /** The promotion swap, structural half. */
    @Test
    fun `a promotion swap never calls a ledger that throws for any non-Spawn claim`() {
        val ledgers = mutableMapOf<String, CountingLedger>()
        val hosts = hosts { scope -> structural().also { ledgers[scope] = it } }
        runPromotion(hosts) {
            for (h in hosts.all) h.boundaryDenialCount() shouldBe 0L
            for ((scope, l) in ledgers) (scope to l.calls.get()) shouldBe (scope to 0)
        }
    }

    /** Regression for the former non-atomic COMMIT failure (`computenet-4yvsx`). */
    @Test
    fun `a promotion swap never leaves the incumbent gone and the candidate unhosted`() {
        val ledgers = mutableMapOf<String, RecordingLedger>()
        val hosts = hosts { scope -> refuseAll(scope).also { ledgers[scope] = it } }
        val attempt = attemptPromotion(hosts) {
            for (h in hosts.all) h.boundaryDenialCount() shouldBe 0L
            for ((scope, l) in ledgers) (scope to l.charges.toList()) shouldBe (scope to emptyList<BudgetClaim>())
        }

        // Inspect the post-COMMIT topology before the exception assertion: on
        // the old path Spawn was refused after despawn, so these two checks
        // exposed the half-done swap instead of stopping at the thrown refusal.
        assertCandidateHosted(hosts, attempt)
        attempt.failure.shouldBeNull()
        for (h in hosts.all) h.boundaryDenialCount() shouldBe 0L
        for ((scope, l) in ledgers) (scope to l.charges.toList()) shouldBe (scope to emptyList<BudgetClaim>())
    }
}
