package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ReplayScope
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.durability.Journal
import civictech.cell.port.FanOutlet
import civictech.cell.port.OutletWaveState
import civictech.cell.port.PortRegistry
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
private const val RECORD_OUTLET_WAVE: Byte = 4
private const val RECORD_BASELINE: Byte = 5

/**
 * `[24-DUR-08]`'s bound: the most discharged-baseline positions
 * ([HostDurability.dischargedBaselines]) one `Effectful` inlet retains
 * (`computenet-yh6.1.3.4.2`; spec 24 §Effectful, end of the catch-up-baselines passage).
 *
 * **The set had no bound.** A baseline firing never advances the wave-position
 * processed-frontier (`[24-DUR-07]`), so the only compaction available — drop what the
 * frontier already covers, applied at [HostDurability.checkpoint] — never fires for a
 * source lane that emits baselines and no live frames after them. N-shard pull replies,
 * repeated link installs against a state-holder that only answers `StateRequest`s, and
 * repeated crash recoveries firing journal tails each leave permanent entries, in memory
 * and in every subsequent checkpoint blob.
 *
 * **Why a cap and not the other two candidates.** A *per-source contiguity collapse* — fold
 * a run of consecutive discharged counters into a per-source high-water — is genuinely
 * lossless (contiguity means every counter at or below the high-water was itself
 * discharged, and 93 I-14 Rule S1 forbids re-issuing a pair, so an arrival at or below it
 * can only be a re-delivery of a frame that already fired). It is nonetheless not a bound —
 * though not because the growth case is always sparse: a lane that only answers
 * `StateRequest`s stamps consecutive counters, so that case *is* a contiguous run and would
 * collapse. What the collapse cannot bound is the **source dimension**. A [FanOutlet]'s
 * `sourceId` is minted per outlet instance and re-minted on every epoch bump, so the
 * distinct source lanes one inlet sees over a long life — N shards, each remote peer, each
 * restart or replica spawn — are themselves unbounded, and one high-water each still grows
 * without limit. A collapse is a legitimate *complement* to this cap (it would postpone
 * eviction on a single lane) and never a replacement for it; it is not implemented.
 * Consulting such a high-water only for baseline-marked frames would bound it, and is what
 * makes it wrong: a baseline at an undischarged counter below the high-water would then be
 * suppressed without ever having fired — the silent, unrecoverable omission `[24-DUR-07]`
 * chose firing over — and it would make replay-vs-pull an observable distinction at an
 * effect boundary, which spec 24 §Effectful says it must not be. A *retention horizon tied
 * to source-lane liveness* is the semantically exact answer, but it is not a bound either
 * (a live lane retains forever) and it needs link-teardown knowledge this class does not
 * have and cannot acquire without pushing an obligation back onto the catch-up protocol —
 * which is precisely what `[24-DUR-08]` was designed to avoid.
 *
 * **The loss mode, stated.** Eviction is oldest-discharge-first, after every
 * frontier-covered position (which is redundant by construction) has been dropped. An
 * evicted position is no longer suppressed: if that exact frame is re-delivered — an
 * upstream retransmit of a baseline reply, or a journal-tail replay of it — the sink
 * **re-fires the effect for it**. The loss is a duplicate external effect, bounded to
 * positions older than this inlet's last [DISCHARGED_BASELINE_CAP] baseline firings, and
 * it sits under 93 I-7's stated external-idempotency ceiling. It is deliberately in that
 * direction: eviction only ever shrinks the suppression set, so no live frame can become
 * collaterally suppressed by it and `[24-DUR-07]` cannot be re-broken by this bound —
 * unlike the high-water candidates, whose loss mode is a silent omission.
 *
 * **The number.** A retained position costs on the order of a hundred bytes live (a
 * [Timestamp], its [UUID], and the set entry holding them) and one serialized
 * [BaselineDischargeRecord] in every checkpoint blob — ~285 bytes measured on a
 * structurally identical Java-serialized shape, and somewhat more here since Kotlin's
 * class names are longer — so 1024 holds a per-inlet ceiling in the low hundreds of
 * kilobytes of checkpoint while being far above any plausible legitimate in-flight
 * retransmit window — the shard fan-out and link-install bursts that motivate the residual
 * are units to tens of baselines, not thousands. It is a judgement, not a measurement: no workload has been profiled against
 * it, and the only evidence behind the figure is that arithmetic.
 */
