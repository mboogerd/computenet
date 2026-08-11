package civictech.demo

import civictech.testkit.JvmPeer
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket

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
}
