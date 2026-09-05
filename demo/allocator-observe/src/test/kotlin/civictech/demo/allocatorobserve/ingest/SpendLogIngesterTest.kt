package civictech.demo.allocatorobserve.ingest

import civictech.cell.data.SetCell
import civictech.demo.allocatorobserve.SpendRecord
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The feature's end-to-end examples (`computenet-fpml.1`), exercised through
 * the wired [SpendLogIngester] — reader, classifier and fold together over a
 * real file in a temp dir. The parts in isolation are covered by
 * `SpendLineClassifierTest` (fpml.1.1) and `SpendLogTailReaderTest` /
 * `OffsetCheckpointTest` (fpml.1.2); these tests exist to prove the *wiring*,
 * so every one of them drives [SpendLogIngester.poll] against bytes on disk.
 *
 * No path is hardcoded (fpml.1-D1): the log and the run dir are JUnit temp
 * paths handed to the constructor, and `NoHardcodedLogPathTest` checks the
 * production sources keep it that way.
 */
class SpendLogIngesterTest {

    @TempDir
    lateinit var dir: Path

    private val log: Path get() = dir.resolve("spend.jsonl")
    private val runDir: Path get() = dir.resolve("run")

    private fun line(
        project: String = "socaity",
        machine: String = "MacBoo",
        workItem: String = "socaity-fqf",
        started: String = "2026-08-23T09:00:00Z",
        ended: String = "2026-08-23T09:30:00Z",
    ): String =
        """{"v":1,"project":"$project","machine":"$machine","work_item":"$workItem",""" +
            """"started":"$started","ended":"$ended"}"""

    private fun record(
        project: String = "socaity",
        machine: String = "MacBoo",
        workItem: String = "socaity-fqf",
        started: String = "2026-08-23T09:00:00Z",
        ended: String = "2026-08-23T09:30:00Z",
    ): SpendRecord = SpendRecord(1, project, machine, workItem, started, ended)

