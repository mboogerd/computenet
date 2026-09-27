package civictech.timetravel.cli

import civictech.timetravel.diff.CANONICAL_JSON
import civictech.timetravel.diff.MultiJournalDiffReport
import civictech.timetravel.diff.RunDiff
import civictech.timetravel.diff.RunDiffReport
import civictech.timetravel.diff.Verdict
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.journal.FrameRecord
import civictech.timetravel.journal.JournalReader
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.journal.JournalSource
import civictech.timetravel.reconstruct.GraphSource
import civictech.timetravel.reconstruct.GraphSpecSource
import civictech.timetravel.reconstruct.Reconstructor
import civictech.timetravel.reconstruct.ReconstructorResult
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * computenet-3qkx1.1, `[TTD1-48]`: each command's `--json` stdout decodes through `CANONICAL_JSON`
 * to a DTO equal to the one built directly from the API objects for the same inputs; `--json` and
 * text of one run share the exit code; and the reconstruct text carries the allow-list pessimism
 * line exactly when a cell is `Degraded(UNKNOWN_DETERMINISM)` (3qkx1-D8).
 */
class CliJsonTest {

    private fun cli(vararg args: String): CliResult = CliFixture.run(*args)

    /** Runs [args] as text and as `--json`; asserts one exit code and returns the JSON run. */
    private fun both(vararg args: String): CliResult {
        val text = cli(*args)
        val json = cli(*args, "--json")
        json.code shouldBe text.code
        json.err shouldBe ""
        return json
    }

    private fun readFile(file: File): JournalReading = JournalReader.open(JournalSource.File(file))

    private fun reconstruction(file: File, graph: GraphSource, at: Int): ReconstructReport {
        val reading = readFile(file)
        val timeline = RunTimeline.of(reading).values.single()
        val reconstructor = Reconstructor.of(reading, timeline, graph).shouldBeInstanceOf<ReconstructorResult.Ready>()
        return ReconstructReport.of(reconstructor.reconstructor.stateAt(Position.Index(at)), file.name)
    }

    @Test
    fun `inspect --json decodes to InspectReport of the reading, for a file and a directory`(@TempDir dir: File) {
        val file = File(dir, "j/run.bin")
        CliFixture.record(file)
        CliFixture.record(File(dir, "j/other.bin"), CliFixture.SCRIPT_ABC + (0 to "d"))

        val onFile = both("inspect", file.path)
        onFile.code shouldBe 0
        val decoded = CANONICAL_JSON.decodeFromString<InspectReport>(onFile.out.trim())
        decoded shouldBe InspectReport.of(readFile(file))
        // Independently of RecordDto.of — which built both sides of the equality above — every
        // frame's DTO carries the frame's own fields, so a field dropped from the mirror reds here.
        val frames = readFile(file).records.filterIsInstance<FrameRecord>().toList()
        frames.shouldNotBeEmpty()
        val timeline = RunTimeline.of(readFile(file)).values.single()
        val dtos = decoded.journals.single().records
        dtos.size shouldBe timeline.size
        for (frame in frames) {
            dtos[frame.index] shouldBe RecordDto(
                index = frame.index,
                kind = "FrameRecord",
                cellRef = frame.cellRef.id.toString(),
                portName = frame.portName,
                type = frame.type.name,
                contractId = frame.contractId,
                methodId = frame.methodId,
                sourceId = frame.context?.timestamp?.sourceId?.toString(),
                counter = frame.context?.timestamp?.counter,
                methodName = frame.hydrated?.methodName,
                label = timeline.labels(frame.index),
                reasons = frame.reasons.sortedBy { it.name },
            )
        }
        frames.count { it.context != null } shouldBe 2 * CliFixture.SCRIPT_ABC.size // the union's and view's waved frames
        frames.all { it.hydrated != null } shouldBe true

        val onDir = both("inspect", file.parentFile.path)
        onDir.code shouldBe 0
        CANONICAL_JSON.decodeFromString<InspectReport>(onDir.out.trim()) shouldBe
            InspectReport.of(JournalReader.open(JournalSource.Directory(file.parentFile)))
    }

