package civictech.wire.vector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * [WireVectorsMain] driven in-process against a temp COPY of the real corpus
 * (never the checked-in one — this task never writes to `wire/corpus`;
 * task 5 owns authoring). Every mutation here is a text/JSON edit made on the
 * copy, never a vector literal in this source file (feature acceptance
 * clause 6, checked by a grep for the two wire-format field names this file
 * must stay agnostic to — see the KDoc of [WireVectorsMain] for why that grep
 * already has one pre-existing hit unrelated to this task's files).
 */
class WireVectorsMainTest {

    private val SUSPENDED_FILE = "frames/port-api/WV-PORT-API-STALL-SUSPENDED-01.json"
    private val RESUME_ID = "WV-PORT-API-STALL-RESUME-01"
    private val RESUME_FILE = "frames/port-api/WV-PORT-API-STALL-RESUME-01.json"

    // --- (a) clean copy --------------------------------------------------------

    @Test
    fun `a - clean copy passes and changes no byte`(@TempDir tmp: Path) {
        val corpus = copyCorpus(tmp)
        val before = snapshot(corpus)

        val result = runMain(listOf("--corpus", corpus.toString()))

        assertEquals(0, result.exit, "stdout=${result.stdout} stderr=${result.stderr}")
        assertEquals(before, snapshot(corpus), "check mode must touch no file")
    }

    // --- (b) a decoded-side change fails check ----------------------------------

    @Test
    fun `b - a decoded-side change fails check naming the vector and the sentence`(@TempDir tmp: Path) {
        val corpus = copyCorpus(tmp)
        mutateSuspendedDecodedReason(corpus)

        val result = runMain(listOf("--corpus", corpus.toString()))

        assertEquals(1, result.exit, "stdout=${result.stdout} stderr=${result.stderr}")
        assertTrue("WV-PORT-API-STALL-SUSPENDED-01" in result.stdout, result.stdout)
        assertTrue("the encoder changed" in result.stdout || "the encoder changed" in result.stderr, result.stdout + result.stderr)
        assertTrue(Regex("byte offset \\d+").containsMatchIn(result.stdout), result.stdout)
    }

    // --- (c) --write repairs only the two literals, and a second check passes ----

    @Test
    fun `c - write repairs only the two literals of that file and a second check passes`(@TempDir tmp: Path) {
        val corpus = copyCorpus(tmp)
        mutateSuspendedDecodedReason(corpus)
        val beforeWrite = snapshot(corpus)

        val writeResult = runMain(listOf("--write", "--corpus", corpus.toString()))
        assertEquals(0, writeResult.exit, "stdout=${writeResult.stdout} stderr=${writeResult.stderr}")

        val afterWrite = snapshot(corpus)
        // The mutated file's bytes changed (the encoded block was repaired)...
        assertNotEquals(
            String(beforeWrite.getValue(SUSPENDED_FILE)),
            String(afterWrite.getValue(SUSPENDED_FILE)),
            "the encoded block should have been rewritten",
        )
        val rewrittenText = String(afterWrite.getValue(SUSPENDED_FILE))
        assertTrue("RESTARTING" in rewrittenText, rewrittenText)
        assertTrue(!rewrittenText.contains("\\\"reason\\\":\\\"SUSPENDED\\\""), "encoded.utf8 must no longer say SUSPENDED: $rewrittenText")
        // ...but only inside the encoded block: every other line of that file (in
        // particular the mutated `decoded.reason` this test set to RESTARTING, and
        // everything above `"encoded"`) must be byte-identical to the pre-write state.
        assertEquals(
            beforeLinesOutsideEncoded(String(beforeWrite.getValue(SUSPENDED_FILE))),
            beforeLinesOutsideEncoded(rewrittenText),
            "only the encoded block's two literals may change",
        )
        // ...and every OTHER file is untouched by this write.
        for ((path, bytes) in beforeWrite) {
            if (path == SUSPENDED_FILE) continue
            assertArrayEquals(bytes, afterWrite.getValue(path), "$path must be byte-identical after --write")
        }

        val secondCheck = runMain(listOf("--corpus", corpus.toString()))
        assertEquals(0, secondCheck.exit, "stdout=${secondCheck.stdout} stderr=${secondCheck.stderr}")
        assertEquals(afterWrite, snapshot(corpus), "a passing check must touch no file")
    }

    // --- (d) missing file / missing manifest entry --------------------------------

    @Test
    fun `d1 - a vector file missing from disk fails check naming the id`(@TempDir tmp: Path) {
        val corpus = copyCorpus(tmp)
        Files.delete(corpus.resolve(RESUME_FILE))

        val result = runMain(listOf("--corpus", corpus.toString()))

        assertEquals(1, result.exit, "stdout=${result.stdout} stderr=${result.stderr}")
        assertTrue(RESUME_ID in result.stdout, result.stdout)
    }

    @Test
    fun `d2 - a manifest entry missing for a file present on disk fails check naming the file`(@TempDir tmp: Path) {
        val corpus = copyCorpus(tmp)
        removeManifestEntry(corpus, RESUME_ID)

        val result = runMain(listOf("--corpus", corpus.toString()))

        assertEquals(1, result.exit, "stdout=${result.stdout} stderr=${result.stderr}")
        assertTrue(RESUME_FILE in result.stdout, result.stdout)
    }

    // --- (e) write mode is a byte no-op on the clean copy's manifest.json ---------

    @Test
    fun `e - write mode on the clean copy is a byte no-op for manifest json`(@TempDir tmp: Path) {
        val corpus = copyCorpus(tmp)
        val before = snapshot(corpus)

        val result = runMain(listOf("--write", "--corpus", corpus.toString()))

        assertEquals(0, result.exit, "stdout=${result.stdout} stderr=${result.stderr}")
        assertArrayEquals(before.getValue("manifest.json"), snapshot(corpus).getValue("manifest.json"), "manifest.json must be a byte no-op on an already-consistent corpus")
        assertEquals(before, snapshot(corpus), "write mode on an already-consistent corpus must touch no file")
    }

    // --- fixtures -----------------------------------------------------------------

    private fun copyCorpus(tmp: Path): Path {
        val src = VectorLoader.locate().root
        val dest = tmp.resolve("corpus")
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dest.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target)
                }
            }
        }
        return dest
    }

    private fun snapshot(root: Path): Map<String, ByteArray> =
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .associate { root.relativize(it).toString().replace(java.io.File.separatorChar, '/') to Files.readAllBytes(it) }
        }

    private fun Map<String, ByteArray>.equalsMap(other: Map<String, ByteArray>): Boolean =
        keys == other.keys && keys.all { this.getValue(it).contentEquals(other.getValue(it)) }

    private fun assertEquals(a: Map<String, ByteArray>, b: Map<String, ByteArray>, message: String) {
        assertTrue(a.equalsMap(b), "$message (keys a=${a.keys} b=${b.keys})")
    }

    /**
     * The valid, deliberate mutation: `decoded.fields.args[0].fields.reason` goes
     * SUSPENDED -> RESTARTING (a `Stall` reason both loader and codec accept). It
     * is a bare text substitution of the exact plain-quoted token `"SUSPENDED"`,
     * which appears exactly once in this file — the `decoded` field, ahead of
     * `encoded` in file order. `encoded.utf8` spells the same content
     * BACKSLASH-escaped (`\"SUSPENDED\"`), a different byte sequence the plain
     * token never matches, so `encoded` is deliberately left stale: that staleness
     * is what the regeneration check exists to catch.
     */
    private fun mutateSuspendedDecodedReason(corpus: Path) {
        val file = corpus.resolve(SUSPENDED_FILE)
        val text = Files.readString(file)
        val mutated = text.replaceFirst("\"SUSPENDED\"", "\"RESTARTING\"")
        check(mutated != text) { "fixture assumption broken: no plain-quoted SUSPENDED token found in $file" }
        check(!mutated.contains("\\\"RESTARTING\\\"")) { "fixture touched the encoded block, not just decoded: $mutated" }
        Files.writeString(file, mutated)
    }

    private fun removeManifestEntry(corpus: Path, id: String) {
        val manifestFile = corpus.resolve("manifest.json")
        val obj = Json.parseToJsonElement(Files.readString(manifestFile)) as JsonObject
        val vectors = (obj.getValue("vectors") as JsonArray).filterNot {
            ((it as JsonObject).getValue("id") as JsonPrimitive).content == id
        }
        val rewritten = buildJsonObject {
            put("vectors", JsonArray(vectors))
            put("pending", obj.getValue("pending"))
        }
        Files.writeString(manifestFile, Json.encodeToString(JsonObject.serializer(), rewritten))
    }

    /** [text] with the `encoded` object's own lines dropped, for an "everything else unchanged" comparison. */
    private fun beforeLinesOutsideEncoded(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "\"encoded\": {" }
        if (start < 0) return lines
        val end = lines.drop(start).indexOfFirst { it.trim() == "}," || it.trim() == "}" } + start
        return lines.subList(0, start) + lines.subList(end + 1, lines.size)
    }

    private data class RunResult(val exit: Int, val stdout: String, val stderr: String)

    private fun runMain(args: List<String>): RunResult {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(outBuf, true, "UTF-8"))
        System.setErr(PrintStream(errBuf, true, "UTF-8"))
        val exit = try {
            WireVectorsMain.run(args)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return RunResult(exit, outBuf.toString("UTF-8"), errBuf.toString("UTF-8"))
    }
}
