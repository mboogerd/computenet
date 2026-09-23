package civictech.cell.durability

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.RecoveryIncomplete
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.Propagate
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [BatchedFileJournal] — the `BATCHED` durability class (feature computenet-t6b.2,
 * task computenet-t6b.2.2): `[KBLK-05]`, `[KBLK-09]`..`[KBLK-13]`, and the
 * `[KBLK-26]` honesty limit.
 *
 * **What these tests do NOT prove** (`[KBLK-26]`): that `syncEvery - 1` is the
 * physical bound on what a crash loses. Every "crash" here is a file truncated
 * or abandoned inside a live JVM, which leaves the OS page cache intact, so the
 * truncation sweep proves prefix-consistency of the *reader* — whatever reached
 * the file, a truncation of it replays as a prefix — and nothing about what a
 * power loss would leave. That needs power loss or a fault-injecting filesystem,
 * which no test here has.
 */
class BatchedFileJournalTest {

    private fun tempFile(prefix: String, name: String = "host.journal"): File =
        createTempDirectory(prefix).toFile().resolve(name)

    /** Records of varying lengths, including an empty one, so frames straddle every alignment. */
    private fun records(n: Int): List<ByteArray> =
        (0 until n).map { i -> ByteArray((i * 7) % 23) { j -> (i * 31 + j).toByte() } }

    private fun List<ByteArray>.hex(): List<String> = map { r -> r.joinToString("") { "%02x".format(it) } }

    /** [actual] is a prefix of [appended]: in order, no gap, no reorder, nothing foreign. */
    private fun assertPrefixOf(actual: List<ByteArray>, appended: List<ByteArray>, where: String) {
        assertTrue(actual.size <= appended.size, "$where: replayed ${actual.size} records, only ${appended.size} appended")
        actual.hex() shouldBe appended.take(actual.size).hex()
    }

    // ---------------------------------------------------------------- [KBLK-05]

    @Test
    fun `the bound is required, validated, and readable on a fresh instance`() {
        val file = tempFile("bfj-bound")
        shouldThrow<IllegalArgumentException> { BatchedFileJournal(file, 0) }
        shouldThrow<IllegalArgumentException> { BatchedFileJournal(file, -3) }

        val journal = BatchedFileJournal(file, syncEvery = 5)
        journal.syncEvery shouldBe 5
        journal.durability shouldBe DurabilityClass.BATCHED
        // declared before any append or replay touched the file
        file.exists() shouldBe false

        // syncEvery == 1 is legal (the FileJournal-equivalent control) and still declares BATCHED
        BatchedFileJournal(file, syncEvery = 1).durability shouldBe DurabilityClass.BATCHED
    }

    // ------------------------------------------------------ sync accounting

    @Test
    fun `one fsync per syncEvery appends, none per append`() {
        val journal = BatchedFileJournal(tempFile("bfj-sync"), syncEvery = 3)
        val recs = records(10)

        recs.take(2).forEach(journal::append)
        journal.recordSyncs shouldBe 0L // two appends, below the bound: no fsync yet

        journal.append(recs[2])
        journal.recordSyncs shouldBe 1L // the third reaches the bound

        recs.subList(3, 9).forEach(journal::append)
        journal.recordSyncs shouldBe 3L // 9 appends -> exactly 3 fsyncs

        journal.append(recs[9])
        journal.recordSyncs shouldBe 3L // one pending

        journal.sync()
        journal.recordSyncs shouldBe 4L // explicit sync forces the pending one
        journal.sync()
        journal.recordSyncs shouldBe 4L // nothing pending: no fsync

        journal.append(recs[0])
        journal.replay().size shouldBe 11
        journal.recordSyncs shouldBe 5L // replay syncs this instance's pending tail first

        journal.append(recs[1])
        journal.reset(recs.take(2))
        journal.sync()
        journal.recordSyncs shouldBe 5L // reset zeroed the pending count
    }

    // ---------------------------------------------- BS-10, journal level

    /**
     * `[KBLK-10]`/`[KBLK-11]`: the same 25 records through [FileJournal] and through
     * [BatchedFileJournal] produce byte-identical files, and every (reader x writer)
     * pairing replays the identical sequence, in append order.
     */
    @Test
    fun `BS-10 - byte-identical logs and all four cross-replays agree`() {
        val recs = records(25)
        val syncFile = tempFile("bfj-bs10-sync")
        val batchFile = tempFile("bfj-bs10-batch")

        val sync = FileJournal(syncFile)
        val batched = BatchedFileJournal(batchFile, syncEvery = 7)
        recs.forEach { sync.append(it); batched.append(it) }
        batched.sync()

        Files.readAllBytes(batchFile.toPath()).toList() shouldBe Files.readAllBytes(syncFile.toPath()).toList()

        val expected = recs.hex()
        FileJournal(syncFile).replay().hex() shouldBe expected
        FileJournal(batchFile).replay().hex() shouldBe expected
        BatchedFileJournal(syncFile, syncEvery = 3).replay().hex() shouldBe expected
        BatchedFileJournal(batchFile, syncEvery = 3).replay().hex() shouldBe expected

        // reset is the same encoding too
        sync.reset(recs.take(4))
        batched.reset(recs.take(4))
        Files.readAllBytes(batchFile.toPath()).toList() shouldBe Files.readAllBytes(syncFile.toPath()).toList()
    }

