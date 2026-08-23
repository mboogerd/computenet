package civictech.demo.allocatorobserve.ingest

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
 * One poll's result: the complete lines read, why they were read, and the
 * byte offset they leave the reader at.
 *
 * @param lines the COMPLETE lines read this poll, in file order, newline
 *   stripped. A trailing byte run with no terminating `'\n'` is not included —
 *   see [SpendLogTailReader].
 * @param offset the byte offset immediately after the last complete line in
 *   [lines] (or the unchanged prior offset when [lines] is empty). This is
 *   what the next poll resumes from.
 */
data class TailBatch(
    val reason: TailReason,
    val lines: List<String>,
    val offset: Long,
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
 * 5. Hand the batch to the consumer.
 * 6. **Then** persist the new offset and fingerprint.
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
 * @param logPath the spend log. A parameter, never a hardcoded path
 *   (fpml.1-D1): no real socaity log exists yet and its eventual location is
 *   undecided. It need not exist.
 * @param checkpoint where the position is persisted between polls and across
 *   restarts.
 */
class SpendLogTailReader(
    private val logPath: Path,
    private val checkpoint: SpendOffsetStore,
) {

    /**
     * Reads whatever complete lines are new, hands them to [consume], and only
     * then persists the resulting position.
     *
     * Returns the same [TailBatch] that was handed to [consume], so a caller
     * that needs the outcome for its own bookkeeping does not have to capture
     * it out of the lambda.
     */
    fun poll(consume: (TailBatch) -> Unit): TailBatch {
        val persisted = checkpoint.read()

        if (!Files.isRegularFile(logPath)) {
            return TailBatch(TailReason.LogAbsent, emptyList(), persisted?.offset ?: 0L)
                .also(consume)
        }

        val length =
            try {
                Files.size(logPath)
            } catch (_: NoSuchFileException) {
                // The log vanished between the existence check and the size
                // read. Same situation as step 2, same handling.
                return TailBatch(TailReason.LogAbsent, emptyList(), persisted?.offset ?: 0L)
                    .also(consume)
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

        val (lines, offset) = readCompleteLines(start, length)
        val batch = TailBatch(reason, lines, offset)

        consume(batch)

        // Only now, and only if the position actually moved: an unchanged
        // state would rewrite the same two values on every idle poll.
        val next = CheckpointState(offset, fingerprintHead(logPath, offset))
        if (next != persisted) checkpoint.write(next)

        return batch
    }

    /**
     * Reads `[start, length)` and splits off the complete lines.
     *
     * @return the lines (newline stripped) and the offset just past the last
     *   `'\n'` — equal to [start] when the range holds no newline at all.
     */
    private fun readCompleteLines(start: Long, length: Long): Pair<List<String>, Long> {
        if (length <= start) return emptyList<String>() to start

        val span = (length - start).toInt()
        val bytes =
            FileChannel.open(logPath, StandardOpenOption.READ).use { channel ->
                val buffer = ByteBuffer.allocate(span)
                channel.position(start)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // read until the range is exhausted or EOF (the file may
                    // have shrunk since `length` was observed)
                }
                buffer.flip()
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }

        val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
        if (lastNewline < 0) return emptyList<String>() to start

        // Everything strictly before the last newline is complete-line text;
        // `split` on it yields one entry per terminated line (an empty line
        // included, since "".split("\n") is [""], which is the single empty
        // line a lone "\n" really is).
        val complete = String(bytes, 0, lastNewline, Charsets.UTF_8)
        return complete.split("\n") to (start + lastNewline + 1)
    }
}
