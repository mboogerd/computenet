package civictech.cell.durability

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The format generation this build reads and writes.
 *
 * **Bump this whenever a change alters what a journal's bytes MEAN** — the
 * persisted shape of any cell's [civictech.cell.Stateful] snapshot, the wire
 * encoding of a journaled invocation frame, the layout of a checkpoint /
 * frontier / outlet-wave / baseline record, or the meaning of a record type
 * byte. Do **not** bump for a purely additive change that an older reader
 * tolerates and a newer reader can read — a new record type an older journal
 * simply lacks is the worked example
 * ([JournalCompatibilityTest][civictech.cell.durability.JournalCompatibilityTest]'s
 * `RECORD_OUTLET_WAVE` arms), and AGENTS.md's *"prefer additive encoding"* is
 * why that is the preferred kind of change in the first place.
 *
 * Version `1` is both the current generation and the generation an *unversioned*
 * journal is read as; see [PRE_VERSIONING_FORMAT_VERSION].
 */
const val JOURNAL_FORMAT_VERSION: Int = 1

/**
 * The generation a journal carrying **no** version header is read as.
 *
 * Journals written before versioning existed have no header to inspect, so they
 * cannot be discriminated further than this: they are all read as generation
 * `1`, on the evidence that no persisted-shape change is known to have landed
 * between the oldest journal this repo still exercises (the checked-in
 * `prechange-journal.bin` fixture) and the introduction of the header. That is
 * an assumption about history, not a check on it — a pre-versioning journal
 * written under some *other* shape would be accepted here and would then fail
 * inside a cell's `restore`, which is exactly the failure mode the header
 * exists to replace. It is accepted deliberately: refusing every unversioned
 * journal would also refuse the fixture that is this repo's only evidence that
 * a past change was additive on disk.
 */
const val PRE_VERSIONING_FORMAT_VERSION: Int = 1

/**
 * A journal was written under a format generation this build does not read
 * (`[24-DUR-02]`; AGENTS.md *"preserve binary/wire compatibility"*, which for
 * journals was uncheckable until there was a version to compare — computenet-437w).
 *
 * Raised by [Journal.replay] **before any record is decoded**, so a stale journal
 * is refused by name rather than surfacing as a deserialization or cast error
 * from whichever cell's `restore` a replay happens to reach first.
 */
class JournalFormatMismatch(
    val found: Int,
    val expected: Int,
    val source: String,
) : Exception(
    "journal format version mismatch: $source was written under journal format version $found, " +
        "this build reads version $expected. Journals are NOT migrated across format versions " +
        "(see JOURNAL_FORMAT_VERSION) — a journal at another version is unreadable and must be " +
        "discarded, or replayed by a build at version $found.",
)

/**
 * Append-only record log (spec 24 durability, G-25): the journal half of
 * "state transitions are journaled serializable invocations; replay =
 * recovery" (43 §5). Records are opaque bytes — the durable host writes
 * wire-encoded invocation frames (the same `WireCodec` encoding that crosses
 * the network: a journal is a bridge to disk) and, at checkpoints, snapshot
 * records. [reset] atomically replaces the whole log (checkpoint compaction).
 *
 * ## Cross-version policy: REFUSE. Journals are never migrated.
 *
 * Every journal carries the [JOURNAL_FORMAT_VERSION] of the build that wrote it
 * (in a header, for a journal that has somewhere to put one — see [FileJournal]),
 * and [replay] refuses a journal whose version is not [formatVersion] by throwing
 * [JournalFormatMismatch]. There is no migration path and none is planned: an
 * unreadable journal is discarded, and the state it held is rebuilt from a peer
 * (replication catch-up) or lost.
 *
 * **Why refuse rather than migrate**, decided under computenet-437w: the runtime is
 * experimental, no journal outlives a test run today, and migration machinery would
 * have to be written per shape change, exercised against fixtures nobody has, for
 * state nobody is keeping. Refusal costs one constant and gives the property the
 * absence of a version denied: a stale journal fails with a sentence naming the
 * mismatch instead of a `ClassCastException` from a cell's `restore`. The moment
 * durable state is expected to survive a deployment, THAT is the decision to
 * revisit — and it will be revisitable, because by then the version needed to
 * dispatch a migration will already be on disk.
 */
