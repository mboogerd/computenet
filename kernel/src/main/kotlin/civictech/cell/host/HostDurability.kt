package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ReplayScope
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.durability.Journal
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.WireCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.UUID

private const val RECORD_FRAME: Byte = 1
private const val RECORD_CHECKPOINT: Byte = 2
private const val RECORD_FRONTIER: Byte = 3

/**
 * T05 finding 4: [HostDurability.recoverFrom] failed on [recordIndex] of
 * [total] journal records (0-based) and stopped there — every record before
 * it applied, every record from [recordIndex] onward did not. Thrown after
 * the bad record is dead-lettered; callers must not treat a caught partial
 * replay as a complete recovery.
 */
class RecoveryIncomplete(val recordIndex: Int, val total: Int, cause: Throwable) :
    Exception("journal replay aborted at record $recordIndex of $total: ${cause.message}", cause)

/**
 * Durable record of an [civictech.cell.evolve.Effectful] inlet's processed-frontier advance
 * (G-59, fixes C-9; spec 20/24, 30/31, 50/52 "Effectful recovery"): the last applied
 * `(sourceId, counter)` for one `(cellRef, portName)`.
 */
private data class FrontierRecord(val cellRef: CellRef, val portName: String, val timestamp: Timestamp) :
    Serializable

/** Checkpoint payload (M10.2, extended G-59): cell state plus the processed-frontier, atomically together. */
private data class CheckpointRecord(
    val state: Map<CellRef, Serializable>,
    val frontier: Map<Pair<CellRef, String>, Map<UUID, Long>>,
) : Serializable

/**
 * The WAL/journal/checkpoint/frontier durability machinery, extracted from
 * [ManagedHost] (RS-8.2): write-ahead journaling of accepted invocations
 * (M10.1), checkpoint capture + compaction (M10.2), and the per-`(cellRef,
 * portName)` [civictech.cell.evolve.Effectful] processed-frontier (G-59, fixes C-9)
 * that dedupes an already-acted invocation on replay/re-delivery. Owned 1:1 by
 * a `ManagedHost`, which delegates its public `recoverFrom`/`checkpoint` API
 * here and reads [alreadyProcessed]/[advanceAndJournalFrontier] from its own
 * `deliver` (which stays on the host).
 *
 * The wire-envelope journal encoding ([journalFrame]) moves verbatim; its
 * coupling to `WireCodec.VERSION` is a known, out-of-scope issue (unchanged
 * comment below).
 *
 * Collaborators are the host's own [journalSelector] (the SAME lambda
 * instance the host keeps for its own two `enqueueHostedInvocation` journal
 * writes and the PN-12 spawn-time check — sharing it, rather than
 * re-deriving it, keeps the per-cell selection byte-identical), a live
 * [cellsView] of the host's `cells` map, the host's [deadLetter] reporter,
 * [submit] (`enqueueHostedInvocation`, for replayed frames re-entering the
 * intake), and [awaitOnManagementBand] (`enqueueAwaiting(0, ...)`, so
 * [checkpoint] keeps running on the management band, unable to interleave
 * with a dispatching cell, exactly as before). None of these paths touch
 * `dataLock` — durability runs on the management band / synchronous replay,
 * never under the data-plane lock.
 */
