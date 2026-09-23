package civictech.timetravel.fidelity

/**
 * Why a journal record, checkpoint, or frame could not be trusted or read at full
 * fidelity during offline time-travel (TTD1, epic `computenet-ocv`). This is a plain
 * enum — the details a reason needs (found/expected versions, the failing record
 * index, the underlying exception message) ride on the reporting structures in
 * `civictech.timetravel.journal`, never on the enum itself.
 *
 * `RECORD_UNDESERIALIZABLE` and `FRAME_UNPARSEABLE` go beyond the feature's original
 * D3 list: without them, those two failure modes would have no reason to carry and
 * would have to be dropped silently, which the epic's honesty rule ([TTD1-34])
 * forbids. F3 (computenet-kxex2) extends this enum with reconstruction reasons; do
 * not add those here.
 */
enum class Reason {
    /** No descriptor was found for a record's declared type — it cannot be decoded at all. */
    NO_DESCRIPTOR,

    /** A record's wire-format version does not match what its type's descriptor expects. */
    WIRE_VERSION_MISMATCH,

    /** A record's on-disk format version predates or postdates what this reader understands. */
    FORMAT_VERSION_MISMATCH,

    /** A checkpoint record's payload could not be deserialized into its declared state type. */
    CHECKPOINT_UNDESERIALIZABLE,

    /** A non-checkpoint record's payload (type-3/4/5 blob) failed `readObject`. */
    RECORD_UNDESERIALIZABLE,

    /** A type-1 frame's payload is not a well-formed `WireFrame` JSON object. */
    FRAME_UNPARSEABLE,

    /** The journal ends mid-record — a torn write, not a clean close. */
    JOURNAL_TORN,

    /** The record's type tag is not one this reader recognizes. */
    UNKNOWN_RECORD,
}
