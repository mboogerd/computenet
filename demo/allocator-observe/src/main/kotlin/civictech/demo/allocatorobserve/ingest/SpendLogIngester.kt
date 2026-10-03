package civictech.demo.allocatorobserve.ingest

import civictech.cell.data.SetOps
import civictech.cell.host.DurableInput
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import civictech.demo.allocatorobserve.LineClassification
import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.classifySpendLine
import civictech.demo.allocatorobserve.declaration.AllocationDeclaration
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.nio.file.Path
import java.time.Instant

@Serializable
@SerialName("AllocatorSpendRecord")
private data class SpendRecordWire(
    val v: Int,
    val project: String,
    val machine: String,
    val workItem: String,
    val started: String,
    val ended: String,
)

private object SpendRecordWireSerializer : KSerializer<SpendRecord> {
    override val descriptor = SpendRecordWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SpendRecord) {
        encoder.encodeSerializableValue(
            SpendRecordWire.serializer(),
            SpendRecordWire(value.v, value.project, value.machine, value.workItem, value.started, value.ended),
        )
    }

    override fun deserialize(decoder: Decoder): SpendRecord {
        val value = decoder.decodeSerializableValue(SpendRecordWire.serializer())
        return SpendRecord(value.v, value.project, value.machine, value.workItem, value.started, value.ended)
    }
}

@Serializable
@SerialName("AllocatorDeclarationEvent")
private data class DeclarationEventWire(
    val observedAt: String,
    val weights: Map<String, Double>,
    val monthlyCapHours: Double,
    val window: String?,
)

private object DeclarationEventWireSerializer : KSerializer<DeclarationEvent> {
    override val descriptor = DeclarationEventWire.serializer().descriptor

    override fun serialize(encoder: Encoder, value: DeclarationEvent) {
        encoder.encodeSerializableValue(
            DeclarationEventWire.serializer(),
            DeclarationEventWire(
                value.observedAt.toString(),
                value.declaration.weights,
                value.declaration.monthlyCapHours,
                value.declaration.window,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): DeclarationEvent {
        val value = decoder.decodeSerializableValue(DeclarationEventWire.serializer())
        return DeclarationEvent(
            Instant.parse(value.observedAt),
            AllocationDeclaration(value.weights, value.monthlyCapHours, value.window),
        )
    }
}

private object AllocatorObserveWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(SpendRecord::class, SpendRecordWireSerializer)
            subclass(DeclarationEvent::class, DeclarationEventWireSerializer)
        }
    }
}

private object AllocatorObserveWireRegistration {
    init {
        WireCodec.contribute(AllocatorObserveWireSerializers)
    }

    fun ensure() = Unit
}

internal fun ensureAllocatorObserveWireTypes() = AllocatorObserveWireRegistration.ensure()

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
 * A kernel set cell keyed by the FULL record tuple — v1 has no id field, so
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
 * inlet is the right seam — not only for this ingester's own adds and
 * removes, but for `AllocatorObserveApp.convergeOnDeletedLog` (design entry
 * 6jbep-D1, see the [TailReason.LogAbsent] bullet below), which writes
 * through the same inlet to clear every record of a log this process had
 * read that has since disappeared. This is not the only writer of the cell,
 * but every writer uses this one seam.
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
 *   A log that HAD arrived and is now gone is the app-level case design entry
 *   6jbep-D1 covers (`AllocatorObserveApp.convergeOnDeletedLog`), not this poll.
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
 * @param records the hosted set inlet. Every write therefore crosses the host
 *   intake and is captured by [input].
 * @param input the kernel durable input that commits the source cursor and all
 *   set mutations from one poll as one journal record.
 * @param view a snapshot read of the live hosted cell's membership.
 * @param maxLinesPerBatch how many lines the reader hands over at a time —
 *   passed through to [SpendLogTailReader] for the same reason its `chunkSize`
 *   is a parameter: it makes the multi-hand-off fold, in particular a
 *   re-baseline reconciled across several hand-offs, reachable from a unit test
 *   over a fixture a temp dir can hold (`computenet-xs5u`).
 */
