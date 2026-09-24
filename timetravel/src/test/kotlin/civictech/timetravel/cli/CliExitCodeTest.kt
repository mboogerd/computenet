package civictech.timetravel.cli

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.durability.FileJournal
import civictech.cell.graph.GraphSpec
import civictech.cell.host.ManagedHost
import civictech.timetravel.diff.RoutedRunFixture
import civictech.timetravel.reconstruct.GraphBuild
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.GraphSpecSource
import civictech.timetravel.reconstruct.Reconstructor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.PrintStream
import java.io.RandomAccessFile
import java.io.Serializable
import java.util.UUID

/** What one [Main.run] did: its exit code and everything it printed. */
data class CliResult(val code: Int, val out: String, val err: String)

/**
 * Shared recording and invocation helpers for the CLI tests (computenet-3qkx1.1). Every journal is
 * a real `RoutedRunFixture` run recorded to a `FileJournal` — three records per scripted add.
 */
object CliFixture {

    val SCRIPT_ABC: List<Pair<Int, String>> = listOf(0 to "a", 0 to "b", 0 to "c")

    fun run(vararg args: String): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, "UTF-8").use { o ->
            PrintStream(err, true, "UTF-8").use { e -> Main.run(arrayOf(*args), o, e) }
        }
        return CliResult(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    fun record(file: File, script: List<Pair<Int, String>> = SCRIPT_ABC, seed: Long = 11): RoutedRunFixture.Recording {
        file.parentFile.mkdirs()
        return RoutedRunFixture.record(seed = seed, sourceCount = 1, script = script, journal = FileJournal(file))
    }

    fun writeSpec(spec: GraphSpec, file: File): File {
        ObjectOutputStream(file.outputStream()).use { it.writeObject(spec) }
        return file
    }

    fun readSpec(path: String): GraphSpec = ObjectInputStream(FileInputStream(path)).use { it.readObject() as GraphSpec }

    /** `[TTD1-50]`: no stack-trace frame reached stderr. */
    fun assertNoStackTrace(err: String) {
        err shouldNotContain "\tat "
        err shouldNotContain "at civictech"
    }
}

/**
 * A `Stateful` cell outside `civictech.cell.data`: the allow-list cannot vouch for it, so its
 * reconstruction is `Degraded(UNKNOWN_DETERMINISM)` (`ReconstructAtWaveTest.Opaque`'s recipe, made
 * `Stateful`).
 */
class CliProbe(override val ref: CellRef) : Cell, Stateful {
    override fun snapshot(): Serializable = "probe"

    override fun restore(state: Serializable) {}
}

/**
 * A `--graph-provider` built through its `(String)` constructor (3qkx1-D7): the recorded spec at
 * [specPath] plus one [CliProbe], whose ref the journal never names.
 */
class SpecWithProbeProvider(private val specPath: String) : GraphSource {
    override fun build(host: ManagedHost): GraphBuild {
        val base = GraphSpecSource(CliFixture.readSpec(specPath)).build(host)
        val probe = CliProbe(PROBE_REF)
        host.managementInlet.call.spawn(probe)
        return GraphBuild(base.cells + probe)
    }

    companion object {
        val PROBE_REF = CellRef(UUID(77, 77))
    }
}

/** A `GraphSource` with neither a public no-arg nor a public `(String)` constructor. */
class IntOnlyProvider(@Suppress("unused") private val n: Int) : GraphSource {
    override fun build(host: ManagedHost): GraphBuild = GraphBuild(emptyList())
}

/** A class with a public no-arg constructor that is not a `GraphSource`. */
class NotAGraphSource

/**
 * computenet-3qkx1.1: the CLI's exit codes are the contract (`[TTD1-49]`) — 0 ran, 1 `diff`
 * diverged, 2 could not read or reconstruct — and every expected refusal prints the path or class
 * and the reason with no stack trace (`[TTD1-50]`). BS-9 and BS-12, exit halves.
 */
class CliExitCodeTest {

    private fun cli(vararg args: String): CliResult = CliFixture.run(*args)