private const val DISCHARGED_BASELINE_CAP = 1024

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

/**
 * Durable record that an [civictech.cell.evolve.Effectful] inlet **discharged a baseline**
 * frame at one exact position (`[24-DUR-08]`, spec 24 §Effectful; 93 I-24): the sink acted
 * on a frame carrying a [civictech.cell.MessageContext.baseline], whose timestamp — by
 * `[24-DUR-07]` — may not advance the wave-position processed-frontier ([FrontierRecord]).
 * Without this record such a firing would have nothing to suppress its own `recoverFrom`
 * replay, and a crash after a catch-up join would re-fire the whole catch-up.
 *
 * An **exact position**, not a per-source high-water like [FrontierRecord]: a high-water
 * would suppress every live frame from that source below the baseline's counter, which is
 * precisely the frontier pollution `[24-DUR-07]` exists to prevent. An exact set suppresses
 * only a re-delivery of the very frame that already fired, so it is safe to consult for
 * every frame at the inlet, baseline-marked or not. Keying on `(sourceId, counter)` rather
 * than on the baseline's link-install anchor also avoids the anchor recurring — two shards
 * replying with equal frontiers (`PartitionedShardSet`'s N per-shard baselines) share an
 * anchor but never a position, since 93 I-14 Rule S1 forbids re-issuing a pair.
 *
 * Because the set is exact and a baseline advances no frontier, it only ever grows;
 * [DISCHARGED_BASELINE_CAP] is the bound, and states its loss mode.
 *
 * A **separate additive record type** for the same reason as [OutletWaveRecord]: a journal
 * written before this change contains no `RECORD_BASELINE` and replays byte-for-byte as it
 * always did, whereas widening [CheckpointRecord] would change its computed
 * `serialVersionUID` and make every pre-existing checkpoint blob undecodable.
 */
private data class BaselineDischargeRecord(val cellRef: CellRef, val portName: String, val timestamp: Timestamp) :
    Serializable

/**
 * Durable record of one [FanOutlet]'s **whole emission epoch** — `sourceId` *and* counter
 * high-water — at checkpoint time (KFX-12; spec `[24-DUR-04]`, 93 I-14 Rule S1's
 * preserved-epoch clause + §4's durable-counter optimization).
 *
 * The `sourceId` is carried rather than re-derived on restore, even though
 * [OutletWaveState.durable]'s ref-derivation is what puts a journaled outlet on its epoch
 * in the first place. In the ordinary case the two are the same value. They diverge
 * whenever some *other* decided transition moved the outlet off its derived epoch before
 * the checkpoint — RESTART supervision's `mintFreshEpoch` (`ManagedHost`, `[KFX-14]`), a
 * fallback promotion swap, or a drain/migration/promotion `adoptWaveState` of a
 * predecessor's lane (`[KFX-15]`) — and there re-deriving would pair the *derived* id with
 * *another* epoch's counter, re-issuing `(sourceId, counter)` pairs the network already
 * observed under the derived lane. Downstream that reads as already-acted, i.e. silent
 * effect loss. Recording the epoch in force keeps Rule S1's "never reuse a pair" intact
 * across every combination of an epoch transition and a later crash.
 *
 * A **separate additive record type** rather than a field on [CheckpointRecord] on
 * purpose: a journal written before this change contains no `RECORD_OUTLET_WAVE`, so it
 * replays byte-for-byte as it always did (absent-tolerant by construction), whereas
 * widening [CheckpointRecord] would change its computed `serialVersionUID` and make every
 * pre-existing checkpoint blob undecodable. Same shape as
 * [FrontierRecord]/`RECORD_FRONTIER`.
 */