class SpendLogIngester(
    logPath: Path,
    private val records: SetOps<SpendRecord>,
    private val input: DurableInput,
    private val view: () -> Set<SpendRecord>,
    maxLinesPerBatch: Int = SpendLogTailReader.DEFAULT_MAX_LINES_PER_BATCH,
) {

    init {
        ensureAllocatorObserveWireTypes()
    }

    private val reader = SpendLogTailReader(
        logPath = logPath,
        committed = { input.committed() as? CheckpointState },
        maxLinesPerBatch = maxLinesPerBatch,
    )

    /**
     * Running per-reason failure counts since this ingester was constructed.
     * Monotonic — see [SpendIngestFailures] for what that does and does not
     * claim.
     */
    var failures: SpendIngestFailures = SpendIngestFailures()
        private set

    /** The materialized record set: what the log currently says, as records. */
    fun view(): Set<SpendRecord> = view.invoke()

    /**
     * Reads whatever the log has for us and folds it in.
     *
     * The fold happens inside the reader's consumer callback — which the reader
     * invokes once per bounded batch, one or more times per poll — i.e. *before*
     * the reader persists its new offset, the crash-ordering rule the reader
     * documents. A crash anywhere in that sequence, including between two
     * hand-offs, re-delivers the whole range, which the fold absorbs
     * idempotently because it is keyed by record identity.
     */
    fun poll(): SpendPollOutcome {
        val fold = PollFold()
        input.commit {
            reader.poll(fold::absorb).next
        }
        // The reader always delivers at least one batch per poll, on every
        // branch including LogAbsent, and exactly one of them carries `last`.
        return checkNotNull(fold.outcome) { "tail reader did not close the poll with a final batch" }
    }

    /**
     * The per-poll fold state, spanning the reader's one-or-more hand-offs
     * (`computenet-xs5u`).
     *
     * It exists because a re-baseline is inherently a whole-file operation — the
     * removals are `what the fold holds` minus `what the WHOLE re-read
     * produced`, so they cannot be computed from one hand-off — while an append
     * is not. So the two branches accumulate differently, and only the
     * re-baseline branch waits for [TailBatch.last]:
     *
     * - **Append**: each batch is folded in as it arrives and its lines are
     *   dropped. Nothing accumulates but counts.
     * - **Re-baseline**: the *records* accumulate (they are what the `SetCell`
     *   is about to hold anyway, so this adds no asymptotic residency the fold
     *   did not already have) while the *lines* are dropped batch by batch. Raw
     *   line content is what the bead was about, and none of it is retained.
     *
     * Either way the fold is complete before the final hand-off RETURNS, so the
     * reader's checkpoint write still happens strictly after it.
     */
    private inner class PollFold {

        /** Non-null once the final batch has been folded; the poll's answer. */
        var outcome: SpendPollOutcome? = null
            private set

        private var malformed = 0L
        private var unknownVersion = 0L
        private var added = 0
        private var removed = 0

        /** Re-baseline only: every valid record the whole re-read has produced. */
        private val desired = mutableSetOf<SpendRecord>()

        /**
         * Append only: `records.membership().size` as observed before this
         * poll's first hand-off was folded in, captured once (`computenet-brvag`).
         *
         * The append branch only ever adds, so `added` — distinct records this
         * poll put into the fold that were not already there — is exactly the
         * membership size delta between "before this poll touched anything" and
         * "after the last hand-off is folded in", however many hand-offs the
         * poll took. Reading membership per hand-off (the previous shape) made
         * an append's cost grow with the number of hand-offs, and so with the
         * log size for a [TailReason.FirstStart] whole-file read — quadratic
         * overall, since [SetCell.membership] is itself `O(fold size)`. Reading
         * it once here and once more at the final hand-off bounds the call
         * count at 2 per poll regardless of hand-off count.
         */
        private var appendBaseline: Set<SpendRecord>? = null

        /** Append-only records seen across all hand-offs in this poll. */
        private val appended = mutableSetOf<SpendRecord>()

        fun absorb(batch: TailBatch) {
            val valid = mutableListOf<SpendRecord>()
            for (line in batch.lines) {
                when (val classification = classifySpendLine(line)) {
                    is LineClassification.Valid -> valid += classification.record
                    LineClassification.Malformed -> malformed++
                    is LineClassification.UnknownVersion -> unknownVersion++
                }
            }

            if (batch.reason is TailReason.ReBaselined) {
                // Converge on the file's current content: the POLL (not this
                // batch) is the whole file, so anything the fold holds that the
                // re-read did not produce is stale and must go — which is only
                // knowable once every batch is in.
                desired += valid
                if (batch.last) {
                    val live = view()
                    val toAdd = desired - live
                    val toRemove = live - desired
                    toAdd.forEach(records::add)
                    toRemove.forEach(records::remove)
                    added = toAdd.size
                    removed = toRemove.size
                }
            } else {
                // Append (or first start, or an absent log's empty batch):
                // add-only, so each batch can be applied on arrival. Re-adding an
                // element already present is a no-op for membership, which is
                // what makes re-delivery safe. The baseline is read once, before
                // the first hand-off's adds land, so a record repeated within a
                // batch, across batches, or already present is counted added
                // exactly once either way — the delta is size-based, not a set
                // difference per batch.
                if (appendBaseline == null) appendBaseline = view()
                appended += valid
                valid.forEach(records::add)
                if (batch.last) added = (appended - checkNotNull(appendBaseline)).size
            }

            if (batch.last) {
                failures =
                    SpendIngestFailures(
                        malformed = failures.malformed + malformed,
                        unknownVersion = failures.unknownVersion + unknownVersion,
                    )
                outcome =
                    SpendPollOutcome(
                        batch.reason,
                        added,
                        removed,
                        SpendIngestFailures(malformed, unknownVersion),
                    )
            }
        }
    }
}
