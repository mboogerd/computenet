package civictech.testkit.dst

import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Owned
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostScheduler
import civictech.cell.host.ManagedHost
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The BS-14 graph: exclusive payloads handed across a bridge, under a rig that destroys,
 * duplicates and crashes.
 *
 * ## The transfer model, and why it is the honest one
 *
 * The sender **retains** the `Owned` handle until it observes an ack. Handing a payload to the
 * wire is not a discharge here, and that is the whole point: a graph that discharged its
 * obligation at the send site would define the loss away — every destroyed frame would balance,
 * and [CHA1-53] would be unfalsifiable on this graph. Keeping the obligation open until the far
 * side is observed is what makes a frame the rig destroyed a *detectable* loss.
 *
 * Retries are bounded. On exhaustion the two variants diverge, and only there:
 *
 *  - [conforming] **explicitly discharges** the payload, naming the reason. That is the
 *    conforming answer to an undeliverable exclusive: it is accounted, on a failure path, by
 *    the code that owned it.
 *  - [silentlyDropping] lets the handle go. Nothing else differs. It is the diverging control
 *    ([CHA1-62]/[CHA1-63]): if the check cannot tell these two apart, it checks nothing.
 */
object ExclusiveBridgeGraph {

    const val CONFORMING_ID: String = "dst-selftest-exclusive-bridge"
    const val CONTROL_ID: String = "dst-selftest-exclusive-bridge-control"
    const val CHECK_ID: String = "dst-selftest-exclusive-check"
    const val PAYLOADS: Int = 6
    const val MAX_ATTEMPTS: Int = 4

    val conforming: GraphSpec = GraphSpec(CONFORMING_ID, builder(dischargeOnExhaustion = true))
    val silentlyDropping: GraphSpec = GraphSpec(CONTROL_ID, builder(dischargeOnExhaustion = false))

    /**
     * The adversary BS-14 names: drop-mode partition, duplication and a crash, on one plan.
     *
     * `drop-ack` destroys the whole ack channel, which is what drives the sender to retry
     * exhaustion and so reaches the one branch the two graph variants differ in. Note what that
     * means and does not mean: a payload whose *data* frame arrived but whose ack was destroyed
     * is discharged by the sender as undeliverable even though the receiver saw it. That is the
     * honest position of a sender that cannot observe the far side — the property under test is
     * that the obligation is **accounted**, not that delivery was exactly-once.
     */
    fun plan(seed: Long): FaultPlan = FaultPlan.of(
        seed,
        PartitionFault.drop("drop-sr", "s->r", from = 0, until = 3),
        PartitionFault.drop("drop-ack", "r->s", from = 0),
        DuplicateFault.frames("dup-sr", "s->r", copies = 1, probability = 0.5),
        CrashFault.midDrain("crash-receiver", "receiver", atStep = 2),
    )

    private fun builder(dischargeOnExhaustion: Boolean): GraphBuilder = GraphBuilder { world ->
        val ledger = ExclusiveLedgers.declare(world)
        val schedulers = mutableMapOf<String, HostScheduler>()
        val journal = world.journals.declare("sender-journal")

        val sender = world.hosts.declare("sender") { ctx ->
            schedulers["sender"] = ctx.scheduler
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry, journalFor = { journal })
        }
        val receiver = world.hosts.declare("receiver") { ctx ->
            schedulers["receiver"] = ctx.scheduler
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
        }
        world.cells.declare("sender", sender.host.ref)
        world.cells.declare("receiver", receiver.host.ref)
        world.edges.declare("s->r", from = "sender", to = "receiver")
        world.edges.declare("r->s", from = "receiver", to = "sender")

        /** Live handles the sender still owes an account for, by token id. */
        val outbox = linkedMapOf<String, Owned<TrackedExclusive>>()
        val attempts = linkedMapOf<String, Int>()

        fun ack(id: String) {
            world.edges.deliver("r->s", id.toByteArray()).forEach { frame ->
                schedulers.getValue("sender").submit(10) {
                    world.trace.emit(host = "sender", cell = "sender", port = "ack")
                    // A duplicate frame produces a duplicate ack; the second finds an empty
                    // slot and accounts nothing. Without this, duplication would double-consume
                    // and the ledger would report it — which is the point of tracking both.
                    outbox.remove(String(frame))?.let { ledger.consume(it, "acked by receiver") }
                }
            }
        }

