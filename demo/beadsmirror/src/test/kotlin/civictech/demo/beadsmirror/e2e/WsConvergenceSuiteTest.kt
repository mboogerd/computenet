package civictech.demo.beadsmirror.e2e

/**
 * Task computenet-7em.2.2 — [ConvergenceSuite] bound to the only rig
 * [TwoNodeRig.create] can build today: two [civictech.demo.beadsmirror.BeadsMirrorApp]s
 * wired through [civictech.demo.beadsmirror.WsMirrorTransport] (task
 * computenet-7em.2.1's binding). This is the suite's only production
 * instantiation — a future transport (DSC0, epic computenet-7em §3) adds a
 * sibling class supplying a different rig factory, not an edit to
 * [ConvergenceSuite] itself.
 *
 * Guarded exactly like [TwoNodeRigTest]: green-but-skipped where `bd`/`dolt`
 * are not on `PATH` (CI installs neither), a real gate on a developer
 * machine — [ConvergenceSuite.checkPrerequisites] runs the check, inherited
 * here.
 */
class WsConvergenceSuiteTest : ConvergenceSuite(newRig = { TwoNodeRig.create("bds2-convergence") })
