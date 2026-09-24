package civictech.timetravel.journal

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.ReBaselineNotice
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.JOURNAL_FORMAT_VERSION
import civictech.cell.durability.Journal
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.RecoveryIncomplete
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import civictech.nature.ContractRegistry
import civictech.testkit.dst.JournalMutation
import civictech.testkit.dst.MutatingJournal
import civictech.timetravel.fidelity.Reason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/**
 * TTD1 F1 (computenet-wzbww.3): [JournalReader] over journals written by a real durable host —
 * never hand-built record bytes, except where a test deliberately rewrites one frame's JSON.
 */
class JournalReaderTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** What a fixture wrote: the cell's ref, and each outlet's wave state at checkpoint time. */
    private class Written(val ref: CellRef, val wavesAtCheckpoint: Map<String, Pair<UUID, Long>>)

    /**
     * One `SetCell<String>` journaled to [journal]; three adds; optionally a checkpoint followed
     * by one more add, so the journal reads `[checkpoint, outletWave…, frame]`.
     */
    private fun writeJournal(journal: Journal, checkpoint: Boolean = false, seed: Long = 7): Written {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(seed, seed))
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        repeat(3) { api.add("e$it") }
        controller.runToIdle()
        var waves = emptyMap<String, Pair<UUID, Long>>()
        if (checkpoint) {
            host.checkpoint(journal)
            val ports = PortRegistry.of(cell)
            waves = ports.names().mapNotNull { name ->
                (ports[name] as? FanOutlet<*>)?.waveState()?.let { name to (it.sourceId to it.highWater) }
            }.toMap()
            api.add("e3")
            controller.runToIdle()
        }
        return Written(ref, waves)
    }

    private fun read(journal: Journal): List<JournalRecord> =
        JournalReader.open(JournalSource.InMemory(journal, "j")).records.toList()

    /** The first frame's raw WireCodec payload: the journal record minus its type byte. */
    private fun firstFramePayload(journal: Journal): ByteArray =
        journal.replay().first().let { it.copyOfRange(1, it.size) }

    private fun rewriteFrame(payload: ByteArray, edit: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): ByteArray {
        val obj = Json.parseToJsonElement(payload.decodeToString()).jsonObject.toMutableMap()
        edit(obj)
        // the frame record's type byte, prepended exactly as the host's journal writer does
        return byteArrayOf(1) + JsonObject(obj).toString().encodeToByteArray()
    }

    @Test
    fun `TTD1-01 reader indices equal RecoveryIncomplete recordIndex`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal)
        val k = 1
        val corrupting = MutatingJournal(journal, JournalMutation.CorruptAt(k))

        val controller = SimulationController(seed = 8)
        val host = ManagedHost(scheduler = controller.scheduler())
        host.managementInlet.call.spawn(SetCell<String>(written.ref))
        controller.runToIdle()
        shouldThrow<RecoveryIncomplete> { host.recoverFrom(corrupting) }.recordIndex shouldBe k

        val records = read(corrupting)
        (records.size > k) shouldBe true
        records[k] shouldBe UnknownRecord(index = k, journalId = "j", typeByte = 99)
        records.forEachIndexed { position, record -> record.index shouldBe position }
    }

    @Test
    fun `TTD1-02 every record of a checkpointed journal is classified through the kernel decode`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal, checkpoint = true)
        val records = read(journal)

        val checkpoint = records[0].shouldBeInstanceOf<CheckpointRecord>()
        checkpoint.restoredCells shouldBe setOf(written.ref)
        val waves = records.drop(1).takeWhile { it is OutletWaveRecord }
        waves.shouldNotBeEmpty()
        waves.forEach { (it as OutletWaveRecord).cellRef shouldBe written.ref }
        val rest = records.drop(1 + waves.size)
        rest.shouldNotBeEmpty()
        rest.forEach { it.shouldBeInstanceOf<FrameRecord>() }
        records.forEach { withClue(it) { it.reasons.shouldBeEmpty() } }
    }

    @Test
    fun `TTD1-03 TTD1-04 a frame exposes structural fields and, with descriptors, the hydrated call`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal)
        val frame = read(journal).first().shouldBeInstanceOf<FrameRecord>()
        val codec = WireCodec.decodeFrame(firstFramePayload(journal)).frame

        frame.cellRef shouldBe written.ref
        frame.portName shouldBe "inlet"
        frame.type shouldBe HostedPortInvocation.Type.PORT_API
        frame.context shouldBe codec.context
        frame.wireVersion shouldBe null
        frame.reasons.shouldBeEmpty()
        frame.hydrationFailure shouldBe null
        frame.hydrated!!.methodName shouldBe "add"
        frame.hydrated!!.args shouldBe listOf("e0")
    }

    @Test
    fun `TTD1-05 a frame with no descriptor keeps its structural fields, is marked NO_DESCRIPTOR, and reading continues`() {
        val source = InMemoryJournal()
        writeJournal(source)
        val real = read(source).first() as FrameRecord
        ContractRegistry.method(-1L, real.methodId) shouldBe null // the choice is checked, not assumed

        val journal = InMemoryJournal()
        journal.append(rewriteFrame(firstFramePayload(source)) { it["contractId"] = JsonPrimitive(-1L) })
        journal.append(source.replay()[1])

        val records = read(journal)
        records.size shouldBe 2
        val foreign = records[0].shouldBeInstanceOf<FrameRecord>()
        foreign.contractId shouldBe -1L
        foreign.methodId shouldBe real.methodId
        foreign.cellRef shouldBe real.cellRef
        foreign.portName shouldBe real.portName
        foreign.type shouldBe real.type
        foreign.context shouldBe real.context
        foreign.hydrated shouldBe null
        foreign.hydrationFailure shouldNotBe null
        foreign.reasons shouldBe setOf(Reason.NO_DESCRIPTOR)

        val next = records[1].shouldBeInstanceOf<FrameRecord>()
        next.hydrated!!.args shouldBe listOf("e1")
        next.reasons.shouldBeEmpty()
    }

    @Test
    fun `TTD1-06 an explicit foreign wire version is reported and never hydrated, an absent one is no mismatch`() {
        val source = InMemoryJournal()
        writeJournal(source)
        val journal = InMemoryJournal()
        journal.append(rewriteFrame(firstFramePayload(source)) { it["version"] = JsonPrimitive(99) })
        journal.append(source.replay()[1])

        val records = read(journal)
        val foreign = records[0].shouldBeInstanceOf<FrameRecord>()
        foreign.wireVersion shouldBe 99
        foreign.expectedWireVersion shouldBe WireCodec.VERSION
        foreign.hydrated shouldBe null
        foreign.hydrationFailure shouldBe null // decodeFrame was not called
        foreign.reasons shouldBe setOf(Reason.WIRE_VERSION_MISMATCH)

        val untouched = records[1].shouldBeInstanceOf<FrameRecord>()
        untouched.wireVersion shouldBe null
        untouched.expectedWireVersion shouldBe WireCodec.VERSION
        untouched.reasons.shouldBeEmpty()
        untouched.hydrated shouldNotBe null
    }

    @Test
    fun `D4 the structural tuple of every hydrated frame equals the codec's`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal, checkpoint = true)
        // Proxy calls from outside the graph carry no context, so the fixture's own frames would
        // compare only nulls. Add one WireCodec-encoded frame whose context exercises every
        // field — the UUID-keyed map and set included, where the Json flags matter.
        val source = UUID(9, 9)
        val context = MessageContext(
            timestamp = Timestamp(source, 5),
            sourcePort = PortRef.generate(written.ref),
            reBaseline = ReBaselineNotice(setOf(UUID(1, 2), UUID(3, 4)), supersede = true),
            baseline = TagFrontier(mapOf(source to 4L, UUID(5, 6) to 1L)),
            hop = 2,
        )
        val invocation = HostedPortInvocation(
            written.ref, "inlet", HostedPortInvocation.Type.PORT_API,
            Invocation.of(SetOps::class.java.getMethod("add", Any::class.java), arrayOf("x"), context),
        )
        journal.append(byteArrayOf(1) + WireCodec.encode(invocation))
        val raw = journal.replay()
        val frames = read(journal).filterIsInstance<FrameRecord>()
        frames.shouldNotBeEmpty()
        frames.forEach { frame ->
            frame.hydrated shouldNotBe null
            val bytes = raw[frame.index]
            val codec = WireCodec.decodeFrame(bytes.copyOfRange(1, bytes.size)).frame
            val structural = listOf(frame.cellRef, frame.portName, frame.type, frame.contractId, frame.methodId, frame.context)
            structural shouldBe listOf(codec.cellRef, codec.portName, codec.type, codec.contractId, codec.methodId, codec.context)
        }
        // non-vacuity: the context comparison compared a fully populated context
        frames.last().context shouldBe context
    }

    @Test
    fun `TTD1-07 a checkpoint exposes restored cells and its processed-frontier`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal, checkpoint = true)
        val checkpoint = read(journal).filterIsInstance<CheckpointRecord>().single()
        checkpoint.restoredCells shouldBe setOf(written.ref)
        // a SetCell-only graph has no Effectful inlet, so nothing advances a processed-frontier
        checkpoint.frontier shouldBe emptyMap()
    }

    @Test
    fun `TTD1-09 outlet-wave records carry the outlet's wave state at checkpoint time`() {
        val journal = InMemoryJournal()
        val written = writeJournal(journal, checkpoint = true)
        val waves = read(journal).filterIsInstance<OutletWaveRecord>()
        waves.map { it.portName }.toSet() shouldBe written.wavesAtCheckpoint.keys
        waves.forEach { wave ->
            wave.cellRef shouldBe written.ref
            (wave.sourceId to wave.highWater) shouldBe written.wavesAtCheckpoint.getValue(wave.portName)
        }
        // non-vacuity: at least one outlet had emitted by the checkpoint
        waves.any { it.highWater > 0 } shouldBe true
    }

    /** An `Effectful` sink: the only kind of cell whose deliveries journal frontier / baseline records. */
    class EffectSink(override val ref: CellRef) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {}
            })
        }
    }

    interface EffectSinkProxy {
        val inlet: Use<Consumer<Int>>
    }

    @Test
    fun `TTD1-09 frontier and baseline-discharge records carry the cell, the inlet and the exact position`() {
        val journal = InMemoryJournal()
        val controller = SimulationController(seed = 9)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(9, 9))
        host.managementInlet.call.spawn(EffectSink(ref))
        controller.runToIdle()
        val sink = (HostedCellProxy.create(ref, host, EffectSinkProxy::class.java) as EffectSinkProxy).inlet.call
        val lane = UUID(4, 2)
        // a catch-up baseline at position 5 (journals a baseline discharge), then a live frame at
        // position 1 on the same lane (advances the processed-frontier) — the shapes
        // EffectfulBaselineGuardTest drives
        val baseline = MessageContext(Timestamp(lane, 5), PortRef.generate(), baseline = TagFrontier(mapOf(UUID(7, 7) to 4L)))
        CurrentContext.with(baseline) { sink.provide(100) }
        controller.runToIdle()
        CurrentContext.with(MessageContext(Timestamp(lane, 1), PortRef.generate())) { sink.provide(1) }
        controller.runToIdle()

        val records = read(journal)
        records.filterIsInstance<BaselineDischargeRecord>().map { Triple(it.cellRef, it.portName, it.timestamp) } shouldBe
            listOf(Triple(ref, "inlet", Timestamp(lane, 5)))
        records.filterIsInstance<FrontierRecord>().map { Triple(it.cellRef, it.portName, it.timestamp) } shouldBe
            listOf(Triple(ref, "inlet", Timestamp(lane, 1)))
        records.forEach { withClue(it) { it.reasons.shouldBeEmpty() } }
    }

    @Test
    fun `TTD1-13 a directory reads each journal file independently, sorted, attributed by path`(@TempDir dir: File) {
        val b = writeJournal(FileJournal(File(dir, "b.bin")), seed = 2)
        val a = writeJournal(FileJournal(File(dir, "a.bin")), seed = 1)

        val reading = JournalReader.open(JournalSource.Directory(dir))
        reading.journals.size shouldBe 2
        reading.journals[0].journalId shouldEndWith "a.bin"
        reading.journals[1].journalId shouldEndWith "b.bin"
        reading.reasons.shouldBeEmpty()

        val records = reading.records.toList()
        records.size shouldBe reading.journals.sumOf { it.recordCount }
        records.filterIsInstance<FrameRecord>().forEach { frame ->
            val expected = if (frame.cellRef == a.ref) "a.bin" else "b.bin"
            frame.journalId shouldEndWith expected
        }
        records.filter { it.journalId.endsWith("a.bin") }.map { it.cellRefOrNull() }.toSet() shouldBe setOf(a.ref)
        records.filter { it.journalId.endsWith("b.bin") }.map { it.cellRefOrNull() }.toSet() shouldBe setOf(b.ref)
        records.filter { it.journalId.endsWith("b.bin") }.map { it.index } shouldContainExactly
            (0 until reading.journals[1].recordCount).toList()
        // re-iterable
        reading.records.count() shouldBe records.size
    }

    private fun JournalRecord.cellRefOrNull(): CellRef? = (this as? FrameRecord)?.cellRef

    @Test
    fun `a foreign format version is reported on the summary, the run and every record, and records are still read`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "j.bin")
        writeJournal(FileJournal(file, formatVersion = 7))

        val reading = JournalReader.open(JournalSource.File(file))
        val summary = reading.journals.single()
        summary.declaredFormatVersion shouldBe 7
        summary.expectedFormatVersion shouldBe JOURNAL_FORMAT_VERSION
        (Reason.FORMAT_VERSION_MISMATCH in summary.reasons) shouldBe true
        (Reason.FORMAT_VERSION_MISMATCH in reading.reasons) shouldBe true
        val records = reading.records.toList()
        records.shouldNotBeEmpty()
        records.forEach {
            (Reason.FORMAT_VERSION_MISMATCH in it.reasons) shouldBe true
            it.shouldBeInstanceOf<FrameRecord>().hydrated shouldNotBe null
        }
    }

    @Test
    fun `an unreadable source is refused before anything is created`(@TempDir dir: File) {
        val absent = File(dir, "absent")
        val refusal = shouldThrow<JournalUnreadable> {
            JournalReader.open(JournalSource.File(File(absent, "sub/j.bin")))
        }
        refusal.path shouldEndWith "j.bin"
        absent.exists() shouldBe false

        shouldThrow<JournalUnreadable> { JournalReader.open(JournalSource.Directory(File(dir, "nodir"))) }
        File(dir, "nodir").exists() shouldBe false
    }
}
