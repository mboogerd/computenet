package civictech.demo.beadsmirror.e2e

/**
 * Task computenet-7em.2.2 — [ConvergenceSuite] bound to [TwoNodeRig.create]'s
 * default wiring: two [civictech.demo.beadsmirror.BeadsMirrorApp]s wired
 * through [civictech.demo.beadsmirror.WsMirrorTransport] (task
 * computenet-7em.2.1's binding, made a [TwoNodeRig.create] parameter by task
 * computenet-7em.2.3 and defaulted to that binding — the only one that
 * exists). This is the suite's only production instantiation — a future
 * transport (DSC0, epic computenet-7em §3) adds a sibling class whose rig
 * factory passes that binding to the same parameter, not an edit to
 * [ConvergenceSuite] itself.
 *
 * Guarded exactly like [TwoNodeRigTest]: green-but-skipped where `bd`/`dolt`
 * are not on `PATH` (CI installs neither), a real gate on a developer
 * machine — [ConvergenceSuite.checkPrerequisites] runs the check, inherited
 * here.
 */
class WsConvergenceSuiteTest : ConvergenceSuite(newRig = { TwoNodeRig.create("bds2-convergence") })
