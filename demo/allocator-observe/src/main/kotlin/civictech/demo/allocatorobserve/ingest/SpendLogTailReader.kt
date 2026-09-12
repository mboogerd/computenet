package civictech.demo.allocatorobserve.ingest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Why the spend log had to be re-read from offset 0 rather than resumed.
 *
 * Carried as data, not as log prose, so the wiring task's fold can react to
 * it (converge the materialized set on the re-read content) and a test can
 * assert it — the same shape `:demo:beadsmirror`'s
 * `baseline/RebaselineReason` uses, copied by example rather than imported
 * (fpml.1-D3).
 */
sealed interface ReBaselineCause {

    /**
     * The log is now SHORTER than the checkpoint's offset, so the bytes the
     * checkpoint refers to no longer exist: the file was truncated or replaced
     * by a smaller one.
     */
    data class Truncated(val checkpointOffset: Long, val currentLength: Long) : ReBaselineCause

    /**
     * The log is long enough to hold the checkpoint's offset, but its head no
     * longer hashes to the fingerprint captured when that offset was reached —
     * so the bytes at those positions are not the bytes that were consumed.
     * This is the case length alone cannot see (see [CheckpointState.fingerprint]).
     */
    data class Replaced(
        val checkpointOffset: Long,
        val expectedFingerprint: String,
        val actualFingerprint: String,
    ) : ReBaselineCause
}

/**
 * What a [SpendLogTailReader.poll] did, as a typed value the caller can
 * `when` over exhaustively.
 *
 * The point of the type is that [ReBaselined] is distinguishable from
 * [Resumed]: an ordinary resume delivers only new bytes and leaves earlier
 * state alone, while a re-baseline delivers the log's WHOLE current content
 * from offset 0 and tells the consumer that everything it holds from earlier
 * polls is superseded by this batch.
 */
sealed interface TailReason {

    /** No checkpoint existed: this reader has never consumed this log. The batch starts at offset 0. */
    data object FirstStart : TailReason

    /**
     * The log is the same file, still at least as long as [fromOffset], with a
     * matching head fingerprint: the batch holds only bytes at or after
     * [fromOffset] and nothing earlier was re-read.
     */
    data class Resumed(val fromOffset: Long) : TailReason

    /**
     * The log was truncated or replaced ([cause]), so it was re-read from
     * offset 0 and the batch is its full current content. The consumer must
     * converge on this batch rather than adding to what it already holds.
     */
    data class ReBaselined(val cause: ReBaselineCause) : TailReason

    /**
     * No log file exists at the configured path. Not an error: the log syncs
     * over the beads/dolt channel and may simply not have arrived yet. The
     * batch is empty and the checkpoint is left exactly as it was.
     */
    data object LogAbsent : TailReason
}

/**
 * ONE HAND-OFF of a poll: a bounded run of complete lines, why they were read,
 * and the byte offset they leave the reader at.
 *
 * A poll hands the consumer one *or more* of these — see
 * [SpendLogTailReader.poll]. A batch is therefore a slice of the poll, not the
 * poll: only the batch with [last] set closes it.
 *
 * @param lines COMPLETE lines, in file order, newline stripped, at most
 *   [SpendLogTailReader.maxLinesPerBatch] of them. A trailing byte run with no
 *   terminating `'\n'` is not included — see [SpendLogTailReader].
 * @param offset the byte offset immediately after the last complete line in
 *   [lines] (or the offset this hand-off started at, when [lines] is empty).
 *   On the [last] batch this is what the next poll resumes from; on an earlier
 *   one it is how far the poll had got, and is NOT yet persisted.
 * @param last whether this is the final hand-off of the poll. Exactly one batch
 *   per poll carries `true`, it is always the last one delivered, and a consumer
 *   that must see the whole poll before acting (a re-baseline reconcile, say)
 *   acts on it. A poll that reads nothing at all still delivers one empty batch
 *   with `last = true`.
 */
data class TailBatch(
    val reason: TailReason,
    val lines: List<String>,
    val offset: Long,
    val last: Boolean,
)

/**
 * What a whole [SpendLogTailReader.poll] did, in aggregate — returned rather
 * than handed over, because the lines themselves are deliberately not retained
 * to be returned.
 *
 * @param lineCount the total complete lines delivered across every hand-off.
 * @param offset the offset the poll leaves the reader at: the one persisted.
 * @param handOffs how many times [SpendLogTailReader.poll] invoked its consumer.
 *   Always at least 1.
 */
data class TailSummary(
    val reason: TailReason,
    val lineCount: Long,
    val offset: Long,
    val handOffs: Int,
)

