package civictech.demo.beadsmirror.e2e

/**
 * Task computenet-7em.2.2 — [ConvergenceSuite] bound to [TwoNodeRig.create]'s
 * default wiring: two [civictech.demo.beadsmirror.BeadsMirrorApp]s wired
 * through [TwoNodeRig.create]'s default `PeerTransports.forScheme("ws")`
 * binding. Other transports supply a different binding to the same rig
 * parameter without editing [ConvergenceSuite].
 *
 * Guarded exactly like [TwoNodeRigTest]: green-but-skipped where `bd`/`dolt`
 * are not on `PATH` (CI installs neither), a real gate on a developer
 * machine — [ConvergenceSuite.checkPrerequisites] runs the check, inherited
 * here.
 */
class WsConvergenceSuiteTest : ConvergenceSuite(newRig = { TwoNodeRig.create("bds2-convergence") })