    @Test
    fun `reconstruct --json decodes to ReconstructReport of the reconstruction`(@TempDir dir: File) {
        val file = File(dir, "run.bin")
        val recording = CliFixture.record(file)
        val spec = CliFixture.writeSpec(recording.spec, File(dir, "spec.bin"))
        val last = 3 * CliFixture.SCRIPT_ABC.size - 1

        val result = both("reconstruct", file.path, "--at", "$last", "--graph", spec.path)

        result.code shouldBe 0
        val decoded = CANONICAL_JSON.decodeFromString<ReconstructReport>(result.out.trim())
        decoded shouldBe reconstruction(file, GraphSpecSource(recording.spec), last)
        decoded.cells.map { it.cellRef } shouldBe recording.refs.all.map { it.id.toString() }.sorted()
    }

    @Test
    fun `diff --json of two files decodes to RunDiff diff, for an identical and a diverging pair`(@TempDir dir: File) {
        val a = File(dir, "a.bin").also { CliFixture.record(it) }
        val same = File(dir, "same.bin").also { CliFixture.record(it) }
        val longer = File(dir, "longer.bin").also { CliFixture.record(it, CliFixture.SCRIPT_ABC + (0 to "d")) }

        for ((other, code) in listOf(same to 0, longer to 1)) {
            val result = both("diff", a.path, other.path)
            result.code shouldBe code
            val tA = RunTimeline.of(readFile(a)).values.single()
            val tB = RunTimeline.of(readFile(other)).values.single()
            CANONICAL_JSON.decodeFromString<RunDiffReport>(result.out.trim()) shouldBe RunDiff.diff(tA, tB)
        }
    }

    @Test
    fun `diff --json of two directories decodes to RunDiff diffReadings`(@TempDir dir: File) {
        val dirA = File(dir, "A")
        val dirB = File(dir, "B")
        CliFixture.record(File(dirA, "run.bin"))
        CliFixture.record(File(dirB, "run.bin"))
        CliFixture.record(File(dirB, "extra.bin"))

        val result = both("diff", dirA.path, dirB.path)

        result.code shouldBe 1
        CANONICAL_JSON.decodeFromString<MultiJournalDiffReport>(result.out.trim()) shouldBe RunDiff.diffReadings(
            JournalReader.open(JournalSource.Directory(dirA)),
            JournalReader.open(JournalSource.Directory(dirB)),
        )
    }

    @Test
    fun `reconstruct text carries the allow-list line exactly once for UNKNOWN_DETERMINISM and never for a Faithful run`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "run.bin")
        val recording = CliFixture.record(file)
        val spec = CliFixture.writeSpec(recording.spec, File(dir, "spec.bin"))
        fun allowListLines(text: String) = text.lines().count { "UNKNOWN_DETERMINISM" in it && "allow-list" in it }

        val probeArgs = arrayOf(
            "reconstruct", file.path, "--at", "5",
            "--graph-provider", SpecWithProbeProvider::class.java.name, "--graph-arg", spec.path,
        )
        val degraded = both(*probeArgs)
        degraded.code shouldBe 0
        val degradedReport = CANONICAL_JSON.decodeFromString<ReconstructReport>(degraded.out.trim())
        degradedReport shouldBe reconstruction(file, SpecWithProbeProvider(spec.path), 5)
        val probe = degradedReport.cells.single { it.cellRef == SpecWithProbeProvider.PROBE_REF.id.toString() }
        probe.fidelity.verdict shouldBe Verdict.DEGRADED
        probe.fidelity.reasons shouldBe listOf(Reason.UNKNOWN_DETERMINISM)
        allowListLines(cli(*probeArgs).out) shouldBe 1

        val faithful = both("reconstruct", file.path, "--at", "5", "--graph", spec.path)
        faithful.code shouldBe 0
        CANONICAL_JSON.decodeFromString<ReconstructReport>(faithful.out.trim()).run.verdict shouldBe Verdict.FAITHFUL
        val faithfulText = cli("reconstruct", file.path, "--at", "5", "--graph", spec.path).out
        allowListLines(faithfulText) shouldBe 0
        faithfulText shouldNotContain "UNKNOWN_DETERMINISM"
    }
}
