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
 * This is a self-contained type with its own test. It is not wired into
 * `InspectorServer` here — the `GET /api/inspect/applies` route that serves
 * it is feature "http surface" (`computenet-wczst`).
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
