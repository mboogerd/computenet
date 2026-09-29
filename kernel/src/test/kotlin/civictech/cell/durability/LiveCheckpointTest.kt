package civictech.cell.durability

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeBound
import civictech.cell.host.IntakeState
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SaturationPolicy
import civictech.cell.host.SimulationController
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * computenet-xy7w4 R-D (design D3; 93 I-7 R7, spec 24 `[24-DUR-02]`): `checkpoint(J)` is safe at
 * ANY inter-invocation boundary. It runs on the management band (priority 0), ahead of every
 * staged data task (priority 20), so when it compacts J some accepted frames teeing to J have
 * not been delivered yet — their effect is in no snapshot. The compacted J must carry them as
 * ordinary `RECORD_FRAME`s after the checkpoint records, in host-sequence order, read and reset
 * under the intake's `dataLock` — or a crash after the checkpoint loses them.
 *
 * Before D3 (base d2b4d160) the reset kept the checkpoint records only: (a) recovered 0 of 8
 * elements, (c) recovered nothing of the replayed tail.
 *
 * No test here fences on quiescence before checkpointing — that is the point.
 */
class LiveCheckpointTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun ops(host: ManagedHost, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun records(journal: Journal): List<DecodedJournalRecord> = journal.replay().map(JournalRecords::decode)

    /** Crash: only [journal] survives. A fresh host + cell recover from it and run to idle. */
    private fun recover(controller: SimulationController, journal: Journal, ref: CellRef): SetCell<String> {
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        host.recoverFrom(journal)
        controller.runToIdle()
        return cell
    }

    @Test
    fun `(a) a checkpoint overtaking 8 staged adds carries them into the compacted journal`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        val elements = (1..8).map { "e$it" }
        elements.forEach { ops(host, ref).add(it) } // staged, NOT delivered
        host.stagedWorkTotal() shouldBe 8

        // The sim scheduler steps only until the checkpoint's own task completes, and runs by
        // priority: the management task (0) goes first, the 8 data tasks (20) stay queued.
        host.checkpoint(journal)
        withClue("the checkpoint must have run ahead of every staged delivery") {
            host.stagedWorkTotal() shouldBe 8
            cell.membership() shouldBe emptySet()
        }

        val compacted = records(journal)
        compacted.first().let { it is DecodedJournalRecord.Checkpoint } shouldBe true
        val frames = compacted.filterIsInstance<DecodedJournalRecord.Frame>()
        withClue("the 8 undelivered frames ride after the checkpoint records, in acceptance order") {
            frames.map { civictech.cell.wire.WireCodec.decode(it.payload).invocation.args.single() } shouldBe elements
            compacted.takeLast(8).all { it is DecodedJournalRecord.Frame } shouldBe true
        }

        controller.runToIdle()
        cell.membership() shouldBe elements.toSet()
        // delivering the carried frames appends nothing: they were journaled once, at acceptance
        records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 8

        recover(controller, journal, ref).membership() shouldBe elements.toSet()
    }

    @Test
    fun `(b) a coalesced staged entry is carried as the merge of its originals`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            journal = journal,
            intakeBound = IntakeBound(highWater = 1, lowWater = 0, policy = SaturationPolicy.Coalesce),
        )
        host.managementInlet.call.spawn(SetCell<String>(ref))
        controller.runToIdle()

        // Two deltas in one source+wave slot: the second arrives on a SATURATED intake and is
        // merged INTO the first's staged entry. The WAL holds both originals; the queue one merge.
        val source = PortRef.generate()
        val context = MessageContext(Timestamp(source.id, 7), source)
        val tags = UUID.randomUUID()
        fun delta(element: String, counter: Long) = HostedPortInvocation(
            ref, "deltaInlet", HostedPortInvocation.Type.PORT_API,
            Invocation.of(
                Propagate::class.java.getMethod("propagate", Any::class.java),
                arrayOf(SetDelta(adds = mapOf(element to setOf(Timestamp(tags, counter))))),
                context,
            ),
        )
        host.enqueueHostedInvocation(delta("x", 1))
        host.currentIntakeState shouldBe IntakeState.SATURATED
        host.enqueueHostedInvocation(delta("y", 2))
        withClue("the second delta coalesced into the first's staged entry") {
            host.stagedWorkTotal() shouldBe 1
            records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 2
        }

        host.checkpoint(journal)
        withClue("one staged entry — the merge — is carried, not the two appended originals") {
            records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 1
        }

        recover(controller, journal, ref).membership() shouldBe setOf("x", "y")
    }

    @Test
    fun `(c) a checkpoint between recoverFrom and its delivery keeps the replayed tail`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val live = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        live.managementInlet.call.spawn(SetCell<String>(ref))
        controller.runToIdle()
        val elements = (1..5).map { "r$it" }
        elements.forEach { ops(live, ref).add(it) }
        controller.runToIdle()

        // Crash 1, then recover — which only STAGES the 5 frames — and checkpoint before any
        // data task runs. The fresh cell's snapshot is empty; the tail is still staged.
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        host.recoverFrom(journal).replayedFrames shouldBe 5
        host.checkpoint(journal)
        withClue("the checkpoint ran before any replayed frame was delivered") {
            cell.membership() shouldBe emptySet()
        }
        records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 5

        controller.runToIdle()
        cell.membership() shouldBe elements.toSet()
        withClue("delivering the carried replay re-journals nothing (D2's rule still holds)") {
            records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 5
        }

        // Crash 2: the compacted journal still reproduces the fold.
        recover(controller, journal, ref).membership() shouldBe cell.membership()
    }

    @Test
    fun `(d) checkpoints taken concurrently with a live writer lose no accepted frame`() {
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val scheduler = VirtualThreadScheduler("xy7w4.2-RD-live")
        val host = ManagedHost(scheduler = scheduler, journal = journal)
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val n = 1000
        val every = 50
        val checkpointsDue = LinkedBlockingQueue<Int>()
        var checkpoints = 0

        try {
            val checkpointer = CompletableFuture.runAsync {
                while (true) {
                    val at = checkNotNull(checkpointsDue.poll(30, TimeUnit.SECONDS)) {
                        "checkpointer starved after $checkpoints checkpoints"
                    }
                    if (at < 0) return@runAsync
                    host.checkpoint(journal)
                    checkpoints++
                }
            }
            val writer = CompletableFuture.runAsync {
                val inlet = ops(host, ref)
                for (i in 1..n) {
                    inlet.add("v$i") // accepted (journaled + staged) when this returns
                    if (i % every == 0) checkpointsDue.put(i)
                }
                checkpointsDue.put(-1)
            }
            writer.get(60, TimeUnit.SECONDS)
            checkpointer.get(60, TimeUnit.SECONDS)
            host.quiescence().await(30_000)
        } finally {
            scheduler.shutdown()
        }

        val expected = (1..n).map { "v$it" }.toSet()
        checkpoints shouldBe n / every
        cell.membership() shouldBe expected
        withClue("the journal was compacted, not merely appended to") {
            records(journal).count { it is DecodedJournalRecord.Checkpoint } shouldBe 1
            (records(journal).count { it is DecodedJournalRecord.Frame } <= n) shouldBe true
        }

        recover(SimulationController(seed = 1), journal, ref).membership() shouldBe expected
    }
}