interface Journal {
    /**
     * The format generation this journal speaks: written into what it writes,
     * required of what it reads. Defaults to the build's [JOURNAL_FORMAT_VERSION];
     * an implementation takes it as a parameter only so a test can stand in for a
     * build at another version.
     */
    val formatVersion: Int get() = JOURNAL_FORMAT_VERSION

    fun append(record: ByteArray)

    /** @throws JournalFormatMismatch if the log was written at another [formatVersion]. */
    fun replay(): List<ByteArray>

    /** Atomically replace the log's contents — the checkpoint/compaction primitive. */
    fun reset(records: List<ByteArray>)
}

/**
 * Deterministic in-memory journal for the simulated host (P1: no filesystem in seeds).
 *
 * Carries no version header and needs none: it dies with the process that made it,
 * so it can only ever be read by the build that wrote it and [JournalFormatMismatch]
 * is unreachable here by construction.
 */
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
 * Length-prefixed records in one append-only file, behind a version header;
 * append syncs to disk before returning (write-ahead), [reset] writes a fresh
 * file and renames it into place (atomic on POSIX). A torn trailing record
 * (crash mid-append) is ignored on replay — the invocation it held was never
 * acknowledged as accepted anyway.
 *
 * ## The header
 *
 * A journal this class writes begins with [MAGIC] (4 bytes) followed by a
 * big-endian `int` [formatVersion]; the length-prefixed records follow. The
 * header is written by whichever of [append] / [reset] first puts bytes in the
 * file, so an empty or absent file has no header and acquires one on its first
 * write.
 *
 * A file that does **not** begin with [MAGIC] is a journal written before
 * versioning existed and is read as [PRE_VERSIONING_FORMAT_VERSION] — the
 * additive half of the change, and what keeps the checked-in
 * `prechange-journal.bin` fixture replayable. The discrimination is safe in
 * practice rather than by construction: a pre-versioning journal's first four
 * bytes are a record length, and to be mistaken for [MAGIC] that length would
 * have to be exactly `0x434E4A4C` — a single 1.1 GB record.
 *
 * ponytail: one file, fsync per append, whole-log replay in memory — segments,
 * group commit, and streaming replay when a real workload's journal hurts.
 */
class FileJournal(
    private val file: File,
    override val formatVersion: Int = JOURNAL_FORMAT_VERSION,
) : Journal {

    companion object {
        /** `CNJL` — the marker that distinguishes a versioned journal from a pre-versioning one. */
        val MAGIC: ByteArray = byteArrayOf(0x43, 0x4E, 0x4A, 0x4C)

        /** [MAGIC] plus the big-endian `int` version that follows it. */
        private const val HEADER_BYTES = 8
    }

    init {
        file.parentFile?.mkdirs()
    }

    @Synchronized
    override fun append(record: ByteArray) {
        val needsHeader = !file.exists() || file.length() == 0L
        FileOutputStream(file, true).use { out ->
            DataOutputStream(out.buffered()).apply {
                if (needsHeader) writeHeader(this)
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
        val skip = readAndCheckHeader() ?: return emptyList()
        val records = mutableListOf<ByteArray>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            input.skipNBytes(skip)
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
                writeHeader(this)
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

    private fun writeHeader(out: DataOutputStream) {
        out.write(MAGIC)
        out.writeInt(formatVersion)
    }

    /**
     * The version this file declares, checked against [formatVersion] before a single
     * record is decoded. Returns how many bytes of header the record reader must skip,
     * or `null` when the file holds nothing at all.
     *
     * @throws JournalFormatMismatch if the declared version is not [formatVersion].
     */
    private fun readAndCheckHeader(): Long? {
        val head = DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = input.readNBytes(MAGIC.size)
            if (!magic.contentEquals(MAGIC)) {
                return@use if (magic.isEmpty()) null else PRE_VERSIONING_FORMAT_VERSION to 0L
            }
            try {
                input.readInt() to HEADER_BYTES.toLong()
            } catch (_: EOFException) {
                // MAGIC written, version not yet on disk: a crash inside the very first
                // append, which acknowledged nothing. Same disposition as a torn record.
                null
            }
        } ?: return null
        val (declared, skip) = head
        if (declared != formatVersion) throw JournalFormatMismatch(declared, formatVersion, file.path)
        return skip
    }
}
