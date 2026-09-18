package civictech.demo.allocatorobserve.restart

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The declaration history's durable half (design entry `fpml.5-D4b`): one
 * append-only file under the run directory holding one line per observed
 * [DeclarationEvent], replayed into a fresh cell when a process starts.
 *
 * ## Why a file here rather than cell durability
 *
 * `SetCell`'s durability is the kernel's `Stateful` snapshot/restore seam, and
 * `AllocatorObserveApp` wires none of it up (the epic's non-goal: no ingest
 * framework). The spend fold does not need it — the spend log IS its durable
 * form, re-read whole at every cold start — but the declaration history has no
 * such source: `allocation.yaml` holds only the CURRENT declaration, so every
 * event observed before a restart would be lost, and with it every sub-interval
 * boundary of the R5 report. One file, appended to and replayed, is the whole
 * seam.
 *
 * ## Ordering, and what a crash can lose
 *
 * [append] is called strictly AFTER `DeclarationIngester.poll` has folded the
 * event into the cell — the checkpoint's own fold-before-persist rule. A crash
 * in that window loses the line, and the next poll then re-observes the file's
 * declaration as a fresh `Appended` event with a LATER `observedAt`. That is
 * bounded and visible in the history (a declaration change appears to have been
 * noticed later than it was), never a silently dropped event.
 *
 * ## File name
 *
 * `<runDir>/declaration-history`, deliberately extensionless and built by
 * [Path.resolve] rather than by any literal containing a separator: this
 * module's `ingest/NoHardcodedLogPathTest` is a lexical scan of `src/main` that
 * fails any string literal starting with `/` or ending in `.jsonl`, and it
 * cannot tell a run-directory-relative file name from a hardcoded deployment
 * path (over-broad, filed as `computenet-fpml.6`; not fixed here, avoided).
 *
 * @param runDir the same run directory the byte-offset checkpoint lives in. It
 *   is created if absent, exactly as `OffsetCheckpoint` does.
 */
class DeclarationHistoryJournal(runDir: Path) {

    private val file: Path = runDir.also { Files.createDirectories(it) }.resolve("declaration-history")

    /**
     * How many non-blank lines [replayInto] could not parse, across every call
     * on this instance.
     *
     * Accounting, never silence: an unparseable line is skipped so the rest of
     * the history still replays, but it is counted here so the loss is
     * observable rather than inferred from a report that looks merely odd.
     */
    var replayFailures: Long = 0L
        private set

    /** Appends one line for [event]. Creates the file if it does not exist yet. */
    fun append(event: DeclarationEvent) {
        val line = json.encodeToString(JournalLineDto.serializer(), event.toDto()) + "\n"
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /**
     * Replays every parseable line into [cell] and returns how many events were
     * added to it, counting unparseable lines in [replayFailures].
     *
     * Only COMPLETE lines are replayed: a trailing byte run with no terminating
     * newline is a half-written append and is ignored (not counted as a
     * failure), the same rule `SpendLogTailReader` applies to the spend log.
     * Blank lines are skipped silently; they carry no event to lose.
     *
     * Replay is idempotent by the cell's own set semantics — a replayed event
     * equals the event that produced its line, so replaying twice leaves the
     * same membership. The returned count is the number of LINES replayed, not
     * the resulting membership size; they differ exactly when the journal holds
     * duplicates.
     *
     * An absent journal replays 0, which is the correct reading of a run
     * directory no declaration has ever been observed in.
     */
    fun replayInto(cell: SetCell<DeclarationEvent>): Int {
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            return 0
        }
        if (text.isEmpty()) return 0
        // Splitting on the terminator leaves one trailing element that is never
        // a complete line: the empty string after a final newline, or the
        // half-written run of a torn append. Dropping it covers both.
        val complete = text.split("\n").dropLast(1)
        var replayed = 0
        for (line in complete) {
            if (line.isBlank()) continue
            val event = try {
                json.decodeFromString(JournalLineDto.serializer(), line).toEvent()
            } catch (_: Exception) {
                replayFailures++
                continue
            }
            cell.inlet.call.add(event)
            replayed++
        }
        return replayed
    }

    private companion object {
        /**
         * `encodeDefaults` matches `http/AllocatorJson.kt`'s encoder so the
         * `declaration` object written here is byte-for-byte the one the served
         * exchange document carries under `subIntervals[].declaration`
         * (fpml.5-D7; pinned by `DeclarationHistoryJournalTest`).
         */
        val json = Json { encodeDefaults = true }
    }
}

/**
 * The journal's own line shape.
 *
 * Deliberately NOT `http/AllocatorJson.kt`'s DTOs: those are the HTTP
 * exchange's, and the restart seam must not acquire a dependency on the serving
 * layer to persist a value it already holds. The `declaration` object's fields
 * and their order match `DeclarationDto` field for field on purpose, and a test
 * pins that agreement by parsing one journal line against one served document
 * rather than trusting this comment.
 */
@Serializable
private data class JournalLineDto(
    val observedAt: String,
    val declaration: JournalDeclarationDto,
)

@Serializable
private data class JournalDeclarationDto(
    val weights: Map<String, Double>,
    val monthlyCapHours: Double,
    val window: String?,
)

private fun DeclarationEvent.toDto(): JournalLineDto = JournalLineDto(
    observedAt = observedAt.toString(),
    declaration = JournalDeclarationDto(
        // Sorted for the same reason `AllocatorJson` sorts every project-keyed
        // map: the line must be a function of the event's value alone.
        weights = declaration.weights.toSortedMap(),
        monthlyCapHours = declaration.monthlyCapHours,
        window = declaration.window,
    ),
)

private fun JournalLineDto.toEvent(): DeclarationEvent = DeclarationEvent(
    observedAt = Instant.parse(observedAt),
    declaration = AllocationDeclaration(
        weights = declaration.weights,
        monthlyCapHours = declaration.monthlyCapHours,
        window = declaration.window,
    ),
)
