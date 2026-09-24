package civictech.timetravel

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.timetravel.cli.CliFixture
import civictech.timetravel.cli.Main
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.journal.JournalUnreadable
import civictech.timetravel.reconstruct.GraphBuild
import civictech.timetravel.reconstruct.GraphSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID

/**
 * TTD1 F1 (computenet-wzbww.4): the reader half of BS-15, `[TTD1-12]` — the reader opens files
 * read-only and never writes, truncates, renames or locks a journal, and refuses an absent path
 * without creating anything.
 */
class ReadOnlyOfflineTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    /** Every record type the `SetCell` fixture can produce: frames, a checkpoint, outlet waves. */
    private fun writeFullJournal(journal: Journal, seed: Long = 61): CellRef {
        val controller = SimulationController(seed = seed)
        val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
        val ref = CellRef(UUID(seed, seed))
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        val api = (HostedCellProxy.create(ref, host, SetInletProxy::class.java) as SetInletProxy).inlet.call
        repeat(3) { api.add("e$it") }
        controller.runToIdle()
        host.checkpoint(journal)
        api.add("e3")
        controller.runToIdle()
        return ref
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `TTD1-12 opening and fully, twice, reading a file and a directory changes nothing on disk`(
        @TempDir dir: File,
    ) {
        val journalsDir = File(dir, "journals")
        journalsDir.mkdirs()
        val file = File(journalsDir, "j.bin")
        writeFullJournal(FileJournal(file))

        val sizeBefore = file.length()
        val mtimeBefore = file.lastModified()
        val hashBefore = sha256(file)
        val listingBefore = journalsDir.listFiles()!!.map { it.name }.sorted()

        val fileReading = JournalReader.open(JournalSource.File(file))
        fileReading.records.toList() // hydrate
        fileReading.records.toList() // and again — re-iterable, still read-only

        val dirReading = JournalReader.open(JournalSource.Directory(journalsDir))
        dirReading.records.toList()
        dirReading.records.toList()
        dirReading.journals.map { it.journalId } // touch summaries too

        file.length() shouldBe sizeBefore
        file.lastModified() shouldBe mtimeBefore
        sha256(file) shouldBe hashBefore
        val listingAfter = journalsDir.listFiles()!!.map { it.name }.sorted()
        listingAfter shouldBe listingBefore
        listingAfter.any { it.endsWith(".tmp") || it.endsWith(".lock") }.shouldBeFalse()
    }

    @Test
    fun `TTD1-12 a non-existent file or directory path is refused without creating anything`(@TempDir dir: File) {
        val absentFile = File(dir, "absent")
        val refusal = shouldThrow<JournalUnreadable> {
            JournalReader.open(JournalSource.File(File(absentFile, "deeper/j.bin")))
        }
        refusal.path.endsWith("j.bin") shouldBe true
        absentFile.exists().shouldBeFalse()

        val absentDir = File(dir, "absent2")
        shouldThrow<JournalUnreadable> { JournalReader.open(JournalSource.Directory(absentDir)) }
        absentDir.exists().shouldBeFalse()
    }

    @Test
    fun `TTD1-12 a torn journal keeps its truncated length after being read`(@TempDir dir: File) {
        val file = File(dir, "torn.bin")
        writeFullJournal(FileJournal(file))
        val sizes = FileJournal(file).replay().map { it.size }
        val offsetOfLast = 8L + sizes.dropLast(1).sumOf { 4L + it }
        RandomAccessFile(file, "rw").use { it.setLength(offsetOfLast + 2) }
        val lengthBefore = file.length()

        JournalReader.open(JournalSource.File(file)).records.toList()

        file.length() shouldBe lengthBefore
    }

    /**
     * TTD1 F7 (computenet-3qkx1.2), `[TTD1-12]`, BS-15: `inspect`, `reconstruct` (through
     * [CapturingProvider]) and `diff` are each run through [Main.run] against the same
     * [FileJournal], and each leaves the file's size, mtime and SHA-256 untouched and adds no
     * directory entry — `Reconstructor.openSession`'s `journalFor = { DiscardingJournal }` is
     * what makes the reconstruct leg true (`Reconstructor.kt`). One test, three commands, so a
     * failure names the command via [withClue]. `reconstruct` additionally proves BS-15's "no
     * host outlives the call": [CapturingProvider] stashes a [WeakReference] to the reconstruction
     * host, and it must clear within 20 GC rounds after [Main.run] returns — `Reconstructor.close`'s
     * `scheduler.shutdown()` and the `finally` in `Reconstructor.stateAt` are what let it go.
     */
    @Test
    fun `TTD1-51 inspect, reconstruct and diff each leave the journal byte-identical and retain no host`(
        @TempDir dir: File,
    ) {
        val journalsDir = File(dir, "journals")
        journalsDir.mkdirs()
        val file = File(journalsDir, "j.bin")
        writeFullJournal(FileJournal(file))
        CapturingProvider.hostRef = null

        val sizeBefore = file.length()
        val mtimeBefore = file.lastModified()
        val hashBefore = sha256(file)
        val listingBefore = journalsDir.listFiles()!!.map { it.name }.sorted()

        fun assertJournalUnchanged(command: String, expectedListing: List<String>) = withClue(command) {
            file.length() shouldBe sizeBefore
            file.lastModified() shouldBe mtimeBefore
            sha256(file) shouldBe hashBefore
            val listingAfter = journalsDir.listFiles()!!.map { it.name }.sorted()
            listingAfter shouldBe expectedListing
            listingAfter.any { it.endsWith(".tmp") || it.endsWith(".lock") }.shouldBeFalse()
        }

        withClue("inspect") { CliFixture.run("inspect", file.path).code shouldBe Main.OK }
        assertJournalUnchanged("inspect", listingBefore)

        val recordCount = JournalReader.open(JournalSource.File(file)).records.toList().size
        withClue("reconstruct") {
            CliFixture.run(
                "reconstruct",
                file.path,
                "--at",
                (recordCount - 1).toString(),
                "--graph-provider",
                CapturingProvider::class.java.name,
            ).code shouldBe Main.OK
        }
        assertJournalUnchanged("reconstruct", listingBefore)

        var cleared = CapturingProvider.hostRef?.get() == null
        var attempt = 0
        while (!cleared && attempt < 20) {
            System.gc()
            Thread.sleep(50)
            cleared = CapturingProvider.hostRef?.get() == null
            attempt++
        }
        withClue("the reconstruction host handed to CapturingProvider must be unreachable after reconstruct returns") {
            cleared.shouldBeTrue()
        }

        val copy = File(journalsDir, "j-copy.bin")
        file.copyTo(copy)
        val listingWithCopy = journalsDir.listFiles()!!.map { it.name }.sorted()
        withClue("diff") { CliFixture.run("diff", file.path, copy.path).code shouldBe Main.OK }
        assertJournalUnchanged("diff", listingWithCopy)
    }

    /**
     * `[TTD1-51]`, BS-15: `timetravel` serves no HTTP and binds no port, so `src/main` imports
     * none of the JDK's server classes. A Gradle test task's working directory is the project
     * directory (`ModuleDependencyTest` reads `File("build.gradle.kts")` the same way), so this
     * walks `src/main` directly. Non-vacuity: at least one file is read, `Main.kt` is among them,
     * and it still declares `import kotlin.system.exitProcess` (computenet-3qkx1 D4's `main`) —
     * proof the scan is not silently walking an empty tree.
     */
    @Test
    fun `TTD1-51 timetravel src main imports no server class`() {
        val forbidden =
            Regex("""^import (java\.net\.ServerSocket|java\.nio\.channels\.ServerSocketChannel|com\.sun\.net\.httpserver)""")
        val files = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        files.shouldNotBeEmpty()
        val mainKt = files.singleOrNull { it.name == "Main.kt" }
        withClue("src/main must contain Main.kt") { (mainKt != null).shouldBeTrue() }
        withClue("Main.kt must still declare the exitProcess import (non-vacuity control)") {
            mainKt!!.readText().contains("import kotlin.system.exitProcess").shouldBeTrue()
        }

        val offenders = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "${f.path}:${index + 1}: $line" else null
            }
        }
        withClue("timetravel/src/main imports a server class") { offenders.shouldBeEmpty() }
    }
}

/**
 * BS-15's "no host retained" fixture (computenet-3qkx1.2, 3qkx1-D11): a public, top-level
 * [GraphSource] with a public no-arg constructor, spawning the same `SetCell<String>` fixture
 * [ReadOnlyOfflineTest.writeFullJournal] records through, and stashing a [WeakReference] to the
 * reconstruction host it is handed so the test can prove nothing outlives [Main.run].
 */
class CapturingProvider : GraphSource {
    override fun build(host: ManagedHost): GraphBuild {
        val cell = SetCell<String>(CellRef(UUID(61, 61)))
        host.managementInlet.call.spawn(cell)
        hostRef = WeakReference(host)
        return GraphBuild(listOf(cell))
    }

    companion object {
        @Volatile
        var hostRef: WeakReference<ManagedHost>? = null
    }
}
