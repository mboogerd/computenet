package civictech.dialogue.extract

import civictech.agora.cell.Polarity
import civictech.dialogue.Segment

/**
 * One extracted item the pipeline refused, with the segment it came from and
 * why ([AGO1-EXTR-05], BS-12). The offending [item] is kept so a status
 * surface can show *what* was rejected, not merely that something was.
 */
data class RejectedItem(
    val segmentId: String,
    val reason: String,
    val item: ExtractedItem,
)

/**
 * One segment whose extraction did not complete ([AGO1-EXTR-06], BS-14;
 * [AGO1-EXTR-08], BS-15). [contentHash] is carried because BS-15 requires a
 * cassette miss to identify the segment *and* its content hash, and because
 * it is the key under which a recording extractor would supply the missing
 * entry.
 */
data class FailedSegment(
    val segmentId: String,
    val contentHash: String,
    val reason: String,
)

/** What the pipeline can say about one segment's extraction. */
sealed class SegmentStatus {
    /** Extraction completed and contributed [itemCount] well-formed items (possibly zero). */
    data class Extracted(val itemCount: Int) : SegmentStatus()

    /** Extraction did not complete; [reason] names the failure. */
    data class Failed(val reason: String) : SegmentStatus()

    /** The pipeline has never extracted this segment. */
    data object Unknown : SegmentStatus()
}

/**
 * The pipeline's rejection/failure ledger and status surface
 * ([AGO1-EXTR-05], [AGO1-EXTR-06] "visible on the status surface").
 *
 * Written only by [ExtractionGate]; read by tests today and by F5's HTTP
 * surface later. The read side is deliberately a plain query object with no
 * kernel types in its signature, so F5 can hand it to `DemoShell` unchanged.
 *
 * **Not thread-safe: drive it from one thread** — the same stance
 * `TranscriptSource` takes, and sufficient for the simulated single-threaded
 * host the demo and its tests run on.
 */
class ExtractionAccounting {
    private val rejectedItems = mutableListOf<RejectedItem>()
    private val failedSegments = mutableListOf<FailedSegment>()
    private val statuses = mutableMapOf<String, SegmentStatus>()

    /** Every item discarded as malformed, in the order they were rejected. */
    val rejected: List<RejectedItem> get() = rejectedItems.toList()

    /** Every segment whose extraction failed, in the order they failed. */
    val failed: List<FailedSegment> get() = failedSegments.toList()

    /** What is known about [segmentId]'s extraction. */
    fun status(segmentId: String): SegmentStatus = statuses[segmentId] ?: SegmentStatus.Unknown

    internal fun recordExtracted(segmentId: String, itemCount: Int, rejections: List<RejectedItem>) {
        rejectedItems += rejections
        statuses[segmentId] = SegmentStatus.Extracted(itemCount)
    }

    internal fun recordFailed(segmentId: String, contentHash: String, reason: String) {
        failedSegments += FailedSegment(segmentId, contentHash, reason)
        statuses[segmentId] = SegmentStatus.Failed(reason)
    }
}

/**
 * Stage 3 of the AGO1 pipeline (epic computenet-2aw §2.2): the total,
 * memoized `(Segment) -> List<ExtractedItem>` that a
 * `civictech.cell.data.op.FlatMapSetCell<Segment, ExtractedItem>` maps with.
 *
 * ### The design decision (cite as 2aw.F2-T2 gate design)
 *
 * `FlatMapSetCell`'s KDoc states "[f] must be pure — dels re-apply it to
 * translate removals", and its late-join catch-up recomputes the whole output
 * from input state by re-applying `f`. But an [Extractor] is the pipeline's
 * determinism firewall and is allowed to be none of that: it may throw
 * ([AGO1-EXTR-06]), a cassette miss throws by design ([AGO1-EXTR-08]), and
 * malformed items must be *recorded once* ([AGO1-EXTR-05]) — recording on
 * every re-application would double-count, and a throw escaping into the
 * kernel would fault a delta translation that is not even the segment's own
 * arrival.
 *
 * The reconciliation is memoization by content hash plus per-segment
 * accounting:
 *
 * - The **outcome** of a delegate call is cached under
 *   [segmentContentHash] — text only, so `[AGO1-EXTR-02]`'s "same segment
 *   content in two different utterances derives the same item set" falls out
 *   of the cache rather than being separately implemented. A cache hit
 *   returns the cached surviving items verbatim and never calls the delegate.
 * - The delegate is called inside `try`/`catch`. Any throwable — including
 *   [CassetteMissException] — becomes a cached *failed* outcome and an
 *   [ExtractionAccounting.recordFailed] entry naming the exception class,
 *   its message and the content hash; the gate then returns an empty list,
 *   so the kernel never sees the throw and the rest of the graph is
 *   unaffected. A failed segment is distinguishable from an empty
 *   extraction: `Failed(reason)`, never `Extracted(0)`.
 * - A normal return is **validated** item by item (below); survivors are
 *   cached and returned, casualties are recorded as [RejectedItem] and the
 *   remaining items still flow ([AGO1-EXTR-05]'s "continue processing").
 * - **Accounting is guarded per segment id**, not per content hash. A
 *   segment already accounted for records nothing further, so del-side
 *   re-application, late-join catch-up and a re-admission all replay the
 *   cached result without growing [ExtractionAccounting.rejected] or
 *   [ExtractionAccounting.failed]. Two *distinct* segments that happen to
 *   share text are each accounted once, each under its own id — they share
 *   the delegate call, not the ledger entry.
 *
 * Net effect: `f` is observably pure — same input, same output, no
 * per-call effects, no throw — which is exactly what the kernel cell demands,
 * without weakening either requirement.
 *
 * ### Two boundaries this gate deliberately does not own
 *
 * - **Self-relations.** BS-12's self-relation is rejected here on *textual*
 *   identity (`sourceText.trim() == targetText.trim()`), which is all this
 *   stage can see. The key-level rule — a relation whose source and target
 *   canonicalize to the same `ClaimKey` — is `[AGO1-REL-04]` and belongs to
 *   **F3 (minting)**. It is *not* done here: F3's author must still
 *   implement it, because two textually different strings can canonicalize
 *   to one key and this gate will pass them through.
 * - **Timeouts.** `[AGO1-EXTR-06]` says "throws or times out". No timeout
 *   machinery is built here, and that is the honest reading rather than an
 *   omission: a hung mapper cannot be interrupted from inside a pure
 *   `FlatMapSetCell.f` without threads or a clock, and `[AGO1-EXTR-01]`
 *   forbids the pipeline from having either outside the [Extractor] SPI. A
 *   timeout is therefore the *delegate's* responsibility — an [Extractor]
 *   that times out internally throws, and the throw lands here as a failed
 *   segment like any other.
 */
