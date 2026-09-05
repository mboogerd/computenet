package civictech.demo.allocatorobserve.ingest

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.LineClassification
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.classifySpendLine
import java.nio.file.Path

/**
 * Per-reason counts of spend-log lines that did not become records
 * (`computenet-fpml.1.3`, feature rule 4).
 *
 * Failure accounting is a system invariant here (AGENTS.md): no line, however
 * broken, is silently dropped and no broken line stops ingestion. Every line
 * a poll reads is therefore either a record in the fold or an increment of one
 * of these counters.
 *
 * **These are process-lifetime observability, not part of the fold.** They
 * count *classification attempts*, so a re-baseline — which re-reads the whole
 * current file — counts the bad lines it re-reads again. That is deliberate:
 * making them a function of the current file content would mean silently
 * *decrementing* on re-read, which is exactly the silent loss of accounting the
 * invariant forbids. The consequence, stated where the number lives rather than
 * only in the bead: `malformed` is "bad lines this process classified", never
 * "bad lines currently in the log". The feature's restart-equality claim is
 * about the record SET only and says nothing about these.
 *
 * @param malformed lines that were not a JSON object, or claimed `v == 1` and
 *   failed v1 validation ([LineClassification.Malformed]). An empty line counts
 *   here — it is a line that yielded no record, and the invariant says such a
 *   line is counted rather than skipped.
 * @param unknownVersion well-formed JSON objects carrying an integer `v != 1`
 *   ([LineClassification.UnknownVersion]).
 */
data class SpendIngestFailures(
    val malformed: Long = 0L,
    val unknownVersion: Long = 0L,
) {
    /** All failures, whatever the reason. */
    val total: Long get() = malformed + unknownVersion
}

/**
 * What one [SpendLogIngester.poll] did.
 *
 * @param reason the tail reader's typed reason for this read — in particular
 *   whether it was an ordinary resume or a [TailReason.ReBaselined] re-read of
 *   the whole file.
 * @param added elements this poll put into the fold that were not already there.
 * @param removed elements this poll took out of the fold. Only a re-baseline
 *   ever removes: appends are add-only.
 * @param failures the per-reason counts contributed by *this poll's* lines. The
 *   ingester's running totals are [SpendLogIngester.failures].
 */
data class SpendPollOutcome(
    val reason: TailReason,
    val added: Int,
    val removed: Int,
    val failures: SpendIngestFailures,
)

/**
 * Wires the spend-log tail reader (`computenet-fpml.1.2`) and the v1 line
 * classifier (`computenet-fpml.1.1`) into a materialized fold of
 * [SpendRecord]s, with exposed per-reason failure counts
 * (`computenet-fpml.1.3`, feature `computenet-fpml.1`).
 *
 * ## The fold
 *
 * A kernel [SetCell] keyed by the FULL record tuple — v1 has no id field, so
 * the record *is* its identity (design note fpml.1-D2). Two consequences the
 * feature relies on:
 *
 * - Two byte-identical valid lines are one element. Re-delivery after a crash
 *   between hand-off and checkpoint persist is therefore harmless, which is what
 *   lets the reader persist its offset last.
 * - Two records differing only in `ended` are two distinct elements and both are
 *   retained. Ingest must not merge them: the differential oracle (F5,
 *   `computenet-fpml.5`) replays the raw log and compares.
 *
 * The cell type and its use are copied from `:demo:beadsmirror`'s
 * `projector/MirrorProjector` **by example, not by import** (fpml.1-D3): this
 * module does not depend on `:demo:beadsmirror`, and the epic defers a shared
 * connector SPI to CON2 (`computenet-rrf`). Unlike that projector, the deltas
 * here are not dot-minted from a feed position, so the cell's own `SetOps`
 * inlet is the right seam — this is the only writer of this cell.
 *
 * ## What one [poll] does
 *
 * - [TailReason.FirstStart] / [TailReason.Resumed]: each complete new line is
 *   classified; `Valid` is added to the fold, `Malformed` / `UnknownVersion`
 *   increments that reason's counter, and either way the next line is processed.
 * - [TailReason.ReBaselined]: the batch is the file's whole current content, so
 *   the fold is *reconciled* against it rather than added to — elements the
 *   re-read produced that the fold lacks are added, and elements the fold holds
 *   that the re-read did not produce are removed. The materialized set therefore
 *   converges on exactly the current file content, and stale records are gone
 *   from [view] rather than merely superseded. Reconciling twice against the
 *   same content is a no-op, since the second pass computes the same difference
 *   and finds it empty.
 * - [TailReason.LogAbsent]: nothing at all. A log that has not arrived yet is
 *   not an empty log, so the fold is left alone rather than reconciled to empty.
 *
 * ## Cadence
 *
 * [poll] is called explicitly; this class starts no thread and schedules
 * nothing. Tests drive it directly and so depend on no timing (AGENTS.md:
 * assert semantic outcomes, not scheduling). Serving and a real cadence belong
 * to F4 (`computenet-fpml.4`).
 *
 * @param logPath the spend log; a parameter, never a hardcoded path
 *   (fpml.1-D1 — no real socaity log exists yet and its eventual location is
 *   undecided). It need not exist.
 * @param runDir where the byte-offset checkpoint is persisted, so a restarted
 *   ingester resumes instead of re-reading the whole log. Also a parameter.
 * @param records the fold. Exposed and injectable because it is what survives a
 *   restart: the checkpoint round-trips through [runDir] on its own, while the
 *   cell's durability is the kernel's `Stateful` snapshot/restore seam, which
 *   this feature does not wire up. A restarted ingester is therefore
 *   constructed over the same [runDir] *and* handed the fold it is resuming
 *   into.
 * @param checkpoint the reader's [SpendOffsetStore], defaulted to the
 *   file-backed [OffsetCheckpoint] over [runDir] so existing callers are
 *   unaffected. Accepting it as a parameter is what makes the crash-ordering
 *   rule documented on [SpendOffsetStore] — fold before persist — observable
 *   at THIS seam: a test double can record its [SpendOffsetStore.write] and
 *   check what the fold already holds at that moment, rather than trusting
 *   that [poll]'s KDoc still matches its body (`computenet-xol9`).
 */