        lateinit var send: (String) -> Unit
        send = { id ->
            attempts[id] = (attempts[id] ?: 0) + 1
            journal.append(id.toByteArray())
            world.edges.deliver("s->r", id.toByteArray()).forEach { frame ->
                schedulers.getValue("receiver").submit(10) {
                    world.trace.emit(host = "receiver", cell = "receiver", port = "recv")
                    ack(String(frame))
                }
            }
            // Bounded retry. Scheduled unconditionally so the sender notices a destroyed frame
            // at all: with no ack and no timer, an exclusive handed to a partitioned edge would
            // sit in the outbox forever and the run would simply quiesce around it.
            schedulers.getValue("sender").submit(20) {
                if (id in outbox) {
                    if ((attempts[id] ?: 0) < MAX_ATTEMPTS) {
                        send(id)
                    } else {
                        val owned = outbox.remove(id)!!
                        if (dischargeOnExhaustion) {
                            ledger.discharge(owned.take(), "undeliverable after $MAX_ATTEMPTS attempts")
                        }
                        // else: the control drops the handle here, accounting nothing.
                    }
                }
            }
        }

        repeat(PAYLOADS) { i ->
            val id = "p$i"
            outbox[id] = ledger.mintOwned(id, origin = "sender")
            send(id)
        }
    }
}

/**
 * [CHA1-53] and BS-14 — no failure path may silently drop an exclusive payload.
 *
 * This is the repo's standing ownership invariant (AGENTS.md "Core invariants to protect", spec
 * 23 R8) mechanised over the rig's own adversary. The pair of tests is the instrument: the
 * conforming graph must survive drop-mode partition, duplication and a crash on **every** seed
 * of the sweep, and the control — identical but for one branch that lets a handle go — must
 * fail. A check that cannot separate those two checks nothing.
 */
class ExclusivePayloadAccountingTest {

    /**
     * BS-14: every exclusive is accounted on every seed, under drop, duplication and crash.
     *
     * The sweep also asserts the faults **fired**. A green sweep whose plan was inert would be
     * the vacuous version of this test, and inertness is exactly what the rig records
     * ([AppliedFault.inert], BS-13) so a consumer can refuse it.
     */
    @Test
    fun everyExclusivePayloadIsAccountedUnderDropDuplicationAndCrash_BS14() {
        val sweep = dstSweep(
            suite = "dst-selftest-exclusive",
            seeds = 1L..25L,
            graph = ExclusiveBridgeGraph.conforming,
            checkId = ExclusiveBridgeGraph.CHECK_ID,
            artifactRoot = root,
            planFor = ExclusiveBridgeGraph::plan,
        )
        sweep.assertAllPassed()

        val fired = sweep.entries.flatMap { it.report?.appliedFaults.orEmpty() }
            .filter { !it.inert }
            .map { it.id }
            .toSet()
        assertEquals(
            setOf("drop-sr", "drop-ack", "dup-sr", "crash-receiver"),
            fired,
            "a green BS-14 sweep whose adversary never fired proves nothing: ${sweep.summary()}",
        )
    }

    /**
     * The diverging control ([CHA1-62]/[CHA1-63]): the same graph, one branch changed from an
     * explicit discharge to letting the handle go, fails on at least one seed — with the
     * [CHA1-53] failure and nothing else.
     */
    @Test
    fun theSilentlyDroppingControlLosesAnExclusiveOnAtLeastOneSeed_BS14() {
        val sweep = dstSweep(
            suite = "dst-selftest-exclusive-control",
            seeds = 1L..25L,
            graph = ExclusiveBridgeGraph.silentlyDropping,
            checkId = ExclusiveBridgeGraph.CHECK_ID,
            artifactRoot = root,
            planFor = ExclusiveBridgeGraph::plan,
        )

        assertTrue(
            sweep.failures.isNotEmpty(),
            "a control that does not diverge fails the rig's own self-test: ${sweep.summary()}",
        )
        val first = sweep.failures.first()
        assertTrue(
            first.cause is ExclusivePayloadLost,
            "the control must fail on the ownership invariant, not on something else: ${first.cause}",
        )
        assertTrue("LOST" in (first.cause as ExclusivePayloadLost).detail(), (first.cause as ExclusivePayloadLost).detail())
    }

    /** A failure report over a control seed renders the lost tokens — in the detail, not the message. */
    @Test
    fun theFailureReportNamesTheLostPayloadsWithoutPuttingThemInTheMessage_CHA1_53() {
        val run = DstRun(
            ExclusiveBridgeGraph.silentlyDropping,
            ExclusiveBridgeGraph.plan(seed = 3),
            check = CheckRegistry.require(ExclusiveBridgeGraph.CHECK_ID),
        )
        val report = run.execute()
        assertEquals(DstOutcome.FAILED, report.outcome, "seed 3 must lose a payload on the control graph")

        val rendered = FailureReport.of(report, suite = "dst-selftest-exclusive-control").render()
        assertTrue("exclusive payload lost ([CHA1-53])" in rendered, rendered)
        assertTrue("LOST p" in rendered, "the report names which payloads were lost:\n$rendered")
        assertTrue("minted at sender" in rendered, rendered)
    }

    // ------------------------------------------------------------- ledger mechanics

