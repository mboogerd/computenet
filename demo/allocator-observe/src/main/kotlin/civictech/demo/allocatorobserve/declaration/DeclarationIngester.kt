package civictech.demo.allocatorobserve.declaration

import civictech.cell.data.SetCell
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant

/** A declaration observed at a particular poll time. */
data class DeclarationEvent(
    val observedAt: Instant,
    val declaration: AllocationDeclaration,
)

/** The result of one [DeclarationIngester.poll]. */
sealed interface DeclarationPollOutcome {
    /** A different parsed declaration was added to the history. */
    data class Appended(val event: DeclarationEvent) : DeclarationPollOutcome

    /** The file parsed successfully, but its declaration was unchanged. */
    data object Unchanged : DeclarationPollOutcome

    /** The declaration file does not exist yet. */
    data object Absent : DeclarationPollOutcome

    /** The file could not be read or did not contain a valid declaration. */
    data object ParseFailed : DeclarationPollOutcome
}

/**
 * Polls a hand-edited allocation declaration and records parsed-content
 * changes in a kernel [SetCell].
 *
 * Declaration identity is parsed content, not file bytes or modification time:
 * both a byte-identical rewrite and a reformat-only rewrite are unchanged.
 * Every appended event is timestamped with the injected observation clock;
 * ordering among events with equal [Instant] values is unspecified. The
 * [history] cell is also the source of the current declaration, so constructing
 * an ingester over a pre-populated cell resumes without a shadow baseline.
 *
 * The whole small file is read on every poll. There is deliberately no offset
 * checkpoint or persistence step: this ingester has no fold-before-persist
 * seam, and its cell is supplied by the caller when a recovered process needs
 * to continue an existing history. [history] returns an ordered snapshot that
 * can be consumed without reading the file again.
 *
 * Parse failures are process-lifetime classification attempts, not a count of
 * currently broken file contents. An absent file is not a failed declaration:
 * it produces [DeclarationPollOutcome.Absent] and does not increment
 * [parseFailures]. A read failure or malformed parse increments the counter
 * and leaves the most recent valid declaration in the cell as current.
 */
class DeclarationIngester(
    private val declarationPath: Path,
    history: SetCell<DeclarationEvent> = SetCell(),
    private val clock: () -> Instant = Instant::now,
) {
    private val historyCell = history

    /** Number of read or parse failures since this ingester was constructed. */
    var parseFailures: Long = 0L
        private set

    /**
     * Reads the current declaration and, only when its parsed content differs
     * from the cell's current declaration, appends one timestamped event.
     */
    fun poll(): DeclarationPollOutcome {
        val text =
            try {
                Files.readString(declarationPath)
            } catch (_: NoSuchFileException) {
                return DeclarationPollOutcome.Absent
            } catch (_: Exception) {
                parseFailures++
                return DeclarationPollOutcome.ParseFailed
            }

        return when (val result = parseAllocationDeclaration(text)) {
            DeclarationParse.Malformed -> {
                parseFailures++
                DeclarationPollOutcome.ParseFailed
            }

            is DeclarationParse.Valid -> {
                val current = historyCell.membership().maxByOrNull { it.observedAt }?.declaration
                if (result.declaration == current) {
                    DeclarationPollOutcome.Unchanged
                } else {
                    val event = DeclarationEvent(clock(), result.declaration)
                    historyCell.inlet.call.add(event)
                    DeclarationPollOutcome.Appended(event)
                }
            }
        }
    }

    /**
     * Returns all observed declarations in ascending observation-time order.
     * This is derived solely from the history cell and does not read the file.
     */
    fun history(): List<DeclarationEvent> = historyCell.membership().sortedBy { it.observedAt }
}
