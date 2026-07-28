package civictech.cell.control

/**
 * A delta payload that can state how big it is (spec 21 §Cycles, 93 I-6):
 * [size] is ≥ 0 and `0.0` means "no effective change". The emitter stamps it
 * — magnitude is a data property riding the data path, never a metadata-plane
 * protocol.
 *
 * Consumers today: magnitude-band dispatch (spec 34 — a host boosts a cell's
 * band to match its largest staged payload, opt-in via
 * [AttentionPolicy.magnitudeBands]). The decided
 * CycleHead feedback-inlet threshold (93 I-5/I-6, unbuilt) will read the same
 * interface. Detection is a runtime `is`-check for now — advisory only, a
 * payload without [Magnitude] simply gets no boost; the KSP `magnitude`
 * descriptor bit (G-60) can replace the check without an API change.
 */
interface Magnitude {
    fun size(): Double
}
