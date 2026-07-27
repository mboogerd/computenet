package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.gen.wire.Contract
import civictech.testkit.awaitUntil
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.nio.file.Files
import java.util.Collections
import java.util.UUID

/** Wire-capable (T06 §B needs the journal to actually encode/decode these frames). */
@Contract
interface LogApi {
    fun append(thread: Int, seq: Int)
}

/**
 * T06 §B: a durable host driven by a real scheduler and real concurrent
 * writer threads — the direct complement to `SimulationController`'s
 * single-threaded durability tests, pinning T04's "journal order =
 * acceptance order" fix (finding 2) and T05's "replay bypasses the
 * SATURATED gate" fix (finding 4) under genuine thread contention.
 */
class TwoWriterDurabilityTest {

    /** Order-sensitive: the live [log] is exactly the per-cell FIFO order invocations were accepted in. */
    private class LogCell(override val ref: CellRef) : Cell, Stateful {
        val log = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val inlet = registerPort("inlet", FanInlet.create<LogApi>())

        init {
            inlet.serve(object : LogApi {
                override fun append(thread: Int, seq: Int) { log += thread to seq }
            })
        }

        override fun snapshot(): Serializable = ArrayList(log)

        @Suppress("UNCHECKED_CAST")
        override fun restore(state: Serializable) {
            log.clear()
            log.addAll(state as List<Pair<Int, Int>>)
        }
    }

    private val appendMethod = LogApi::class.java.methods.single { it.name == "append" }

    private fun appendInvocation(cellRef: CellRef, thread: Int, seq: Int) = HostedPortInvocation(
        cellRef, "inlet", HostedPortInvocation.Type.PORT_API,
        // Invocation.of (not the raw constructor) so contractId/methodId are
        // populated from ContractRegistry — WireCodec.encode (the journal
        // frame path) requires them.
        Invocation.of(appendMethod, arrayOf(thread, seq)),
    )

    /**
     * T06 §B1 — direct test of "journal order == acceptance order" (T04
     * finding 2) under real concurrent writers. Verified locally that this
     * test can fail: temporarily moving the non-coalesce journal append back
     * outside `synchronized(dataLock)` (its pre-T04 position) makes the
     * recovered order diverge from the live order on this same test — not
     * committed; see the T06 report for the exact revert/restore steps.
     */
    @Test
    fun `two concurrent writer threads - recovered order equals live order`() {
        val dir = Files.createTempDirectory("t06-b1")
        val journal = FileJournal(dir.resolve("journal.log").toFile())
        val ref = CellRef(UUID.randomUUID())

        val host = ManagedHost(scheduler = VirtualThreadScheduler("t06-b1-live"), journal = journal)
        val cell = LogCell(ref)
        host.managementInlet.call.spawn(cell)

        val perThread = 300
        val writers = (0 until 2).map { t ->
            Thread {
                for (seq in 0 until perThread) {
                    host.enqueueHostedInvocation(appendInvocation(ref, t, seq))
                }
            }.apply { name = "t06-b1-writer-$t" }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(30_000) }
        writers.forEach { it.isAlive.shouldBeFalse() }

        awaitUntil("all writes delivered on the live host", timeoutMs = 30_000) {
            cell.log.size == 2 * perThread
        }
        val liveOrder = cell.log.toList()

        // "kill" the host: no checkpoint, no graceful shutdown — only the
        // journal (on disk) survives, exactly like a crash.
        val recoveredHost = ManagedHost(scheduler = VirtualThreadScheduler("t06-b1-recovered"))
        val recoveredCell = LogCell(ref)
        recoveredHost.managementInlet.call.spawn(recoveredCell)
        recoveredHost.recoverFrom(journal)

        awaitUntil("recovery fully replayed on the rebuilt host", timeoutMs = 30_000) {
            recoveredCell.log.size == liveOrder.size
        }
        recoveredCell.log.toList() shouldBe liveOrder
    }

