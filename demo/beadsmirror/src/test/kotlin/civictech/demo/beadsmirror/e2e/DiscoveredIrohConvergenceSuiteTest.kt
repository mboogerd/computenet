package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.DiscoveredIrohMirrorTransport
import civictech.demo.beadsmirror.IrohSidecarGate
import civictech.demo.beadsmirror.MulticastGate
import org.junit.jupiter.api.BeforeEach

/**
 * BS-03 of feature `computenet-63um5` (DSC2, epic `computenet-aas`;
 * [DSC2-NEU-02], [41-LOC-01], [42-REPL-05]): [ConvergenceSuite] — every case,
 * unmodified — run over [DiscoveredIrohMirrorTransport], whose peering forms
 * by mDNS discovery. Like [IrohConvergenceSuiteTest], this class imports the
 * binding and the gates and nothing under `civictech.iroh`; the substitution
 * is entirely in the [newRig] factory ([DSC2-NEU-03]).
 *
 * **What the dialling end is handed.** `TwoNodeRig.startDialer` builds
 * `MirrorWire.Dial("ws://localhost:<n>")` from the listener's `boundWsPort`,
 * which for this binding is a synthetic number read off the listener's UDP
 * address. The binding ignores that string: no NodeId and no address crosses
 * from the listening node to the dialling node. The dialling sidecar finds the
 * listening one by its `--mdns` advertisement, and nothing else.
 *
 * **Three skip gates, all `@BeforeEach`, and where this executes.**
 * [ConvergenceSuite.checkPrerequisites] (inherited) needs `bd`/`dolt`;
 * [checkSidecar] needs the flag-built sidecar ([IrohSidecarGate], checked
 * first) and then a host that delivers multicast ([MulticastGate]). On macOS
 * without the Local Network permission (`ne2oh-B6`) the four cases SKIP with
 * `multicast delivery unavailable on this host: ...` — never FAILED, never
 * PASSED. They EXECUTE on ubuntu-latest in the `iroh-sidecar` lane, which
 * names this class in its `--tests` list and in `required-classes` with
 * skipped=0: that run is the evidence for this suite, not a local one.
 */
class DiscoveredIrohConvergenceSuiteTest : ConvergenceSuite(
    newRig = {
        TwoNodeRig.create(
            "bds2-discovered-convergence",
            transport = DiscoveredIrohMirrorTransport(
                binary = IrohSidecarGate.orSkip(),
                reconnectBackoff = { 10L },
            ),
        )
    },
) {

    @BeforeEach
    fun checkSidecar() {
        IrohSidecarGate.orSkip()
        MulticastGate.deliveryOrSkip()
    }
}
