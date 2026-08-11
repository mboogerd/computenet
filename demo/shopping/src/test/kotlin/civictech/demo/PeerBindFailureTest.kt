package civictech.demo

import civictech.testkit.JvmPeer
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * computenet-dqy.25's diagnosability clause, as a test: a launched peer that cannot
 * bind must fail the test **with the bind error**.
 *
 * This is the failure CI actually produced (PR #41, run 31473496092,
 * `build-test-serial`). The peer JVM printed
 * `Exception in thread main java.net.BindException: Address already in use` and died;
 * the test then sat out its bounded wait and failed as
 * `timed out awaiting: both peers serving HTTP`, with the cause visible only in the
 * child process's own output — which Gradle's console does not render. Identifying
 * it took a dig through the CI log. The port guess that caused it is gone
 * ([JvmPeer]'s KDoc), but a peer can still lose a bind for reasons this repository
 * does not control, so the *reporting* is a separate promise and is checked here.
 *
 * The collision is constructed, not waited for: [ServerSocket] is held open for the
 * whole test, so the peer meets a **live** competing holder and fails 100% of the
 * time — `SO_REUSEADDR` admits a port in `TIME_WAIT` but never a live socket
 * (measured in computenet-dqy.22). Note that this is the opposite of the deleted
 * `freePort()`: the port here is never handed to anyone, and never released while
 * anyone might want it.
 */
@Tag("multi-jvm")
class PeerBindFailureTest {

    @Test
    fun `a peer that cannot bind fails with the bind error, not a downstream timeout`() {
        ServerSocket(0).use { held ->
            val peer = JvmPeer.launch("civictech.demo.MainKt", "${held.localPort}")
            try {
                val failure = assertThrows<AssertionError> { peer.port("http") }
                val message = failure.message ?: ""
                // the diagnosis, in the test report itself
                message shouldContain "BindException"
                message shouldContain "Address already in use"
                // ...attributed to the peer, with its fate
                message shouldContain "announcing its \"http\" port"
                message shouldContain "exited with status"
            } finally {
                JvmPeer.destroy(peer)
            }
        }
    }

    /**
     * The companion promise, added in review: a handshake that fails does not leave
     * the peer running.
     *
     * It matters because of where the callers read their ports — before the `try`
     * whose `finally` destroys the peers, since the second peer's launch arguments
     * contain the first peer's announced port. A peer that starts, announces
     * nothing more and does not exit would therefore outlive the test it failed:
     * every test class in this module shares one Gradle test JVM (`forkEvery(80)`
     * never triggers on six classes), so an abandoned demo process would compete
     * with every later test for CPU. `JvmPeer`'s shutdown hook only reaps at JVM
     * exit, which is far too late to keep the rest of the suite honest.
     *
     * Solo mode is the deterministic version of "announces nothing more": a peer
     * launched with no `--listen` and no `--peer` announces `http` and never a `ws`
     * port, and stays up serving HTTP indefinitely.
     */
    @Test
    fun `a peer that never announces the awaited port is stopped, not left running`() {
        val peer = JvmPeer.launch("civictech.demo.MainKt", "0")
        try {
            peer.port("http") shouldBeGreaterThan 0

            val failure = assertThrows<AssertionError> { peer.port("ws", timeoutMs = 2_000) }
            val message = failure.message ?: ""
            message shouldContain "announce its \"ws\" port"
            // the diagnosis still names what the peer DID announce, and quotes it
            message shouldContain "announced so far: [http]"
            message shouldContain "computenet-port http"

            peer.process.waitFor(10, TimeUnit.SECONDS) shouldBe true
        } finally {
            JvmPeer.destroy(peer)
        }
    }
}