    // ------------------------------------------------ BS-10, host level

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun ops(host: ManagedHost, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun drive(host: ManagedHost, controller: SimulationController, ref: CellRef): SetCell<String> {
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        val o = ops(host, ref)
        listOf("apple", "banana", "cherry", "date", "elder").forEach { o.add(it) }
        controller.runToIdle()
        o.remove("banana")
        o.add("fig")
        o.remove("apple")
        o.add("grape")
        controller.runToIdle()
        return cell
    }

    private class Recovered(val cell: SetCell<String>, val deadLetters: List<DeadLetter>)

    /** A fresh host recovering [journal] into a fresh `SetCell` at [ref]. */
    private fun recover(journal: Journal, ref: CellRef, seed: Long = 7): Recovered {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(object : Propagate<DeadLetter> {
                override fun propagate(value: DeadLetter) { letters += value }
            }, PortRef.generate()),
        )
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        host.recoverFrom(journal) // throws RecoveryIncomplete on any failing record
        controller.runToIdle()
        return Recovered(cell, letters)
    }

    /**
     * `[KBLK-09]`: two hosts apply the same op sequence, one on [FileJournal], one on
     * [BatchedFileJournal]; each fresh host then recovers from **the other** log through
     * the unchanged `recoverFrom`. Both recovered states equal each other and the live ones,
     * with no `RecoveryIncomplete` and no dead letter.
     */
    @Test
    fun `BS-10 - a host recovers the same state from either log, cross-read by the other class`() {
        val ref = CellRef(UUID.randomUUID())
        val syncFile = tempFile("bfj-host-sync")
        val batchFile = tempFile("bfj-host-batch")

        val c1 = SimulationController(seed = 42)
        val live1 = drive(ManagedHost(scheduler = c1.scheduler(), journal = FileJournal(syncFile)), c1, ref)
        val c2 = SimulationController(seed = 42)
        val batched = BatchedFileJournal(batchFile, syncEvery = 4)
        val live2 = drive(ManagedHost(scheduler = c2.scheduler(), journal = batched), c2, ref)
        batched.sync() // clean close

        val expected = setOf("cherry", "date", "elder", "fig", "grape")
        live1.membership() shouldBe expected
        live2.membership() shouldBe expected

        // cross-read: the batched host's log through FileJournal, and vice versa
        val fromBatched = recover(FileJournal(batchFile), ref)
        val fromSync = recover(BatchedFileJournal(syncFile, syncEvery = 4), ref)

        fromBatched.cell.membership() shouldBe expected
        fromSync.cell.membership() shouldBe expected
        fromBatched.deadLetters shouldBe emptyList()
        fromSync.deadLetters shouldBe emptyList()

        // and the two logs hold the same number of records, replayed the same way by either reader
        FileJournal(batchFile).replay().size shouldBe FileJournal(syncFile).replay().size
    }

    // ---------------------------------------------------------------- BS-11

    /**
     * `[KBLK-12]`: truncate a batched journal's file at EVERY byte offset and replay it
     * through both readers — each result is a prefix of the appended records (possibly
     * empty), never gapped or reordered, and inside the header it is empty.
     *
     * **This proves reader prefix-consistency only, not the physical loss bound**
     * (`[KBLK-26]`): the truncation is synthesized from a fully written file, so it says
     * what a reader makes of any tail loss, not how much tail a real crash loses.
     */
    @Test
    fun `BS-11 - truncation at every byte offset replays a prefix through both readers`() {
        val recs = records(12)
        val source = tempFile("bfj-bs11")
        val writer = BatchedFileJournal(source, syncEvery = 5)
        recs.forEach(writer::append)
        writer.sync()
        val bytes = Files.readAllBytes(source.toPath())
        val headerBytes = 8

        val scratch = createTempDirectory("bfj-bs11-cut").toFile()
        var strictNonEmpty = 0
        for (offset in 0..bytes.size) {
            val cut = scratch.resolve("cut-$offset.journal")
            cut.writeBytes(bytes.copyOf(offset))
            val viaSync = FileJournal(cut).replay()
            val viaBatched = BatchedFileJournal(cut, syncEvery = 5).replay()
            assertPrefixOf(viaSync, recs, "FileJournal @ $offset")
            assertPrefixOf(viaBatched, recs, "BatchedFileJournal @ $offset")
            viaBatched.hex() shouldBe viaSync.hex()
            if (offset <= headerBytes) viaSync shouldBe emptyList()
            if (offset == bytes.size) viaSync.size shouldBe recs.size
            if (viaSync.isNotEmpty() && viaSync.size < recs.size) strictNonEmpty++
            cut.delete()
        }
        // not vacuous: most offsets land strictly inside the record stream
        assertTrue(strictNonEmpty > 0, "no truncation offset yielded a strict non-empty prefix")
    }

