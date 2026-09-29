package civictech.inspect.edit

import civictech.inspect.RingBuffer

/**
 * The write plane's audit trail: every [ApplyRecord] the process has seen,
 * bounded at [CAPACITY] for the whole process — not per cell, per graph or
 * per identity (`[WKB2-42]`), the same precedent as the activity ring
 * (`civictech.inspect.Activity`, `RING_CAPACITY` = 200). [entries] answers an
 * empty list, never null and never a throw, when nothing has been recorded
 * yet.
 *
 * Built on the existing [RingBuffer] rather than a second ring
 * implementation; that type's `add` is already `@Synchronized`, so this class
 * adds no lock of its own.
 *
 * This is a self-contained type with its own test. `InspectorServer` holds
 * one per process (WKB2 F6, `computenet-wczst`): `WritePlaneRoutes` records
 * each apply's terminal record here and serves [entries] as
 * `GET /api/inspect/applies`.
 */
internal class AuditRing(capacity: Int = CAPACITY) {

    private val ring = RingBuffer<ApplyRecord>(capacity)

    /** Appends [record]; oldest is evicted once [CAPACITY] is exceeded. */
    fun record(record: ApplyRecord) {
        ring.add(record)
    }

    /** Oldest first. Empty when nothing has been recorded — never null, never a throw. */
    fun entries(): List<ApplyRecord> = ring.snapshot()

    companion object {
        const val CAPACITY = 200
    }
}
