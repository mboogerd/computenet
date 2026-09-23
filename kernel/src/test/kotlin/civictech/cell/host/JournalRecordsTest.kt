package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRegistry
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.WireCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-wzbww D2 (`[TTD1-02]`): [JournalRecords.decode] is the one definition of the
 * record types, and it round-trips what the REAL writers of [HostDurability] produce — not
 * hand-built bytes, so a writer/reader drift fails here.
 */
class JournalRecordsTest {

    private val cell = SetCell<String>()
    private val ref: CellRef = cell.ref
    private val journal = InMemoryJournal()
    private val durability = HostDurability(
        journalSelector = { journal },
        cellsView = { mapOf<CellRef, Cell>(ref to cell) },
        deadLetter = { error("unexpected dead letter: $it") },
        submit = { },
        awaitOnManagementBand = { block -> runBlocking { block() } },
    )
    private val src = UUID.randomUUID()

    @Test
    fun `a frame record decodes to Frame carrying the WireCodec payload`() {
        val invocation = HostedPortInvocation(
            ref, "deltaInlet", HostedPortInvocation.Type.PORT_API,
            Invocation.of(SetOps::class.java.getMethod("add", Any::class.java), arrayOf("x"), null),
        )
        val record = durability.journalFrame(invocation)

        val decoded = JournalRecords.decode(record).shouldBeInstanceOf<DecodedJournalRecord.Frame>()
        decoded.payload.contentEquals(WireCodec.encode(invocation)) shouldBe true
        WireCodec.decode(decoded.payload).let {
            it.cellRef shouldBe ref
            it.invocation.args shouldBe listOf("x")
        }
    }

    @Test
    fun `a checkpoint decodes to Checkpoint then one OutletWave per outlet`() {
        durability.advanceAndJournalFrontier(ref, "deltaInlet", Timestamp(src, 3))
        durability.checkpoint(journal)
        val records = journal.replay()

        val checkpoint = JournalRecords.decode(records[0]).shouldBeInstanceOf<DecodedJournalRecord.Checkpoint>()
        checkpoint.state.keys shouldBe setOf(ref)
        checkpoint.frontier shouldBe mapOf((ref to "deltaInlet") to mapOf(src to 3L))

        val outlets = PortRegistry.of(cell).let { reg ->
            reg.names().mapNotNull { name -> (reg[name] as? FanOutlet<*>)?.let { name to it } }.toMap()
        }
        outlets.isNotEmpty() shouldBe true
        val waves = records.drop(1).map { JournalRecords.decode(it) }
        waves.size shouldBe outlets.size
        waves.forEach { decoded ->
            val wave = decoded.shouldBeInstanceOf<DecodedJournalRecord.OutletWave>()
            wave.cellRef shouldBe ref
            val state = outlets.getValue(wave.portName).waveState()
            wave.sourceId shouldBe state.sourceId
            wave.highWater shouldBe state.highWater
        }
    }

    @Test
    fun `a frontier advance decodes to Frontier`() {
        durability.advanceAndJournalFrontier(ref, "deltaInlet", Timestamp(src, 7))

        JournalRecords.decode(journal.replay().single()) shouldBe
            DecodedJournalRecord.Frontier(ref, "deltaInlet", Timestamp(src, 7))
    }

    @Test
    fun `a baseline discharge decodes to BaselineDischarge`() {
        durability.recordAndJournalBaselineDischarge(ref, "deltaInlet", Timestamp(src, 11))

        JournalRecords.decode(journal.replay().single()) shouldBe
            DecodedJournalRecord.BaselineDischarge(ref, "deltaInlet", Timestamp(src, 11))
    }

    @Test
    fun `an unlanded leading byte decodes to Unknown`() {
        JournalRecords.decode(byteArrayOf(9, 1, 2)) shouldBe DecodedJournalRecord.Unknown(9)
    }

    @Test
    fun `an empty record is refused with IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { JournalRecords.decode(byteArrayOf()) }
    }

    @Test
    fun `a corrupt payload propagates the readObject exception unwrapped`() {
        durability.checkpoint(journal)
        val good = journal.replay()[0]
        val corrupt = byteArrayOf(good[0]) + ByteArray(good.size - 1)

        val thrown = shouldThrow<Exception> { JournalRecords.decode(corrupt) }
        thrown.shouldBeInstanceOf<java.io.IOException>()
        // Unwrapped: the JDK's own deserialization exception, not a type of this seam wrapping it.
        thrown::class.java.name.startsWith("java.io.") shouldBe true
        thrown.cause shouldBe null
    }
}