/**
 * The byte-level tail reader for the socaity JSONL spend log
 * (`computenet-fpml.1.2`): incremental reads of only new complete lines, a
 * persisted byte-offset checkpoint so a restart resumes, and detection of
 * truncation/replacement that re-reads from offset 0 under a typed
 * [TailReason.ReBaselined].
 *
 * Deliberately **pure file mechanics**. It does not parse records (that is
 * `classifySpendLine`), does not count failures, does not touch kernel cells,
 * and does not schedule itself — [poll] is called explicitly and the cadence
 * belongs to the wiring task.
 *
 * ## What one [poll] does, and why in that order
 *
 * 1. Read the persisted [CheckpointState] (absent → [TailReason.FirstStart]).
 * 2. If the log file does not exist, report [TailReason.LogAbsent] with an
 *    empty batch and touch nothing.
 * 3. Decide resume-vs-re-baseline: current length < checkpoint offset →
 *    [ReBaselineCause.Truncated]; otherwise head fingerprint mismatch →
 *    [ReBaselineCause.Replaced]; otherwise resume at the checkpoint offset.
 * 4. Read `[start, length)`, keep only the bytes up to and including the last
 *    `'\n'`, and split those into lines.
 * 5. Hand those lines to the consumer, in bounded batches of at most
 *    [maxLinesPerBatch], as they are scanned.
 * 6. **Then**, after the LAST hand-off has returned, persist the new offset and
 *    fingerprint.
 *
 * **5 before 6 is the crash-ordering rule** and is why the consumer is a
 * parameter of [poll] rather than the caller's business after it returns. A
 * crash between them re-delivers the batch on the next start, which the
 * downstream fold absorbs idempotently (it is keyed by record identity,
 * fpml.1-D2). The reverse order would advance the checkpoint past bytes no
 * consumer ever saw, and nothing later can recover them. A consumer that
 * throws likewise leaves the checkpoint untouched, so the batch is retried.
 *
 * ## Partial lines
 *
 * A trailing byte run with no `'\n'` is left unconsumed and the offset does
 * not advance past it; when the newline later arrives the completed line is
 * delivered exactly once. The socaity v1 spec pins appends line-atomic, so
 * this should never fire in production — but the guard costs one index
 * lookup and makes the reader safe against a torn observation (an
 * `O_APPEND`-less writer, a mid-flight file copy, a filesystem that does not
 * honour the atomicity).
 *
 * ## Monotonicity
 *
 * While the log only grows, the persisted offset only advances and no byte is
 * ever read twice. The single exception is a re-baseline, where re-reading
 * from 0 is the whole point.
 *
 * ## Bounded by the CHUNK, not by the range and not by the log
 *
 * [readCompleteLines] no longer buffers the whole `[start, length)` range at
 * once. It reads the range through a reusable [chunkSize]-byte `ByteBuffer`,
 * emitting each complete line as its terminating `'\n'` is scanned and
 * carrying any trailing partial line across the chunk boundary, so the read
 * buffer is a constant `chunkSize` bytes however large the range is. Nothing in
 * the read path narrows a `Long` range to an `Int` any more: the only `Int` is
 * the chunk size itself, which is an `Int` by construction.
 *
 * That removes the three regimes this class used to document and
 * `computenet-v5c7` was filed for — a whole-file read (a
 * [TailReason.FirstStart] with no checkpoint, or any
 * [TailReason.ReBaselined]) of a range in `[2 GiB, 4 GiB)` raising
 * `IllegalArgumentException` out of [poll], a range of exactly `4 GiB` reading
 * zero bytes and reporting an empty batch at an unchanged offset, and a range
 * above `4 GiB` delivering a *prefix* and advancing the checkpoint into it.
 * None of the three is reachable now: the loop is driven by `Long` arithmetic
 * against `length - start` and terminates only when the range is consumed or
 * the channel reports EOF.
 *
 * ## Bounded by the BATCH, not by the range and not by the log
 *
 * [poll] no longer hands the consumer one list of the range's whole line
 * content. It invokes the consumer repeatedly with at most [maxLinesPerBatch]
 * lines at a time as they are scanned, dropping its reference to each batch's
 * lines before assembling the next, so a whole-file read of an arbitrarily
 * large log holds a bounded number of lines at once in the reader
 * (`computenet-xs5u`). The checkpoint is still written strictly after the LAST
 * hand-off returns, so the crash-ordering rule is unchanged and a consumer that
 * throws on ANY hand-off — the first, the last, or one in between — leaves the
 * checkpoint exactly as it was and the whole range is retried.
 *
 * **What that does and does not bound, stated where the bound lives:**
 *
 * - **Bounded**: the lines *this reader* holds — at most [maxLinesPerBatch]
 *   plus the carry, however large the range is.
 * - **NOT bounded by this class**: what the CONSUMER retains. A consumer that
 *   accumulates every batch into one collection has simply moved the
 *   materialization, not removed it; the bound is a contract the consumer has
 *   to honour too (`SpendLogIngester` does — it folds each batch into its
 *   `SetCell` and retains no line).
 * - **Still unbounded, at a much smaller scale**: the partial-line carry grows
 *   to the length of the longest line in the range, because a line is only
 *   emitted once its newline is seen. A line longer than [chunkSize] is
 *   delivered correctly — assembled across as many chunks as it needs — but is
 *   held whole while that happens. At the socaity v1 record width (~130 bytes)
 *   this is nothing; a log with no newlines at all is the pathological case.
 *   This bound is NOT closed by the batching above and survives deliberately.
 *
 * The surviving carry bound fails as `OutOfMemoryError`, which (like every
 * other failure in the read) precedes the hand-off it would have been part of
 * and therefore precedes the checkpoint write — so the range is retried on the
 * next poll rather than skipped.
 *
 * @param logPath the spend log. A parameter, never a hardcoded path
 *   (fpml.1-D1): no real socaity log exists yet and its eventual location is
 *   undecided. It need not exist.
 * @param checkpoint where the position is persisted between polls and across
 *   restarts.
 * @param chunkSize the size in bytes of the single reusable read buffer. A
 *   parameter so the chunk-boundary cases — a line straddling a boundary, a
 *   boundary landing exactly on a newline, a line longer than one chunk — are
 *   testable at a size a unit test can actually build a fixture for; a 4 GiB
 *   fixture is not a unit test. Production has no reason to change it.
 * @param maxLinesPerBatch the most lines handed to the consumer in one
 *   [TailBatch]. A parameter for the same reason [chunkSize] is: it makes the
 *   multi-hand-off path reachable from a unit test over a fixture a temp dir
 *   can hold. It bounds line COUNT rather than bytes because a line's length is
 *   not known until its newline is scanned; at the socaity v1 record width
 *   (~130 bytes) the default is a few hundred KiB of line content resident.
 */
