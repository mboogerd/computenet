package civictech.demo.beadsmirror.feed

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists the last-processed Dolt commit hash under a caller-supplied run
 * directory, so a restarted [DoltFeedPoller] resumes instead of re-reading
 * from genesis.
 *
 * The file format is a bare hash, no newline required (trimmed on read).
 * Writes are atomic: the new hash is written to a temp file in the same
 * directory, then moved over the checkpoint file with [StandardCopyOption.ATOMIC_MOVE].
 * A crash between those two steps leaves the previous checkpoint in place —
 * never a torn/partial file — because the temp file is never itself the
 * checkpoint path.
 */
class FeedCheckpoint(runDir: Path) {

    private val file: Path = runDir.also { Files.createDirectories(it) }.resolve("checkpoint")

    /** The last-persisted commit hash, or `null` if nothing has been checkpointed yet (resume from genesis). */
    fun read(): String? =
        if (Files.exists(file)) Files.readString(file).trim().ifEmpty { null } else null

    /**
     * Persists [commitHash] as the new checkpoint, replacing any prior value.
     *
     * Callers must call this AFTER the record batch belonging to [commitHash]
     * has been handed to the consumer, never before: re-delivery of a batch
     * on crash-before-persist is acceptable and by design (downstream
     * dot-minting is deterministic from feed position, so replay is
     * idempotent under OR-map merge) — skipping a batch by persisting early
     * is not.
     */
    fun write(commitHash: String) {
        val tmp = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, commitHash)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
