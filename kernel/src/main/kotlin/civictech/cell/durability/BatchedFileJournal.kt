package civictech.cell.durability

import java.io.File

/**
 * A [DurabilityClass.BATCHED] file journal: group commit over the exact bytes
 * [FileJournal] writes (`[KBLK-05]`, `[KBLK-09]`..`[KBLK-14]`, `[KBLK-26]`;
 * `[24-DUR-01]`, `[24-DUR-02]`, `[24-DUR-03]`).
 *
 * ## What it is
 *
 * [FileJournal] fsyncs after every [append]; that fsync is the per-record cost
 * this class amortizes (the kept append handle already removed the open/close
 * cost — computenet-sh8z). Here an [append] hands its framed record to the OS
 * in one `write` and returns; the fsync is issued once [syncEvery]
 * acknowledged records have accumulated since the last one. [sync] forces it
 * early (a clean shutdown or a checkpoint caller); [replay] forces it first;
 * [reset] is exactly [FileJournal.reset] (tmp file, fsync, atomic move).
 *
 * **Format**: byte-identical to [FileJournal] — both delegate every byte to
 * the package's single [JournalFile] encoding (header, big-endian length
 * frame, torn-tail rule, cross-instance header lock), so a log written by
 * either replays through the other to the same record sequence (`[KBLK-10]`)
 * and `HostDurability.recoverFrom` needs no change to read it (`[KBLK-09]`).
 *
 * **Ordering is not relaxed** (`[KBLK-11]`): [append] is `@Synchronized`, runs
 * in call order, and writes to an append-only `O_APPEND` descriptor. Only the
 * *moment of durability* moves; nothing reorders.
 *
 * `syncEvery` is required — there is no default — because the bound IS the
 * guarantee this instance declares (`[KBLK-05]`); a default would let a caller
 * inherit a loss window without choosing it. `syncEvery == 1` is legal and
 * fsyncs every append, like [FileJournal], but still reports [BATCHED]: the
 * class declares the guarantee its *type* offers, not the one a particular
 * argument happens to reach.
 *
 * ## The loss window
 *
 * On a crash, the records appended since the last fsync — at most
 * `syncEvery - 1` acknowledged records, **plus whatever the OS page cache had
 * not yet written back, which this class does not control** (see "No physical
 * loss bound" below) — may be absent on replay. What survives is a **prefix**
 * of the append sequence: the file is append-only and each record is one
 * `write`, so a truncated file loses a tail, never a middle, and the reader
 * drops a torn trailing record rather than misreading it (`[KBLK-12]`).
 *
 * ## Named limitation: the Effectful window (computenet-t6b.2-D1, `[KBLK-14]`)
 *
 * An `Effectful` inlet's processed-frontier advance (`RECORD_FRONTIER`, written
 * by `HostDurability.advanceAndJournalFrontier` AFTER the handler — and so the
 * effect — ran) or baseline discharge (`RECORD_BASELINE`) can sit in the
 * unsynced tail when the process dies. It is then lost, so on `recoverFrom`
 * the frame it guarded replays against a frontier that does not cover it and
 * **the effect re-fires**. That is a duplicate — the failure mode `[24-DUR-07]`
 * chose over omission ("firing is loud and bounded") — not an omission.
 * `[24-DUR-05]` is NOT weakened for a [DurabilityClass.SYNCHRONOUS] journal;
 * it is weakened, for this class only, by exactly this window.
 *
 * This class does **not** refuse to serve an `Effectful` cell. A journal sees
 * bytes, not cells, so a refusal would have to be the kernel vetoing a spawn
 * on the deployment's behalf, which `[KBLK-07]` forbids; the deployment reads
 * the host's per-class journal counts and refuses itself. The limitation is
 * also recorded in `concord/corpus/DISPUTES.md` (§"W4-B durability"), and no
 * corpus scenario asserts the re-fire, because a passing scenario for it would
 * assert a weaker rule than `[24-DUR-05]` as though it were decided.
 *
 * ## No physical loss bound is claimed (`[KBLK-26]`)
 *
 * Nothing in this repository proves that `syncEvery - 1` IS the bound on what
 * a crash loses. A JVM-level kill leaves the page cache intact, so it proves
 * only that the *reader* recovers a prefix of whatever reached the file; it
 * says nothing about what a power loss would leave. Establishing the physical
 * bound needs power loss or a fault-injecting filesystem, which no test here
 * has. `BatchedFileJournalTest`'s truncation sweep is accordingly documented as
 * proving reader prefix-consistency only.
 *
 * Nothing in the repo constructs this class except its tests; it is not wired
 * into any host default, flag or property (`[KBLK-03]`).
 */
class BatchedFileJournal(
    file: File,
    /**
     * Records acknowledged since the last fsync before the next [append]
     * forces one. Required — no default (`[KBLK-05]`); must be `>= 1`.
     */
    val syncEvery: Int,
    override val formatVersion: Int = JOURNAL_FORMAT_VERSION,
) : Journal {

    init {
        require(syncEvery >= 1) { "syncEvery must be >= 1 (got $syncEvery): the batch bound is the declared loss window" }
    }

    override val durability: DurabilityClass = DurabilityClass.BATCHED

    private val log = JournalFile(file, formatVersion)

    /** Records written through the kept handle and acknowledged, but not yet fsync'd. */
    private var pending = 0

    /**
     * How many fsyncs of appended records this instance has issued — the
     * sync-accounting seam for tests. Excludes the one-off header fsync and
     * [reset]'s own fsync, which are [JournalFile]'s and identical to
     * [FileJournal]'s.
     */
    @get:Synchronized
    internal var recordSyncs: Long = 0
        private set

    @Synchronized
    override fun append(record: ByteArray) {
        log.write(record)
        pending += 1
        if (pending >= syncEvery) forcePending()
    }

    /**
     * Force every acknowledged-but-unsynced record to stable storage now. For a
     * clean shutdown or a checkpoint caller; [Journal] has no member for it on
     * purpose (a `SYNCHRONOUS` journal has nothing to force).
     */
    @Synchronized
    fun sync() {
        if (pending > 0) forcePending()
    }

    /** Syncs this instance's pending appends, then reads exactly as [FileJournal.replay]. */
    @Synchronized
    override fun replay(): List<ByteArray> {
        sync()
        return log.replay()
    }

    @Synchronized
    override fun reset(records: List<ByteArray>) {
        log.reset(records)
        pending = 0
    }

    private fun forcePending() {
        log.force()
        pending = 0
        recordSyncs += 1
    }
}
