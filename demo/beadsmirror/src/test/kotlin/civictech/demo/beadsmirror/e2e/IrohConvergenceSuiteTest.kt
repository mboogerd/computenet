package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.IrohMirrorTransport
import civictech.demo.beadsmirror.IrohSidecarGate
import org.junit.jupiter.api.BeforeEach

/**
 * Epic `computenet-egl`'s headline acceptance (task `computenet-egl.4.2`):
 * [ConvergenceSuite] — every case, unmodified — run over
 * [IrohMirrorTransport] instead of
 * [civictech.demo.beadsmirror.WsMirrorTransport]. This class imports
 * `civictech.demo.beadsmirror.IrohMirrorTransport` and nothing under
 * `civictech.iroh`; [ConvergenceSuite] itself still imports neither. The
 * substitution is entirely in this file's [newRig] factory — "same tests,
 * different module" (epic `computenet-egl` §2 bullet 2).
 *
 * **Two independent skip gates, both `@BeforeEach`.** [ConvergenceSuite.checkPrerequisites]
 * (inherited) assumes `bd`/`dolt` on `PATH`; [checkSidecar] below assumes the
 * flag-built sidecar binary is present via [IrohSidecarGate]. JUnit runs both
 * for every test method, so either absence alone is enough to SKIP (green),
 * never fail, keeping default (no `-Piroh.enabled`) lanes green exactly as
 * [WsConvergenceSuiteTest]'s bd/dolt gate does today.
 *
 * The [newRig] factory also resolves the binary through [IrohSidecarGate] —
 * redundant with [checkSidecar] on the happy path, but it means [newRig] on
 * its own is never called with a missing binary, since [ConvergenceSuite]'s
 * test bodies construct a rig unconditionally once `@BeforeEach` has passed.
 *
 * The reconnect backoff is the same near-zero T12 seam
 * [WsConvergenceSuiteTest] passes to `WsMirrorTransport` — an unplanned
 * re-dial (there is none on the planned-sever path the partition case takes)
 * costs scheduling, not wall clock.
 */
class IrohConvergenceSuiteTest : ConvergenceSuite(
    newRig = {
        TwoNodeRig.create(
            "bds2-iroh-convergence",
            transport = IrohMirrorTransport(
                binary = IrohSidecarGate.orSkip(),
                reconnectBackoff = { 10L },
            ),
        )
    },
) {

    @BeforeEach
    fun checkSidecar() {
        IrohSidecarGate.orSkip()
    }
}