    /**
     * `Leased.release()` is observed **structurally**, with no cooperation from the releasing
     * code: the ledger supplies the lease's own `returnToPool`.
     *
     * This is the one disposition the ledger does not have to be told about, and it is why a
     * `Leased` released inside the kernel's dead-letter sanitizer
     * (`DeadLetters.sanitizeForDeadLetter`) lands here on its own.
     */
    @Test
    fun aLeasedReleaseIsObservedWithoutTheReleasingCodeKnowingTheLedgerExists() {
        val ledger = ExclusiveLedger("leases")
        val leased = ledger.mintLeased("l0", origin = "pool")
        assertEquals(1, ledger.outstanding().size)

        leased.release()

        assertTrue(ledger.outstanding().isEmpty(), ledger.renderSummary())
        assertEquals(ExclusiveDisposition.RELEASED, ledger.records().single().dispositions.single().first)
        ledger.verify()
    }

    /** A dead-lettered exclusive is accounted, not lost: the token is read out of the letter. */
    @Test
    fun aDeadLetteredExclusiveIsAccountedNotReportedLost_CHA1_53() {
        val ledger = ExclusiveLedger("dl")
        val owned = ledger.mintOwned("p0", origin = "sender")
        // What the kernel's sanitizer leaves in a dead letter for an Owned: a Frozen view
        // (DeadLetters.sanitizeForDeadLetter). The ledger recognises it by token.
        val letter = DeadLetter(
            hostRef,
            IllegalStateException("cell threw"),
            "invocation failed",
            invocation(Frozen(owned.take())),
        )

        ledger.accountFrom(listOf(letter))

        assertTrue(ledger.outstanding().isEmpty(), ledger.renderSummary())
        assertEquals(ExclusiveDisposition.DEAD_LETTERED, ledger.records().single().dispositions.single().first)
        // Idempotent: accounting the same letter twice is not a double-accounting.
        ledger.accountFrom(listOf(letter))
        assertTrue(ledger.doubleAccounted().isEmpty(), ledger.renderSummary())
    }

    /** A live `Owned` in a letter is read through `borrow()`, which does not consume it. */
    @Test
    fun readingATokenOutOfALiveOwnedDoesNotConsumeIt() {
        val ledger = ExclusiveLedger("live")
        val owned = ledger.mintOwned("p0")
        ledger.accountFrom(listOf(DeadLetter(hostRef, null, "undeliverable", invocation(owned))))

        assertTrue(ledger.outstanding().isEmpty(), ledger.renderSummary())
        // Still takeable: borrow() is the non-consuming snapshot view (spec 23 §Taps).
        assertEquals("p0", owned.take().token.id)
    }

    /** Two dispositions on one payload is a violation as much as none is. */
    @Test
    fun anExclusiveAccountedTwiceFailsTheRun_CHA1_53() {
        val ledger = ExclusiveLedger("double")
        val value = ledger.mintOwned("p0").take()
        ledger.discharge(value, "first")
        ledger.discharge(value, "second")

        val failure = runCatching { ledger.verify() }.exceptionOrNull()
        assertTrue(failure is ExclusivePayloadLost, "$failure")
        assertTrue("accounted twice" in failure.message!!, failure.message!!)
        assertTrue("DOUBLE p0" in failure.detail(), failure.detail())
    }

    /**
     * The stated limit, asserted so it cannot rot: a payload consumed by a bare `Owned.take()`
     * — rather than through [ExclusiveLedger.consume] — is reported **lost**, not accounted.
     *
     * `Owned.consumed` is private with no accessor (`civictech.cell.Ownership`), so nothing in
     * `:testkit` can see a bare take. The ledger therefore fails loud in that case rather than
     * assuming the best, and this test pins that direction: the opposite default would let an
     * un-instrumented graph read as conforming.
     */
    @Test
    fun aBareTakeIsNotObservableAndIsReportedLostRatherThanAssumedFine() {
        val ledger = ExclusiveLedger("bare")
        val owned = ledger.mintOwned("p0")

        owned.take()

        assertEquals(listOf("p0"), ledger.outstanding().map { it.token.id })
    }

    companion object {
        private val hostRef = CellRef(java.util.UUID.randomUUID())
        private val root = File("build/dst-selftest/exclusive")

        private fun invocation(vararg args: Any?) = HostedPortInvocation(
            cellRef = hostRef,
            portName = "p",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation("m", emptyList(), args.toList()),
        )

        @JvmStatic
        @BeforeAll
        fun register() {
            GraphRegistry.register(ExclusiveBridgeGraph.conforming)
            GraphRegistry.register(ExclusiveBridgeGraph.silentlyDropping)
            CheckRegistry.register(ExclusiveBridgeGraph.CHECK_ID, ExclusiveLedgers.check())
            root.deleteRecursively()
        }

        @JvmStatic
        @AfterAll
        fun unregister() {
            GraphRegistry.unregister(ExclusiveBridgeGraph.CONFORMING_ID)
            GraphRegistry.unregister(ExclusiveBridgeGraph.CONTROL_ID)
            CheckRegistry.unregister(ExclusiveBridgeGraph.CHECK_ID)
        }
    }
}