private data class OutletWaveRecord(
    val cellRef: CellRef,
    val portName: String,
    val sourceId: UUID,
    val highWater: Long,
) : Serializable

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
     * Discharged baselines (`[24-DUR-08]`, spec 24 §Effectful): per
     * [civictech.cell.evolve.Effectful] inlet `(cellRef, portName)`, the exact positions of
     * baseline-marked frames the sink has already acted on. Durable via
     * [BaselineDischargeRecord], and **separate from** [processedFrontier] on purpose — a
     * baseline's timestamp is anchored at the stamped link-install event rather than at a
     * wave position, so it must never advance a wave-position high-water. This is the
     * sink's own journaled state that makes a baseline firing crash-consistent, with no
     * obligation on the producer, the ingress or the catch-up protocol.
     *
     * Bounded per inlet at [DISCHARGED_BASELINE_CAP], evicting oldest-discharge-first,
     * with the loss mode stated there: an evicted position that is re-delivered re-fires
     * the effect. Insertion-ordered ([LinkedHashSet]) because that eviction order is part
     * of the bound's contract, not an implementation detail.
     */
    private val dischargedBaselines = mutableMapOf<Pair<CellRef, String>, MutableSet<Timestamp>>()

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
                            RECORD_BASELINE -> restoreBaselineDischarge(record.copyOfRange(1, record.size))
                            RECORD_OUTLET_WAVE -> restoreOutletWave(record.copyOfRange(1, record.size))
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

    private fun journalRecord(type: Byte, record: Serializable): ByteArray {
        val blob = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(record) } }
            .toByteArray()
        return byteArrayOf(type) + blob
    }

    /**
     * KFX-12 (spec `[24-DUR-04]`, 93 I-14 Rule S1 preserved-epoch clause): put every
     * outlet of a **journaled** cell on its ref-derived emission epoch, at spawn, before
     * `onActivate` can emit. Called by `ManagedHost`'s spawn.
     *
     * This is the live half of the decision recorded on [OutletWaveState.durable]. A
     * recovered outlet's identity can only *be* the identity the network already observed
     * if the pre-crash run was already emitting under it — deriving it only inside
     * [recoverFrom] would restore an identity nothing downstream had ever recorded.
     *
     * Deliberately gated on `journalSelector(cellRef) != null`: durability is a hosting
     * decision, not a cell concern (spec 30/31). A volatile cell has no journal to prove
     * counter continuity from, so 93 I-14 Rule S1's fresh-epoch default is the correct —
     * and unchanged — behaviour for it. Non-recovery epoch transitions (RESTART's
     * `mintFreshEpoch`, replica/candidate spawn, a fallback promotion swap) are untouched
     * here too (`[KFX-14]`).
     */
    fun installDurableEpochs(cellRef: CellRef, cell: Cell) {
        if (journalSelector(cellRef) == null) return
        forEachOutlet(cell) { _, outlet -> outlet.adoptWaveState(OutletWaveState.durable(outlet.ref)) }
    }

    /** The cell's registered [FanOutlet]s, by port name (PortRegistry is the ManagedHost precedent). */
    private inline fun forEachOutlet(cell: Cell, action: (String, FanOutlet<*>) -> Unit) {
        val registry = PortRegistry.of(cell)
        registry.names().forEach { name -> (registry[name] as? FanOutlet<*>)?.let { action(name, it) } }
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
            // KFX-12: each journaled outlet's emission epoch AT CHECKPOINT TIME, captured
            // on the same management-band pass as the `Stateful` snapshot so the two
            // describe the same instant. Recovery rewinds the outlet here and lets the
            // journal tail deterministically re-derive the counters it already emitted —
            // rewinding to the *crash-time* high-water instead would make every replayed
            // re-emission carry a counter the sink's frontier has never seen, i.e. the
            // double-fire this closes. Recorded unconditionally, epoch and all: an outlet
            // sitting at high-water 0 is not necessarily where `installDurableEpochs` left
            // it (RESTART's `mintFreshEpoch` rotates the epoch and zeroes the counter), and
            // restoring the derived epoch over such a rotation re-issues counters the
            // derived lane already spent — see [OutletWaveRecord].
            val waves = ArrayList<ByteArray>()
            cells.forEach { (cellRef, cell) ->
                if (journalSelector(cellRef) === journal) forEachOutlet(cell) { name, outlet ->
                    val wave = outlet.waveState()
                    waves += journalRecord(
                        RECORD_OUTLET_WAVE,
                        OutletWaveRecord(cellRef, name, wave.sourceId, wave.highWater),
                    )
                }
            }
            // `[24-DUR-08]`: the discharged-baseline positions of this journal's cells
            // survive compaction too — they are the only thing standing between a
            // journaled catch-up baseline and a re-fire of the whole join after a crash,
            // and unlike the processed-frontier they cannot ride [CheckpointRecord]
            // without changing its serialVersionUID (see [BaselineDischargeRecord]).
            // Compacted while being written: a position the processed-frontier already
            // covers is suppressed by [alreadyProcessed] anyway, so keeping it would only
            // grow the set. That is the same test the guard applies first, so dropping it
            // changes no decision. What that rule cannot compact — a lane that emits
            // baselines and no live frames after them — is bounded instead by
            // [DISCHARGED_BASELINE_CAP], so this blob carries at most that many records
            // per inlet.
            val baselines = ArrayList<ByteArray>()
            dischargedBaselines.forEach { (key, positions) ->
                if (journalSelector(key.first) !== journal) return@forEach
                positions.removeIf { (processedFrontier[key]?.get(it.sourceId) ?: -1L) >= it.counter }
                positions.forEach {
                    baselines += journalRecord(RECORD_BASELINE, BaselineDischargeRecord(key.first, key.second, it))
                }
            }
            // PN-0b: reset() truncates the WAL down to this checkpoint blob. If
            // the journal serves cells (frames on disk) but the blob captures
            // NOTHING recoverable — no `Stateful` snapshot, no `Effectful`
            // processed-frontier and no discharged baseline — those frames are the
            // cells' only recovery, and the reset would silently destroy them.
            // Refuse instead. A discharged-baseline position counts as recoverable
            // content on the same footing as a frontier entry (`[24-DUR-08]`): for a
            // sink whose whole durable contribution is "this catch-up already fired",
            // it is exactly what the truncated frames would otherwise be replayed for.
            require(state.isNotEmpty() || frontier.isNotEmpty() || baselines.isNotEmpty() ||
                cells.keys.none { journalSelector(it) === journal }) {
                "checkpoint would truncate a journal whose selected cells contribute " +
                    "no snapshot, no processed-frontier and no discharged baseline — frame " +
                    "replay is their only recovery, so resetting the WAL would destroy their state"
            }
            val blob = ByteArrayOutputStream()
                .also { ObjectOutputStream(it).use { out -> out.writeObject(CheckpointRecord(state, frontier)) } }
                .toByteArray()
            journal.reset(listOf(byteArrayOf(RECORD_CHECKPOINT) + blob) + waves + baselines)
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

    /**
     * KFX-12: rewind one outlet to the epoch that was in force at checkpoint time — the
     * recorded `sourceId` (the ref-derived one in the ordinary case, whatever else
     * [installDurableEpochs] was later overridden by otherwise; see [OutletWaveRecord])
     * plus the journaled counter high-water. Goes through the very same
     * [FanOutlet.adoptWaveState] a drain/migration/promotion continuation uses
     * (`[KFX-15]`): durable recovery is a preserved-epoch continuation, so it takes the
     * preserved-epoch mechanism rather than a parallel one.
     */
    private fun restoreOutletWave(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as OutletWaveRecord
        val outlet = cellsView()[record.cellRef]?.let { PortRegistry.of(it)[record.portName] } as? FanOutlet<*>
            ?: return deadLetter(
                "checkpoint outlet wave state for ${record.cellRef}.${record.portName} but no such " +
                    "FanOutlet — graph rebuilt differently?"
            )
        outlet.adoptWaveState(OutletWaveState(record.sourceId, record.highWater))
    }

    private fun restoreFrontier(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as FrontierRecord
        advanceFrontier(record.cellRef, record.portName, record.timestamp)
    }

    private fun restoreBaselineDischarge(blob: ByteArray) {
        val record = ObjectInputStream(ByteArrayInputStream(blob)).readObject() as BaselineDischargeRecord
        recordBaselineDischarge(record.cellRef, record.portName, record.timestamp)
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
        journalSelector(cellRef)?.append(journalRecord(RECORD_FRONTIER, FrontierRecord(cellRef, portName, timestamp)))
    }

    /**
     * `[24-DUR-08]`: true iff this exact position was already discharged as a baseline
     * firing at this `(cellRef, portName)`. Exact, never a high-water — see
     * [BaselineDischargeRecord] for why, and why that makes it safe to consult for every
     * frame at the inlet rather than only for baseline-marked ones.
     */
    fun alreadyDischargedBaseline(cellRef: CellRef, portName: String, timestamp: Timestamp): Boolean =
        dischargedBaselines[cellRef to portName]?.contains(timestamp) == true

    /**
     * Records a discharged baseline position in memory, then enforces
     * [DISCHARGED_BASELINE_CAP] on that inlet's set. Callers decide whether to also
     * journal it.
     *
     * Eviction runs on the restore path too ([restoreBaselineDischarge]), and journal
     * order is insertion order, so a recovered host holds exactly the set the crashed one
     * held — the bound does not make recovery diverge from the live run.
     */
    private fun recordBaselineDischarge(cellRef: CellRef, portName: String, timestamp: Timestamp) {
        val key = cellRef to portName
        val positions = dischargedBaselines.getOrPut(key) { LinkedHashSet() }
        positions += timestamp
        if (positions.size <= DISCHARGED_BASELINE_CAP) return
        // Free first: a position the wave-position frontier already covers is decided by
        // [alreadyProcessed] before [alreadyDischargedBaseline] is ever consulted, so
        // dropping it changes no decision at all. Same test `checkpoint` applies.
        positions.removeIf { (processedFrontier[key]?.get(it.sourceId) ?: -1L) >= it.counter }
        // Then the lossy step, oldest discharge first (see [DISCHARGED_BASELINE_CAP]).
        val iterator = positions.iterator()
        while (positions.size > DISCHARGED_BASELINE_CAP && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    /**
     * `[24-DUR-08]`, the baseline counterpart of [advanceAndJournalFrontier]: record that
     * this `Effectful` inlet acted on a baseline-marked frame at [timestamp], and journal
     * that fact on the same per-cell tee (CP-C1) the frame itself rode — so `recoverFrom`
     * meets the discharge beside the frame and does not re-fire it. The wave-position
     * processed-frontier is deliberately NOT advanced: a baseline is anchored at the
     * stamped link-install event, not at a wave position.
     */
    fun recordAndJournalBaselineDischarge(cellRef: CellRef, portName: String, timestamp: Timestamp) {
        recordBaselineDischarge(cellRef, portName, timestamp)
        journalSelector(cellRef)?.append(
            journalRecord(RECORD_BASELINE, BaselineDischargeRecord(cellRef, portName, timestamp)),
        )
    }
}
