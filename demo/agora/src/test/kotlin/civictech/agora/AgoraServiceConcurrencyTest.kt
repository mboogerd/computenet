package civictech.agora

import civictech.agora.cell.Polarity
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * computenet-47nz: `AgoraService.graph()` (`:189`) iterates the
 * unsynchronized `nodes` `LinkedHashMap` (`:65`) while
 * `createClaim`/`createEdge`/`remove` mutate the same map from another
 * thread — exactly the shape production hits once `DialogueApp` moves every
 * mutation onto its own driver thread while `/graph` and the
 * `onCredence`-driven `broadcast()` read from other threads (bead
 * description).
 *
 * This drives that interleaving with two real JVM threads directly against
 * `AgoraService` — no `SimWorld`: its deterministic controller never
 * actually interleaves two calling threads, so it cannot see this race at
 * all. A writer thread hammers `createClaim`/`createEdge`/`remove`; a reader
 * thread hammers `graph()` concurrently, for a fixed wall-clock window (this
 * is a race, not a step count — a fixed iteration budget without wall-clock
 * overlap would not force the interleaving).
 *
 * Pre-fix (raw `LinkedHashMap`, no synchronization): reddens reliably with a
 * `ConcurrentModificationException` out of `graph()`'s `nodes.map` — measured
 * 20/20 runs failing, see the bead comment for the run log. Post-fix: green.
 */
class AgoraServiceConcurrencyTest {

    @Test
    fun `graph() survives concurrent createClaim, createEdge and remove`() {
        val registry = LocationRegistry()
        val scheduler = VirtualThreadScheduler("agora-concurrency-test")
        val host = ManagedHost(
            scheduler = scheduler,
            registry = registry,
            attention = civictech.cell.control.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
        )
        val service = AgoraService(host, registry)
        try {
            val stop = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>(null)

            val writer = Thread({
                try {
                    var i = 0
                    while (!stop.get()) {
                        val a = service.createClaim("a$i")
                        val b = service.createClaim("b$i")
                        val edge = service.createEdge(a, b, Polarity.SUPPORT)
                        service.remove(edge)
                        service.remove(a)
                        service.remove(b)
                        i++
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }, "agora-concurrency-writer")

            val reader = Thread({
                try {
                    while (!stop.get()) {
                        service.graph()
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }, "agora-concurrency-reader")

            reader.start()
            writer.start()

            // 3s wall-clock of real overlap between the two threads — sized to
            // reproduce reliably (measured 20/20 pre-fix failures in under 3s
            // each, see the bead comment), short enough to stay well inside a
            // dispatch slot.
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && failure.get() == null) {
                Thread.sleep(20)
            }
            stop.set(true)
            writer.join(5_000)
            reader.join(5_000)

            failure.get()?.let { throw AssertionError("concurrent graph()/mutation race: $it", it) }
        } finally {
            scheduler.shutdown()
        }
    }
}