class SpendLogTailReader(
    private val logPath: Path,
    private val checkpoint: SpendOffsetStore,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val maxLinesPerBatch: Int = DEFAULT_MAX_LINES_PER_BATCH,
) {

    init {
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
        require(maxLinesPerBatch > 0) { "maxLinesPerBatch must be positive, was $maxLinesPerBatch" }
    }

    /**
     * Reads whatever complete lines are new, hands them to [consume] in bounded
     * batches, and only then persists the resulting position.
     *
     * [consume] is invoked **one or more times** — once per at most
     * [maxLinesPerBatch] lines, and always at least once, with the final
     * invocation carrying [TailBatch.last]`= true`. Returns a [TailSummary] of
     * the whole poll; the lines themselves are not returned, because retaining
     * them to return would reinstate exactly the whole-range materialization
     * the batching exists to remove (`computenet-xs5u`).
     */
    fun poll(consume: (TailBatch) -> Unit): TailSummary {
        val persisted = checkpoint.read()

        if (!Files.isRegularFile(logPath)) {
            return absent(persisted, consume)
        }

        val length =
            try {
                Files.size(logPath)
            } catch (_: NoSuchFileException) {
                // The log vanished between the existence check and the size
                // read. Same situation as step 2, same handling.
                return absent(persisted, consume)
            }

        val reason: TailReason
        val start: Long
        when {
            persisted == null -> {
                reason = TailReason.FirstStart
                start = 0L
            }

            length < persisted.offset -> {
                reason = TailReason.ReBaselined(ReBaselineCause.Truncated(persisted.offset, length))
                start = 0L
            }

            else -> {
                val head = fingerprintHead(logPath, persisted.offset)
                if (head != persisted.fingerprint) {
                    reason =
                        TailReason.ReBaselined(
                            ReBaselineCause.Replaced(persisted.offset, persisted.fingerprint, head),
                        )
                    start = 0L
                } else {
                    reason = TailReason.Resumed(persisted.offset)
                    start = persisted.offset
                }
            }
        }

        var lineCount = 0L
        var handOffs = 0
        var offset = start
        streamCompleteLines(start, length) { lines, at, last ->
            lineCount += lines.size
            handOffs++
            offset = at
            consume(TailBatch(reason, lines, at, last))
        }

        // Only now — after the LAST hand-off has RETURNED — and only if the
        // position actually moved: an unchanged state would rewrite the same two
        // values on every idle poll. A consumer that throws on any hand-off,
        // first or last, never reaches this line, so the range is retried.
        val next = CheckpointState(offset, fingerprintHead(logPath, offset))
        if (next != persisted) checkpoint.write(next)

        return TailSummary(reason, lineCount, offset, handOffs)
    }

    /** The one empty hand-off an absent log gets, plus its summary. */
    private fun absent(persisted: CheckpointState?, consume: (TailBatch) -> Unit): TailSummary {
        val offset = persisted?.offset ?: 0L
        consume(TailBatch(TailReason.LogAbsent, emptyList(), offset, last = true))
        return TailSummary(TailReason.LogAbsent, 0L, offset, handOffs = 1)
    }

    /**
     * Reads `[start, length)` in [chunkSize]-byte chunks, splits off the
     * complete lines, and emits them in runs of at most [maxLinesPerBatch].
     *
     * The range is `Long` throughout — nothing narrows it — so the size of the
     * range places no bound on correctness; see the class KDoc's "Bounded by
     * the CHUNK". A line whose bytes straddle a chunk boundary is accumulated
     * in a `ByteArrayOutputStream` carry across as many chunks as it spans and
     * emitted exactly once, when its newline is scanned. `'\n'` (`0x0A`) never
     * occurs inside a multi-byte UTF-8 sequence, so scanning for it bytewise
     * cannot split a character, and decoding per line is equivalent to decoding
     * the whole complete-line region at once.
     *
     * The pending list is REPLACED rather than cleared at each emission, so this
     * method holds no reference to a batch it has already emitted: what a
     * consumer keeps is the consumer's business, but nothing here keeps it. That
     * is the whole residency property (`computenet-xs5u`).
     *
     * [emit] is called at least once. Its arguments are the lines (newline
     * stripped), the offset just past the last `'\n'` emitted so far — equal to
     * [start] while no newline has been seen — and whether this is the final
     * emission of the range.
     */
    private fun streamCompleteLines(
        start: Long,
        length: Long,
        emit: (lines: List<String>, offset: Long, last: Boolean) -> Unit,
    ) {
        if (length <= start) {
            emit(emptyList(), start, true)
            return
        }

        var pending = mutableListOf<String>()
        val carry = ByteArrayOutputStream()
        val span = length - start
        var scanned = 0L
        // Bytes of the range up to and including the last '\n' seen. The offset
        // never advances past this, so a trailing partial line is left for a
        // later poll (class KDoc, "Partial lines").
        var consumed = 0L

        FileChannel.open(logPath, StandardOpenOption.READ).use { channel ->
            channel.position(start)
            val buffer = ByteBuffer.allocate(chunkSize)
            while (scanned < span) {
                buffer.clear()
                // Never read past the observed `length`: the file may have been
                // appended to since, and those bytes belong to a later poll.
                val remaining = span - scanned
                if (remaining < chunkSize.toLong()) buffer.limit(remaining.toInt())
                val read = channel.read(buffer)
                // EOF (the file shrank since `length` was observed), or a
                // pathological zero-byte read: stop rather than spin.
                if (read <= 0) break
                buffer.flip()
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

                var from = 0
                while (from < bytes.size) {
                    val newline = indexOfNewline(bytes, from)
                    if (newline < 0) break
                    carry.write(bytes, from, newline - from)
                    pending += carry.toString(Charsets.UTF_8)
                    carry.reset()
                    consumed = scanned + newline + 1
                    from = newline + 1
                    if (pending.size == maxLinesPerBatch) {
                        // Not `last`: the range is not finished until the read
                        // loop is, and the checkpoint must not be written on a
                        // hand-off that still has bytes behind it.
                        emit(pending, start + consumed, false)
                        pending = mutableListOf()
                    }
                }
                carry.write(bytes, from, bytes.size - from)
                scanned += bytes.size
            }
        }

        // Always exactly one final emission, even when it is empty: it is what
        // closes the poll for a consumer that reconciles on `last`, and its
        // offset is the one that gets persisted.
        emit(pending, start + consumed, true)
    }

    private fun indexOfNewline(bytes: ByteArray, from: Int): Int {
        var i = from
        while (i < bytes.size) {
            if (bytes[i] == NEWLINE) return i
            i++
        }
        return -1
    }

    companion object {
        /**
         * 1 MiB: large enough that a steady-state poll reads its whole (tiny)
         * append in one chunk, small enough that the read buffer is never a
         * consideration however big the log gets.
         */
        const val DEFAULT_CHUNK_SIZE: Int = 1 shl 20

        /**
         * 4096 lines: at the socaity v1 record width (~130 bytes) a few hundred
         * KiB of line content resident per hand-off, and large enough that a
         * steady-state poll — a handful of appended records — is still exactly
         * one hand-off, so the common case is unchanged by the batching.
         */
        const val DEFAULT_MAX_LINES_PER_BATCH: Int = 4096

        private const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
