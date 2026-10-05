package civictech.economy.persist

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64

/**
 * Persistence boundary for budget checkpoints. The wall clock is deliberately owned here,
 * not by the ledger, and is injected so `[ECO1-BUD-07]` remains deterministic.
 */
interface BudgetCheckpointStore {
    fun write(scope: String, state: LedgerState)
    fun read(scope: String): CheckpointRead
    fun wallMillis(): Long
}

/** Machine-distinguishable reasons a checkpoint store refuses its configuration or a write. */
enum class CheckpointStoreRefusal {
    DIRECTORY_MISSING,
    NOT_A_DIRECTORY,
    UNWRITABLE,
}

/** A loud checkpoint-store refusal, naming the path and reason but never checkpoint bytes. */
class CheckpointStoreRefusedException(
    val path: Path,
    val reason: CheckpointStoreRefusal,
    val detail: String,
    cause: Throwable? = null,
) : IllegalStateException("refusing budget checkpoint [$reason] at $path: $detail", cause)

/**
 * One strict JSON checkpoint per ledger scope under [directory]. Scope file names are the
 * URL-safe base64 encoding of the scope's UTF-8 bytes, so characters that a sanitizer might
 * merge remain distinct. Writes replace the target only through an atomic move; a crashed
 * write can leave only a sibling `.tmp`, which [read] never consults.
 */
class FileBudgetCheckpointStore(
    private val directory: Path,
    private val wallMillis: () -> Long,
) : BudgetCheckpointStore {

    private val json = Json { ignoreUnknownKeys = false }

    override fun wallMillis(): Long = wallMillis.invoke()

    override fun read(scope: String): CheckpointRead {
        requireDirectory()
        val file = checkpointFile(scope)
        if (!Files.exists(file)) return CheckpointRead.Missing

        return try {
            val body = Files.readString(file, StandardCharsets.UTF_8)
            CheckpointRead.Present(json.decodeFromString<LedgerState>(body))
        } catch (e: IOException) {
            CheckpointRead.Unreadable("${e::class.simpleName}: ${e.message}")
        } catch (e: SerializationException) {
            CheckpointRead.Unreadable("${e::class.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            CheckpointRead.Unreadable("${e::class.simpleName}: ${e.message}")
        } catch (e: SecurityException) {
            CheckpointRead.Unreadable("${e::class.simpleName}: ${e.message}")
        }
    }

    override fun write(scope: String, state: LedgerState) {
        requireDirectory()
        val file = checkpointFile(scope)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        val body = json.encodeToString(state)

        try {
            Files.writeString(temporary, body, StandardCharsets.UTF_8, CREATE, TRUNCATE_EXISTING, WRITE)
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw unwritable(file, e)
        } catch (e: SecurityException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw unwritable(file, e)
        }
    }

    private fun requireDirectory() {
        if (!Files.exists(directory)) {
            throw CheckpointStoreRefusedException(
                directory,
                CheckpointStoreRefusal.DIRECTORY_MISSING,
                "configured checkpoint directory does not exist",
            )
        }
        if (!Files.isDirectory(directory)) {
            throw CheckpointStoreRefusedException(
                directory,
                CheckpointStoreRefusal.NOT_A_DIRECTORY,
                "configured checkpoint path is not a directory",
            )
        }
    }

    private fun checkpointFile(scope: String): Path {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(scope.toByteArray(StandardCharsets.UTF_8))
        return directory.resolve("scope-$encoded.json")
    }

    private fun unwritable(file: Path, cause: Throwable) =
        CheckpointStoreRefusedException(
            file,
            CheckpointStoreRefusal.UNWRITABLE,
            "checkpoint could not be written and atomically moved into place",
            cause,
        )
}