    /**
     * `[KBLK-12]`, the host half: recovering a truncated batched log through `recoverFrom`
     * either succeeds with exactly the state the surviving prefix encodes, or raises the
     * existing `RecoveryIncomplete` — never a silently-shortened success that differs from
     * the prefix. Swept at every record boundary and one byte short of each, which covers
     * both the whole-record and torn-record cut for every record.
     */
    @Test
    fun `BS-11 - recoverFrom on a truncated batched log recovers the prefix state or raises RecoveryIncomplete`() {
        val ref = CellRef(UUID.randomUUID())
        val source = tempFile("bfj-bs11-host")
        val controller = SimulationController(seed = 3)
        val batched = BatchedFileJournal(source, syncEvery = 4)
        drive(ManagedHost(scheduler = controller.scheduler(), journal = batched), controller, ref)
        batched.sync()

        val full = FileJournal(source).replay()
        val bytes = Files.readAllBytes(source.toPath())
        // record boundaries: header, then each 4-byte length + payload
        val boundaries = full.runningFold(8) { at, r -> at + Int.SIZE_BYTES + r.size }
        boundaries.last() shouldBe bytes.size
        val offsets = (boundaries + boundaries.drop(1).map { it - 1 }).sorted()

        val scratch = createTempDirectory("bfj-bs11-host-cut").toFile()
        var recoveredNonEmpty = 0
        for (offset in offsets) {
            val cut = scratch.resolve("cut-$offset.journal")
            cut.writeBytes(bytes.copyOf(offset))
            val prefix = FileJournal(cut).replay()
            assertPrefixOf(prefix, full, "host cut @ $offset")

            // the reference: the same prefix recovered from memory
            val reference = InMemoryJournal().apply { prefix.forEach(::append) }
            val expected = try {
                recover(reference, ref).cell.membership()
            } catch (_: RecoveryIncomplete) {
                null
            }
            try {
                val got = recover(BatchedFileJournal(cut, syncEvery = 4), ref)
                got.cell.membership() shouldBe expected
                got.deadLetters shouldBe emptyList()
                if (got.cell.membership().isNotEmpty()) recoveredNonEmpty++
            } catch (e: RecoveryIncomplete) {
                expected shouldBe null // failed exactly where the in-memory prefix fails too
                assertTrue(e.recordIndex < prefix.size)
            }
        }
        assertTrue(recoveredNonEmpty > 0, "no truncated log recovered any state — the sweep would be vacuous")
    }

    // ---------------------------------------------------------------- BS-12

    /**
     * `[KBLK-13]`: one host, cell A on a [BatchedFileJournal], cell B on a [FileJournal].
     * Recovering the batched journal alone restores A and re-delivers nothing to B
     * (the `MixedDurabilityTest` shape).
     */
    @Test
    fun `BS-12 - replaying the batched journal re-delivers nothing to a cell on another journal`() {
        val controller = SimulationController(seed = 1)
        val refA = CellRef(UUID.randomUUID())
        val refB = CellRef(UUID.randomUUID())
        val batched = BatchedFileJournal(tempFile("bfj-bs12-a"), syncEvery = 3)
        val sync = FileJournal(tempFile("bfj-bs12-b"))
        val selector: (CellRef) -> Journal? = { if (it == refA) batched else sync }

        var host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        host.managementInlet.call.spawn(SetCell<String>(refA))
        host.managementInlet.call.spawn(SetCell<String>(refB))
        controller.runToIdle()
        ops(host, refA).add("apple")
        ops(host, refA).add("banana")
        ops(host, refB).add("fig")
        ops(host, refB).add("grape")
        controller.runToIdle()

        // CRASH: only the journals survive
        host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
        val recoveredA = SetCell<String>(refA)
        val recoveredB = SetCell<String>(refB)
        host.managementInlet.call.spawn(recoveredA)
        host.managementInlet.call.spawn(recoveredB)
        controller.runToIdle()
        host.recoverFrom(batched)
        controller.runToIdle()

        recoveredA.membership() shouldBe setOf("apple", "banana")
        recoveredB.membership() shouldBe emptySet()

        // control: B's own journal does hold B's traffic, so B's emptiness above is scoping
        host.recoverFrom(sync)
        controller.runToIdle()
        recoveredB.membership() shouldBe setOf("fig", "grape")
    }
}
