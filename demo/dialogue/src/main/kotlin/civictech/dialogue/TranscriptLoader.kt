package civictech.dialogue

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.Reader

/**
 * One rejected line from a transcript load, per the load report
 * [AGO1-SRC-05]: the line number (1-based, matching the source's line
 * numbering) and the reason its content could not be parsed into an
 * [Utterance]. The deserialization exception's message is used verbatim as
 * the reason — it already names what was wrong.
 */
data class TranscriptLoadIssue(
    val lineNumber: Int,
    val reason: String,
)

/**
 * The report accompanying a [TranscriptLoader] load: every rejected line,
 * plus counts a caller can render without recomputing them from the issue
 * list. `parsedCount` is the number of utterances successfully returned;
 * `rejectedCount` is `issues.size`. Blank lines are skipped and are not
 * counted in either — see [TranscriptLoader]'s class doc.
 */
data class TranscriptLoadReport(
    val issues: List<TranscriptLoadIssue>,
    val parsedCount: Int,
) {
    val rejectedCount: Int get() = issues.size
}

/**
 * The result of loading a transcript: the parsed [Utterance]s in file order,
 * and the [TranscriptLoadReport] describing what happened to every line that
 * did not parse.
 */
data class TranscriptLoadResult(
    val utterances: List<Utterance>,
    val report: TranscriptLoadReport,
)

/**
 * Loads the JSONL transcript fixture format (epic computenet-2aw §2.2 stage
 * 1): one [Utterance] per line, `{id, turn, speaker, tsMillis, text}`,
 * deserialized via kotlinx-serialization JSON.
 *
 * A pure function of the line sequence — no wall clock, no network, and it
 * does not touch the dataflow graph, order by turn, or dedupe. Turn ordering
 * and admission are the replay driver's job (sibling task,
 * [AGO1-SRC-03]/[AGO1-SRC-04]); this loader's boundary is
 * `file -> (utterances, report)`.
 *
 * A parse failure on line N rejects that line only: it is recorded in the
 * returned [TranscriptLoadReport] with its line number and the
 * deserialization message as reason, and loading continues with the
 * remaining lines [AGO1-SRC-05]. The loader never throws on a bad line.
 *
 * Blank lines (empty or all-whitespace) are skipped rather than reported —
 * they carry no content to name a failure reason for, and treating a blank
 * separator line as a rejected record would misrepresent the report as
 * naming a data problem when there is none.
 *
 * The loader does not police semantics beyond parseability: a
 * duplicate-id or otherwise odd-but-parseable line is returned as a parsed
 * [Utterance] like any other — that policing (turn-order admission, dedup)
 * belongs to the replay driver, not this loader.
 *
 * **Id policing happens at the driver, not here** (computenet-gkol). This
 * loader still returns a transcript carrying one id twice — with identical
 * or different content — as two parsed [Utterance]s, exactly as before; the
 * fixture `demo/dialogue/src/test/resources/duplicate-id-parseable.jsonl` is
 * that shape and still parses cleanly. What now differs is what happens when
 * those two parsed utterances are *admitted* through [TranscriptSource.offer]:
 * a second offer of an already-admitted id with identical content is a
 * silent no-op ([AGO1-SRC-02]), and a second offer of an already-admitted id
 * with *different* content is rejected with [DuplicateUtteranceIdException]
 * rather than being admitted alongside the first — the ingress set can no
 * longer end up holding two elements sharing one id. Id uniqueness remains
 * an assumed property of the transcript as far as this loader is concerned;
 * only the driver enforces it.
 */
object TranscriptLoader {

    private val json = Json { ignoreUnknownKeys = false }

    /** Loads from an arbitrary sequence of lines, 1-based line numbering. */
    fun load(lines: Sequence<String>): TranscriptLoadResult {
        val utterances = mutableListOf<Utterance>()
        val issues = mutableListOf<TranscriptLoadIssue>()

        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            if (rawLine.isBlank()) return@forEachIndexed

            try {
                utterances.add(json.decodeFromString(Utterance.serializer(), rawLine))
            } catch (e: SerializationException) {
                issues.add(TranscriptLoadIssue(lineNumber, e.message ?: e.toString()))
            } catch (e: IllegalArgumentException) {
                // kotlinx.serialization's JSON decoder can surface a malformed
                // payload as IllegalArgumentException rather than
                // SerializationException depending on where parsing fails;
                // both are "this line did not parse", not a loader bug.
                issues.add(TranscriptLoadIssue(lineNumber, e.message ?: e.toString()))
            }
        }

        return TranscriptLoadResult(
            utterances = utterances,
            report = TranscriptLoadReport(issues = issues, parsedCount = utterances.size),
        )
    }

    /** Loads from a [Reader], consuming it line by line. */
    fun load(reader: Reader): TranscriptLoadResult = load(reader.readLines().asSequence())

    /** Loads from a file on disk. */
    fun load(file: File): TranscriptLoadResult = load(file.bufferedReader())
}