    /**
     * T06 §B1b — the same property as [B1][`two concurrent writer threads - recovered order equals live order`],
     * over an [InMemoryJournal] instead of a [FileJournal], because **B1 as
     * written cannot fail**: `FileJournal.append` is `@Synchronized` *and*
     * fsyncs, so it serializes the two writers for the whole (millisecond-
     * scale) duration of the append. The pre-T04 race needs both threads to
     * be between "append returned" and "dataLock acquired" simultaneously —
     * a window fsync makes vanishingly small. Reverting T04's append-inside-
     * the-lock fix leaves B1 green (verified: 3/3 passes with the append
     * moved back outside `synchronized(dataLock)`), so B1 alone is a
     * regression test that cannot regress.
     *
     * [InMemoryJournal] holds its monitor only for a list append, so the
     * post-append window dominates and the interleaving is reached within a
     * few hundred invocations. This variant DOES fail on the reverted code,
     * which is the whole point of keeping it: it is the executable half of
     * "journal order == acceptance order".
     */
    @Test
    fun `B1b - concurrent writers over a non-fsyncing journal - recovered order equals live order`() {
        val journal = InMemoryJournal()
        val ref = CellRef(UUID.randomUUID())

        val host = ManagedHost(scheduler = VirtualThreadScheduler("t06-b1b-live"), journal = journal)
        val cell = LogCell(ref)
        host.managementInlet.call.spawn(cell)

        val writerCount = 4
        val perThread = 400
        val writers = (0 until writerCount).map { t ->
            Thread {
                for (seq in 0 until perThread) {
                    host.enqueueHostedInvocation(appendInvocation(ref, t, seq))
                }
            }.apply { name = "t06-b1b-writer-$t" }
        }
        writers.forEach { it.start() }
        writers.forEach { it.join(30_000) }
        writers.forEach { it.isAlive.shouldBeFalse() }

        awaitUntil("all writes delivered on the live host", timeoutMs = 30_000) {
            cell.log.size == writerCount * perThread
        }
        val liveOrder = cell.log.toList()

        val recoveredHost = ManagedHost(scheduler = VirtualThreadScheduler("t06-b1b-recovered"))
        val recoveredCell = LogCell(ref)
        recoveredHost.managementInlet.call.spawn(recoveredCell)
        recoveredHost.recoverFrom(journal)

        awaitUntil("recovery fully replayed on the rebuilt host", timeoutMs = 30_000) {
            recoveredCell.log.size == liveOrder.size
        }
        recoveredCell.log.toList() shouldBe liveOrder
    }

    /**
     * T06 §B2 — recovery-under-bound, real scheduler (the SimulationController
     * form of this is `civictech.cell.durability.RecoveryAccountingTest`, T05
     * finding 4): a journal longer than `intakeBound`'s high-water still
     * recovers fully instead of aborting mid-replay.
     */
    @Test
    fun `a journal longer than intakeBound high-water recovers fully under a real scheduler`() {
        val dir = Files.createTempDirectory("t06-b2")
        val journal = FileJournal(dir.resolve("journal.log").toFile())
        val ref = CellRef(UUID.randomUUID())
        val elements = 20

        val writeHost = ManagedHost(scheduler = VirtualThreadScheduler("t06-b2-write"), journal = journal)
        val writeCell = LogCell(ref)
        writeHost.managementInlet.call.spawn(writeCell)
        for (seq in 0 until elements) {
            writeHost.enqueueHostedInvocation(appendInvocation(ref, 0, seq))
        }
        awaitUntil("all writes journaled and delivered", timeoutMs = 30_000) { writeCell.log.size == elements }

        val recoverHost = ManagedHost(
            scheduler = VirtualThreadScheduler("t06-b2-recover"),
            intakeBound = IntakeBound(highWater = 3, lowWater = 1, policy = SaturationPolicy.Park),
        )
        val recoverCell = LogCell(ref)
        recoverHost.managementInlet.call.spawn(recoverCell)
        recoverHost.recoverFrom(journal) // pre-T05: IntakeSaturatedException aborts partway

        awaitUntil("recovery completes despite the low intakeBound", timeoutMs = 30_000) {
            recoverCell.log.size == elements
        }
        recoverCell.log.map { it.second } shouldBe (0 until elements).toList()
    }
}