class SpendLogIngester(
    logPath: Path,
    runDir: Path,
    val records: SetCell<SpendRecord> = SetCell(),
    checkpoint: SpendOffsetStore = OffsetCheckpoint(runDir),
) {

    private val reader = SpendLogTailReader(logPath, checkpoint)

    /**
     * Running per-reason failure counts since this ingester was constructed.
     * Monotonic — see [SpendIngestFailures] for what that does and does not
     * claim.
     */
    var failures: SpendIngestFailures = SpendIngestFailures()
        private set

    /** The materialized record set: what the log currently says, as records. */
    fun view(): Set<SpendRecord> = records.membership()

    /**
     * Reads whatever the log has for us and folds it in.
     *
     * The fold happens inside the reader's consumer callback, i.e. *before* the
     * reader persists its new offset — the crash-ordering rule the reader
     * documents. A crash between the two re-delivers the batch, which the fold
     * absorbs idempotently because it is keyed by record identity.
     */
    fun poll(): SpendPollOutcome {
        var outcome: SpendPollOutcome? = null
        reader.poll { batch -> outcome = fold(batch) }
        // The reader always invokes the consumer exactly once per poll, on every
        // branch including LogAbsent, so this is never null.
        return checkNotNull(outcome) { "tail reader did not hand the batch to its consumer" }
    }

    private fun fold(batch: TailBatch): SpendPollOutcome {
        val valid = mutableListOf<SpendRecord>()
        var malformed = 0L
        var unknownVersion = 0L

        for (line in batch.lines) {
            when (val classification = classifySpendLine(line)) {
                is LineClassification.Valid -> valid += classification.record
                LineClassification.Malformed -> malformed++
                is LineClassification.UnknownVersion -> unknownVersion++
            }
        }

        val pollFailures = SpendIngestFailures(malformed, unknownVersion)
        failures =
            SpendIngestFailures(
                malformed = failures.malformed + malformed,
                unknownVersion = failures.unknownVersion + unknownVersion,
            )

        val live = records.membership()
        val added: Int
        var removed = 0

        if (batch.reason is TailReason.ReBaselined) {
            // Converge on the file's current content: the batch IS the whole
            // file, so anything the fold holds that the re-read did not produce
            // is stale and must go.
            val desired = valid.toSet()
            val toAdd = desired - live
            val toRemove = live - desired
            toAdd.forEach { records.inlet.call.add(it) }
            toRemove.forEach { records.inlet.call.remove(it) }
            added = toAdd.size
            removed = toRemove.size
        } else {
            // Append (or first start, or an absent log's empty batch): add-only.
            // Re-adding an element already present is a no-op for membership,
            // which is what makes re-delivery safe.
            val toAdd = valid.toSet() - live
            valid.forEach { records.inlet.call.add(it) }
            added = toAdd.size
        }

        return SpendPollOutcome(batch.reason, added, removed, pollFailures)
    }
}