class ExtractionGate(
    private val delegate: Extractor,
    val accounting: ExtractionAccounting = ExtractionAccounting(),
) : Extractor {

    /** A delegate call's memoized result, keyed by content hash. */
    private sealed class Outcome {
        /** The delegate returned; [items] survived validation, [rejections] did not. */
        data class Completed(
            val items: List<ExtractedItem>,
            val rejections: List<Pair<ExtractedItem, String>>,
        ) : Outcome()

        /** The delegate threw; [reason] names the exception. */
        data class Failed(val reason: String) : Outcome()
    }

    private val outcomes = mutableMapOf<String, Outcome>()
    private val accountedSegments = mutableSetOf<String>()

    override fun extract(segment: Segment): List<ExtractedItem> {
        val contentHash = segmentContentHash(segment)
        val outcome = outcomes.getOrPut(contentHash) { evaluate(segment) }
        account(segment, contentHash, outcome)
        return when (outcome) {
            is Outcome.Completed -> outcome.items
            is Outcome.Failed -> emptyList()
        }
    }

    private fun evaluate(segment: Segment): Outcome =
        try {
            val returned = delegate.extract(segment)
            val survivors = mutableListOf<ExtractedItem>()
            val rejections = mutableListOf<Pair<ExtractedItem, String>>()
            returned.forEach { item ->
                val reason = malformedReason(item)
                if (reason == null) survivors += item else rejections += item to reason
            }
            Outcome.Completed(survivors.toList(), rejections.toList())
        } catch (failure: Throwable) {
            // Deliberately Throwable, not Exception: nothing an extractor can
            // raise may escape into the kernel's delta translation.
            Outcome.Failed(describe(failure))
        }

    /** Records [segment]'s outcome exactly once, however often the mapper is re-applied. */
    private fun account(segment: Segment, contentHash: String, outcome: Outcome) {
        if (!accountedSegments.add(segment.id)) return
        when (outcome) {
            is Outcome.Completed -> accounting.recordExtracted(
                segmentId = segment.id,
                itemCount = outcome.items.size,
                rejections = outcome.rejections.map { (item, reason) ->
                    RejectedItem(segmentId = segment.id, reason = reason, item = item)
                },
            )

            is Outcome.Failed -> accounting.recordFailed(
                segmentId = segment.id,
                contentHash = contentHash,
                reason = outcome.reason,
            )
        }
    }

    companion object {
        /**
         * Why [item] is malformed ([AGO1-EXTR-05]), or `null` if it is well
         * formed. The four cases are exactly the ones BS-12 exercises.
         */
        fun malformedReason(item: ExtractedItem): String? = when (item) {
            is ExtractedClaim ->
                if (item.text.isBlank()) "blank claim text" else null

            is ExtractedRelation -> when {
                parsePolarity(item.polarity) == null ->
                    "unparseable polarity '${item.polarity}'"
                // Textual self-relation only; the key-level rule is F3's
                // ([AGO1-REL-04]) and is NOT satisfied by this check.
                item.sourceText.trim() == item.targetText.trim() ->
                    "self-relation: source and target text are identical ('${item.sourceText.trim()}')"

                else -> null
            }

            is ExtractedStance ->
                if (item.value in 0.0..1.0) null else "stance value ${item.value} outside [0.0, 1.0]"
        }

        /**
         * [Polarity] named case-insensitively, or `null` when the string names
         * no polarity — the representable-but-invalid case `ExtractedRelation`
         * keeps `polarity` a raw `String` for.
         */
        fun parsePolarity(raw: String): Polarity? =
            Polarity.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

        private fun describe(failure: Throwable): String {
            val message = failure.message
            return if (message.isNullOrBlank()) {
                failure::class.simpleName ?: failure::class.java.name
            } else {
                "${failure::class.simpleName ?: failure::class.java.name}: $message"
            }
        }
    }
}
