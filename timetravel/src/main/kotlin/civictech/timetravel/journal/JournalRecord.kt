package civictech.timetravel.journal

import civictech.cell.CellRef
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.WireCodec
import civictech.timetravel.fidelity.Reason
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * One journal record as the offline reader sees it (TTD1 F1, feature D1). Every record the
 * journal holds yields exactly one of these — a record the reader cannot interpret is an
 * [UnknownRecord] or [MalformedRecord] carrying its [Reason], never an exception and never a
 * silent skip.
 *
 * @property index 0-based position in the journal's record list — the same number
 *   `HostDurability.recoverFrom` reports as `RecoveryIncomplete.recordIndex` for that record
 *   (`[TTD1-01]`). Restarts at 0 for every journal of a directory.
 * @property journalId the file's path for a file or directory source; the caller's label for
 *   an in-memory `Journal`.
 * @property reasons why this record is not at full fidelity; empty when it is.
 */
sealed interface JournalRecord {
    val index: Int
    val journalId: String
    val reasons: Set<Reason>
}

/**
 * A type-1 record: one journaled invocation frame. The structural fields come from the frame's
 * JSON alone, with no descriptor and no `ContractRegistry` (`[TTD1-03]`); [hydrated] is present
 * only when `WireCodec.decodeFrame` succeeds against the descriptors on this classpath
 * (`[TTD1-04]`).
 *
 * @property args the raw `args` element of the frame, or `JsonNull` when the key is absent.
 * @property wireVersion the frame's explicit `version` key, `null` when absent (the codec never
 *   emits it, so absent is the normal case and is never a mismatch — `[TTD1-06]`).
 * @property expectedWireVersion the version this reader's `WireCodec` would have written —
 *   `WireCodec.VERSION` — so a `WIRE_VERSION_MISMATCH` consumer can report both the found and
 *   the expected value without importing `WireCodec` itself (`[TTD1-06]`).
 * @property hydrationFailure the exception `WireCodec.decodeFrame` threw, as text, when
 *   hydration was attempted and failed; `null` otherwise.
 */
data class FrameRecord(
    override val index: Int,
    override val journalId: String,
    val cellRef: CellRef,
    val portName: String,
    val type: HostedPortInvocation.Type,
    val contractId: Long,
    val methodId: Long,
    val context: MessageContext?,
    val args: JsonElement,
    val wireVersion: Int?,
    val expectedWireVersion: Int = WireCodec.VERSION,
    val hydrated: HydratedFrame?,
    val hydrationFailure: String?,
    override val reasons: Set<Reason>,
) : JournalRecord

/** A frame decoded through its descriptor: the invocation the host would have replayed. */
data class HydratedFrame(val invocation: HostedPortInvocation) {
    val methodName: String get() = invocation.invocation.methodName
    val args: List<Any?> get() = invocation.invocation.args
}

/**
 * A type-2 record: a checkpoint (`[TTD1-07]`).
 *
 * @property restoredCells the cells whose `Stateful` snapshot the checkpoint carries.
 * @property frontier the processed-frontier per `(cell, inlet)`: source id to counter.
 */
data class CheckpointRecord(
    override val index: Int,
    override val journalId: String,
    val restoredCells: Set<CellRef>,
    val frontier: Map<Pair<CellRef, String>, Map<UUID, Long>>,
    override val reasons: Set<Reason>,
) : JournalRecord

/** A type-3 record: one `Effectful` inlet's processed-frontier advance (`[TTD1-09]`). */
data class FrontierRecord(
    override val index: Int,
    override val journalId: String,
    val cellRef: CellRef,
    val portName: String,
    val timestamp: Timestamp,
    override val reasons: Set<Reason>,
) : JournalRecord

/** A type-4 record: one outlet's emission epoch at checkpoint time (`[TTD1-09]`). */
data class OutletWaveRecord(
    override val index: Int,
    override val journalId: String,
    val cellRef: CellRef,
    val portName: String,
    val sourceId: UUID,
    val highWater: Long,
    override val reasons: Set<Reason>,
) : JournalRecord

/** A type-5 record: one discharged-baseline position at an `Effectful` inlet (`[TTD1-09]`). */
data class BaselineDischargeRecord(
    override val index: Int,
    override val journalId: String,
    val cellRef: CellRef,
    val portName: String,
    val timestamp: Timestamp,
    override val reasons: Set<Reason>,
) : JournalRecord

/**
 * A record whose leading byte is no record type the kernel defines.
 *
 * @property typeByte the leading byte, or `null` for an empty record (which has none).
 */
data class UnknownRecord(
    override val index: Int,
    override val journalId: String,
    val typeByte: Byte?,
    override val reasons: Set<Reason> = setOf(Reason.UNKNOWN_RECORD),
) : JournalRecord

/**
 * A record of a known type whose payload could not be read.
 *
 * @property reason [Reason.CHECKPOINT_UNDESERIALIZABLE], [Reason.RECORD_UNDESERIALIZABLE] or
 *   [Reason.FRAME_UNPARSEABLE].
 * @property message what failed, for a human.
 */
data class MalformedRecord(
    override val index: Int,
    override val journalId: String,
    val typeByte: Byte,
    val reason: Reason,
    val message: String,
    override val reasons: Set<Reason> = setOf(reason),
) : JournalRecord