    @Test
    fun `a -- inspect of a nonexistent path is 2 with the path and reason and no stack trace`(@TempDir dir: File) {
        val absent = File(dir, "no/such/journal.bin")

        val result = cli("inspect", absent.path)

        result.code shouldBe 2
        result.err shouldContain absent.path
        result.err shouldContain "not a readable regular file"
        result.err shouldNotContain "Exception"
        CliFixture.assertNoStackTrace(result.err)
        result.out shouldBe ""
        absent.exists() shouldBe false
    }

    @Test
    fun `b -- inspect of a torn journal is 0 and reports the tear`(@TempDir dir: File) {
        val file = File(dir, "torn.bin")
        CliFixture.record(file)
        val sizes = FileJournal(file).replay().map { it.size }
        val offsetOfLast = 8L + sizes.dropLast(1).sumOf { 4L + it }
        RandomAccessFile(file, "rw").use { it.setLength(offsetOfLast + 2) }

        val text = cli("inspect", file.path)
        val json = cli("inspect", file.path, "--json")

        text.code shouldBe 0
        text.out shouldContain "journal torn.bin: ${sizes.size - 1} records"
        text.out shouldContain "torn: last intact record #${sizes.size - 2}, 2 trailing bytes"
        text.out shouldContain "JOURNAL_TORN"
        json.code shouldBe 0
        json.out shouldContain "\"tearTrailingBytes\":2"
    }