    private fun append(vararg lines: String) {
        Files.writeString(
            log,
            lines.joinToString("") { "$it\n" },
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun ingester(
        logPath: Path = log,
        run: Path = runDir,
        fold: SetCell<SpendRecord> = SetCell(),
    ) = SpendLogIngester(logPath, run, fold)

    /**
     * Feature example 1: "Given an empty log file, When three valid v1 lines
     * are appended, Then the record set has 3 entries and failure counts are
     * all zero."
     */
    @Test
    fun `three appended valid lines become three records with no failures`() {
        Files.createFile(log)
        val ingester = ingester()

        // The empty log really is observed as empty before anything is appended.
        ingester.poll().added shouldBe 0
        ingester.view() shouldBe emptySet()

        append(line(workItem = "a"), line(workItem = "b"), line(workItem = "c"))

        val outcome = ingester.poll()
        outcome.added shouldBe 3
        outcome.removed shouldBe 0
        ingester.view() shouldBe setOf(record(workItem = "a"), record(workItem = "b"), record(workItem = "c"))
        ingester.failures shouldBe SpendIngestFailures(malformed = 0, unknownVersion = 0)
        ingester.failures.total shouldBe 0L
    }

    /**
     * Feature example 2: "Given a checkpoint at EOF, When the process restarts
     * and two more lines are appended, Then only the new bytes are read and the
     * set has 5 entries" — plus the feature's restart-equality rule: the set
     * equals the set from an uninterrupted run over the same log.
     *
     * The restart is a NEW ingester (and so a new tail reader and a new
     * checkpoint reader) over the same run dir. What crosses the restart on
     * disk is the byte offset; the fold is handed across because the cell's own
     * durability is the kernel `Stateful` seam, which this feature does not
     * wire up — see [SpendLogIngester]'s `records` parameter.
     *
     * **A malformed line sits in the pre-restart segment on purpose.** Re-reading
     * a valid line is invisible in the fold (set semantics make it a no-op) and
     * invisible in `added` (which is computed against live membership), so
     * neither can witness "only the new bytes were read". A *bad* line can: the
     * restarted ingester's failure counters start at zero and count
     * classification attempts, so if the reader had re-read from offset 0 the
     * resumed ingester would have re-counted it. Measured: without this
     * assertion a reader mutated to `start = 0L` while still reporting
     * `Resumed(offset)` passes this test.
     */
    @Test
    fun `a restarted ingester resumes at the checkpoint, reads only new bytes, and matches an uninterrupted run`() {
        append(line(workItem = "a"), "not json", line(workItem = "b"), line(workItem = "c"))

        val fold = SetCell<SpendRecord>()
        val first = ingester(fold = fold)
        first.poll().added shouldBe 3
        first.failures shouldBe SpendIngestFailures(malformed = 1, unknownVersion = 0)
        val offsetAtRestart = Files.size(log)

        // --- restart ---------------------------------------------------------
        val resumed = ingester(fold = fold)
        append(line(workItem = "d"), line(workItem = "e"))

        val outcome = resumed.poll()
        // Only the new bytes, on two independent witnesses: the reader reports
        // resuming at exactly the offset the first ingester's checkpoint left,
        // and the restarted ingester never classified the pre-restart segment —
        // had it re-read from 0 it would have re-counted the malformed line
        // there.
        outcome.reason shouldBe TailReason.Resumed(offsetAtRestart)
        resumed.failures shouldBe SpendIngestFailures(malformed = 0, unknownVersion = 0)
        outcome.failures shouldBe SpendIngestFailures(malformed = 0, unknownVersion = 0)
        outcome.added shouldBe 2
        outcome.removed shouldBe 0

        val afterRestart = resumed.view()
        afterRestart.size shouldBe 5

        // --- the same log, ingested in one uninterrupted run -----------------
        val uninterrupted = ingester(run = dir.resolve("run-uninterrupted"), fold = SetCell())
        uninterrupted.poll().added shouldBe 5

        afterRestart shouldBe uninterrupted.view()
    }

    /**
     * Feature example 3: "Given a log truncated to its first line, When the
     * poller next observes it, Then re-baseline yields a set of exactly 1
     * entry" — and the stale records are *removed*, not merely superseded, so
     * they are absent from the materialized set.
     *
     * The second half is the feature's idempotence claim: reconciling twice
     * against the same content changes nothing. A second re-baseline is forced
     * by planting a stale checkpoint (an offset past EOF), which is the same
     * signal a truncation produces — so the ingester runs the reconcile path
     * again over byte-identical content.
     */
    @Test
    fun `truncation re-baselines the fold onto the current file content, idempotently`() {
        append(line(workItem = "a"), line(workItem = "b"), line(workItem = "c"))
        val ingester = ingester()
        ingester.poll()
        ingester.view().size shouldBe 3

        // Truncate to the first line only.
        Files.writeString(log, line(workItem = "a") + "\n")

        val rebaselined = ingester.poll()
        rebaselined.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
        rebaselined.added shouldBe 0 // "a" was already in the fold
        rebaselined.removed shouldBe 2
        ingester.view() shouldBe setOf(record(workItem = "a"))

        // Idempotence: force the reconcile path a second time over the same
        // bytes and nothing moves.
        OffsetCheckpoint(runDir).write(CheckpointState(Files.size(log) + 4096, "0".repeat(64)))
        val again = ingester.poll()
        again.reason.shouldBeInstanceOf<TailReason.ReBaselined>()
        again.added shouldBe 0
        again.removed shouldBe 0
        ingester.view() shouldBe setOf(record(workItem = "a"))
    }

    /**
     * Feature example 4: "Given a line 'not json' and a line with v:99
     * interleaved with valid lines, When ingested, Then counts show 1 malformed
     * and 1 unknown-version and all valid lines are present."
     *
     * The interleaving is the point: the bad lines sit *between* good ones, so a
     * reader that stopped at the first failure would lose the records after it.
     */
    @Test
    fun `malformed and unknown-version lines are counted per reason and never stop ingestion`() {
        append(
            line(workItem = "a"),
            "not json",
            line(workItem = "b"),
            """{"v":99,"project":"socaity","machine":"MacBoo"}""",
            line(workItem = "c"),
        )

        val ingester = ingester()
        val outcome = ingester.poll()

        outcome.failures shouldBe SpendIngestFailures(malformed = 1, unknownVersion = 1)
        ingester.failures shouldBe SpendIngestFailures(malformed = 1, unknownVersion = 1)
        ingester.failures.total shouldBe 2L

        // Every valid line's record is present — including "c", which follows
        // both failures.
        ingester.view() shouldBe setOf(record(workItem = "a"), record(workItem = "b"), record(workItem = "c"))
        outcome.added shouldBe 3

        // Ingestion continues on the next poll too: the failures did not wedge
        // the checkpoint.
        append(line(workItem = "d"))
        ingester.poll().added shouldBe 1
        ingester.view().size shouldBe 4
        ingester.failures shouldBe SpendIngestFailures(malformed = 1, unknownVersion = 1)
    }

    /**
     * Record identity is the full tuple (fpml.1-D2): duplicates collapse,
     * near-duplicates do not. The oracle (F5) replays the raw log, so ingest
     * must not merge two sessions that differ only in when they ended.
     */
    @Test
    fun `identical lines are one element while lines differing only in ended are two`() {
        val duplicated = line(workItem = "dup")
        append(duplicated, duplicated)
        append(
            line(workItem = "twin", ended = "2026-08-23T09:30:00Z"),
            line(workItem = "twin", ended = "2026-08-23T10:30:00Z"),
        )

        val ingester = ingester()
        ingester.poll()

        ingester.view() shouldBe
            setOf(
                record(workItem = "dup"),
                record(workItem = "twin", ended = "2026-08-23T09:30:00Z"),
                record(workItem = "twin", ended = "2026-08-23T10:30:00Z"),
            )
    }

    /**
     * At the wired seam (`computenet-xol9`), the crash-ordering rule
     * [SpendOffsetStore] documents — "the checkpoint is written only AFTER the
     * batch it covers has been handed to the consumer" — must be an observable
     * property of [SpendLogIngester], not just of [SpendLogTailReader] in
     * isolation ([SpendLogTailReaderTest] already covers the reader alone).
     *
     * The double's [SpendOffsetStore.write] snapshots the SAME fold cell the
     * ingester folds into, at the instant persistence happens. If the fold ran
     * first, as the crash-ordering rule requires, that snapshot already
     * contains the record the poll just read. Delegating to a real
     * [OffsetCheckpoint] keeps the checkpoint file itself correct, so this test
     * is purely an observation, not a stub that changes ingester behavior.
     *
     * Mutation-checked (`computenet-xol9`): rewriting [SpendLogIngester.poll]
     * to `val batch = reader.poll { }; return fold(batch)` — folding only AFTER
     * `reader.poll` returns, which is also after the reader has persisted —
     * makes this test's assertion fail, because the snapshot is taken while the
     * fold is still empty.
     */
    @Test
    fun `the fold runs before the checkpoint is persisted, at the wired seam`() {
        append(line(workItem = "a"))
        val fold = SetCell<SpendRecord>()
        val real = OffsetCheckpoint(runDir)
        var membershipAtPersistTime: Set<SpendRecord>? = null
        val observingStore =
            object : SpendOffsetStore {
                override fun read(): CheckpointState? = real.read()

                override fun write(state: CheckpointState) {
                    membershipAtPersistTime = fold.membership()
                    real.write(state)
                }
            }
        val ingester = SpendLogIngester(log, runDir, fold, observingStore)

        val outcome = ingester.poll()

        outcome.added shouldBe 1
        // The whole point: at the moment the checkpoint was persisted, the
        // fold this poll just produced was already visible.
        membershipAtPersistTime shouldBe setOf(record(workItem = "a"))
    }

    /**
     * A log that has not arrived yet is not an empty log: the fold keeps what it
     * has rather than being reconciled to nothing. (The spend log syncs over the
     * beads/dolt channel — see the feature description.)
     */
    @Test
    fun `an absent log leaves the fold untouched`() {
        append(line(workItem = "a"))
        val ingester = ingester()
        ingester.poll()

        Files.delete(log)

        val outcome = ingester.poll()
        outcome.reason shouldBe TailReason.LogAbsent
        outcome.added shouldBe 0
        outcome.removed shouldBe 0
        ingester.view() shouldBe setOf(record(workItem = "a"))
    }
}
