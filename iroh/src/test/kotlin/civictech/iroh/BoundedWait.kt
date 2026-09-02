package civictech.iroh

import kotlin.test.fail

/**
 * Bounded-wait helpers shared across `:iroh`'s sidecar-backed tests
 * (`computenet-sr48`) — each was declared privately, and repeatedly, in
 * [IrohPeeringTest], [IrohKeyBoundAdmissionTest], [IrohReconnectTest] and
 * [IrohRefusedDialBoundTest] before this extraction.
 */

/** Poll [condition] until it is true, or fail after [timeoutMs] naming [what]. */
fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) fail("timed out awaiting: $what")
        Thread.sleep(50)
    }
}

/** Nothing ever became true within [millis] — used to pin an absence. */
fun neverWithin(millis: Long = 3_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return false
        Thread.sleep(50)
    }
    return !condition()
}

/**
 * Reads [value] only once it has stopped changing for [settleMillis]
 * (computenet-6lam) — closes the window where a re-dial already in flight
 * when a refused peer's connection is closed can still land its effect on a
 * counter a moment after a naive read of it. No fixed sleep: this polls for
 * an absence of change, the same discipline [neverWithin] uses for presence.
 */
fun quiesced(settleMillis: Long = 1_500, timeoutMs: Long = 30_000, value: () -> Long): Long {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last = value()
    var lastChangedAt = System.currentTimeMillis()
    while (true) {
        Thread.sleep(50)
        val now = value()
        val time = System.currentTimeMillis()
        if (now != last) {
            last = now
            lastChangedAt = time
        } else if (time - lastChangedAt >= settleMillis) {
            return last
        }
        if (time > deadline) fail("timed out waiting for value to quiesce (stuck at $last)")
    }
}