    @Test
    fun `c -- BS-9 reconstruct without a graph flag is 2 naming the spec section, while inspect and diff are 0`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "run.bin")
        CliFixture.record(file)

        val reconstruct = cli("reconstruct", file.path, "--at", "0")
        reconstruct.code shouldBe 2
        reconstruct.err shouldContain Reconstructor.NO_GRAPH_SOURCE_MESSAGE
        reconstruct.err shouldContain file.path
        reconstruct.out shouldBe ""
        CliFixture.assertNoStackTrace(reconstruct.err)

        cli("inspect", file.path).code shouldBe 0
        val diff = cli("diff", file.path, file.path)
        diff.code shouldBe 0
        diff.out shouldContain "NO_GRAPH_SOURCE"
    }

    @Test
    fun `d -- reconstruct at the last record from a serialized GraphSpec is 0 and names the view`(@TempDir dir: File) {
        val file = File(dir, "run.bin")
        val recording = CliFixture.record(file)
        val spec = CliFixture.writeSpec(recording.spec, File(dir, "spec.bin"))
        val last = 3 * CliFixture.SCRIPT_ABC.size - 1

        val result = cli("reconstruct", file.path, "--at", "$last", "--graph", spec.path)

        result.code shouldBe 0
        result.out shouldContain "${recording.refs.view.id}:"
        result.out shouldContain "position #$last"
        result.err shouldBe ""
    }

    @Test
    fun `d -- a graph provider with --graph-arg is built through its String constructor`(@TempDir dir: File) {
        val file = File(dir, "run.bin")
        val recording = CliFixture.record(file)
        val spec = CliFixture.writeSpec(recording.spec, File(dir, "spec.bin"))

        val result = cli(
            *arrayOf(
                "reconstruct", file.path, "--at", "2",
                "--graph-provider", SpecWithProbeProvider::class.java.name, "--graph-arg", spec.path,
            ),
        )

        result.code shouldBe 0
        result.out shouldContain "${SpecWithProbeProvider.PROBE_REF.id}: ${CliProbe::class.java.name} DEGRADED"
    }

    @Test
    fun `e -- a graph provider or graph file that cannot be loaded is 2 naming the class or path`(@TempDir dir: File) {
        val file = File(dir, "run.bin")
        CliFixture.record(file)
        fun reconstruct(vararg graph: String) = cli("reconstruct", file.path, "--at", "0", *graph)

        val missing = reconstruct("--graph-provider", "no.such.Class")
        missing.code shouldBe 2
        missing.err shouldContain "no.such.Class"
        missing.err shouldContain "class not found"

        val noArgCtor = reconstruct("--graph-provider", IntOnlyProvider::class.java.name)
        noArgCtor.code shouldBe 2
        noArgCtor.err shouldContain IntOnlyProvider::class.java.name
        noArgCtor.err shouldContain "no-arg constructor"

        val noStringCtor = reconstruct("--graph-provider", IntOnlyProvider::class.java.name, "--graph-arg", "x")
        noStringCtor.code shouldBe 2
        noStringCtor.err shouldContain "(String) constructor"

        val notASource = reconstruct("--graph-provider", NotAGraphSource::class.java.name)
        notASource.code shouldBe 2
        notASource.err shouldContain NotAGraphSource::class.java.name
        notASource.err shouldContain "does not implement"

        val notASpec = reconstruct("--graph", file.path) // a journal, not a serialized GraphSpec
        notASpec.code shouldBe 2
        notASpec.err shouldContain file.path

        val absentSpec = reconstruct("--graph", File(dir, "absent.bin").path)
        absentSpec.code shouldBe 2
        absentSpec.err shouldContain "absent.bin"

        for (result in listOf(missing, noArgCtor, noStringCtor, notASource, notASpec, absentSpec)) {
            result.out shouldBe ""
            result.err.trimEnd().lines().size shouldBe 1
            CliFixture.assertNoStackTrace(result.err)
        }
    }

    @Test
    fun `f -- an unparseable or out-of-range --at is 2`(@TempDir dir: File) {
        val file = File(dir, "run.bin")
        val recording = CliFixture.record(file)
        val spec = CliFixture.writeSpec(recording.spec, File(dir, "spec.bin")).path

        val garbage = cli("reconstruct", file.path, "--at", "x", "--graph", spec)
        garbage.code shouldBe 2
        garbage.err shouldContain "--at x"

        val outOfRange = cli("reconstruct", file.path, "--at", "99", "--graph", spec)
        outOfRange.code shouldBe 2
        outOfRange.err shouldContain "99"
        CliFixture.assertNoStackTrace(outOfRange.err)

        cli("reconstruct", file.path, "--graph", spec).code shouldBe 2 // --at missing
    }

    @Test
    fun `usage errors are 2 with the usage on stderr`() {
        for (args in listOf(arrayOf<String>(), arrayOf("frobnicate"), arrayOf("inspect"), arrayOf("inspect", "x", "--nope"))) {
            val result = cli(*args)
            result.code shouldBe 2
            result.err shouldContain "usage: timetravel"
        }
    }

    @Test
    fun `g -- BS-12 two same-seed recordings diff as 0`(@TempDir dir: File) {
        val a = File(dir, "a.bin").also { CliFixture.record(it) }
        val b = File(dir, "b.bin").also { CliFixture.record(it) }

        cli("diff", a.path, b.path).code shouldBe 0
    }

    @Test
    fun `h -- run B with one extra add diffs as 1`(@TempDir dir: File) {
        val a = File(dir, "a.bin").also { CliFixture.record(it) }
        val b = File(dir, "b.bin").also { CliFixture.record(it, CliFixture.SCRIPT_ABC + (0 to "d")) }

        val result = cli("diff", a.path, b.path)

        result.code shouldBe 1
        result.out shouldContain "divergence: ONLY_IN_B"
    }

    @Test
    fun `i -- directories with an extra journal on one side diff as 1, a file against a directory is 2`(
        @TempDir dir: File,
    ) {
        val dirA = File(dir, "A")
        val dirB = File(dir, "B")
        CliFixture.record(File(dirA, "run.bin"))
        CliFixture.record(File(dirB, "run.bin"))

        cli("diff", dirA.path, dirB.path).code shouldBe 0

        CliFixture.record(File(dirB, "extra.bin"))
        val extra = cli("diff", dirA.path, dirB.path)
        extra.code shouldBe 1
        extra.out shouldContain "journal extra.bin: ONLY_IN_B"

        val mixed = cli("diff", File(dirA, "run.bin").path, dirB.path)
        mixed.code shouldBe 2
        mixed.err shouldContain "cannot diff a journal file against a directory"
        CliFixture.assertNoStackTrace(mixed.err)
    }

    @Test
    fun `reconstruct over a directory holding more than one journal is 2 naming the count`(@TempDir dir: File) {
        val recording = CliFixture.record(File(dir, "j/one.bin"))
        CliFixture.record(File(dir, "j/two.bin"))
        val spec = CliFixture.writeSpec(recording.spec, File(dir, "spec.bin")).path

        val result = cli("reconstruct", File(dir, "j").path, "--at", "0", "--graph", spec)

        result.code shouldBe 2
        result.err shouldContain "holds 2"
    }
}
