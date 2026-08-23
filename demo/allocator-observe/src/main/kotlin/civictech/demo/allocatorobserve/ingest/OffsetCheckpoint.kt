package civictech.demo.allocatorobserve.ingest

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * How many bytes of the spend log's head are hashed into a
 * [CheckpointState.fingerprint].
 *
 * The window exists so the identity check stays O(1) in the size of a log
 * that only grows: hashing the whole consumed prefix on every poll would make
 * each poll cost as much as the entire history. 4 KiB is comfortably more
 * than one JSONL record, so a replaced log almost always differs inside it;
 * see [CheckpointState.fingerprint] for what that "almost" does not cover.
 */
const val FINGERPRINT_WINDOW_BYTES: Int = 4096

/**
 * The persisted position of a spend-log tail reader: how many bytes of the
 * log have been handed to the consumer, plus a fingerprint of the log's head
 * as it looked when that offset was reached.
 *
 * @param offset the byte offset immediately after the last COMPLETE line that
 *   was delivered. Never points into the middle of a line.
 * @param fingerprint hex SHA-256 of the log's first `min(offset,
 *   FINGERPRINT_WINDOW_BYTES)` bytes, captured at the moment the checkpoint
 *   was written.
 *
 *   This is what distinguishes *append* from *replacement* when the file is no
 *   shorter than [offset] — length alone cannot, because a log replaced by a
 *   different log of at least the same size looks exactly like one that grew.
 *   It is deliberately NOT a whole-file hash (see [FINGERPRINT_WINDOW_BYTES]),
 *   so the case it cannot see is a replacement whose length is >= [offset] and
 *   whose first `min(offset, FINGERPRINT_WINDOW_BYTES)` bytes are byte-identical
 *   to the old log's. Note that the compared prefix is the SMALLER of the two:
 *   early in a log's life, with [offset] far below the window, only those few
 *   leading bytes are checked, so the blind spot is widest exactly when the
 *   checkpoint is youngest — it is not bounded at 4 KiB of agreement. For an
 *   append-only, one-writer log a replacement is not a shape the socaity spec
 *   can produce at all; the limit is recorded here rather than hidden because
 *   a future multi-writer or rotating log could.
 */
data class CheckpointState(val offset: Long, val fingerprint: String)

/**
 * Where a tail reader's [CheckpointState] lives.
 *
 * An interface, not just the file class below, for one reason that the
 * feature's crash-ordering rule makes load-bearing: the rule "the checkpoint
 * is written only AFTER the batch it covers has been handed to the consumer"
 * is a statement about the ORDER of two effects, and the only way to assert an
 * order is to observe both effects. A test double implementing this records
 * its writes into the same log the consumer appends to, so the ordering is
 * checked rather than asserted by reading the production code.
 */
interface SpendOffsetStore {

    /** The last-persisted position, or `null` if nothing has been checkpointed yet (read the log from 0). */
    fun read(): CheckpointState?

    /**
     * Persists [state], replacing any prior value.
     *
     * Callers must call this AFTER the batch that reaches [state] has been
     * handed to the consumer, never before: re-delivering a batch after a
     * crash-before-persist is acceptable, skipping one by persisting early is
     * not.
     */
    fun write(state: CheckpointState)
}

/**
 * The file-backed [SpendOffsetStore]: one `spend-offset` file under a
 * caller-supplied run directory, so a restarted reader resumes instead of
 * re-reading the whole spend log.
 *
 * Both the run directory and (in [SpendLogTailReader]) the log path are
 * parameters — no path is hardcoded anywhere (design note fpml.1-D1 on
 * `computenet-fpml.1`: the socaity log syncs over the beads/dolt channel and
 * its concrete location is not decided).
 *
 * Writes are atomic, copying `:demo:beadsmirror`'s
 * `feed/FeedCheckpoint` idiom by example rather than by import (fpml.1-D3;
 * the epic defers a shared connector SPI to CON2, `computenet-rrf`): the new
 * state goes to a temp file in the same directory and is then moved over the
 * checkpoint path with [StandardCopyOption.ATOMIC_MOVE], so a crash mid-write
 * leaves the previous checkpoint intact and never a torn one.
 *
 * The encoding is one line, `"<offset> <hex-sha256>"`. A checkpoint file that
 * is absent, empty, or unparseable reads as `null` — i.e. as "never
 * checkpointed", which re-reads the log from 0. That is the safe direction:
 * re-delivery is idempotent downstream (the fold is keyed by record identity,
 * fpml.1-D2) while trusting a garbled offset would skip bytes forever.
 */
class OffsetCheckpoint(runDir: Path) : SpendOffsetStore {

    private val file: Path = runDir.also { Files.createDirectories(it) }.resolve("spend-offset")

    override fun read(): CheckpointState? {
        if (!Files.exists(file)) return null
        val text = Files.readString(file).trim()
        if (text.isEmpty()) return null
        val parts = text.split(" ")
        if (parts.size != 2) return null
        val offset = parts[0].toLongOrNull() ?: return null
        if (offset < 0) return null
        val fingerprint = parts[1].ifEmpty { return null }
        return CheckpointState(offset, fingerprint)
    }

    override fun write(state: CheckpointState) {
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, "${state.offset} ${state.fingerprint}")
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

/**
 * Hex SHA-256 of the first `min(upTo, FINGERPRINT_WINDOW_BYTES)` bytes of
 * [path], or of the empty byte string when the file is absent or the window
 * is empty.
 *
 * A missing file hashing as "empty window" is intentional: it makes the
 * function total, and a vanished log is handled as its own case by
 * [SpendLogTailReader] before any fingerprint is compared.
 */
internal fun fingerprintHead(path: Path, upTo: Long): String {
    val window = minOf(upTo, FINGERPRINT_WINDOW_BYTES.toLong()).toInt().coerceAtLeast(0)
    val bytes =
        if (window == 0) {
            ByteArray(0)
        } else {
            try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val buffer = ByteBuffer.allocate(window)
                    while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                        // read until the window is full or EOF
                    }
                    buffer.flip()
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
            } catch (_: NoSuchFileException) {
                ByteArray(0)
            }
        }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
