package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.SuspendingCell
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.control.AttentionPolicy
import civictech.cell.control.AttentionSupport
import civictech.cell.control.Progress
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.CoroutineScheduler
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.IntakeBound
import civictech.cell.host.IntakeState
import civictech.cell.host.JournalRecords
import civictech.cell.host.ManagedHost
import civictech.cell.host.SaturationPolicy
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.link.Link
import civictech.cell.port.Admit
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.EdgeOpen
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.ArrayList
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    private val consumerString =
        @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<String>>)

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface IntInletProxy {
        val inlet: Use<Consumer<Int>>
    }

    interface StringInletProxy {
        val inlet: Use<Consumer<String>>
    }

    interface SuspendingInlet {
        suspend fun trigger()
    }

    private class SupervisedFoldCell(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val received = mutableListOf<Int>()

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input < 0) throw IllegalStateException("poison: $input")
                    received += input
                }
            })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            received.clear()
            received += state as List<Int>
        }
    }

    private class ColdFoldCell(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())
        val received = mutableListOf<String>()

        fun activate() {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            received.clear()
            received += state as List<String>
        }
    }

    private class FrontierFoldCell(
        override val ref: CellRef,
        active: Boolean,
    ) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())
        val received = mutableListOf<String>()

        init {
            inlet.install(WaveFrontier(GlitchFreeCell.WaveMode.WAIT))
            if (active) activate()
        }

        fun activate() {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }

        override fun snapshot(): Serializable = ArrayList(received)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            received.clear()
            received += state as List<String>
        }
    }

    private class SuspendingFoldCell(
        override val ref: CellRef,
        private val entered: CountDownLatch,
        private val release: CompletableDeferred<Unit>,
    ) : Cell, Stateful, SuspendingCell {
        val inlet = registerPort("inlet", FanInlet(SuspendingInlet::class.java))
        var applied = false

        init {
            inlet.serve(object : SuspendingInlet {
                override suspend fun trigger() {
                    entered.countDown()
                    release.await()
                    applied = true
                }
            })
        }

        override fun snapshot(): Serializable = applied

        override fun restore(state: Serializable) {
            applied = state as Boolean
        }
    }

    private fun ops(host: ManagedHost, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun records(journal: Journal): List<DecodedJournalRecord> = journal.replay().map(JournalRecords::decode)

    private fun fakeLink(from: PortRef, to: PortRef): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from: PortRef = from
        override val to: PortRef = to
        override fun unlink() {}
    }

    private fun openFrontierEdges(cell: FrontierFoldCell): Pair<Link, Link> {
        val first = fakeLink(PortRef.generate(), cell.inlet.ref)
        val second = fakeLink(PortRef.generate(), cell.inlet.ref)
        ProtocolSupport.of(cell.inlet).deliver(Protocols.TopologyOrder, first, EdgeOpen)
        ProtocolSupport.of(cell.inlet).deliver(Protocols.TopologyOrder, second, EdgeOpen)
        return first to second
    }

    private fun openFrontierEdge(inlet: FanInlet<*>): Link =
        fakeLink(PortRef.generate(), inlet.ref).also { link ->
            ProtocolSupport.of(inlet).deliver(Protocols.TopologyOrder, link, EdgeOpen)
        }

    private fun frontierInvocation(
        sourcePort: PortRef,
        sourceId: UUID,
        counter: Long,
        value: String,
    ): Invocation = Invocation.of(
        consumerString.getMethod("provide", Any::class.java),
        arrayOf(value),
        MessageContext(Timestamp(sourceId, counter), sourcePort),
    )

    private fun checkpointAcceptanceCount(inlet: FanInlet<*>): Int {
        val field = FanInlet::class.java.getDeclaredField("checkpointOrder").apply { isAccessible = true }
        return (field.get(inlet) as Map<*, *>).size
    }

    private fun frontierFrame(
        ref: CellRef,
        sourcePort: PortRef,
        sourceId: UUID,
        counter: Long,
        value: String,
    ) = HostedPortInvocation(
        ref,
        "inlet",
        HostedPortInvocation.Type.PORT_API,
        Invocation.of(
            consumerString.getMethod("provide", Any::class.java),
            arrayOf(value),
            MessageContext(Timestamp(sourceId, counter), sourcePort),
        ),
    )

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

    @Test
    fun `(e) attention-parked frames are carried like queued ones`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            journal = journal,
            attention = AttentionPolicy(suspendAfter = 3),
        )
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        AttentionSupport.of(cell).attend(0f) // band NONE: past the window, traffic parks

        val elements = (1..10).map { "p$it" }
        elements.forEach { ops(host, ref).add(it) }
        controller.runToIdle()
        withClue("the window admitted 3 deliveries; the other 7 are attention-parked, not queued") {
            cell.membership().size shouldBe 3
            host.stagedWorkTotal() shouldBe 0
        }

        host.checkpoint(journal)
        records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 7

        recover(controller, journal, ref).membership() shouldBe elements.toSet()
    }

    @Test
    fun `(f) frames staged for two cells are carried in host-sequence order, not per cell`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val a = CellRef(UUID.randomUUID())
        val b = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        host.managementInlet.call.spawn(SetCell<String>(a))
        host.managementInlet.call.spawn(SetCell<String>(b))
        controller.runToIdle()

        val accepted = listOf(a to "a1", b to "b1", a to "a2", b to "b2")
        accepted.forEach { (ref, element) -> ops(host, ref).add(element) }
        host.checkpoint(journal)

        records(journal).filterIsInstance<DecodedJournalRecord.Frame>()
            .map { civictech.cell.wire.WireCodec.decode(it.payload).invocation.args.single() } shouldBe
            accepted.map { it.second }
    }

    /** Holds the first [reset] at a gate, so a test can act while a compaction is in progress. */
    private class GatedResetJournal(val inner: InMemoryJournal = InMemoryJournal()) : Journal by inner {
        val resetEntered = java.util.concurrent.CountDownLatch(1)
        val releaseReset = java.util.concurrent.CountDownLatch(1)

        override fun reset(records: List<ByteArray>) {
            if (resetEntered.count > 0) {
                resetEntered.countDown()
                check(releaseReset.await(30, TimeUnit.SECONDS)) { "reset gate never released" }
            }
            inner.reset(records)
        }
    }

    @Test
    fun `(g) a frame accepted while the compaction is in progress is not truncated by it`() {
        val journal = GatedResetJournal()
        val ref = CellRef(UUID.randomUUID())
        val scheduler = VirtualThreadScheduler("xy7w4.2-RD-atomic")
        val host = ManagedHost(scheduler = scheduler, journal = journal)
        host.managementInlet.call.spawn(SetCell<String>(ref))
        try {
            ops(host, ref).add("before")
            val checkpointer = CompletableFuture.runAsync { host.checkpoint(journal) }
            check(journal.resetEntered.await(30, TimeUnit.SECONDS)) { "checkpoint never reached reset" }
            // The reset holds the intake's lock, so this add cannot be appended until the
            // reset is done — it lands after the compacted records. Were the staged-set read
            // and the reset not atomic w.r.t. the intake, the add would append now and the
            // reset below would truncate it: staged and delivered, but gone from the journal.
            val late = CompletableFuture.runAsync { ops(host, ref).add("late") }
            runCatching { late.get(500, TimeUnit.MILLISECONDS) } // give a non-atomic reset its chance
            journal.releaseReset.countDown()
            checkpointer.get(30, TimeUnit.SECONDS)
            late.get(30, TimeUnit.SECONDS)
            host.quiescence().await(30_000)
        } finally {
            journal.releaseReset.countDown()
            scheduler.shutdown()
        }

        recover(SimulationController(seed = 1), journal.inner, ref).membership() shouldBe setOf("before", "late")
    }

    @Test
    fun `(h) a checkpoint carries frames parked by supervision SUSPEND`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SupervisedFoldCell(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        host.managementInlet.call.supervise(ref, SupervisionPolicy.SUSPEND)

        val inlet = (HostedCellProxy.create(ref, host, IntInletProxy::class.java) as IntInletProxy).inlet.call
        listOf(-1, 1, 2).forEach(inlet::provide)
        controller.runToIdle()
        withClue("the poison suspended the cell and the later frames are parked, not applied") {
            host.isSuspended(ref) shouldBe true
            cell.received shouldBe emptyList()
        }

        host.checkpoint(journal)
        records(journal).filterIsInstance<DecodedJournalRecord.Frame>()
            .map { civictech.cell.wire.WireCodec.decode(it.payload).invocation.args.single() } shouldBe listOf(1, 2)

        val recoveredHost = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recovered = SupervisedFoldCell(ref)
        recoveredHost.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()
        recovered.received shouldBe listOf(1, 2)
    }

    @Test
    fun `(i) a checkpoint carries a cold inlet pre-activation tail`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = ColdFoldCell(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        val inlet = (HostedCellProxy.create(ref, host, StringInletProxy::class.java) as StringInletProxy).inlet.call
        val accepted = listOf("first", "second", "third")
        accepted.forEach(inlet::provide)
        controller.runToIdle()
        cell.received shouldBe emptyList()

        host.checkpoint(journal)
        records(journal).filterIsInstance<DecodedJournalRecord.Frame>()
            .map { civictech.cell.wire.WireCodec.decode(it.payload).invocation.args.single() } shouldBe accepted

        val recoveredHost = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recovered = ColdFoldCell(ref)
        recoveredHost.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()
        recovered.received shouldBe emptyList()
        recovered.activate()
        recovered.received shouldBe accepted
    }

    @Test
    fun `(j) a suspending handler completes before a checkpoint can run`() {
        val journal = InMemoryJournal()
        val scheduler = CoroutineScheduler("hknt0-mid-handler")
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val ref = CellRef(UUID.randomUUID())
        val durableRef = CellRef(UUID.randomUUID())
        val host = ManagedHost(
            scheduler = scheduler,
            journalFor = { if (it == durableRef) journal else null },
        )
        host.managementInlet.call.spawn(SetCell<String>(durableRef))
        val cell = SuspendingFoldCell(ref, entered, release)
        host.managementInlet.call.spawn(cell)
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                ref,
                "inlet",
                HostedPortInvocation.Type.PORT_API,
                Invocation("trigger", emptyList(), emptyList()),
            ),
        )
        check(entered.await(15, TimeUnit.SECONDS)) { "suspending handler never started" }

        val checkpointRequested = CountDownLatch(1)
        val checkpointer = CompletableFuture.runAsync {
            checkpointRequested.countDown()
            host.checkpoint(journal)
        }
        check(checkpointRequested.await(15, TimeUnit.SECONDS)) { "checkpoint caller never started" }

        try {
            shouldThrow<TimeoutException> {
                checkpointer.get(300, TimeUnit.MILLISECONDS)
            }
            cell.applied shouldBe false

            release.complete(Unit)
            checkpointer.get(15, TimeUnit.SECONDS)
            cell.applied shouldBe true
            records(journal).count { it is DecodedJournalRecord.Frame } shouldBe 0
        } finally {
            release.complete(Unit)
            runCatching { checkpointer.get(15, TimeUnit.SECONDS) }
            scheduler.shutdown()
        }
    }


    /**
     * Crash-recovers a SUSPEND-supervised cell whose parked frames were resumed and delivered
     * live, with or without a checkpoint taken while they were parked. Recovery must deliver
     * each accepted frame exactly once: a resume that re-tees parked frames leaves a second
     * WAL copy (beside the intake's, or beside the copy the checkpoint carried).
     */
    private fun resumedThenRecovered(checkpointWhileParked: Boolean): List<Int> {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = SupervisedFoldCell(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        host.managementInlet.call.supervise(ref, SupervisionPolicy.SUSPEND)
        val inlet = (HostedCellProxy.create(ref, host, IntInletProxy::class.java) as IntInletProxy).inlet.call
        listOf(-1, 1, 2).forEach(inlet::provide)
        controller.runToIdle()
        if (checkpointWhileParked) host.checkpoint(journal)
        host.managementInlet.call.resume(ref)
        controller.runToIdle()
        withClue("resume delivers the parked frames live") { cell.received shouldBe listOf(1, 2) }

        val recoveredHost = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recovered = SupervisedFoldCell(ref)
        recoveredHost.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()
        return recovered.received
    }

    @Test
    fun `(k) resumed SUSPEND-parked frames are not re-journaled, so recovery delivers them once`() {
        withClue("checkpoint taken while parked, then resume, then crash") {
            resumedThenRecovered(checkpointWhileParked = true) shouldBe listOf(1, 2)
        }
        withClue("no checkpoint: intake copy only") {
            resumedThenRecovered(checkpointWhileParked = false) shouldBe listOf(1, 2)
        }
    }

    @Test
    fun `(l) a checkpoint carries a frame buffered in an active inlet ALIGN policy`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = FrontierFoldCell(ref, active = true)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        val firstEdge = openFrontierEdges(cell).first
        val sourceId = UUID.randomUUID()

        host.enqueueHostedInvocation(frontierFrame(ref, firstEdge.from, sourceId, 1, "held-active"))
        controller.runToIdle()
        withClue("the incomplete wave is retained by ALIGN, not reflected in the cell snapshot") {
            cell.received shouldBe emptyList()
            host.stagedWorkTotal() shouldBe 0
        }

        host.checkpoint(journal)
        withClue("the compacted journal must carry the host-accepted frame held in ALIGN") {
            records(journal).filterIsInstance<DecodedJournalRecord.Frame>().map {
                civictech.cell.wire.WireCodec.decode(it.payload).invocation.args.single()
            } shouldBe listOf("held-active")
        }

        val recoveredHost = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recovered = FrontierFoldCell(ref, active = true)
        recoveredHost.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()
        recovered.received shouldBe listOf("held-active")
    }

    @Test
    fun `(m) a checkpoint carries an ALIGN frame released cold by a later progress event`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = FrontierFoldCell(ref, active = false)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        val (firstEdge, secondEdge) = openFrontierEdges(cell)
        val sourceId = UUID.randomUUID()

        val accepted = listOf("held-then-cold-1", "held-then-cold-2")
        accepted.forEachIndexed { index, value ->
            host.enqueueHostedInvocation(frontierFrame(ref, firstEdge.from, sourceId, index + 1L, value))
        }
        controller.runToIdle()
        cell.received shouldBe emptyList()

        // This completes both waves outside any hosted offer. ALIGN releases the earlier
        // frames into the still-cold ACTIVATE tail on this protocol-event stack.
        ProtocolSupport.of(cell.inlet).deliver(Protocols.Progress, secondEdge, Progress(sourceId, 2))

        host.checkpoint(journal)
        withClue("the cold tail must retain the frame's original host acceptance position") {
            records(journal).filterIsInstance<DecodedJournalRecord.Frame>().map {
                civictech.cell.wire.WireCodec.decode(it.payload).invocation.args.single()
            } shouldBe accepted
        }

        val recoveredHost = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val recovered = FrontierFoldCell(ref, active = false)
        recoveredHost.managementInlet.call.spawn(recovered)
        controller.runToIdle()
        recoveredHost.recoverFrom(journal)
        controller.runToIdle()
        recovered.received shouldBe emptyList()
        recovered.activate()
        recovered.received shouldBe accepted
    }

    @Test
    fun `(n) resetting every arm of a shared ALIGN frontier releases every checkpoint acceptance`() {
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val first = FanInlet.create<Consumer<String>>()
        val second = FanInlet.create<Consumer<String>>()
        first.install(frontier.arm())
        second.install(frontier.arm())
        val firstEdge = openFrontierEdge(first)
        val secondEdge = openFrontierEdge(second)
        openFrontierEdge(second)
        val sourceId = UUID.randomUUID()

        first.offerHosted(frontierInvocation(firstEdge.from, sourceId, 1, "first-arm"), 1)
        second.offerHosted(frontierInvocation(secondEdge.from, sourceId, 1, "second-arm"), 2)
        first.checkpointParked().size shouldBe 1
        second.checkpointParked().size shouldBe 1

        first.resetPolicies()
        second.resetPolicies()

        first.checkpointParked() shouldBe emptyList()
        second.checkpointParked() shouldBe emptyList()
        withClue("reset must release acceptance records for every arm, including the sibling cleared by the first reset") {
            checkpointAcceptanceCount(first) shouldBe 0
            checkpointAcceptanceCount(second) shouldBe 0
        }
    }

    @Test
    fun `(o) replacing the same wave edge releases the replaced checkpoint acceptance`() {
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet = FanInlet.create<Consumer<String>>()
        inlet.install(frontier)
        val edge = openFrontierEdge(inlet)
        openFrontierEdge(inlet)
        val sourceId = UUID.randomUUID()

        inlet.offerHosted(frontierInvocation(edge.from, sourceId, 1, "replaced"), 1)
        inlet.offerHosted(frontierInvocation(edge.from, sourceId, 1, "replacement"), 2)

        inlet.checkpointParked().map { (sequence, invocation) -> sequence to invocation.args.single() } shouldBe
            listOf(2L to "replacement")
        withClue("only the replacement may remain strongly referenced by checkpoint bookkeeping") {
            checkpointAcceptanceCount(inlet) shouldBe 1
        }

        inlet.resetPolicies()
        checkpointAcceptanceCount(inlet) shouldBe 0
    }

    @Test
    fun `(p) a handler failure releases checkpoint acceptances abandoned with the removed wave`() {
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet = FanInlet.create<Consumer<String>>()
        inlet.install(frontier)
        inlet.serve(object : Consumer<String> {
            override fun provide(input: String) = throw IllegalStateException("handler failed")
        })
        val firstEdge = openFrontierEdge(inlet)
        val secondEdge = openFrontierEdge(inlet)
        val thirdEdge = openFrontierEdge(inlet)
        val sourceId = UUID.randomUUID()

        inlet.offerHosted(frontierInvocation(firstEdge.from, sourceId, 1, "throws"), 1)
        inlet.offerHosted(frontierInvocation(secondEdge.from, sourceId, 1, "abandoned"), 2)
        val failure = shouldThrow<IllegalStateException> {
            inlet.offerHosted(frontierInvocation(thirdEdge.from, sourceId, 1, "trigger"), 3)
        }

        failure.message shouldBe "handler failed"
        inlet.checkpointParked() shouldBe emptyList()
        withClue("a removed wave must not leave an unserved sibling retained only by checkpoint bookkeeping") {
            checkpointAcceptanceCount(inlet) shouldBe 0
        }
    }

    @Test
    fun `(q) a throwing sibling preserves the completing offer already released into a cold tail`() {
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val cold = FanInlet.create<Consumer<String>>()
        val hot = FanInlet.create<Consumer<String>>()
        cold.install(frontier.arm())
        hot.install(frontier.arm())
        val coldEdge = openFrontierEdge(cold)
        val hotEdge = openFrontierEdge(hot)
        val sourceId = UUID.randomUUID()
        val originalFailure = IllegalStateException("sibling failed")
        hot.serve(object : Consumer<String> {
            override fun provide(input: String) = throw originalFailure
        })

        hot.offerHosted(frontierInvocation(hotEdge.from, sourceId, 1, "throws"), 1)
        val survivor = frontierInvocation(coldEdge.from, sourceId, 1, "cold-survivor")
        val failure = shouldThrow<IllegalStateException> { cold.offerHosted(survivor, 2) }

        (failure === originalFailure) shouldBe true
        withClue("successful cold prefix keeps its acceptance position when a later arm throws") {
            cold.checkpointParked() shouldBe listOf(2L to survivor)
            checkpointAcceptanceCount(cold) shouldBe 1
            checkpointAcceptanceCount(hot) shouldBe 0
        }
        cold.resetPolicies()
        hot.resetPolicies()
        cold.checkpointParked() shouldBe listOf(2L to survivor)

        val delivered = mutableListOf<String>()
        cold.serve(object : Consumer<String> {
            override fun provide(input: String) { delivered += input }
        })
        delivered shouldBe listOf("cold-survivor")
        cold.checkpointParked() shouldBe emptyList()
        checkpointAcceptanceCount(cold) shouldBe 0
    }

    @Test
    fun `(r) reoffering the same invocation to ALIGN preserves its original acceptance`() {
        val frontier = WaveFrontier(GlitchFreeCell.WaveMode.WAIT)
        val inlet = FanInlet.create<Consumer<String>>()
        inlet.install(frontier)
        val edge = openFrontierEdge(inlet)
        openFrontierEdge(inlet)
        val invocation = frontierInvocation(edge.from, UUID.randomUUID(), 1, "same-object")
        inlet.offerHosted(invocation, 7)

        frontier.offer(invocation)

        withClue("reoffering the identical object is not a discarded replacement") {
            inlet.checkpointParked() shouldBe listOf(7L to invocation)
            checkpointAcceptanceCount(inlet) shouldBe 1
        }
        inlet.resetPolicies()
        checkpointAcceptanceCount(inlet) shouldBe 0
    }

    @Test
    fun `(s) hosted checkpoint tracking preserves each policy offer context`() {
        val controller = SimulationController(seed = 1)
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val cell = FrontierFoldCell(ref, active = true)
        val policyContexts = mutableListOf<MessageContext?>()
        cell.inlet.install(Admit(admits = {
            policyContexts += CurrentContext.get()
            true
        }))
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        val edge = openFrontierEdge(cell.inlet)
        val sourceId = UUID.randomUUID()
        val frames = listOf(
            frontierFrame(ref, edge.from, sourceId, 1, "first-context"),
            frontierFrame(ref, edge.from, sourceId, 2, "second-context"),
        )
        frames.forEach(host::enqueueHostedInvocation)
        controller.runToIdle()

        withClue("the hosted policy path must run under each input's own context, like ordinary invocation delivery") {
            policyContexts shouldBe frames.map { it.invocation.context }
        }
        cell.received shouldBe listOf("first-context", "second-context")
        CurrentContext.get() shouldBe null
    }
}
