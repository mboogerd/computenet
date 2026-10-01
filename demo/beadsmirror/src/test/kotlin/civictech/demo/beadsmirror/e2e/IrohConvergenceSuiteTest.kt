package civictech.demo.beadsmirror.e2e

import civictech.cell.wire.PeerTransports
import civictech.demo.beadsmirror.IrohSidecarGate
import org.junit.jupiter.api.BeforeEach

/**
 * Epic `computenet-egl`'s headline acceptance (task `computenet-egl.4.2`):
 * [ConvergenceSuite] — every case, unmodified — run over
 * the kernel `iroh` provider instead of the `ws` provider.
 * [ConvergenceSuite] itself names neither binding; substitution stays in
 * this file's [newRig] factory — "same tests, different module".
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
 * The provider receives the sidecar binary through its ordinary config.
 */
class IrohConvergenceSuiteTest : ConvergenceSuite(
    newRig = {
        TwoNodeRig.create(
            "bds2-iroh-convergence",
            transport = PeerTransports.forScheme(
                "iroh",
                mapOf("binary" to IrohSidecarGate.orSkip().toString()),
            ),
        )
    },
) {

    @BeforeEach
    fun checkSidecar() {
        IrohSidecarGate.orSkip()
    }
}