internal class HostDurability(
    private val journalSelector: (CellRef) -> Journal?,
    private val cellsView: () -> Map<CellRef, Cell>,
    private val deadLetter: (String) -> Unit,
    private val submit: (HostedPortInvocation) -> Unit,
    private val awaitOnManagementBand: (suspend () -> Unit) -> Unit,
) {

    /** Suppresses journaling while [recoverFrom] replays — replay must not re-journal itself. */
    @Volatile
    var recovering = false
        private set

    /**
     * PN-2 (plan §3 Rule of recovery, §4 PN-2): stamp every replayed frame that
     * carries a wave context (a mid-graph cell's frames — root frames driven
     * externally carry none and are left untouched, preserving byte-for-byte
     * behavior for non-opting graphs) as a catch-up [civictech.cell.MessageContext.baseline],
     * so [recoverFrom] re-enters the intake as a *baseline*, not a live wave.
     * `false` reverts to the pre-PN-2 behavior — replay as ordinary waves,
     * which stalls an asymmetric diamond join (the volatile arm never advances).
     * Test seam for `DurableGlitchFreeReplayTest`'s control; production always
     * replays as baseline.
     */
    var replayAsBaseline = true

    /**
     * Processed-frontier (G-59, fixes C-9; spec 20/24, 30/31, 50/52): per
     * [civictech.cell.evolve.Effectful] inlet `(cellRef, portName)`, the last applied
     * `Timestamp` per source — durable via [FrontierRecord]/[CheckpointRecord] so both
     * journal replay and post-recovery live re-delivery dedupe an
     * already-acted invocation instead of re-firing it.
     */
    private val processedFrontier = mutableMapOf<Pair<CellRef, String>, MutableMap<UUID, Long>>()

    /**
     * Replay this host's [journal] (M10.1): checkpoint records restore
     * `Stateful` state directly; invocation frames re-enter through the
     * ordinary intake (decode = the same path a network frame takes — a
     * journal is a bridge to disk). Call after the graph is rebuilt (cells
     * spawned) and before new traffic; replays are not re-journaled.
     *
     * Per-cell (CP-C1): a journal only ever holds records for the cells whose
     * selector tees to it (the write path is per-cell), so replaying it
     * restores exactly those cells and re-delivers nothing to volatile cells
     * that were never written. Recover each distinct journal once.
     */
    fun recoverFrom(journal: Journal) {
        recovering = true
        // PN-2: the whole replay runs inside one [ReplayScope] so a cell that
        // *originates* mid-replay marks that emission a baseline too; the frame
        // itself is stamped up front (below) so a reactive re-emission inherits
        // the baseline through the ordinary context copy across the async
        // dispatch that follows this synchronous re-injection.
        //
        // T04 finding 7 (extended, T06 §C1a): staging (this loop) and
        // delivery (a later, independent scheduler task) are decoupled, so
        // this ReplayScope.with block's dynamic extent never actually covers
        // delivery — it only covers this synchronous submit() loop. A
        // mid-graph frame's baseline survives that gap because it is
        // stamped directly onto its own MessageContext (`frame.baselined`,
        // below); a ROOT frame carries no context to stamp, so its baseline
        // is carried instead via HostedPortInvocation.replayFrontier, which
        // ManagedHost.deliver re-installs (via ReplayScope.withSuspending)
        // around the handler call — surviving a suspension to a different
        // worker thread too.
        val scope: TagFrontier? = if (replayAsBaseline) TagFrontier(emptyMap()) else null
        try {
            ReplayScope.with(scope) {
                val records = journal.replay()
                records.forEachIndexed { index, record ->
                    // T05 finding 4: a bare forEach with no per-record handling
                    // meant any decode/readObject throw (or the else -> error
                    // below) silently abandoned every remaining record —
                    // recovering still reset in the finally, and the host
                    // resumed live traffic on truncated state with nothing
                    // to say so. Dead-letter the bad record, then rethrow so
                    // the caller cannot mistake a partial replay for a
                    // complete one.
                    try {
                        when (record[0]) {
                            RECORD_FRAME -> submit(
                                WireCodec.decode(record.copyOfRange(1, record.size)).let { frame ->
                                    (if (scope == null) frame else frame.baselined(scope))
                                        .copy(replayFrontier = scope)
                                }
                            )

                            RECORD_CHECKPOINT -> restoreCheckpoint(record.copyOfRange(1, record.size))
                            RECORD_FRONTIER -> restoreFrontier(record.copyOfRange(1, record.size))
                            else -> error("unknown journal record type ${record[0]}")
                        }
                    } catch (e: Exception) {
                        deadLetter("journal replay: record $index of ${records.size} failed: $e")
                        throw RecoveryIncomplete(index, records.size, e)
                    }
                }
            }
        } finally {
            recovering = false
        }
    }

    /**
     * PN-2: stamp a replayed frame's wave context as a catch-up [baseline]
     * (plan §4 PN-2). Only a frame that already carries a context — a *mid-graph*
     * cell's frame, reactive from an upstream wave — is marked; a root cell's
     * externally-driven frame carries no context and is replayed verbatim
     * (byte-for-byte behavior for non-opting graphs). An existing baseline is
     * preserved, never overwritten.
     */
    private fun HostedPortInvocation.baselined(frontier: TagFrontier): HostedPortInvocation {
        val ctx = invocation.context ?: return this
        return copy(invocation = invocation.copy(context = ctx.copy(baseline = ctx.baseline ?: frontier)))
    }

    fun journalFrame(hostedInvocation: HostedPortInvocation): ByteArray =
        byteArrayOf(RECORD_FRAME) + WireCodec.encode(hostedInvocation)

    private fun journalFrontier(record: FrontierRecord): ByteArray {
        val blob = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(record) } }
            .toByteArray()
        return byteArrayOf(RECORD_FRONTIER) + blob
    }

    /**
     * Checkpoint (M10.2, extended G-59; keyed per-cell CP-C1): capture the
     * `Stateful` snapshot AND processed-frontier of exactly the cells whose
     * selector tees to THIS [journal], as one record, and compact that journal
     * down to it — replay after a checkpoint is restore + tail. Keying keeps a
     * per-cell journal free of state belonging to another journal (or to a
     * volatile cell). For the degenerate whole-host constant selector every
     * cell maps here, byte-identical to pre-CP-C1. Runs on the management band
     * so it can't interleave with a dispatching cell.
     */
    fun checkpoint(journal: Journal) {
        awaitOnManagementBand {
            val cells = cellsView()
            val state = HashMap<CellRef, Serializable>()
            cells.forEach { (cellRef, cell) ->
                if (cell is Stateful && journalSelector(cellRef) === journal) state[cellRef] = cell.snapshot()
            }
            val frontier = processedFrontier
                .filterKeys { journalSelector(it.first) === journal }
                .mapValues { HashMap(it.value) as Map<UUID, Long> }
            // PN-0b: reset() truncates the WAL down to this checkpoint blob. If
            // the journal serves cells (frames on disk) but the blob captures
            // NOTHING recoverable — no `Stateful` snapshot and no `Effectful`
            // processed-frontier — those frames are the cells' only recovery,
            // and the reset would silently destroy them. Refuse instead.
            require(state.isNotEmpty() || frontier.isNotEmpty() ||
                cells.keys.none { journalSelector(it) === journal }) {
                "checkpoint would truncate a journal whose selected cells contribute " +
                    "no snapshot and no processed-frontier — frame replay is their only " +
                    "recovery, so resetting the WAL would destroy their state"
            }
            val blob = ByteArrayOutputStream()
                .also { ObjectOutputStream(it).use { out -> out.writeObject(CheckpointRecord(state, frontier)) } }
                .toByteArray()
            journal.reset(listOf(byteArrayOf(RECORD_CHECKPOINT) + blob))
        }
    }

    private fun restoreCheckpoint(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as CheckpointRecord
        val cells = cellsView()
        record.state.forEach { (cellRef, snapshot) ->
            (cells[cellRef] as? Stateful)?.restore(snapshot)
                ?: deadLetter("checkpoint state for $cellRef but no Stateful cell — graph rebuilt differently?")
        }
        record.frontier.forEach { (key, sources) -> processedFrontier.getOrPut(key) { mutableMapOf() } += sources }
    }

    private fun restoreFrontier(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as FrontierRecord
        advanceFrontier(record.cellRef, record.portName, record.timestamp)
    }

    /**
     * Effectful processed-frontier (G-59, fixes C-9): true iff [timestamp]
     * (from a specific source) is at or behind the last applied counter for
     * this `(cellRef, portName)` — an effect-boundary replay to suppress.
     */
    fun alreadyProcessed(cellRef: CellRef, portName: String, timestamp: Timestamp): Boolean =
        (processedFrontier[cellRef to portName]?.get(timestamp.sourceId) ?: -1L) >= timestamp.counter

    /** Advances the frontier in memory. Callers decide whether to also durably journal it. */
    private fun advanceFrontier(cellRef: CellRef, portName: String, timestamp: Timestamp) {
        processedFrontier.getOrPut(cellRef to portName) { mutableMapOf() }[timestamp.sourceId] = timestamp.counter
    }

    /**
     * [ManagedHost.deliver]'s Effectful call shape (host-side): advance the
     * in-memory frontier AND journal the advance together, in that order —
     * exactly the two statements `deliver` used to run inline before this
     * extraction. The per-cell tee (CP-C1): the frontier advance rides the
     * same journal as this cell's frames — a volatile cell's selector (null)
     * skips the write.
     */
    fun advanceAndJournalFrontier(cellRef: CellRef, portName: String, timestamp: Timestamp) {
        advanceFrontier(cellRef, portName, timestamp)
        journalSelector(cellRef)?.append(journalFrontier(FrontierRecord(cellRef, portName, timestamp)))
    }
}
