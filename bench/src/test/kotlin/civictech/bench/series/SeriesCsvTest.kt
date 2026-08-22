package civictech.bench.series

import civictech.bench.RunEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The series file's codec: round-trip fidelity, and the shapes it refuses. */
class SeriesCsvTest {

    @Test
    fun `an entry survives a render-parse round trip exactly`() {
        val entry = entry(runId = "2026-08-22T07-00-00Z", value = 4.321050323941347, dispersion = 0.004992364297944783)

        val parsed = SeriesCsv.parse(
            SeriesCsv.headerLine() + "\n" + SeriesCsv.render(entry) + "\n",
            "test",
        )

        assertEquals(listOf(entry), parsed)
    }

    @Test
    fun `a cpu model containing a comma round trips through quoting`() {
        // Not theoretical: `/proc/cpuinfo` model names carry commas routinely, and an
        // unquoted one would shift every column after it by one.
        val entry = entry(cpuModel = "Intel(R) Xeon(R) Platinum 8375C CPU @ 2.90GHz, rev 6")

        val rendered = SeriesCsv.render(entry)
        assertTrue(rendered.contains("\""), "expected the cpu model cell to be quoted: $rendered")

        val parsed = SeriesCsv.parse(SeriesCsv.headerLine() + "\n" + rendered, "test")
        assertEquals(entry.env.cpuModel, parsed.single().env.cpuModel)
        assertEquals(entry.env.os, parsed.single().env.os)
    }

    @Test
    fun `a header-only file parses to an empty series rather than failing`() {
        // The legitimate state of a series that has been set up but not yet seeded. This
        // repository ships exactly that file, so treating it as an error would make the
        // committed state unreadable.
        assertEquals(emptyList<SeriesEntry>(), SeriesCsv.parse(SeriesCsv.headerLine() + "\n", "test"))
    }

    @Test
    fun `a file with a different header is refused, not read positionally`() {
        val shifted = SeriesCsv.HEADER.toMutableList().also { it.removeAt(3) }.joinToString(",")

        val failure = assertThrows<SeriesFormatException> {
            SeriesCsv.parse(shifted + "\n", "series.csv")
        }
        assertTrue(failure.message!!.contains("unexpected header"), failure.message)
        assertTrue(failure.message!!.contains("series.csv"), failure.message)
    }

    @Test
    fun `a row with a non-numeric score is refused naming the line and the column`() {
        val bad = SeriesCsv.render(entry()).replaceFirst("4.5", "not-a-number")

        val failure = assertThrows<SeriesFormatException> {
            SeriesCsv.parse(SeriesCsv.headerLine() + "\n" + bad, "series.csv")
        }
        assertTrue(failure.message!!.contains("line 2"), failure.message)
        assertTrue(failure.message!!.contains("score"), failure.message)
    }

    @Test
    fun `a row with an unknown host state is refused`() {
        val bad = SeriesCsv.render(entry()).replaceFirst("QUIESCED", "PROBABLY_FINE")

        val failure = assertThrows<SeriesFormatException> {
            SeriesCsv.parse(SeriesCsv.headerLine() + "\n" + bad, "series.csv")
        }
        assertTrue(failure.message!!.contains("hostState"), failure.message)
    }

    @Test
    fun `params encode canonically and decode back`() {
        val params = mapOf("elements" to "1000", "degree" to "16")
        assertEquals("degree=16;elements=1000", SeriesCsv.encodeParams(params))
        assertEquals(params, SeriesCsv.decodeParams("degree=16;elements=1000"))
        assertEquals(emptyMap<String, String>(), SeriesCsv.decodeParams(""))
    }

    @Test
    fun `append creates the file with a header and then only adds`(@TempDir dir: File) {
        val file = File(dir, "series.csv")

        SeriesCsv.append(file, listOf(entry(runId = "run-1", value = 1.0)))
        SeriesCsv.append(file, listOf(entry(runId = "run-2", value = 2.0)))

        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(SeriesCsv.headerLine(), lines.first())
        assertEquals(3, lines.size)
        assertEquals(
            listOf("run-1", "run-2"),
            SeriesCsv.parse(file.readText(), file.path).map { it.runId },
        )
    }

    @Test
    fun `appending nothing to a fresh file still leaves a readable header`(@TempDir dir: File) {
        val file = File(dir, "series.csv")

        SeriesCsv.append(file, emptyList())

        assertEquals(emptyList<SeriesEntry>(), SeriesCsv.parse(file.readText(), file.path))
    }

    @Test
    fun `append refuses a file it cannot read back rather than mixing two formats`(@TempDir dir: File) {
        val file = File(dir, "series.csv")
        file.writeText("runId,score\nrun-1,1.0\n")

        assertThrows<SeriesFormatException> { SeriesCsv.append(file, listOf(entry())) }
        // Unchanged: the refusal happens before anything is written.
        assertEquals("runId,score\nrun-1,1.0\n", file.readText())
    }

    private fun entry(
        runId: String = "run-1",
        value: Double = 4.5,
        dispersion: Double = 0.01,
        cpuModel: String = "Apple M3 Max",
        hostState: HostState = HostState.QUIESCED,
    ) = SeriesEntry(
        runId = runId,
        runTimestampUtc = "2026-08-22T07:00:00Z",
        benchmark = "civictech.bench.micro.SmokeBenchmark.baseline",
        params = mapOf("degree" to "16"),
        mode = "avgt",
        value = value,
        dispersion = dispersion,
        unit = "ns/op",
        hostState = hostState,
        env = RunEnvironment(
            jvmVendor = "Eclipse Adoptium",
            jvmVersion = "21.0.11",
            heapSettings = "launched with no VM options",
            cpuModel = cpuModel,
            coreCount = 16,
            os = "Mac OS X 26.6.1",
            jmhMode = "AverageTime",
            forkCount = 5,
            warmupIterations = 5,
            measurementIterations = 5,
            harnessCommitSha = "ec98411f",
        ),
    )
}
