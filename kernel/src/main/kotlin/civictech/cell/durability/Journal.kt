package civictech.cell.durability

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Append-only record log (spec 24 durability, G-25): the journal half of
 * "state transitions are journaled serializable invocations; replay =
 * recovery" (43 §5). Records are opaque bytes — the durable host writes
 * wire-encoded invocation frames (the same `WireCodec` encoding that crosses
 * the network: a journal is a bridge to disk) and, at checkpoints, snapshot
 * records. [reset] atomically replaces the whole log (checkpoint compaction).
 */
interface Journal {
    fun append(record: ByteArray)
    fun replay(): List<ByteArray>

    /** Atomically replace the log's contents — the checkpoint/compaction primitive. */
    fun reset(records: List<ByteArray>)
}

/** Deterministic in-memory journal for the simulated host (P1: no filesystem in seeds). */
class InMemoryJournal : Journal {
    private val records = mutableListOf<ByteArray>()

    @Synchronized
    override fun append(record: ByteArray) {
        records += record
    }

    @Synchronized
    override fun replay(): List<ByteArray> = records.toList()

    @Synchronized
    override fun reset(records: List<ByteArray>) {
        this.records.clear()
        this.records += records
    }
}

/**
 * Length-prefixed records in one append-only file; append syncs to disk
 * before returning (write-ahead), [reset] writes a fresh file and renames it
 * into place (atomic on POSIX). A torn trailing record (crash mid-append) is
 * ignored on replay — the invocation it held was never acknowledged as
 * accepted anyway.
 *
 * ponytail: one file, fsync per append, whole-log replay in memory — segments,
 * group commit, and streaming replay when a real workload's journal hurts.
 */
class FileJournal(private val file: File) : Journal {

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    override fun append(record: ByteArray) {
        FileOutputStream(file, true).use { out ->
            DataOutputStream(out.buffered()).apply {
                writeInt(record.size)
                write(record)
                flush()
            }
            out.fd.sync()
        }
    }

    @Synchronized
    override fun replay(): List<ByteArray> {
        if (!file.exists()) return emptyList()
        val records = mutableListOf<ByteArray>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            while (true) {
                val size = try {
                    input.readInt()
                } catch (_: EOFException) {
                    break
                }
                val record = ByteArray(size)
                try {
                    input.readFully(record)
                } catch (_: EOFException) {
                    break // torn trailing record: never acknowledged, drop it
                }
                records += record
            }
        }
        return records
    }

    @Synchronized
    override fun reset(records: List<ByteArray>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            DataOutputStream(out.buffered()).apply {
                records.forEach {
                    writeInt(it.size)
                    write(it)
                }
                flush()
            }
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
